// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.report;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;

import jche.cache.Origin;
import jche.cache.RecvKind;
import jche.config.Config;
import jche.config.PackagePattern;
import jche.framework.GeneratedImpl;
import jche.graph.CallGraph;
import jche.graph.CallbackContracts;
import jche.graph.CallResolver;
import jche.graph.DataflowContext;
import jche.graph.DataflowResolver;
import jche.graph.IntArray;
import jche.graph.GuardEvaluator;
import jche.graph.MethodTable;
import jche.graph.Resolution;
import jche.util.Log;
import jche.util.Messages;

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
    /**
     * 注記のタグ。
     *
     * 注記は英語の説明文で、先頭に大文字のタグを置いて grep で拾えるようにしている。
     * <b>CSV の中身は表示言語に関わらず英語で固定</b>である（{@code docs/nls-qa.md} の Q6）。
     * 期待値との比較・Excel のフィルタ・他のツールへの受け渡しに使われるもので、
     * 読み手の言語で変わってはいけない。
     * {@code [UNEXPANDED:*]} は「ここから先へ降りなかった」ことを表し、
     * タグだけで「辿り切れなかった箇所」を一括で数えられる。
     * {@code [EXTERNAL]} は自プロジェクトの外を指しているという別の性質なので、
     * 打ち切りではあるが {@code UNEXPANDED} の配下には入れない。
     */
    static final String UNEXPANDED = "[UNEXPANDED:";
    /** 自プロジェクトの外を指しているため辿れない辺の印 */
    static final String EXTERNAL_MARK = "[EXTERNAL]";
    /** 経路上で既に呼んでいるメソッドへ戻る辺の印 */
    static final String CYCLE_MARK = UNEXPANDED + "CYCLE] returns to a method already on this path";

    // --- 階層CSVの注記と methods.csv の unresolvedCause で共通に使う文言 ---
    // 同じ「絞れなかった」を一覧と階層で別の文にすると、片方で見つけた呼び出しを
    // もう片方で追えなくなる。1か所に置いて {@link InventoryReport} と分け合う
    /** 本体を持つ実装がソース上に1つも無い */
    static final String CAUSE_NO_IMPL = UNEXPANDED + "NO_IMPL] no implementation with a body in the source";
    /** ラムダ／メソッド参照が同じインターフェースを実装している */
    static final String CAUSE_LAMBDA = UNEXPANDED + "LAMBDA] implemented by a lambda/method reference";

    /**
     * 実装がコンパイル時のアノテーション処理で生成される型の注記。
     *
     * @param framework 生成するフレームワークの名前（Doma 等）
     * @return タグ付きの文言
     */
    static String generatedCause(String framework) {
        return UNEXPANDED + "GENERATED] implementation is generated at compile time (" + framework + ")";
    }

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
    /** 契約（jar の中のメソッドが渡した値を呼び戻す）で繋いだ件数 */
    private long callbackHits;
    private long reflectionHits;
    private long fieldHits;
    private long newHits;
    /** 条件分岐の静的解析で「この経路では呼ばれない」と判定して打ち切った件数 */
    private long prunedCalls;
    /** 絞れなかった呼び出しから作る、契約表のひな形 */
    private final ContractSuggestions suggestions = new ContractSuggestions();

    private int rootId;
    private long totalRows;
    private boolean limitWarned;
    private boolean candidateLimitWarned;

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

    /** 契約で呼び戻される側へ繋いだ件数 */
    public long callbackHits() {
        return callbackHits;
    }

    /** 絞れなかった呼び出しから作った、契約表のひな形 */
    public ContractSuggestions suggestions() {
        return suggestions;
    }

    /** 条件分岐の静的解析で打ち切った呼び出しの件数 */
    public long prunedCalls() {
        return prunedCalls;
    }

    /** そのメソッドが階層CSVに1行でも出たか */
    boolean inHierarchy(int methodId) {
        return methodId >= 0 && methodId < inHierarchy.length && inHierarchy[methodId];
    }

    /**
     * 階層CSVに出なかった理由の文言。分からなければ「上流が未出力」。
     *
     * タグは call-hierarchy.csv の注記と揃える。同じ打ち切りを
     * 「階層側では注記」「一覧側では absentCause」と別の名前で書くと、
     * 片方で見つけた件数をもう片方で追えなくなる。
     */
    String absentCauseOf(int methodId) {
        byte cause = (methodId >= 0 && methodId < absentCause.length) ? absentCause[methodId] : ABSENT_NONE;
        return switch (cause) {
            case ABSENT_EXCLUDED -> "[EXCLUDED] excluded by exclude.packages";
            case ABSENT_CHA -> UNEXPANDED + "CHA] not expanded (CHA candidate)";
            case ABSENT_CYCLE -> UNEXPANDED + "CYCLE] not expanded (cycle)";
            case ABSENT_PRUNED_SUBTREE -> PRUNED_SUBTREE_CAUSE;
            // 呼び出し先として一度も見ていない = そこへ至る呼び出し自体が出力されていない
            // （深さ制限・行数上限の先、起点から辿り着かない）
            default -> "[NOT_REACHED] no caller row was emitted";
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
    static final String PRUNED_SUBTREE_CAUSE = "[UNREACHABLE] below a call pruned by a condition";

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
            path[0].set(rootId, -1, null, null, null, null, null);
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
            // 絞れなかった呼び出しは、それを直す契約表の行のひな形にしておく。
            // リフレクション（名前で照合）は契約表では直せないので除く
            if (res.isMultiple() && unreachable == null
                    && !Resolution.REFLECTION.equals(res.label())) {
                suggestions.add(graph, dataflow, path[depth].context(), e, callerId,
                        declaredCallee, targets);
            }
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
                        resolvedBy(declaredCallee, res),
                        targetParams, targetCtorArgs,
                        (targetCtorArgs == null) ? null : methods.typeFqn(target),
                        // ラムダの本体へ降りるとき、今の段が「そのラムダを生成したメソッド」なら
                        // その引数を「捕捉した値」として渡す（jche.cache.Origin#CAPTURED）。
                        // 生成の辺の先と、生成したメソッドの中で r.run() した形がこれに当たる。
                        // 引数で渡した先（runIt(Runnable r) の r.run()）のように別のメソッドの段から
                        // 降りるときは渡さない。捕捉した値はラムダを作った時点で決まる
                        // （JLS 15.27.2）ので、実行した側の引数を当てると別の値を指してしまう
                        // （docs/lambda-expansion-qa.md の Q10）
                        capturedTypesFor(depth, target));

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

            // 呼び出し先が jar の中でも、契約で「渡した値を呼び戻す」と分かるものは
            // その先へ繋ぐ（Thread#start → Runnable#run 等。docs/callback-contracts-qa.md）。
            // 呼び出し先自身の行はそのまま残し、その次に呼び戻される側を並べる
            if (unreachable == null) {
                descendCallbacks(depth, e, declaredCallee);
            }
        }
    }

    /**
     * 契約で呼び戻されるメソッドを、その辺の追加の候補として出力し、降りる。
     * 通常の候補と同じく、除外・循環の扱いを通す
     */
    private void descendCallbacks(int depth, int e, int declaredCallee) throws IOException {
        for (CallbackContracts.Match match : resolver.callbackTargets(e, path[depth].context())) {
            if (isRowLimitReached()) {
                return;
            }
            int target = match.target();
            callbackHits++;
            if (isExcluded(target)) {
                markAbsent(target, ABSENT_EXCLUDED);
                if (!onCurrentPath(target, depth)) {
                    skipThrough(depth, target, null, null);
                }
                continue;
            }
            boolean cycle = onCurrentPath(target, depth);
            if (cycle) {
                markAbsent(target, ABSENT_CYCLE);
            }
            Resolution res = Resolution.single(target, Resolution.CALLBACK);
            String note = noteFor(target, declaredCallee, res, depth, cycle, graph.recvKindOf(e), null);
            path[depth + 1].set(target, graph.callLineOf(e),
                    note + " contract: " + match.contract(), resolvedBy(declaredCallee, res),
                    null, null, null, null);
            emit(depth + 1);
            if (!cycle) {
                descend(depth + 1);
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
     * ラムダの合成メソッド {@code target} へ降りるときに渡す「捕捉した値」。
     * 今の段がそのラムダを生成したメソッドでなければ null（捕捉した引数は解決しない）
     */
    private String[] capturedTypesFor(int depth, int target) {
        if (!methods.isLambdaBody(target) || !graph.createsLambda(path[depth].methodId, target)) {
            return null;
        }
        return path[depth].paramTypes;
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
        // 入れ子（{} の中）の ';' で切らないよう、必ず Origin 側の分け方を通す
        for (String entry : Origin.entriesOf(spec)) {
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
            String origin = Origin.unnest(entry.substring(eq + 1));
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
                Log.warn(Messages.format("report.walker.excludeDepthCap", DEPTH_HARD_CAP));
            }
            return;
        }
        PathFrame saved = path[parentDepth];
        PathFrame replacement = new PathFrame();
        replacement.set(skippedId, saved.callLine, saved.note, saved.resolvedBy,
                skippedParams, skippedCtorArgs,
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
     * その行の解決方法（resolved-by 列）。
     *
     * 「接頭辞（確度） + 段のラベル（手法）」の形で、必ず値が入る。注記と違って
     * 固定列なので、Excel のフィルタで確度・手法ごとに行を選べる。
     *
     * 判定の順序は {@link #noteFor} と同じにしてある。片方だけを直すと、
     * 同じ行の列と注記が食い違う（docs/call-hierarchy-columns-qa.md の Q3）。
     */
    private String resolvedBy(int declaredCallee, Resolution res) {
        if (res.isMultiple()) {
            // 1件に絞れなかった。ラベルは「候補をどう集めたか」を表す
            // （CHA / LOCAL_NEW_MULTI / CONTRACT / REFLECTION / 拡張のラベル）
            return ResolvedBy.UNEXPANDED + res.label();
        }
        if (Resolution.DATAFLOW_LAMBDA.equals(res.label())) {
            // どのラムダが渡ってきたかまで分かった。下の「未特定」とは逆の結論なので先に判定する
            return ResolvedBy.RESOLVED + res.label();
        }
        if (graph.hasFunctionalImpl(declaredCallee)) {
            // ソース上の実装が1件でも、ラムダ／メソッド参照が同じインターフェースを
            // 実装している。ラベル（SINGLE_IMPL 等）をそのまま出すと確定に見えるため言い換える
            return ResolvedBy.UNEXPANDED + ResolvedBy.LAMBDA;
        }
        if (res.isGeneratedImpl() || Resolution.NO_IMPL.equals(res.label())) {
            // 呼び出し先は宣言のままで、その先へは降りられない
            return ResolvedBy.UNEXPANDED + res.label();
        }
        return ResolvedBy.RESOLVED + res.label();
    }

    /**
     * ノードに付ける注記。call-hierarchy列の最後の要素として出す。
     *
     * 独立した列にすると call-hierarchy より後ろに列ができてしまい、
     * 「可変長の階層を最終列に置く」という構成が崩れるため、
     * 階層の末尾に追記する形にしている（そのぶん行末grepは効かなくなる）。
     *
     * 解決方法そのものは resolved-by 列に出るので、注記には
     * <b>列に無い情報がある場合だけ</b>後半を付ける（候補の件数とレシーバの由来、
     * 生成される実装のFQN、繋いだ契約）。
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
            // 検証は経ていない（メンバの実在・オーバーロードは未確認）。
            // ソースが無いのと同じ [EXTERNAL] だが、こちらは推定が外れている
            // 可能性があるため、説明文で言い分ける（タグは分けない）
            sb.append(EXTERNAL_MARK).append(" type guessed from an import (unverified)");
        } else if (!methods.hasSource(target)) {
            sb.append(EXTERNAL_MARK).append(" no source to follow");
        } else if (depth + 1 >= maxDepth) {
            sb.append(UNEXPANDED).append("DEPTH] depth limit (").append(maxDepth).append(") reached");
        }

        String detail;
        if (res.isMultiple() && Resolution.REFLECTION.equals(res.label())) {
            // getMethod の引数型（クラスリテラル）が揃わず、名前だけで照合した
            detail = UNEXPANDED + "REFLECTION] " + res.targets().length
                    + " candidates: matched by name because argument types are unknown";
        } else if (res.isMultiple()) {
            // 「なぜ絞れないのか」まで出す。レシーバの由来で次に調べる場所が変わる。
            // 候補数が上限を超えたときは、行にならなかった候補があることも書く。
            // 黙って切ると、methods.csv の inDegree（全候補で数える）と行数が合わず、
            // 読み手が「候補が消えた」のか「元から無い」のか区別できない
            int n = res.targets().length;
            detail = UNEXPANDED + "CHA] " + n + " candidates: " + RecvKind.describe(recvKind)
                    + ((n > Config.CHA_MAX_CANDIDATES)
                            ? " (only the first " + Config.CHA_MAX_CANDIDATES + " are written as rows)" : "");
            if (n > Config.CHA_MAX_CANDIDATES && !candidateLimitWarned) {
                candidateLimitWarned = true;
                Log.warn(Messages.format("report.walker.chaCandidateLimit", Config.CHA_MAX_CANDIDATES,
                        methods.fullSignature(declaredCallee)));
            }
        } else if (Resolution.DATAFLOW_LAMBDA.equals(res.label())) {
            // どのラムダが渡ってきたかまで分かった呼び出し。下の「未特定」とは逆の結論なので、
            // 先に判定する。解決方法は resolved-by 列に出るので注記は付けない
            detail = null;
        } else if (graph.hasFunctionalImpl(declaredCallee)) {
            // ソース上の実装が1件しか無くても、ラムダ／メソッド参照が
            // 同じインターフェースを実装している。それを数に入れずに
            // 「RESOLVED:SINGLE_IMPL」と書くと、実際とは違う1件に決め打ちしたまま
            // 確定したように見えてしまう。
            // ここに来るのは、ラムダを値として追えなかった呼び出し（jar の中から
            // 呼ばれる forEach 形式など）。追えた場合は上の DATAFLOW_LAMBDA で確定する
            detail = CAUSE_LAMBDA + " (which one runs is undetermined)";
        } else if (res.isGeneratedImpl()) {
            // 実装はコンパイル時のアノテーション処理で生成される（Doma の @Dao 等）。
            // 生成物はソースコードリポジトリに存在しないため、ここから先は辿れない。
            // 「実装なし（宣言のまま）」と同じ状態だが、原因が違うので言い分ける
            String framework = res.label().substring(Resolution.GENERATED_IMPL_PREFIX.length());
            String declType = methods.typeFqn(declaredCallee);
            GeneratedImpl def = GeneratedImpl.of(graph.hierarchy().annotationsOf(declType));
            detail = generatedCause(framework) + ": "
                    + ((def == null) ? declType : def.implFqnOf(declType))
                    + " is produced by annotation processing and has no source";
        } else if (Resolution.NO_IMPL.equals(res.label())) {
            // 本体を持つ実装がソース上に1つも無い。ソースが読めないだけの
            // [EXTERNAL] とは違い、「読めた上で見つからない」状態で、
            // source.folders の設定漏れかデッドコードの疑いがある。
            // 調べる価値がある側なので、methods.csv の unresolvedCause だけでなく
            // 階層側にも出す
            detail = CAUSE_NO_IMPL;
        } else if (Resolution.CALLBACK.equals(res.label())) {
            // 「どの契約で繋いだか」は列に無い情報なので注記に残す。
            // 契約の本文は descendCallbacks がこの後ろに足す
            detail = "[RESOLVED:" + Resolution.CALLBACK + "]";
        } else {
            // 1件に確定した呼び出しは resolved-by 列だけで足りる。
            // 同じことを注記にも書くと、可変長の階層列が読みにくくなるだけ
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
            Log.warn(Messages.format("report.walker.maxRows", config.maxRows));
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
