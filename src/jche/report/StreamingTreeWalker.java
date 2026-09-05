/*
 * Copyright 2026 the java-call-hierarchy-exporter authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jche.report;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;

import jche.cache.Origin;
import jche.cache.RecvKind;
import jche.config.Config;
import jche.config.PackagePattern;
import jche.graph.CallGraph;
import jche.graph.CallResolver;
import jche.graph.DataflowContext;
import jche.graph.DataflowResolver;
import jche.graph.MethodTable;
import jche.graph.Resolution;
import jche.util.Log;

/**
 * フェーズ3: 呼び出し階層を深さ優先で辿りながら、CSVを1行ずつ書き出す。
 *
 * ヒープに載るのは「現在の経路（深さぶんの {@link PathFrame}）」だけ。
 * ツリー全体をオブジェクトで組み立てないため、探索が広くても
 * メモリ使用量は深さに比例した一定量にとどまる。
 *
 * 安全策:
 * <ul>
 *   <li>max.depth … 深さ制限（0以下で無制限だが、循環検出があるため止まる）</li>
 *   <li>max.rows … 出力行数の上限（組合せ爆発への最後の砦。0以下で無制限）</li>
 *   <li>循環検出 … 「現在の経路（rootからそのノードまでの祖先）」に同じメソッドが
 *       既にあれば、その辺を1行だけ出力してそこから先へは降りない。
 *       判定は経路単位なので、別の経路で同じ呼び出しが現れた場合は
 *       そちらでも改めて出力する（グローバルな訪問済み集合は持たない）</li>
 *   <li>除外パッケージ … 除外対象のノード自身は出力しないが、その先は
 *       除外されたノードを呼び出し元として辿り続ける。読み飛ばした除外ノードと
 *       差し替えた親も「経路上」として扱い、除外メソッド同士の相互再帰で
 *       無限に再帰しないようにする</li>
 * </ul>
 */
public final class StreamingTreeWalker {

    /**
     * max.depth が 0以下（無制限指定）のときに使う実効上限。
     *
     * 探索は再帰なので、本当に無制限にするとスタックオーバーフローになる。
     * 循環は経路単位で検出して打ち切るため深さは「相異なるメソッド数」で
     * 頭打ちになるが、大規模プロジェクトではそれでも数千に達しうる。
     */
    private static final int DEPTH_HARD_CAP = 512;
    /** 経路上で既に呼んでいるメソッドへ戻る辺の印 */
    static final String CYCLE_MARK = "[CYCLE]";

    private final CallGraph graph;
    private final MethodTable methods;
    private final CallResolver resolver;
    private final DataflowResolver dataflow;
    private final Config config;
    private final CallHierarchyCsvWriter writer;
    private final int maxDepth;

    /** 現在の経路（深さぶんだけ確保） */
    private final PathFrame[] path;
    /**
     * 除外パッケージの読み飛ばし（{@link #skipThrough}）で path[] から外れているが、
     * 呼び出しの連鎖としては祖先にあたるメソッド。差し替えられた親と、読み飛ばし中の
     * 除外メソッドが入る。{@link #onCurrentPath} はこれも経路上とみなす。
     * これが無いと、除外メソッド A → B → A の相互再帰を検出できず、深さも行数も
     * 増えないまま無限に再帰して StackOverflowError になる。
     */
    private final ArrayDeque<Integer> hiddenAncestors = new ArrayDeque<>();
    /** 入れ子になっている読み飛ばしの数。読み飛ばしは深さを増やさないため、別に数えて上限を掛ける */
    private int skipNesting;
    private boolean skipLimitWarned;

    /** データフロー・リフレクションで具象クラスを特定した件数（ログ用） */
    private long paramHits;
    private long factoryHits;
    private long reflectionHits;
    private long fieldHits;
    private long newHits;

    private int rootId;
    private long totalRows;
    private boolean limitWarned;

    public StreamingTreeWalker(CallGraph graph, CallResolver resolver, Config config,
                               CallHierarchyCsvWriter writer) {
        this.graph = graph;
        this.methods = graph.methods();
        this.resolver = resolver;
        this.dataflow = resolver.dataflow();
        this.config = config;
        this.writer = writer;
        this.maxDepth = (config.maxDepth > 0) ? config.maxDepth : DEPTH_HARD_CAP;
        this.path = new PathFrame[Math.max(2, this.maxDepth + 2)];
        for (int i = 0; i < path.length; i++) {
            path[i] = new PathFrame();
        }
    }

