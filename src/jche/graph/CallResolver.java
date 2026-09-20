// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import jche.cache.HintFact;
import jche.cache.Origin;
import jche.cache.RecvKind;
import jche.extension.Hint;
import jche.framework.GeneratedImpl;
import jche.extension.TypeCandidateProvider;
import jche.extension.UsageReporter;
import jche.util.Log;
import jche.util.Messages;

/**
 * エッジ単位の解決パイプライン。呼び出し先の具象候補を求める。
 * <pre>
 *   段0 STATIC_BOUND               仮想ディスパッチされない呼び出し
 *   段1 NO_OVERRIDE / SINGLE_IMPL  オーバーライド候補が1つに定まる
 *   段2 LOCAL_NEW(_MULTI)          同一メソッド内で new された型
 *   段3 CONTRACT / CUSTOM_*        利用者が与えた条件（契約表の種類 C → 拡張の順に尋ねる）
 *   段4 DATAFLOW_*                 ファクトリの戻り値等から特定（経路非依存の分）
 *   段5 SPRING_DI(_QUALIFIER)      DIコンテナのBean定義で候補を絞る
 *   段6 CHA                        候補が複数のまま（低確度）
 * </pre>
 * 段1で確定するならそれが最も確実なので、証拠より先に採用する。
 * リフレクション（Method.invoke / Class.forName / newInstance）は段0の前に試す。
 * 呼び出し先はjar内のAPIなので静的束縛に見えるが、実際に動くのは名前で指定されたメソッド。
 *
 * 経路（呼び出し元から渡された引数・コンストラクタ実引数）に依存する分は
 * {@link #resolveOnPath} で、経路を歩く側（jche.report.StreamingTreeWalker）がもう一度試す。
 */
public final class CallResolver {

    private final CallGraph graph;
    private final MethodTable methods;
    private final DataflowResolver dataflow;
    private final List<TypeCandidateProvider> providers;
    /** ソースの外を経由して呼び戻される辺の契約表（無ければ空の表） */
    private final CallbackContracts callbacks;
    /** フレームワークが起点として呼ぶメソッドの契約表（無ければ空の表） */
    private final FrameworkEntries frameworkEntries;
    /** 「この宣言型はこの具象型」の契約表（無ければ空の表） */
    private final TypeContracts typeContracts;

    /** 段1の結果のメモ（メソッドIDごと。仮想呼び出しのみ対象） */
    private int[][] resolvedTargets;
    private String[] resolvedLabels;
    /**
     * {@link #resolve} の結果のメモ（エッジごと）。
     *
     * 結果は決定的なので、同じエッジを何度解いても同じになる。呼び出す側は入次数の集計・到達判定・
     * methods.csv・呼び出し階層の展開と 4 つあり、特に階層の展開では同じエッジが経路の数だけ
     * 現れる。毎回ヒントの走査・拡張への問い合わせ・親を辿る BFS をやり直すと、
     * 大規模プロジェクトでは解決だけで時間の大半を使う。
     *
     * メモリはエッジあたり参照 2 本。候補が 1 件の結果は {@link #singletonOf} で配列を
     * メソッドごとに共有し、ラベルは定数か {@link #internLabel} で共有するので、
     * エッジ数ぶんの配列や文字列を新たに抱えることはない。
     */
    private int[][] edgeTargets;
    private String[] edgeLabels;
    /** 候補 1 件の int[] をメソッドIDごとに共有する（エッジごとに new int[1] しない） */
    private int[][] singletons;
    /** 動的に組み立てるラベル（"STATIC_BOUND:理由" 等）の共有 */
    private final HashMap<String, String> labelPool = new HashMap<>();
    /** 既に警告した「使えない候補」（{@link #warnUnusableCandidate}）。同じものを繰り返さないため */
    private final HashSet<String> warnedCandidates = new HashSet<>();
    /** 解決後の入次数。宣言型ではなく解決先に対して数える */
    private int[] inDegree;

    public CallResolver(CallGraph graph, DataflowResolver dataflow,
                        List<TypeCandidateProvider> providers) {
        this(graph, dataflow, providers, CallbackContracts.jdk(graph, dataflow),
                FrameworkEntries.bundled(graph), TypeContracts.empty());
    }

