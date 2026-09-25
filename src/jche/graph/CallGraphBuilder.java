// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jche.cache.CacheFormat;
import jche.cache.CacheReader;
import jche.cache.CallEdgeFact;
import jche.cache.CallSiteValues;
import jche.cache.FieldAssignFact;
import jche.cache.FieldDeclFact;
import jche.cache.FunctionalImplFact;
import jche.cache.Guard;
import jche.cache.MethodDeclFact;
import jche.cache.MethodRef;
import jche.cache.ModifierTokens;
import jche.cache.Origin;
import jche.cache.OverrideFact;
import jche.cache.ReturnFact;
import jche.cache.SymbolTable;
import jche.cache.TempFiles;
import jche.cache.TypeFact;
import jche.cache.UnresolvedCallFact;
import jche.cache.ValueNode;
import jche.util.Log;
import jche.util.Messages;
import jche.util.Names;
import jche.util.RunControl;
import jche.util.Warnings;

/**
 * キャッシュファイルをスキャンして {@link CallGraph} を構築する。
 * <pre>
 *   スキャン（1 回）… メソッドを ID 化し、呼び出し元ごとの本数を数える。型階層・フィールド注入の判定・
 *                    戻り値もこの回で済ませる。エッジ 1 本ごとに、ID と呼び出し箇所の値
 *                    （値の表の参照・条件の表の番号、修飾する型は文字列の置き場の番号、証拠は証拠の表の
 *                    番号にしたもの）を一時ファイルへ固定長のバイナリの記録として書き出す（{@link EdgeSpill}）
 *   配置         … 数えた本数から offsets とちょうどの長さのエッジ配列を作り、一時ファイルを
 *                    先頭から読み直して、各記録を {@code cursor[呼び出し元]++} の位置に置く
 * </pre>
 * キャッシュを 2 回スキャンしていた以前の形と、ID の振られ方・エッジの並び（呼び出し元ごとにファイル上の順）は
 * まったく同じになる（どちらもファイル上の順に処理するため）。
 * ヒープに載るのは、1 ブロック分の記号表（S 行）と値グラフ（N 行）と条件（G 行）の手元、取り込んだノードだけの
 * 値の表（下の「値の表」）、ちょうどの長さのエッジ配列。エッジの一覧をヒープに溜めない代わりに、
 * 一時ファイル（エッジ 1 本あたり 34 バイト）を使う。一時ファイルはキャッシュと同じフォルダに作り、
 * 終われば（失敗しても）消す。
 *
 * <p>型解決に失敗した呼び出しの一覧（{@code call-hierarchy.csv} の末尾。{@code jche.report.UnresolvedReport}）
 * に出す U 行も、このスキャンで拾って {@link UnresolvedCalls} に渡す（CSV を書く側がキャッシュを
 * 読み直さなくて済むように。解析サーバーは一覧を書かないので拾わない）。
 *
 * <h2>ブロックの読み方</h2>
 * メソッドを指す列は、ブロックの記号表（S 行）の番号で書かれている（{@link SymbolTable}）。
 * S 行を読んだ時点ではメソッドを ID 化せず、参照する行を読んだときに ID 化する。
 * {@link MethodTable} の ID は初めて ID 化した順に振られ、ID 化の順は「戻り値（R 行）→ 宣言（D 行）→
 * 上書き（O 行）→ 呼び出し（C・U 行。呼び出し元、呼び出し先の順）」である（書き手もブロックの行をこの順に並べる）。
 * M 行・A 行の呼び出し元は ID 化しない。ID の順はブロックの並び（差分更新で変わる）に左右されるので、
 * 出力の並びには使わない。同じ行に並ぶ宣言の前後は、D 行がブロックの中で何番目か（宣言の順番）で決める
 * （{@link MethodTable#compareDeclarationOrder}）。
 *
 * <p>呼び出し箇所の値（レシーバ・実引数・ガードの番号・new の証拠）は C 行・U 行の末尾の列にある
 * （{@link CallSiteValues.Row}）。値はどれも同じブロックの N 行のノードを番号で指す。読み手（{@link DataflowResolver}・
 * {@link GuardEvaluator} など）は、それを取り込んだ値の表と条件の表（下の「値の表」）を読む。
 * <ul>
 *   <li>戻り値（R 行）… ノードを丸ごと取り込む。追跡できない（-1）は {@link ValueStore#NONE}</li>
 *   <li>フィールドへの代入（J 行）… ノードの頭（種別と値の葉）だけ（{@link FieldFacts} は頭で比べる）</li>
 *   <li>条件（G 行の表）… 条件の表に写す。判定される式はノードの頭の葉</li>
 *   <li>証拠（hints 列）… 型の並びを証拠のリストにして、エッジに直接付ける</li>
 * </ul>
 * フィールドへの代入（J 行）は同じブロックの V 行・D 行と組で判定するので、ブロックを読み終えてから渡す。
 *
 * <h2>値の表</h2>
 * 値を値の表（{@link ValueStore}）と条件の表（{@link GuardTable}）に取り込む（{@link ValueStoreBuilder} /
 * {@link GuardTableBuilder}）。取り込むのは戻り値・代入・条件・呼び出し箇所から辿れるノードだけ。
 * 表は文字列の文法を通さないので、値が {@code | ; { }} を含んでも読み違えない
 * （以前は出所の文字列 {@link Origin} に組み直して渡していた。{@code docs/cache-unification-qa.md} の「読み手が値の表を読む」）。
 *
 * <p>値を読まない指定（{@code dataflow.enabled=false}）のときは、N・G・R・J 行と
 * 呼び出し箇所の値の列を読まない。値が無いものとして組むので、具象クラスの解決は CHA まで、
 * 条件分岐の打ち切りは起きない
 *
 * <p>読み終えたら、フェーズ1 が最後まで書き終えたキャッシュか（ヘッダの形式の版・最終行の Z 行とブロック数）を
 * 確かめ、違えば組まずに例外にする。書きかけのキャッシュ（同じキャッシュのフォルダを使う別の実行と重なった、など）
 * から組むと、呼び出しの欠けたグラフで CSV を「成功」として書いてしまうため（{@code docs/cache-unification-qa.md} の Q56）。
 *
 * 読み手の判断として、U行（型解決失敗）に import からの推定候補があれば、
 * それをエッジにする（クラスパス不足で階層から消えるより、未検証と分かる形で残す方針）。
 */