    public long paramHits() {
        return paramHits;
    }

    public long factoryHits() {
        return factoryHits;
    }

    public long reflectionHits() {
        return reflectionHits;
    }

    public long fieldHits() {
        return fieldHits;
    }

    public long newHits() {
        return newHits;
    }

    /** データフローで具象クラスを1件でも特定したか（ログを出すかの判定用） */
    public boolean anyDataflowHits() {
        return factoryHits > 0 || paramHits > 0 || fieldHits > 0 || newHits > 0;
    }

    /** 全ての起点から辿り、出力した行数を返す */
    public long walkAll(int[] entries) throws IOException {
        for (int entry : entries) {
            rootId = entry;
            // 起点メソッドの引数も、そのオブジェクトの生成箇所も、経路の中に無いので分からない
            path[0].set(rootId, -1, null, null, null, null);
            descend(0);
            if (isRowLimitReached()) {
                break;
            }
        }
        return totalRows;
    }

    /** depth のノードから、その呼び出し先を辿る */
    private void descend(int depth) throws IOException {
        if (depth >= maxDepth || depth + 1 >= path.length) {
            return;
        }
        int callerId = path[depth].methodId;
        for (int e = graph.edgeStart(callerId); e < graph.edgeEnd(callerId); e++) {
            if (isRowLimitReached()) {
                return;
            }
            int declaredCallee = graph.calleeOf(e);
            Resolution res = resolver.resolveOnPath(e, path[depth].context());
            countHits(res);

            // CHAで候補が複数になった呼び出しは、候補を1件ずつ行にして見せるが、
            // そこから先へは降りない（候補数^深さ で爆発するため）。
            // 並べる候補数にも上限を設ける
            int[] targets = res.targets();
            boolean expand = (targets.length == 1);
            int limit = Math.min(targets.length, Config.CHA_MAX_CANDIDATES);

            for (int ti = 0; ti < limit; ti++) {
                if (isRowLimitReached()) {
                    return;
                }
                int target = targets[ti];
                String[] targetParams = bindArguments(e, depth, target);
                String[] targetCtorArgs = bindConstructorArguments(e, depth, target);

                if (isExcluded(target)) {
                    // 除外対象のノード自身は出力しないが、その先は辿る。
                    // 経路上（読み飛ばし中の除外メソッドを含む）へ戻る辺は循環なので降りない
                    if (!onCurrentPath(target, depth)) {
                        skipThrough(depth, target, targetParams, targetCtorArgs);
                    }
                    continue;
                }

                boolean cycle = onCurrentPath(target, depth);
                path[depth + 1].set(target, graph.callLineOf(e),
                        noteFor(target, declaredCallee, res, depth, cycle, graph.recvKindOf(e)),
                        targetParams, targetCtorArgs,
                        (targetCtorArgs == null) ? null : methods.typeFqn(target));

                // コンストラクタ呼び出しそのものは行にしない。
                // 「new したこと」自体より「その先で何を呼んでいるか」が知りたいため。
                // 経路には積むので、コンストラクタ内からの呼び出しは
                // call-hierarchy 列に <init> を含んだ形で出力される
                if (!methods.isConstructor(target)) {
                    emit(depth + 1);
                }

                // 循環（この経路上で既に呼んでいるメソッドへ戻る辺）はここで打ち切る
                if (expand && !cycle) {
                    descend(depth + 1);
                }
            }
        }
    }

    private void countHits(Resolution res) {
        if (res.isDataflow()) {
            switch (res.label()) {
                case Resolution.DATAFLOW_FIELD -> fieldHits++;
                case Resolution.DATAFLOW_PARAM -> paramHits++;
                case Resolution.DATAFLOW_NEW -> newHits++;
                default -> factoryHits++;
            }
        }
        if (res.isReflection()) {
            reflectionHits++;
        }
    }

    /**
     * この呼び出しで渡す実引数の具象型を求め、呼び出し先の引数の環境を作る。
     *
     * 経路の1つ上（呼び出し元）の環境しか見ないので、rootからの1本の経路に対して
     * 決定的に決まる。呼び出し元の候補を遡って集めることはしない。
     *
     * 何も分からない場合や、呼び出し先が引数を使い回さない場合は null を返す。
     * null を返せば以降の深さでは何もしないので、解析コストが必要な箇所だけに絞れる。
     */
    private String[] bindArguments(int edgeIndex, int depth, int target) {
        if (!dataflow.enabled() || !dataflow.usesContext(target)) {
            return null;
        }
        return resolveArgs(graph.argOrigins(edgeIndex), depth);
    }