    public CallResolver(CallGraph graph, DataflowResolver dataflow,
                        List<TypeCandidateProvider> providers, CallbackContracts callbacks,
                        FrameworkEntries frameworkEntries, TypeContracts typeContracts) {
        this.graph = graph;
        this.methods = graph.methods;
        this.dataflow = dataflow;
        this.providers = providers;
        this.callbacks = callbacks;
        this.frameworkEntries = frameworkEntries;
        this.typeContracts = typeContracts;
    }

    /** フレームワークが起点として呼ぶメソッドの契約表 */
    public FrameworkEntries frameworkEntries() {
        return frameworkEntries;
    }

    /**
     * 契約表と拡張が「効いたか」を知らせる。CSV を書き終えたあとに 1 回だけ呼ぶ。
     *
     * <p>グラフ全体の走査が済んでいることが前提。呼び戻しの契約は {@link #inDegrees()} が全エッジ、
     * 入口の契約は methods.csv の出力が全メソッドについて問い合わせるので、そこまで終わって初めて
     * 「一度も当たらなかった」と言える。部分的にしか辿らない経路（解析サーバー）からは呼ばない。
     */
    public void reportUsage() {
        ContractUsage.report(callbacks.usage(), frameworkEntries.usage(), typeContracts.usage());
        for (TypeCandidateProvider provider : providers) {
            if (provider instanceof UsageReporter reporter) {
                try {
                    reporter.reportUsage();
                } catch (RuntimeException e) {
                    // 報告の失敗で解析の結果を捨てない。出せなかったことだけ知らせる
                    Log.warn(Messages.format("graph.provider.usageFailed",
                            provider.getClass().getName(), e));
                }
            }
        }
    }

    /**
     * その辺の呼び出し先（jar の中）が、契約で呼び戻すメソッド。無ければ空。
     *
     * 通常の解決（{@link #resolve}）とは別の候補として扱う。呼び出し先そのものの行は
     * そのまま出し、その次に呼び戻される側を {@link Resolution#CALLBACK} で並べる。
     *
     * @param ctx 経路で分かっていること。null なら経路に依らず決まるもの（new した型・ラムダ）だけ
     */
    public List<CallbackContracts.Match> callbackTargets(int edgeIndex, DataflowContext ctx) {
        int callee = graph.calleeOf(edgeIndex);
        if (!callbacks.hasContract(callee)) {
            return List.of();
        }
        return callbacks.matchesOf(edgeIndex, ctx);
    }

    public DataflowResolver dataflow() {
        return dataflow;
    }

    /** 経路に依存しない解決。結果は決定的で、同じエッジには常に同じ結果を返す */
    public Resolution resolve(int edgeIndex) {
        if (edgeTargets == null) {
            edgeTargets = new int[graph.edgeCount()][];
            edgeLabels = new String[graph.edgeCount()];
        }
        int[] memo = edgeTargets[edgeIndex];
        if (memo != null) {
            return new Resolution(memo, edgeLabels[edgeIndex]);
        }
        Resolution res = resolveUncached(edgeIndex);
        int[] targets = res.targets();
        if (targets.length == 1) {
            targets = singletonOf(targets[0]);
        }
        edgeTargets[edgeIndex] = targets;
        edgeLabels[edgeIndex] = internLabel(res.label());
        return new Resolution(targets, edgeLabels[edgeIndex]);
    }

    private int[] singletonOf(int methodId) {
        if (singletons == null) {
            singletons = new int[methods.size()][];
        }
        int[] a = singletons[methodId];
        if (a == null) {
            a = new int[] {methodId};
            singletons[methodId] = a;
        }
        return a;
    }

    private String internLabel(String label) {
        String shared = labelPool.putIfAbsent(label, label);
        return (shared == null) ? label : shared;
    }