public final class CallGraphBuilder {

    private final CallGraph graph = new CallGraph();
    private final MethodTable methods = graph.methods;
    private final Path cacheFile;
    /** 値（N・G・R・J 行と呼び出し箇所の値）を読むか（{@code dataflow.enabled}） */
    private final boolean readValues;
    /** 型解決に失敗した呼び出しの一覧に出す U 行の置き場。拾わないなら null（解析サーバー） */
    private final UnresolvedCalls unresolved;

    /** スキャンで数える、呼び出し元ごとのエッジ数 */
    private final IntArray outDegree = new IntArray(1 << 16);
    private final FieldFacts fields = new FieldFacts();
    private long edgeCount;

    /** 値の表と条件の表が共有する文字列の置き場（修飾する型も置く） */
    private final StringPoolBuilder strings = new StringPoolBuilder();
    private final ValueStoreBuilder valueBuilder = new ValueStoreBuilder(strings, methods);
    private final GuardTableBuilder guardBuilder = new GuardTableBuilder(strings, valueBuilder);
    /**
     * 戻り値（R 行）をファイル上の順に溜めたもの（メソッドID と参照の組）。スキャンの後でメソッドごとに並べ、
     * 並べ終えたら捨てる（{@link #freezeValues}）
     */
    private IntArray returnMethods = new IntArray(1 << 10);
    private IntArray returnRefs = new IntArray(1 << 10);

    /** ブロックの外を指す番号に出会ったことを警告したか（1度だけ出す） */
    private boolean warnedAboutReference;

    private CallGraphBuilder(Path cacheFile, boolean readValues, UnresolvedCalls unresolved) {
        this.cacheFile = cacheFile;
        this.readValues = readValues;
        this.unresolved = unresolved;
    }

    /**
     * 型解決に失敗した呼び出しを拾わずに組む（{@link #build(Path, boolean, List, SpringBeans, UnresolvedCalls)}
     * の最後を null にしたもの）
     */
    public static CallGraph build(Path cacheFile, boolean readValues,
                                 List<String> sourceFolderOrder, SpringBeans beans)
            throws IOException {
        return build(cacheFile, readValues, sourceFolderOrder, beans, null);
    }

    /**
     * @param readValues        値（値グラフ・戻り値の出所・フィールドへの代入・証拠・呼び出し箇所の値）を
     *                          読むか。{@code dataflow.enabled=false} なら false
     * @param sourceFolderOrder 起点の並び替えに使うソースフォルダの順（プロジェクトルートからの相対パス）
     * @param beans             DIコンテナのBean定義の取り込み先（使わないなら {@link SpringBeans#DISABLED}）
     * @param unresolved        型解決に失敗した呼び出しの一覧に出す U 行の置き場。拾わないなら null
     */
    public static CallGraph build(Path cacheFile, boolean readValues,
                                 List<String> sourceFolderOrder, SpringBeans beans,
                                 UnresolvedCalls unresolved)
            throws IOException {
        CallGraphBuilder b = new CallGraphBuilder(cacheFile, readValues, unresolved);
        b.graph.sourceFolderOrder = sourceFolderOrder;
        b.graph.beans = beans;
        try (EdgeSpill spill = new EdgeSpill(cacheFile)) {
            b.scan(spill);
            RunControl.checkCancelled();
            // 値の表の構築にだけ使う索引とブロックの手元は、エッジ配列を確保する前に捨てる
            b.freezeValues();
            b.allocateEdges();
            b.placeEdges(spill);
        }
        b.graph.finishBuild();
        return b.graph;
    }

    // ------------------------------------------------------------
    // スキャン: ID化と本数カウント、エッジの記録を一時ファイルへ
    // ------------------------------------------------------------