    /**
     * 呼び出し先のオブジェクトが、この経路でどう生成されたかを求める。
     *
     * レシーバが {@code new X(...)} なら、その実引数の具象型が
     * コンストラクタ注入されたフィールドの中身になる。
     * レシーバが無い（this への呼び出し）場合は、同じオブジェクトの
     * 別のメソッドを呼んでいるので、今の環境をそのまま引き継ぐ。
     */
    private String[] bindConstructorArguments(int edgeIndex, int depth, int target) {
        if (!dataflow.enabled()) {
            return null;
        }
        String targetType = methods.typeFqn(target);
        if (!graph.hasInjectedFields(targetType)) {
            return null;   // 注入されたフィールドを持たない型には渡す意味が無い
        }
        String recvOrigin = graph.recvOrigin(edgeIndex);
        if (recvOrigin == null) {
            // レシーバなし = this。同じ型のメソッドを呼んでいる間だけ引き継ぐ
            return targetType.equals(path[depth].ctorOwner) ? path[depth].ctorArgs : null;
        }
        if (Origin.kindOf(recvOrigin) != Origin.NEW || !targetType.equals(Origin.valueOf(recvOrigin))) {
            // new 以外（引数・フィールド・戻り値）から来たオブジェクトは、
            // どのコンストラクタ実引数で作られたかがこの経路では分からない
            return null;
        }
        return resolveArgs(Origin.argsOf(recvOrigin), depth);
    }

    /** "位置=出所;..." を、この経路で分かっている具象型の配列に変換する */
    private String[] resolveArgs(String spec, int depth) {
        if (spec == null || spec.isEmpty()) {
            return null;
        }
        DataflowContext ctx = path[depth].context();
        String[] bound = null;
        for (String entry : spec.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            int index;
            try {
                index = Integer.parseInt(entry.substring(0, eq));
            } catch (NumberFormatException ignore) {
                continue;
            }
            String origin = entry.substring(eq + 1);
            String fqn = dataflow.concreteTypeOf(origin, ctx);
            if (fqn == null) {
                // 具象型は決まらないが、リテラルやクラスリテラルなら「値」として渡す
                // （リフレクションのメソッド名・クラスが引数で渡ってくる形のため）
                fqn = dataflow.valueOriginOf(origin, ctx);
            }
            if (fqn == null) {
                continue;
            }
            if (bound == null) {
                bound = new String[index + 1];
            } else if (index >= bound.length) {
                bound = Arrays.copyOf(bound, index + 1);
            }
            bound[index] = fqn;
        }
        return bound;
    }

    private boolean isExcluded(int id) {
        return PackagePattern.matchesAny(config.excludePatterns,
                methods.pkg(id), methods.typeFqn(id), methods.methodName(id));
    }

    /**
     * 除外されたノード自身は出力せず、その呼び出し先を辿り直す。
     * 親の段を一時的に除外されたノードへ差し替えて降りる。
     * 経路の環境（引数・コンストラクタ実引数）も一緒に差し替える。元のまま残すと、
     * 除外されたメソッドの中の呼び出しに、その呼び出し元の引数を当ててしまう。
     */
    private void skipThrough(int parentDepth, int skippedId,
                             String[] skippedParams, String[] skippedCtorArgs) throws IOException {
        // 読み飛ばしは path[] の深さを増やさずに再帰する。相異なる除外メソッドの連鎖が
        // 長くても Java のスタックを使い切らないよう、経路の深さと合わせて上限を掛ける
        if (parentDepth + skipNesting >= DEPTH_HARD_CAP) {
            if (!skipLimitWarned) {
                skipLimitWarned = true;
                Log.warn("除外パッケージの読み飛ばしが深さ上限(" + DEPTH_HARD_CAP + ")に達したため、その先は辿りません");
            }
            return;
        }
        PathFrame saved = path[parentDepth];
        PathFrame replacement = new PathFrame();
        replacement.set(skippedId, saved.callLine, saved.note, skippedParams, skippedCtorArgs,
                (skippedCtorArgs == null) ? null : methods.typeFqn(skippedId));
        path[parentDepth] = replacement;
        // 差し替えた親と読み飛ばす除外メソッドは、path[] からは見えなくなるが祖先のまま
        hiddenAncestors.push(saved.methodId);
        hiddenAncestors.push(skippedId);
        skipNesting++;
        try {
            descend(parentDepth);
        } finally {
            skipNesting--;
            hiddenAncestors.pop();
            hiddenAncestors.pop();
            path[parentDepth] = saved;
        }
    }