    private Resolution resolveUncached(int edgeIndex) {
        int calleeId = graph.calleeOf(edgeIndex);
        char bindKind = graph.bindKindOf(edgeIndex);

        // --- リフレクション（出所のリテラル・クラスリテラル・レシーバの連鎖から決める） ---
        if (dataflow.reflectiveKindOf(calleeId) != DataflowResolver.REFLECT_NONE) {
            Resolution reflective = dataflow.reflectiveResolution(edgeIndex, null);
            if (reflective != null) {
                return reflective;
            }
        }

        // --- importからの推定（未検証の外部ライブラリ呼び出し） ---
        if (bindKind == BindKind.GUESSED) {
            return Resolution.single(calleeId, Resolution.EXTERNAL_GUESS);
        }

        // --- 段0: 静的束縛 ---
        if (bindKind != BindKind.VIRTUAL) {
            // 既定では確定として扱うが、ここで打ち切ると拡張に到達せず
            // 呼び出し階層が切れてしまう。opt-inした拡張には必ず声をかける。
            Resolution custom = askProviders(edgeIndex, calleeId, true, null);
            if (custom != null) {
                return custom;
            }
            return Resolution.single(calleeId,
                    Resolution.STATIC_BOUND_PREFIX + BindKind.staticBoundReason(bindKind));
        }

        // --- ラムダ／メソッド参照が渡ってきた呼び出し（経路に依らず決まる分） ---
        // 関数型インターフェースのメソッドは、候補が0件（NO_IMPL）でも複数（CHA）でも、
        // 渡された値がラムダなら実行されるのはその本体。段1の候補数に関係なく先に決める。
        // 経路の引数で渡ってきたものは resolveOnPath で改めて試す
        if (dataflow.enabled() && graph.hasFunctionalImpl(calleeId)) {
            int viaLambda = dataflow.functionalTargetOf(graph.recvOrigin(edgeIndex), null);
            if (viaLambda >= 0) {
                return Resolution.single(viaLambda, Resolution.DATAFLOW_LAMBDA);
            }
        }

        // --- 段1: オーバーライド候補 ---
        Resolution base = resolveVirtual(calleeId);
        if (!Resolution.CHA.equals(base.label())) {
            return base;
        }

        // --- 段2: new された型 ---
        String sig = methods.signature(calleeId);
        IntArray fromNew = new IntArray(2);
        for (Hint h : graph.hintsOf(edgeIndex)) {
            if (!HintFact.KIND_NEW.equals(h.kind())) {
                continue;
            }
            // new された型が自分で宣言していない（親から継承した）実装も拾う。
            // 宣言だけを引くと、継承しているだけの型は候補が 0 件になって次の段へ落ちる
            int id = graph.implementationIn(h.value(), sig);
            if (id >= 0) {
                fromNew.addIfAbsent(id);
            }
        }
        if (!fromNew.isEmpty()) {
            return new Resolution(fromNew.toArray(),
                    fromNew.size() == 1 ? Resolution.LOCAL_NEW : Resolution.LOCAL_NEW_MULTI);
        }

        // --- 段3: 契約表（種類 C）→ 拡張 ---
        // 表のほうを先に引く。食い違ったときに「どちらが効いたか」を追いやすいのは、
        // 読み手が中身を見られる表のほう（docs/contracts-unification-design.md の §4）
        Resolution fromContract = askTypeContracts(edgeIndex, calleeId, null);
        if (fromContract != null) {
            return fromContract;
        }
        Resolution custom = askProviders(edgeIndex, calleeId, false, null);
        if (custom != null) {
            return custom;
        }

        // --- 段4: データフロー（経路に依存しない分） ---
        if (dataflow.enabled()) {
            String recv = graph.recvOrigin(edgeIndex);
            int resolved = dataflow.targetOf(recv, calleeId, null);
            if (resolved >= 0) {
                return Resolution.single(resolved, DataflowResolver.labelFor(recv));
            }
        }

        // --- 段5: DIコンテナのBean定義 ---
        Resolution di = springResolution(edgeIndex, calleeId, base);
        if (di != null) {
            return di;
        }

        // --- 段6: CHA ---
        return base;
    }