    private void scan(EdgeSpill spill) throws IOException {
        String label = Messages.get("graph.progress.build");
        long size = Files.size(cacheFile);
        // 進捗はブロックの切れ目で、読んだバイト数がおよそ 2% 進むごとに出す（解析サーバーは通知を 1 行ずつ送るため）
        long step = Math.max(size / 50, 1L << 16);
        long nextReport = 0;
        try (CacheReader in = CacheReader.open(cacheFile)) {
            // フェーズ1 が書き終えたキャッシュか（形式の版・最終行の Z 行とブロック数）。書きかけ・別の形式のものを
            // 読んで組んだグラフは呼び出しが欠けているので、組まずに止める（下の checkComplete）
            boolean headerOk = in.header().startsWith(CacheFormat.VERSION + CacheFormat.SEP);
            long blocks = 0;
            long trailer = -1;
            boolean afterTrailer = false;
            String currentFile = null;
            SymbolTable.Reader symbols = new SymbolTable.Reader();
            BlockNodes nodes = new BlockNodes();
            BlockGuards guards = new BlockGuards(nodes);
            // ブロックの中で次に読む D 行の位置（ファイルの中の宣言の順番）
            int declOrdinal = 0;
            while (in.next()) {
                if (trailer >= 0) {
                    afterTrailer = true;   // Z 行（最終行）の後ろに行がある
                }
                switch (in.rowType()) {
                    case CacheFormat.ROW_END -> trailer = CacheFormat.trailerCountOf(in.columns());
                    case CacheFormat.ROW_FILE -> {
                        blocks++;
                        // 中止の受け付けと進捗はブロックの切れ目で（途中で抜けてもキャッシュは読むだけなので壊れない）
                        RunControl.checkCancelled();
                        if (in.lineStart() >= nextReport) {
                            RunControl.progress(label, in.lineStart(), size);
                            nextReport = in.lineStart() + step;
                        }
                        // ファイル単位で完結する判定（フィールド注入）を、読み終えた前のブロックについて確定する。
                        // 代入（J行）はブロックの後ろにあるので、宣言（V行・D行）が揃ったこの時点で渡す
                        applyPendingAssigns();
                        fields.flushInto(graph.fieldHeads);
                        currentFile = in.filePath();
                        symbols.clear();
                        nodes.clear();
                        guards.clear();
                        valueBuilder.beginBlock();
                        guardBuilder.beginBlock();
                        declOrdinal = 0;
                        if (unresolved != null) {
                            unresolved.beginBlock(currentFile);
                        }
                    }
                    case CacheFormat.ROW_SYMBOL -> symbols.add(in.columns());
                    case CacheFormat.ROW_VALUE_NODE -> {
                        if (readValues) {
                            nodes.add(ValueNode.fromRow(in.columns()));
                        }
                    }
                    case CacheFormat.ROW_GUARD -> {
                        if (readValues) {
                            guards.add(in.columns());
                        }
                    }
                    case CacheFormat.ROW_RETURN -> {
                        // D 行より前に並ぶので、戻り値のあるメソッドは宣言より先に ID 化される
                        if (readValues && inRange(in.columns(), symbols.array())) {
                            readReturn(ReturnFact.fromRow(in.columns(), symbols.array()), nodes);
                        }
                    }
                    case CacheFormat.ROW_TYPE -> {
                        TypeFact t = TypeFact.fromRow(in.columns());
                        if (t != null) {
                            graph.hierarchy.add(t);
                            graph.beans.type(t);
                        }
                    }
                    case CacheFormat.ROW_METHOD_DECL -> {
                        // 読めない行も数える（順番は書き手が D 行を並べた位置そのもの。どの行が読めたかに左右させない）
                        int ordinal = declOrdinal++;
                        MethodDeclFact d = inRange(in.columns(), symbols.array())
                                ? MethodDeclFact.fromRow(in.columns(), symbols.array()) : null;
                        if (d != null) {
                            int id = methods.intern(d.ref());
                            ensure(outDegree, id);
                            if (takesDeclaration(id, currentFile, d)) {
                                methods.setDeclaration(id, currentFile, d.declLine(), d.endLine(),
                                        d.hasBody(), ordinal);
                                if (ModifierTokens.has(d.mods(), ModifierTokens.LAMBDA)) {
                                    methods.markLambdaBody(id);
                                }
                                methods.setDeclarationDetails(id, d.annotations(), d.mods());
                            }
                            fields.declaration(d);
                            graph.beans.method(id, d);
                        }
                    }
                    case CacheFormat.ROW_OVERRIDE -> {
                        // O行はD行の直後に並ぶので、ここで intern すれば宣言の情報は揃っている
                        OverrideFact o = inRange(in.columns(), symbols.array())
                                ? OverrideFact.fromRow(in.columns(), symbols.array()) : null;
                        if (o != null) {
                            graph.overrides.add(o, methods.intern(o.ref()));
                        }
                    }
                    case CacheFormat.ROW_CALL -> addCall(in.columns(), symbols.array(), nodes, guards, spill);
                    case CacheFormat.ROW_UNRESOLVED ->
                            addUnresolved(in.columns(), symbols.array(), nodes, guards, spill);
                    case CacheFormat.ROW_FIELD_DECL -> {
                        FieldDeclFact v = FieldDeclFact.fromRow(in.columns());
                        if (v != null) {
                            fields.field(v);
                            graph.beans.field(v);
                        }
                    }
                    case CacheFormat.ROW_FUNCTIONAL_IMPL -> {
                        // 使うのは関数型インターフェースのメソッドキーだけで、呼び出し元は使わない。
                        // 呼び出し元の記号が壊れていても行は捨てない（呼び出し元 null として読む）
                        FunctionalImplFact m = FunctionalImplFact.fromRow(in.columns(), symbols.array());
                        if (m != null && !m.ifaceMethodKey().isEmpty()) {
                            graph.functionalImpls.add(m.ifaceMethodKey());
                        }
                    }
                    case CacheFormat.ROW_FIELD_ASSIGN -> {
                        // 代入はフィールドの宣言（V 行）が揃ってからでないと拾われないので、
                        // ここでは溜めるだけにして、ブロックを読み終えてから渡す。
                        // 値はノードの頭の葉に直しておく（N 行はブロックの先頭にあるので揃っている）
                        FieldAssignFact j = readValues ? FieldAssignFact.fromRow(in.columns()) : null;
                        if (j != null) {
                            pendingAssigns.add(new PendingAssign(j, nodes.headOf(j.node()),
                                    valueBuilder.localKind(j.node())));
                        }
                    }
                    default -> {
                        // I行は差分更新のためだけの行、A行・K行は今の読み手は使わない
                    }
                }
            }
            applyPendingAssigns();
            fields.flushInto(graph.fieldHeads);
            RunControl.progress(label, size, size);
            warnDuplicateTypes();
            if (!headerOk || afterTrailer || trailer != blocks) {
                // ふつうは起きない（フェーズ1 が書き終えたものを、同じキャッシュのフォルダの錠を持ったまま読む）。
                // 起きたら、呼び出しの欠けたグラフで CSV を「成功」として書かないよう、ここで止める
                throw new IOException(Messages.format("graph.cacheIncomplete", cacheFile, blocks,
                        (trailer < 0) ? "-" : String.valueOf(trailer)));
            }
        }
        spill.finishWriting();
        if (unresolved != null) {
            unresolved.finishWriting();
        }
        graph.hierarchy.sortForDeterminism();
        Log.info(Messages.format("graph.collected", graph.hierarchy.size(), methods.size(), edgeCount));
        if (edgeCount > Integer.MAX_VALUE) {
            throw new IOException(Messages.format("graph.tooManyEdges", edgeCount));
        }
    }

