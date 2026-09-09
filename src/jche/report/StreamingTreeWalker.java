// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
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
import jche.graph.IntArray;
import jche.graph.GuardEvaluator;
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
 *   <li>条件分岐の静的解析 … 呼び出し箇所を囲む条件が、この経路で成立しないと
 *       言い切れる場合は、その辺を1行だけ「呼ばれない理由」付きで出力し、
 *       そこから先へは降りない（{@link GuardEvaluator}）</li>
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

    // --- 階層CSVに出なかったメソッドの理由（methods.csv の absentCause 列。弱い順） ---
    /** 観測できていない（呼び出し先として一度も見ていない＝そこへ至る呼び出し自体が出ていない） */
    static final byte ABSENT_NONE = 0;
    /** 条件分岐で打ち切った呼び出しから先にしかない（打ち切りで階層から消えた部分木） */
    static final byte ABSENT_PRUNED_SUBTREE = 1;
    /** 経路上で既に呼んでいるメソッドへ戻る辺だった */
    static final byte ABSENT_CYCLE = 2;
    /** CHAで候補が複数のまま（候補は行になるが、その先へは降りない） */
    static final byte ABSENT_CHA = 3;
    /** exclude.packages で除外された */
    static final byte ABSENT_EXCLUDED = 4;

    private final CallGraph graph;
    private final MethodTable methods;
    private final CallResolver resolver;
    private final DataflowResolver dataflow;
    private final GuardEvaluator guards;
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
    /** 条件分岐の静的解析で「この経路では呼ばれない」と判定して打ち切った件数 */
    private long prunedCalls;

    private int rootId;
    private long totalRows;
    private boolean limitWarned;

    /**
     * 階層CSVに1行でも出たメソッド。
     *
     * 打ち切った呼び出しの先は階層CSVから丸ごと消えるため、「消えたメソッド」を
     * methods.csv 側で拾えるようにする（inHierarchy 列）。
     */
    private final boolean[] inHierarchy;
    /** 呼び出し先として見たが降りなかった理由。強い理由で上書きする（absentCause 列） */
    private final byte[] absentCause;
    /** 条件分岐で打ち切った呼び出しの、呼び出し先（打ち切りで消えた部分木の根） */
    private final IntArray prunedTargets = new IntArray(64);

    public StreamingTreeWalker(CallGraph graph, CallResolver resolver, Config config,
                               CallHierarchyCsvWriter writer) {
        this.graph = graph;
        this.methods = graph.methods();
        this.resolver = resolver;
        this.dataflow = resolver.dataflow();
        this.guards = new GuardEvaluator(config.branchPruningEnabled);
        this.config = config;
        this.writer = writer;
        this.maxDepth = (config.maxDepth > 0) ? config.maxDepth : DEPTH_HARD_CAP;
        this.inHierarchy = new boolean[methods.size()];
        this.absentCause = new byte[methods.size()];
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

    /** 条件分岐の静的解析で打ち切った呼び出しの件数 */
    public long prunedCalls() {
        return prunedCalls;
    }

    /** そのメソッドが階層CSVに1行でも出たか */
    boolean inHierarchy(int methodId) {
        return methodId >= 0 && methodId < inHierarchy.length && inHierarchy[methodId];
    }

    /** 階層CSVに出なかった理由の文言。分からなければ「上流が未出力」 */
    String absentCauseOf(int methodId) {
        byte cause = (methodId >= 0 && methodId < absentCause.length) ? absentCause[methodId] : ABSENT_NONE;
        return switch (cause) {
            case ABSENT_EXCLUDED -> "除外パッケージ";
            case ABSENT_CHA -> "CHA候補のため未展開";
            case ABSENT_CYCLE -> "循環のため未展開";
            case ABSENT_PRUNED_SUBTREE -> PRUNED_SUBTREE_CAUSE;
            // 呼び出し先として一度も見ていない = そこへ至る呼び出し自体が出力されていない
            // （深さ制限・行数上限の先、起点から辿り着かない）
            default -> "上流が未出力";
        };
    }

    /** 降りなかった理由を記録する。より強い理由が来たときだけ上書きする */
    private void markAbsent(int methodId, byte cause) {
        if (methodId >= 0 && methodId < absentCause.length && absentCause[methodId] < cause) {
            absentCause[methodId] = cause;
        }
    }

    /** データフローで具象クラスを1件でも特定したか（ログを出すかの判定用） */
    public boolean anyDataflowHits() {
        return factoryHits > 0 || paramHits > 0 || fieldHits > 0 || newHits > 0;
    }

    /** 条件分岐の打ち切りが理由で階層CSVに出なかったことを表す文言 */
    static final String PRUNED_SUBTREE_CAUSE = "条件分岐で打ち切った先";

    /**
     * 打ち切った呼び出しの先にしか無いメソッドに印を付ける。
     *
     * 打ち切った呼び出し自体は行になるので、階層CSVから丸ごと消えるのは
     * <b>その先</b>。どこまでが消えたかは、打ち切った呼び出し先から
     * 宣言上のエッジを辿って求める（経路ごとの解決までは追わない近似）。
     * 別の経路で1行でも出たメソッドは対象外なので、印が付くのは
     * 「打ち切りが無ければ出ていたはずのメソッド」だけになる。
     */
    private void markPrunedSubtrees() {
        if (prunedTargets.isEmpty()) {
            return;
        }
        boolean[] seen = new boolean[methods.size()];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < prunedTargets.size(); i++) {
            int id = prunedTargets.get(i);
            if (id >= 0 && id < seen.length && !seen[id]) {
                seen[id] = true;
                queue.add(id);
            }
        }
        while (!queue.isEmpty()) {
            int id = queue.poll();
            if (!inHierarchy[id]) {
                markAbsent(id, ABSENT_PRUNED_SUBTREE);
            }
            for (int e = graph.edgeStart(id); e < graph.edgeEnd(id); e++) {
                int next = graph.calleeOf(e);
                if (next >= 0 && next < seen.length && !seen[next]) {
                    seen[next] = true;
                    queue.add(next);
                }
            }
        }
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
        markPrunedSubtrees();
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

            // この呼び出しを囲む条件が、この経路では成立しないと言い切れるか。
            // 言い切れるなら、呼び出し自体は理由付きで1行出すが、その先へは降りない
            String unreachable = guards.unreachableReason(graph.guard(e), path[depth].context());
            if (unreachable != null) {
                prunedCalls++;
            }

            // CHAで候補が複数になった呼び出しは、候補を1件ずつ行にして見せるが、
            // そこから先へは降りない（候補数^深さ で爆発するため）。
            // 並べる候補数にも上限を設ける
            int[] targets = res.targets();
            boolean expand = (targets.length == 1) && (unreachable == null);
            int limit = Math.min(targets.length, Config.CHA_MAX_CANDIDATES);

            for (int ti = 0; ti < limit; ti++) {
                if (isRowLimitReached()) {
                    return;
                }
                int target = targets[ti];
                String[] targetParams = bindArguments(e, depth, target);
                String[] targetCtorArgs = bindConstructorArguments(e, depth, target);

                if (unreachable != null) {
                    // 打ち切った呼び出し自体は行になるが、その先の階層は消える。
                    // 消えた範囲は探索の後にまとめて求める（markPrunedSubtrees）
                    prunedTargets.add(target);
                }
                if (isExcluded(target)) {
                    markAbsent(target, ABSENT_EXCLUDED);
                    // 除外対象のノード自身は出力しないが、その先は辿る。
                    // 経路上（読み飛ばし中の除外メソッドを含む）へ戻る辺は循環なので降りない。
                    // この経路で呼ばれない呼び出しは、除外ノードの先も辿らない
                    if (unreachable == null && !onCurrentPath(target, depth)) {
                        skipThrough(depth, target, targetParams, targetCtorArgs);
                    }
                    continue;
                }

                boolean cycle = onCurrentPath(target, depth);
                if (cycle) {
                    markAbsent(target, ABSENT_CYCLE);
                } else if (targets.length > 1) {
                    markAbsent(target, ABSENT_CHA);
                }
                path[depth + 1].set(target, graph.callLineOf(e),
                        noteFor(target, declaredCallee, res, depth, cycle, graph.recvKindOf(e),
                                unreachable),
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
                           boolean cycle, char recvKind, String unreachable) {
        StringBuilder sb = new StringBuilder();
        if (unreachable != null) {
            // 条件分岐の静的解析で、この経路では実行されないと分かった呼び出し。
            // 呼び出しが書かれている事実は残しつつ、ここで階層を打ち切る
            sb.append(unreachable);
        } else if (cycle) {
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
        int id = path[depth].methodId;
        if (id >= 0 && id < inHierarchy.length) {
            inHierarchy[id] = true;
        }
        totalRows++;
    }
}