    /**
     * 経路の情報も使った解決。{@link #resolve} で絞れなかった呼び出しだけ、
     * この経路で渡ってきた引数の具象型を使ってもう一度試す。
     * 「必要なときだけ」にするのは、全呼び出しで試すと解析コストが呼び出し数に比例して効いてくるため。
     *
     * @param ctx この経路で分かっていること（無ければ null）
     */
    public Resolution resolveOnPath(int edgeIndex, DataflowContext ctx) {
        Resolution res = resolve(edgeIndex);
        int calleeId = graph.calleeOf(edgeIndex);

        // 経路が分かってから、利用者が与えた条件（段3）をもう一度試す。
        // ファクトリに渡すキーが呼び出し元から引数で渡ってくる形は、経路が決まって初めて値が分かる
        //
        //   void run()            { helper("USER_DAO"); }     ← 呼び出し元では決まっている
        //   void helper(String k) { Factory.get(k).find(); }  ← ここは経路ごとに決まる
        //
        // 既に 1 件に絞れているものはやり直さない。広い指定（型単位の契約など）で決まったものを
        // 経路ごとに覆すと、同じ設定でも経路によって答えが変わり、読み手が追えなくなる
        if (res.isMultiple()) {
            Resolution viaContract = askTypeContracts(edgeIndex, calleeId, ctx);
            if (viaContract == null) {
                viaContract = askProviders(edgeIndex, calleeId, false, ctx);
            }
            if (viaContract != null) {
                res = viaContract;
            }
        }
        if (res.isMultiple() && dataflow.enabled()) {
            String recv = graph.recvOrigin(edgeIndex);
            int viaPath = dataflow.targetOf(recv, calleeId, ctx);
            if (viaPath >= 0) {
                res = Resolution.single(viaPath, DataflowResolver.labelFor(recv));
            }
        }
        // ラムダ／メソッド参照が渡ってきた呼び出し。候補が複数かどうかに関係なく試す。
        // 関数型インターフェースのメソッドは、ソース上の実装が無い（Runnable#run 等）ことが
        // 多く、その場合は候補が1件（宣言のまま）になって上の条件に入らないため。
        //
        // 実装しているラムダが1つでもあるメソッド（M行がある＝hasFunctionalImpl）に限る。
        // そうしないと、ラムダを入れた変数への Object#toString() のような
        // 関数型インターフェースと関係ない呼び出しまでラムダ本体に繋いでしまう
        if (dataflow.enabled() && graph.hasFunctionalImpl(calleeId)) {
            int viaLambda = dataflow.functionalTargetOf(graph.recvOrigin(edgeIndex), ctx);
            if (viaLambda >= 0) {
                res = Resolution.single(viaLambda, Resolution.DATAFLOW_LAMBDA);
            }
        }
        // リフレクション: クラス名・メソッド名が引数で渡ってくる形は、
        // この経路で分かっている引数の値を使ってもう一度試す
        if (dataflow.reflectiveKindOf(calleeId) != DataflowResolver.REFLECT_NONE && !res.isReflection()) {
            Resolution viaPath = dataflow.reflectiveResolution(edgeIndex, ctx);
            if (viaPath != null) {
                res = viaPath;
            }
        }
        return res;
    }

    /**
     * 段1: 仮想呼び出しのオーバーライド候補を求める。
     * <pre>
     *  NO_OVERRIDE … オーバーライドしている型が1つも無い → 宣言のまま確定
     *  SINGLE_IMPL … 候補が1つだけ（IFに実装が1つ等） → その実装で確定
     *  CHA         … 候補が複数。ここは低確度
     *  NO_IMPL     … 本体を持つ候補が皆無（ソース外の実装等）。宣言のまま扱う
     *  GENERATED_IMPL:名 … 同上だが、実装がコンパイル時のアノテーション処理で生成される型
     * </pre>
     * 重要: 候補数は「サブタイプ数」ではなく「そのメソッドをオーバーライドしている
     * 宣言の数」。サブクラスが多くてもオーバーライドが1件なら候補は1件のまま。
     */
    private Resolution resolveVirtual(int calleeId) {
        if (resolvedTargets == null) {
            resolvedTargets = new int[methods.size()][];
            resolvedLabels = new String[methods.size()];
        }
        if (resolvedTargets[calleeId] != null) {
            return new Resolution(resolvedTargets[calleeId], resolvedLabels[calleeId]);
        }

        String declType = methods.typeFqn(calleeId);
        String sig = methods.signature(calleeId);

        IntArray cands = new IntArray(4);
        if (methods.hasBody(calleeId)) {
            cands.add(calleeId);   // 宣言型自身の実装（IFの抽象メソッドは除外される）
        }
        for (String sub : graph.hierarchy.transitiveSubtypes(declType)) {
            // サブタイプ自身の宣言ではなく「そのサブタイプで実際に動く実装」を候補にする。
            // 直接の宣言だけを見ると、次の 2 つを取りこぼす。
            //   (1) 親クラスから継承した実装: abstract class Base { m(){} }、
            //       class C extends Base implements I {} のとき、I.m() の実装は Base.m だが
            //       C#m は宣言されていないので候補が 0 件（実装なし）になっていた
            //   (2) サブインターフェースでの抽象な再宣言: interface B extends A { m(); } は
            //       本体を持たないのに候補に数えられ、実装が 1 件でも CHA（未展開）のままになっていた
            // implementationIn は本体を持つ宣言まで親を辿るので、どちらも正しく扱える
            int id = graph.implementationIn(sub, sig);
            if (id >= 0) {
                cands.addIfAbsent(id);
            }
        }

        int[] targets;
        String label;
        if (cands.isEmpty()) {
            targets = new int[] {calleeId};
            // 実装がコンパイル時のアノテーション処理で生成される型（Doma の @Dao 等）は、
            // 生成物がソースに無いだけで「実装が無い」わけではない。両者を混ぜない
            GeneratedImpl generated = GeneratedImpl.of(graph.hierarchy.annotationsOf(declType));
            label = (generated == null) ? Resolution.NO_IMPL
                    : Resolution.GENERATED_IMPL_PREFIX + generated.label();
        } else if (cands.size() == 1) {
            targets = new int[] {cands.get(0)};
            label = (cands.get(0) == calleeId) ? Resolution.NO_OVERRIDE : Resolution.SINGLE_IMPL;
        } else {
            targets = cands.toArray();
            label = Resolution.CHA;
        }
        resolvedTargets[calleeId] = targets;
        resolvedLabels[calleeId] = label;
        return new Resolution(targets, label);
    }