    /**
     * D 行の宣言をメソッド表に記録するか。同じメソッドが 2 つのファイルで宣言されている（同じクラスが 2 つの
     * ソースフォルダにある）ときは、先に並ぶソースフォルダの宣言を使う（JDT がソースパスの先勝ちで解決するのと
     * 同じ。同じフォルダならパスの順）。以前は後から読んだ宣言が勝ち、キャッシュの中のブロックの並び（差分更新で
     * 変わる）で methods.csv の宣言の場所が変わっていた。重なったことは警告する（{@link #warnDuplicateTypes}）
     */
    private boolean takesDeclaration(int id, String file, MethodDeclFact d) {
        String prev = methods.declFile(id);
        if (prev == null || prev.equals(file)) {
            return true;
        }
        String first = (prev.compareTo(file) < 0) ? prev : file;
        String second = first.equals(prev) ? file : prev;
        if (duplicateTypes.size() < DUPLICATE_TYPES_LIMIT || duplicateTypes.containsKey(first + '\n' + second)) {
            duplicateTypes.putIfAbsent(first + '\n' + second, d.ref().typeFqn());
        } else {
            duplicateTypesOmitted = true;
        }
        int now = graph.sourceFolderIndexOf(file);
        int before = graph.sourceFolderIndexOf(prev);
        return now < before || (now == before && file.compareTo(prev) < 0);
    }

    /**
     * 同じ型を宣言しているファイルの組を警告する（ビルドが通らない状態の 1 つ。warnings.txt の
     * 「ソースにコンパイルエラーがある」に載る）。名前の違う 2 つのファイルが同じ型を宣言していると、JDT は
     * 同じバッチなら後ろの方を「型が重複している」エラーにしてその型を読まず、別々のバッチなら両方を読む
     * （ここに来るのは両方を読んだとき。同じメソッドの宣言は 1 つにまとまり、呼び出しは両方のものが出る）。
     * どちらになるかは一緒に解析したファイルの組み合わせで決まり、差分更新と全件解析とで変わりうる。文言は
     * それをそのまま伝える（「片方の呼び出しは出ない」とは言い切らない。{@code docs/cache-unification-qa.md} の
     * Q61・Q68）
     */
    private void warnDuplicateTypes() {
        for (Map.Entry<String, String> e : duplicateTypes.entrySet()) {
            String[] files = e.getKey().split("\n", 2);
            int a = graph.sourceFolderIndexOf(files[0]);
            int b = graph.sourceFolderIndexOf(files[1]);
            String used = (b < a) ? files[1] : files[0];
            Warnings.warn(Warnings.Topic.BUILD, Messages.format("graph.duplicateType", e.getValue(), files[0],
                    files[1], used));
        }
        if (duplicateTypesOmitted) {
            Warnings.warn(Warnings.Topic.BUILD, Messages.format("graph.duplicateType.more", DUPLICATE_TYPES_LIMIT));
        }
    }

    /** 警告するファイルの組の上限 */
    private static final int DUPLICATE_TYPES_LIMIT = 20;
    /** 同じメソッドを宣言していたファイルの組（パスの順に改行でつないだもの）-> 型 */
    private final Map<String, String> duplicateTypes = new TreeMap<>();
    /** 上限を超えて警告しなかった組があったか */
    private boolean duplicateTypesOmitted;