    /**
     * ノードに付ける注記。call-hierarchy列の最後の要素として出す。
     *
     * 独立した列にすると call-hierarchy より後ろに列ができてしまい、
     * 「可変長の階層を最終列に置く」という構成が崩れるため、
     * 階層の末尾に追記する形にしている（そのぶん行末grepは効かなくなる）。
     */
    private String noteFor(int target, int declaredCallee, Resolution res, int depth,
                           boolean cycle, char recvKind) {
        StringBuilder sb = new StringBuilder();
        if (cycle) {
            // この経路上で既に呼んでいるメソッドへ戻る辺。ここから先へは降りない
            sb.append(CYCLE_MARK);
        } else if (Resolution.EXTERNAL_GUESS.equals(res.label())) {
            // クラスパス不足でバインディング解決自体ができなかった呼び出し。
            // importの単一型インポートから型名を推定しただけで、JDTによる
            // 検証は経ていない（メンバの実在・オーバーロードは未確認）
            sb.append("外部ライブラリ（import推定・未検証）");
        } else if (!methods.hasSource(target)) {
            sb.append("ソースなし（展開不可）");
        } else if (depth + 1 >= maxDepth) {
            sb.append("深さ制限(").append(maxDepth).append(")のため打ち切り");
        }

        String detail;
        if (res.isMultiple() && Resolution.REFLECTION.equals(res.label())) {
            // getMethod の引数型（クラスリテラル）が揃わず、名前だけで照合した
            detail = "リフレクション候補" + res.targets().length + "件（未展開）: 引数型が不明なため名前で照合";
        } else if (res.isMultiple()) {
            // 「なぜ絞れないのか」まで出す。レシーバの由来で次に調べる場所が変わる
            detail = "CHA候補" + res.targets().length + "件（未展開）: " + RecvKind.describe(recvKind);
        } else if (graph.hasFunctionalImpl(declaredCallee)) {
            // ソース上の実装が1件しか無くても、ラムダ／メソッド参照が
            // 同じインターフェースを実装している。それを数に入れずに
            // 「解決:SINGLE_IMPL」と書くと、実際とは違う1件に決め打ちしたまま
            // 確定したように見えてしまう
            detail = "ラムダ/メソッド参照の実装あり（未展開・本体は定義元メソッドに計上）";
        } else if (Resolution.NO_IMPL.equals(res.label())) {
            // 本体を持つ実装がソース上に1つも無い。宣言のまま出しているだけで、
            // 実行時に何が動くかはこのツールでは分からない
            detail = "実装なし（宣言のまま）: " + RecvKind.describe(recvKind);
        } else if (target != declaredCallee || res.isDataflow()) {
            // データフローで決めた場合は、宣言型と同じ結論でも「CHAで諦めずに
            // 絞れた」ことに意味があるので必ず出す
            detail = "解決:" + res.label();
        } else {
            detail = null;
        }
        if (detail != null) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(detail);
        }
        return (sb.length() == 0) ? null : sb.toString();
    }

    /**
     * そのメソッドが「現在の経路」に既に現れているか。
     *
     * 見るのは root から depth までの祖先（除外の読み飛ばしで path[] から外れた祖先を含む）
     * だけで、探索済みの他の経路は見ない。これにより、別経路で同じ呼び出しがあっても
     * 独立して出力される（ダイヤモンド状の依存を潰さない）。
     */
    private boolean onCurrentPath(int methodId, int depth) {
        for (int i = 0; i <= depth; i++) {
            if (path[i].methodId == methodId) {
                return true;
            }
        }
        return hiddenAncestors.contains(methodId);
    }

    private boolean isRowLimitReached() {
        if (config.maxRows <= 0 || totalRows < config.maxRows) {
            return false;
        }
        if (!limitWarned) {
            limitWarned = true;
            Log.warn("出力行数の上限(" + config.maxRows + ")に達したため打ち切りました");
        }
        return true;
    }

    /** 1行を即座に書き出す（溜め込まない） */
    private void emit(int depth) throws IOException {
        writer.writeRow(methods, rootId, path, depth);
        totalRows++;
    }
}