    /**
     * 段5: DIコンテナ（Spring）が実際に注入しうる型だけに候補を絞る。
     *
     * CHAの候補は「宣言型のサブタイプすべて」だが、コンテナが注入するのは
     * Beanとして登録された型だけ。候補のうちBeanが1つだけなら、そのBeanが動く。
     * &#64;Qualifier / &#64;Resource(name) の指定があればBean名でさらに絞る。
     *
     * レシーバがフィールドか引数のときだけ適用する。DIで受け取ったインスタンスは
     * 必ずこのどちらかの形で現れ、その場で new したレシーバ（段2で解決済み）や
     * static 呼び出しにコンテナの都合を持ち込むと、かえって誤って絞ることになるため。
     *
     * 候補は宣言型のサブタイプから引き直す。Beanクラス自身がそのメソッドを
     * オーバーライドせず、抽象基底クラスから継承している場合、CHAの候補には
     * 基底クラスの宣言しか現れず、Beanかどうかで照合できないため。
     *
     * @param base 段1の結果（CHA＝候補が複数）
     * @return 1つに定まったときだけ Resolution。定まらなければ null
     */
    private Resolution springResolution(int edgeIndex, int calleeId, Resolution base) {
        SpringBeans beans = graph.beans();
        if (!beans.enabled() || !base.isMultiple()) {
            return null;
        }
        char recvKind = graph.recvKindOf(edgeIndex);
        if (recvKind != RecvKind.FIELD && recvKind != RecvKind.PARAM) {
            return null;
        }
        String recvOrigin = graph.recvOrigin(edgeIndex);
        String qualifier = (Origin.kindOf(recvOrigin) == Origin.FIELD)
                ? beans.qualifierOf(Origin.valueOf(Origin.head(recvOrigin))) : null;

        String declType = methods.typeFqn(calleeId);
        String sig = methods.signature(calleeId);
        IntArray hits = new IntArray(2);
        List<String> types = new ArrayList<>(graph.hierarchy.transitiveSubtypes(declType));
        types.add(declType);
        for (String type : types) {
            if (!beans.isBean(type) || (qualifier != null && !beans.hasBeanName(type, qualifier))) {
                continue;
            }
            int id = graph.implementationIn(type, sig);
            if (id >= 0) {
                hits.addIfAbsent(id);
            }
        }
        if (hits.size() != 1) {
            return null;   // Beanが無い、または複数。絞れないことより誤って絞ることの方が害が大きい
        }
        return Resolution.single(hits.get(0),
                (qualifier == null) ? Resolution.SPRING_DI : Resolution.SPRING_DI_QUALIFIER);
    }