    /**
     * J 行 1 件と、その値（ノードの頭の葉の参照 {@link BlockNodes#headOf} と、その種別）
     */
    private record PendingAssign(FieldAssignFact fact, int head, char headKind) {
    }

    /** 今のブロックのフィールドへの代入（J行）。宣言が揃ってから {@link #fields} に渡す */
    private final List<PendingAssign> pendingAssigns = new ArrayList<>();

    /** 溜めた代入を渡して捨てる。{@code fields.flushInto} の直前に呼ぶ */
    private void applyPendingAssigns() {
        for (PendingAssign a : pendingAssigns) {
            fields.assignment(a.fact(), a.head(), a.headKind());
        }
        pendingAssigns.clear();
    }

    /**
     * R 行（戻り値）1 件。メソッドを ID 化し、値のノードを子も含めて値の表に取り込む。
     * 追跡できない（-1）・ブロックの外を指すものは {@link ValueStore#NONE}（「1 つでも追跡できなければ戻り値は
     * 不定」の判定に効く。ブロックの外は 1 度だけ警告する）。並びはファイル上の順で、同じ参照はスキャンの後で
     * 1 つにまとめる（{@link #freezeValues}）。メソッドの鍵で集めるだけなので、どのブロックで出会っても同じ結果になる。
     * 値を切り詰めないので、戻り値の文字列リテラルが {@code | ;} を含んでもそのまま使える
     * （以前は、そういう文字列を読み違えないよう U として渡していた。docs/cache-unification-qa.md の Q9）
     */
    private void readReturn(ReturnFact r, BlockNodes nodes) {
        if (r == null) {
            return;
        }
        int id = methods.intern(r.method());
        ensure(outDegree, id);
        if (r.node() != ValueNode.NONE) {
            if (nodes.inRange(r.node(), false)) {
                nodes.checkReferences();
            } else {
                warnBadReference();
            }
        }
        returnMethods.add(id);
        returnRefs.add(valueBuilder.importValue(r.node()));
    }

    /** C 行を読む。記号がブロックの外を指す・列が足りなければ null */
    private CallEdgeFact callOf(String[] cols, MethodRef[] symbols) {
        return inRange(cols, symbols) ? CallEdgeFact.fromRow(cols, symbols) : null;
    }

    /** U 行を読む。{@link #callOf} と同じく、記号がブロックの外を指す・列が足りなければ null */
    private UnresolvedCallFact unresolvedOf(String[] cols, MethodRef[] symbols) {
        return inRange(cols, symbols) ? UnresolvedCallFact.fromRow(cols, symbols) : null;
    }

    /**
     * 行の記号がどれもブロックの記号表の読めた S 行を指すか（{@link SymbolTable#refsInRange}）。
     * 範囲の外、または読めなかった S 行を指していれば 1 度だけ警告して false（その行は使わない）。
     *
     * <p>差分更新（{@code CacheUpdater} のパス1）がブロックの検査値を確かめているので、ここで外れることは
     * 事実上ない。外れたときに黙って読み飛ばすと呼び出しが静かに消えるので、利用者に知らせる
     */
    private boolean inRange(String[] cols, MethodRef[] symbols) {
        if (SymbolTable.refsInRange(cols, symbols)) {
            return true;
        }
        warnBadReference();
        return false;
    }

    private void warnBadReference() {
        if (!warnedAboutReference) {
            warnedAboutReference = true;
            Path dir = cacheFile.toAbsolutePath().getParent();
            Log.warn(Messages.format("cache.badReference", cacheFile.getFileName(),
                    (dir == null) ? cacheFile : dir));
        }
    }

    private void countEdge(int caller, int callee) {
        ensure(outDegree, caller);
        ensure(outDegree, callee);
        outDegree.set(caller, outDegree.get(caller) + 1);
        edgeCount++;
    }

    /** C 行 1 行をエッジにする。本数を数え、エッジの中身を一時ファイルへ書く */
    private void addCall(String[] cols, MethodRef[] symbols, BlockNodes nodes, BlockGuards guards,
                         EdgeSpill spill) throws IOException {
        CallEdgeFact c = callOf(cols, symbols);
        if (c == null) {
            return;
        }
        int caller = methods.intern(c.caller());
        int callee = methods.intern(c.callee());
        countEdge(caller, callee);
        CallSiteValues.Row values = valuesOf(cols, nodes, guards);
        String q = c.qualifier();
        int qualifier = (q == null || q.isEmpty()) ? -1 : strings.intern(q);
        spill.write(caller, callee, c.callLine(), (byte) BindKind.of(c.callee().name(), c.calleeMods()),
                (byte) c.recvKind(), qualifier, graph.internHints(values.hints()),
                valueBuilder.importValue(values.recv()), valueBuilder.importArgs(values.args()),
                guardBuilder.global(values.guard()));
    }

    /**
     * U 行 1 行を、import からの推定候補があればエッジにする。
     * 型解決に失敗した呼び出しの一覧に出す行（使える候補が無い行）なら {@link #unresolved} に渡す
     */
    private void addUnresolved(String[] cols, MethodRef[] symbols, BlockNodes nodes, BlockGuards guards,
                               EdgeSpill spill) throws IOException {
        UnresolvedCallFact u = unresolvedOf(cols, symbols);
        if (u != null && u.hasUsableCandidate()) {
            int caller = methods.intern(u.caller());
            int callee = internGuessedCallee(u);
            countEdge(caller, callee);
            CallSiteValues.Row values = valuesOf(cols, nodes, guards);
            spill.write(caller, callee, u.line(), (byte) BindKind.GUESSED, (byte) u.recvKind(),
                    -1, graph.internHints(values.hints()),
                    valueBuilder.importValue(values.recv()), valueBuilder.importArgs(values.args()),
                    guardBuilder.global(values.guard()));
        }
        if (unresolved != null) {
            // 一覧には、呼び出し元の記号が壊れていても行を捨てず、呼び出し元不明として出す（OUTSIDE_METHOD の行と同じ）。
            // グラフの側はその行を警告して使わないので、ここで落とすと黙って消える
            UnresolvedCallFact r = UnresolvedCallFact.fromRowKeepingUnknownCaller(cols, symbols);
            // import 推定でエッジになっている行は、call-hierarchy.csv 側に
            // 「[EXTERNAL] import から型名を推定（未検証）」の注記付きで出ているので、一覧には出さない
            // （F 行の未解決数と同じ定義。呼び出し元が引けなかった行はエッジになっていないので出す）
            if (r != null && !r.hasUsableCandidate()) {
                unresolved.add(r.line(), (r.caller() == null) ? "" : r.caller().key(),
                        r.expression(), r.reason());
            }
        }
    }

    /**
     * U行の候補（レシーバの単純名と一致する単一型 import）を呼び出し先としてID化する。
     * パッケージ名は FQN の最後のドットまで、とみなす（推定なので厳密ではない）
     */
    private int internGuessedCallee(UnresolvedCallFact u) {
        String fqn = u.candidate();
        return methods.intern(Names.packageOf(fqn), fqn, u.expression(), "");
    }

    private static void ensure(IntArray a, int id) {
        while (a.size() <= id) {
            a.add(0);
        }
    }

    // ------------------------------------------------------------
    // 値の表を作り終える
    // ------------------------------------------------------------

    /**
     * 値の表・条件の表を作り終え、戻り値の参照をメソッドごとに並べる（CSR）。
     * 構築のときだけ使う索引（文字列・葉）とブロックの手元はここで捨てる。メソッドキーと同じ中身の文字列は、
     * メソッド表の文字列を共有する形にそろえる（メソッド表はスキャンを終えてそろっている）。
     *
     * <p>戻り値は、ファイル上の順に溜めた組をメソッドID で安定に並べ（同じメソッドの中の順は保つ）、
     * 同じ参照は最初の 1 つだけ残す。参照が違っても項目の同じノード（ブロックをまたいでまとめないもの）は
     * 残るが、読み手はどれも「すべての戻り値が一致するか」で畳むので結果は変わらない
     */
    private void freezeValues() {
        StringPool pool = strings.freeze(methods);
        graph.values = valueBuilder.freeze(pool);
        graph.guardTable = guardBuilder.freeze(pool);
        int n = methods.size();
        int[] off = new int[n + 1];
        for (int i = 0; i < returnMethods.size(); i++) {
            off[returnMethods.get(i) + 1]++;
        }
        for (int m = 0; m < n; m++) {
            off[m + 1] += off[m];
        }
        int[] placed = new int[returnMethods.size()];
        int[] cursor = Arrays.copyOf(off, n);
        for (int i = 0; i < returnMethods.size(); i++) {
            placed[cursor[returnMethods.get(i)]++] = returnRefs.get(i);
        }
        // メソッドごとに、同じ参照の 2 つ目以降を落として詰める（1 つのメソッドの戻り値は数個なので線形に探す）
        int[] compactOff = new int[n + 1];
        int w = 0;
        for (int m = 0; m < n; m++) {
            int begin = w;
            for (int k = off[m]; k < off[m + 1]; k++) {
                int ref = placed[k];
                boolean seen = false;
                for (int j = begin; j < w; j++) {
                    if (placed[j] == ref) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) {
                    placed[w++] = ref;
                }
            }
            compactOff[m + 1] = w;
        }
        graph.returnOff = compactOff;
        graph.returnRef = Arrays.copyOf(placed, w);
        returnMethods = new IntArray(1);
        returnRefs = new IntArray(1);
    }

    // ------------------------------------------------------------
    // offsets（累積和）とエッジ配列の確保
    // ------------------------------------------------------------

    private void allocateEdges() {
        int n = methods.size();
        graph.offsets = new int[n + 1];
        for (int i = 0; i < n; i++) {
            int d = (i < outDegree.size()) ? outDegree.get(i) : 0;
            graph.offsets[i + 1] = graph.offsets[i] + d;
        }
        int edges = (int) edgeCount;
        graph.calleeIds = new int[edges];
        graph.callLines = new int[edges];
        graph.bindKinds = new byte[edges];
        graph.recvKinds = new byte[edges];
        graph.edgeHint = new int[edges];
        Arrays.fill(graph.edgeHint, -1);
        graph.qualifierIds = new int[edges];
        graph.recvNodes = new int[edges];
        graph.argsNodes = new int[edges];
        graph.guardRefs = new int[edges];
        // @Bean メソッドが登録する型は、戻り値（R 行。値の表の側）が揃って初めて決まる
        graph.beans.resolveBeanMethods(graph);
    }