    /**
     * 契約表（種類 C）で具象型が決まるか見る。決まらなければ null。
     *
     * <p>ファクトリ＋キーの行（C-3）は、レシーバの出所がファクトリの戻り値なら、そこに載っている
     * 実引数の値で引く。フェーズAの証拠採取は要らない（{@link TypeContracts} の「C-3 のキーは
     * どこから来るか」）。
     *
     * <p>{@code ctx} が null なら経路に依存しない分だけを決める（{@link #resolve} から）。
     * 経路が分かってからの呼び出し（{@link #resolveOnPath}）では、呼び出し元から引数で渡ってきた
     * キーも値まで辿れる。
     *
     * <p>右辺の型を 1 つも採用できないとき（その型にも親にもその本体が無い）は候補を落として
     * CHA に戻す。ここで警告は出さず、解析の最後に {@link ContractUsage} がまとめて挙げる
     * （エッジごとに呼ばれるので、その場で出すと同じ行の警告が何度も並ぶ）。
     */
    private Resolution askTypeContracts(int edgeIndex, int calleeId, DataflowContext ctx) {
        if (typeContracts.isEmpty()) {
            return null;
        }
        // 引く順番は C-3（ファクトリ＋キー）→ C-2（型＋メソッド）→ C-1（型）。狭いほうが先
        TypeContracts.Contract contract = dataflow.enabled()
                ? typeContracts.matchFactory(graph.recvOrigin(edgeIndex), dataflow, ctx) : null;
        if (contract == null) {
            contract = typeContracts.matchFor(methods.typeFqn(calleeId), methods.signature(calleeId));
        }
        if (contract == null) {
            return null;
        }
        String sig = methods.signature(calleeId);
        IntArray ids = new IntArray(contract.candidates().length);
        for (String fqn : contract.candidates()) {
            // 契約が指す型が自分で宣言していない（親から継承した）実装も拾う。拡張と同じ扱い
            int id = graph.implementationIn(fqn, sig);
            if (id >= 0) {
                ids.addIfAbsent(id);
            }
        }
        if (ids.isEmpty()) {
            return null;
        }
        typeContracts.usage().markApplied(contract.row());
        return new Resolution(ids.toArray(), Resolution.CONTRACT);
    }

    /**
     * 拡張（フェーズB）に候補を尋ねる。
     *
     * @param staticBoundOnly true なら appliesToStaticBound() が true の拡張だけに尋ねる
     * @return 解決できた場合のみ Resolution。できなければ null
     */
    private Resolution askProviders(int edgeIndex, int calleeId, boolean staticBoundOnly,
                                    DataflowContext ctx) {
        if (providers.isEmpty()) {
            return null;
        }
        List<Hint> hints = hintsFor(edgeIndex, ctx);
        String declType = methods.typeFqn(calleeId);
        String sig = methods.signature(calleeId);

        for (TypeCandidateProvider provider : providers) {
            if (staticBoundOnly && !provider.appliesToStaticBound()) {
                continue;
            }
            String[] candidates;
            try {
                candidates = provider.candidates(declType, sig, hints);
            } catch (RuntimeException e) {
                Log.warn(Messages.format("graph.provider.failed", provider.getClass().getName(), e));
                continue;
            }
            if (candidates == null || candidates.length == 0) {
                continue;
            }
            IntArray ids = new IntArray(candidates.length);
            for (String c : candidates) {
                // 拡張が返した型が自分で宣言していない（親から継承した）実装も拾う。
                // 宣言だけを引くと、継承しているだけの型を返した拡張が黙って効かなくなる。
                // 単純名で返されたものは FQN に直す（1 件に定まるときだけ。TypeNames）
                int id = graph.implementationIn(graph.typeNames().toFqn(c), sig);
                if (id >= 0) {
                    ids.addIfAbsent(id);
                } else {
                    warnUnusableCandidate(provider, c, sig);
                }
            }
            if (!ids.isEmpty()) {
                return new Resolution(ids.toArray(), provider.label());
            }
        }
        return null;
    }

    /** キーの種別（{@link FactoryCalls}）を、拡張へ渡す証拠の種別に直す */
    private static String hintKindOf(char keyKind) {
        return switch (keyKind) {
            case Origin.CONST -> Hint.KIND_FACTORY_CONST;
            case Origin.CLASS -> Hint.KIND_FACTORY_CLASS;
            default -> Hint.KIND_FACTORY_KEY;
        };
    }