    // ------------------------------------------------------------
    // 配置: 一時ファイルのエッジを CSR の位置に置く
    // ------------------------------------------------------------

    /**
     * 一時ファイルの記録を先頭から読み、{@code cursor[呼び出し元]++} の位置に置く。
     * 記録はキャッシュ上の順に並んでいるので、呼び出し元ごとのエッジの並びはファイル上の順になる
     */
    private void placeEdges(EdgeSpill spill) throws IOException {
        int n = methods.size();
        int[] cursor = Arrays.copyOf(graph.offsets, n);
        int edges = (int) edgeCount;
        try (DataInputStream in = spill.reader()) {
            for (int placed = 0; placed < edges; placed++) {
                if ((placed & 0xFFFF) == 0) {
                    RunControl.checkCancelled();
                }
                int caller = in.readInt();
                int pos = cursor[caller]++;
                graph.calleeIds[pos] = in.readInt();
                graph.callLines[pos] = in.readInt();
                graph.bindKinds[pos] = in.readByte();
                graph.recvKinds[pos] = in.readByte();
                graph.qualifierIds[pos] = in.readInt();
                graph.edgeHint[pos] = in.readInt();
                graph.recvNodes[pos] = in.readInt();
                graph.argsNodes[pos] = in.readInt();
                graph.guardRefs[pos] = in.readInt();
            }
            if (in.read() >= 0) {
                // 数えた本数と書いた記録の数が食い違う（書き手の誤り）。黙って配列の外にずれないよう止める
                throw new IOException(Messages.format("graph.spillMismatch", edges));
            }
        } catch (EOFException e) {
            throw new IOException(Messages.format("graph.spillMismatch", edges), e);
        }
    }

    /**
     * エッジの記録を置く一時ファイル（キャッシュと同じフォルダ。{@link TempFiles}）。
     *
     * <p>1 本の記録は、呼び出し元・呼び出し先の ID、行、束縛の種別、レシーバの由来、修飾する型の
     * 文字列の置き場の番号、証拠の表の番号（{@link CallGraph#internHints}）、レシーバ・実引数の並びの
     * 値の表の参照と条件の表の番号（どれも無ければ -1）。固定長（34 バイト）。
     *
     * <p>最初の記録を書くときにファイルを作る（エッジが 1 本も無ければ作らない）。書くのも読むのも
     * 作ったときに開いた 1 つのチャネルで行い、名前で開き直さない（別の実行が残り物として消しても読める）。
     * {@link #close} で閉じて消す
     */
    private static final class EdgeSpill implements Closeable {

        private final Path cacheFile;
        private Path file;
        private FileChannel channel;
        private DataOutputStream out;

        EdgeSpill(Path cacheFile) {
            this.cacheFile = cacheFile;
        }

        void write(int caller, int callee, int line, byte bindKind, byte recvKind,
                   int qualifier, int hints, int recvNode, int argsNode, int guardRef)
                throws IOException {
            if (out == null) {
                file = TempFiles.create(cacheFile, TempFiles.EDGES);
                channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                out = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel), 1 << 16));
            }
            out.writeInt(caller);
            out.writeInt(callee);
            out.writeInt(line);
            out.writeByte(bindKind);
            out.writeByte(recvKind);
            out.writeInt(qualifier);
            out.writeInt(hints);
            out.writeInt(recvNode);
            out.writeInt(argsNode);
            out.writeInt(guardRef);
        }

        /** 書き終えた（読み直す前に、溜めた分をファイルへ出す。チャネルは開いたまま） */
        void finishWriting() throws IOException {
            if (out != null) {
                out.flush();
            }
        }

        /** 書いた記録を先頭から読む。1 本も書いていなければ空。閉じるとチャネルも閉じる */
        DataInputStream reader() throws IOException {
            if (channel == null) {
                return new DataInputStream(InputStream.nullInputStream());
            }
            channel.position(0);
            return new DataInputStream(new BufferedInputStream(Channels.newInputStream(channel), 1 << 16));
        }

        @Override
        public void close() {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException e) {
                Log.info(Messages.format("cache.tempNotDeleted", file, e));
            }
            TempFiles.delete(file);
        }
    }

    /**
     * 呼び出し箇所の値。値を読まない指定なら {@link CallSiteValues.Row#NONE}。
     * ノード番号・ガード番号がブロックの外を指していれば 1 度だけ警告する（その値は取り込まれないので使われない。
     * 条件なら「条件なし」になるので、打ち切りには使われない）
     */
    private CallSiteValues.Row valuesOf(String[] cols, BlockNodes nodes, BlockGuards guards) {
        if (!readValues) {
            return CallSiteValues.Row.NONE;
        }
        CallSiteValues.Row values = CallSiteValues.Row.fromRow(cols);
        if (!nodes.inRange(values.recv(), true) || !nodes.argsInRange(values.args())
                || !guards.inRange(values.guard())) {
            warnBadReference();
        }
        nodes.checkReferences();
        return values;
    }

    /**
     * 1 ブロック分の値グラフ（N 行）の数と、ノードどうしの参照がブロックに収まるかの見張り。
     * ノードそのものは値の表の組み手（{@link ValueStoreBuilder#node}）が持つ。番号は並びの位置。
     *
     * <p>ノードが指す子（レシーバ・実引数）がブロックの外を指していれば、ブロックの最初の呼び出し箇所か
     * 戻り値で 1 度だけ警告する（N 行は C 行・U 行・R 行より前に並ぶので、そのときには揃っている）。
     * 外を指す子は取り込まない（無いものとして扱う。{@link ValueStoreBuilder}）
     */
    private final class BlockNodes {

        private int size;
        /** ノードが指す子の番号の最大（無ければ -1） */
        private int maxChild = -1;
        /** 負の番号・形の崩れた実引数を指すノードがあったか */
        private boolean malformedChild;

        void clear() {
            size = 0;
            maxChild = -1;
            malformedChild = false;
        }

        /**
         * N 行を 1 つ足す。番号は並びの位置なので、読めない行や番号が並びと食い違う行でも
         * <b>読み飛ばさず</b>「追跡できない」ノードで場所を埋める。読み飛ばすと以降の番号が
         * 1 つずつずれ、別の値を別の呼び出し箇所に結びつけてしまう（ブロックの crc があるので
         * 実際には起きないが、起きたら 1 度だけ警告する）
         */
        void add(ValueNode n) {
            if (n == null || n.id() != size) {
                warnBadReference();
                n = new ValueNode(size, Origin.UNKNOWN, "", ValueNode.NONE, "", -1, "");
            }
            size++;
            noteChild(n.recv(), true);
            if (!n.args().isEmpty()) {
                for (String entry : n.args().split(String.valueOf(ValueNode.ARG_SEP))) {
                    int eq = entry.indexOf('=');
                    noteChild((eq < 0) ? Integer.MIN_VALUE
                            : ValueNode.intOf(entry.substring(eq + 1), Integer.MIN_VALUE), false);
                }
            }
            valueBuilder.node(n.kind(), n.value(), n.recv(), n.args(), n.argCount(), n.staticRecv());
        }

        private void noteChild(int id, boolean allowNone) {
            if (allowNone && id == ValueNode.NONE) {
                return;
            }
            if (id < 0) {
                malformedChild = true;
            } else if (id > maxChild) {
                maxChild = id;
            }
        }

        /** ノードどうしの参照がどれもブロックに収まるか。収まらなければ 1 度だけ警告する */
        void checkReferences() {
            if (malformedChild || maxChild >= size) {
                warnBadReference();
            }
        }

        /** ノード番号がブロックに収まるか。{@code allowNone} なら {@link ValueNode#NONE} も収まるとみなす */
        boolean inRange(int id, boolean allowNone) {
            return (allowNone && id == ValueNode.NONE) || (id >= 0 && id < size);
        }

        /**
         * ノードの頭の葉（{@link ValueStoreBuilder#importHead}）。{@link ValueNode#NONE} なら {@link ValueStore#NONE}。
         * ブロックの外を指していれば 1 度だけ警告して {@link ValueStore#NONE}（「追跡できない」は安全側）
         */
        int headOf(int id) {
            if (id != ValueNode.NONE && !inRange(id, false)) {
                warnBadReference();
            }
            return valueBuilder.importHead(id);
        }

        /** 実引数の列（{@code 位置=番号} のカンマ区切り）の番号がどれもブロックに収まるか */
        boolean argsInRange(String args) {
            if (args.isEmpty()) {
                return true;
            }
            for (String entry : args.split(String.valueOf(ValueNode.ARG_SEP))) {
                int eq = entry.indexOf('=');
                if (eq < 0 || !inRange(ValueNode.intOf(entry.substring(eq + 1), Integer.MIN_VALUE), false)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 1 ブロック分の条件（G 行）の数。ガード番号は並びの位置（0 から詰めて振ってある）。
     * アトムは条件の表の組み手（{@link GuardTableBuilder#atom}）が持つ。判定される式（subject）はノードの頭の葉。
     *
     * <p>壊れた行（番号が並びと食い違う・列が足りない）は 1 度だけ警告して、そのアトムを使わない。
     * アトムが減ったガードは条件が弱くなるだけなので、打ち切りが増えることはない（安全側）
     */
    private final class BlockGuards {

        private final BlockNodes nodes;
        private int size;

        BlockGuards(BlockNodes nodes) {
            this.nodes = nodes;
        }

        void clear() {
            size = 0;
        }

        /** G 行を 1 つ足す。番号は今のガード（続きのアトム）か、次のガード（新しい番号）のどちらか */
        void add(String[] cols) {
            int id = Guard.Atom.guardIdOf(cols);
            Guard.Atom atom = Guard.Atom.fromRow(cols);
            if (atom == null || id < 0 || id > size) {
                warnBadReference();
                return;
            }
            if (id == size) {
                size++;
            } else if (id != size - 1) {
                warnBadReference();   // 前のガードに戻っている。並びが崩れているので使わない
                return;
            }
            // subject はノードの頭の葉（ノードが無い・ブロックの外なら NONE。読み手は判定しない）
            guardBuilder.atom(id, atom.op(), nodes.headOf(atom.subject()), atom.values(), atom.text());
        }

        /** ガード番号がブロックに収まるか（-1 は「条件なし」で、収まるとみなす） */
        boolean inRange(int id) {
            return id == -1 || (id >= 0 && id < size);
        }
    }
}