    /**
     * 拡張に渡す証拠。
     *
     * <p>キャッシュに載っている組み込みの証拠（X 行の {@code NEW}）に加えて、<b>ファクトリに
     * 渡されたキーをデータフローから読んで足す</b>。キーは値グラフに載っているので、
     * 呼び出し箇所を走査し直す必要が無い（契約表の種類 C と同じ読み口。{@link FactoryCalls}）。
     *
     * <p>「どのファクトリから来た値か」も {@link Hint#KIND_FACTORY} で渡すので、同じ型を返す
     * ファクトリが複数あって規則が違う場合も、拡張の中だけで場合分けできる。
     *
     * <p>同じ証拠が既に並んでいれば足さない（同じものが 2 つ並ばないように）。
     */
    private List<Hint> hintsFor(int edgeIndex, DataflowContext ctx) {
        List<Hint> stored = graph.hintsOf(edgeIndex);
        if (!dataflow.enabled()) {
            return stored;
        }
        List<FactoryCalls.Key> keys = FactoryCalls.keysOf(graph.recvOrigin(edgeIndex), dataflow, ctx);
        if (keys.isEmpty()) {
            return stored;
        }
        List<Hint> all = new ArrayList<>(stored.size() + keys.size() + 1);
        all.addAll(stored);
        addIfAbsent(all, new Hint(Hint.KIND_FACTORY, keys.get(0).typeAndName()));
        for (FactoryCalls.Key key : keys) {
            addIfAbsent(all, new Hint(hintKindOf(key.kind()), key.key()));
        }
        return all;
    }

    private static void addIfAbsent(List<Hint> hints, Hint hint) {
        if (!hints.contains(hint)) {
            hints.add(hint);
        }
    }

    /**
     * 拡張が返した候補を採用できなかったことを知らせる（同じものは 1 回だけ）。
     *
     * 採用できないのは、その型（と親）にそのシグネチャの本体が無いとき。FQN の打ち間違い、
     * 解析対象外の型、シグネチャ違いが原因になる。候補を落とすだけなので呼び出しは漏れないが、
     * 黙って CHA に戻ると「対応表を書いたのに効いていない」ことに気づけない。
     * エッジごとに呼ばれるので、同じ候補で何度も出さないよう記録しておく。
     */
    private void warnUnusableCandidate(TypeCandidateProvider provider, String fqn, String sig) {
        if (!warnedCandidates.add(provider.label() + "\t" + fqn + "#" + sig)) {
            return;
        }
        List<String> conflicts = graph.typeNames().ambiguousCandidates(fqn);
        if (!conflicts.isEmpty()) {
            // 単純名が複数の型に当たる。どちらかに決めると誤った型へ静かに解決するので使わない
            Log.warn(Messages.format("graph.provider.ambiguous", fqn, conflicts.size(),
                    String.join(" / ", conflicts), provider.getClass().getName()));
            return;
        }
        Log.warn(Messages.format("graph.provider.unusable", fqn, sig,
                provider.getClass().getName(), provider.label()));
    }

    // ------------------------------------------------------------
    // 解決後のグラフの集計
    // ------------------------------------------------------------

    /**
     * 入次数を数える。
     *
     * 重要: 宣言型の呼び出し先ではなく「解決後の候補」に対して数える。
     * そうしないと、IF経由でしか呼ばれない実装クラス（DAO実装など）が
     * すべて入次数0となり、真の入口と区別がつかなくなる。
     */
    public int[] inDegrees() {
        if (inDegree != null) {
            return inDegree;
        }
        inDegree = new int[methods.size()];
        for (int caller = 0; caller < methods.size(); caller++) {
            for (int e = graph.edgeStart(caller); e < graph.edgeEnd(caller); e++) {
                for (int t : resolve(e).targets()) {
                    inDegree[t]++;
                }
                // 契約で呼び戻される側も「呼ばれている」。ここで数えないと
                // Thread で起動する Runnable の run が ENTRY_CANDIDATE に混ざる
                for (CallbackContracts.Match m : callbackTargets(e, null)) {
                    inDegree[m.target()]++;
                }
            }
        }
        return inDegree;
    }

    /** 起点集合から解決後のエッジを辿って到達できるメソッドに印を付ける */
    public boolean[] reachableFrom(int[] roots) {
        boolean[] seen = new boolean[methods.size()];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int r : roots) {
            if (!seen[r]) {
                seen[r] = true;
                queue.add(r);
            }
        }
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            for (int e = graph.edgeStart(cur); e < graph.edgeEnd(cur); e++) {
                for (int t : resolve(e).targets()) {
                    if (!seen[t]) {
                        seen[t] = true;
                        queue.add(t);
                    }
                }
                for (CallbackContracts.Match m : callbackTargets(e, null)) {
                    int t = m.target();
                    if (!seen[t]) {
                        seen[t] = true;
                        queue.add(t);
                    }
                }
            }
        }
        return seen;
    }
}
