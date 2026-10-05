// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.external;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import jche.cache.MethodRef;
import jche.config.Config;
import jche.graph.CallGraph;
import jche.graph.MethodSelection;
import jche.graph.MethodTable;
import jche.report.CallHierarchyCsvWriter;
import jche.util.Log;
import jche.util.Names;
import jche.util.Messages;
import jche.util.Warnings;

/**
 * 他チームのjarを走査し、自分のメソッドがどこから参照されているかを出力する。
 *
 * 用途は改修時の影響調査。「このメソッドを直すと誰に影響するか」に答える。
 * class ファイルの命令列を歩く（{@link ClassFileRefs}）ので、「どの jar・どのクラスの
 * どのメソッドの何行目から」まで分かり、呼び出し階層の行と同じスタックトレース形式で出せる。
 * 行番号は class に LineNumberTable が残っている（javac の既定。{@code -g:none} で消える）ときだけ。
 *
 * <h2>FatJar（jar の中の jar）</h2>
 * Spring Boot の実行可能 jar（{@code BOOT-INF/lib/*.jar}）や war（{@code WEB-INF/lib/*.jar}）、
 * ear（war や jar を内包）のように、jar の中に jar が入っている形も、中の jar を順に開いて走査する。
 * 中の jar はファイルとして取り出さず、エントリのストリームを {@link ZipInputStream} で読む。
 * {@code BOOT-INF/classes/} や {@code WEB-INF/classes/} 直下の class は、パスに関係なく
 * 「.class で終わるエントリ」として最初から読めている。
 * 出力の jar 名は、中の jar なら {@code 外側.jar!/BOOT-INF/lib/中.jar} のように
 * jar URL と同じ {@code !/} 区切りでどこに入っていたかまで書く。
 *
 * <p>被参照の結び先（JVM の解決が結び付ける宣言）を探す「クラスの連鎖 → 最も特定的な親インターフェース」の順は
 * ここには無く、{@link MethodSelection#resolvedDeclaration}（{@link #lookupRef} が使う）が持つ。以前ここにあった
 * 別実装 inheritedFrom は Issue #186 で消した。順の正本は docs/resolution-selection-design.md の 4 節で、写しは
 * jche.graph.MethodSelection#search・同 #resolvedDeclaration・jche.analysis.ImplicitCalls#findNoArgMethod の 3 か所（Issue #189）。
 *
 * <p>「自分の型か」の判定は、ソースの型（H 行）のバイナリ名（{@code p.Outer$Inner$1}）→ FQN の索引（{@link #ourTypeOf}）で引く。
 * {@code $} を {@code .} に置き換えるだけでは、入れ子のクラスの中の匿名クラス（{@code p.Outer$Inner$1}。H 行の名前は
 * {@code p.Outer.Inner$1}）がどちらの綴りでも当たらず、混在した jar の中の自分の匿名クラスを「相手のクラス」として
 * 走査し、自分自身からの呼び出しを被参照に数えていた
 */
public final class ExternalUsageScanner {

    /** 被参照スキャンの集計 */
    public static final class Stats {
        long jars;
        /** jar の中に入っていた jar（FatJar の内側）の数。{@link #jars} とは別に数える */
        long nestedJars;
        long classes;
        long selfClasses;
        public long hits;
        public long implicitCtors;
        public long unmatched;
        long usedMethods;

        @Override
        public String toString() {
            return Messages.format("external.summary", jars,
                    (nestedJars > 0) ? Messages.format("external.summary.nested", nestedJars) : "",
                    classes, hits, usedMethods, implicitCtors, unmatched, selfClasses);
        }
    }

    private final CallGraph graph;
    private final MethodTable methods;
    private final CallHierarchyCsvWriter out;
    private final Stats stats = new Stats();
    /**
     * 自分の型かどうかの判定に使う: ソース上に宣言のある型（H 行）のバイナリ名（class ファイルが名乗る形。
     * {@code p.Outer$Inner}・{@code p.Outer$Inner$1}）→ その型の FQN（H 行の名前。{@code p.Outer.Inner}・{@code p.Outer.Inner$1}）。
     * バイナリ名の組み立ては CallHierarchyCsvWriter#stackTrace と同じ（パッケージ + 単純名の {@code .} を {@code $} に）
     */
    private final Map<String, String> ourTypesByBinaryName;
    /** メソッドごとの被参照回数（「自分のメソッド N 個」の集計用） */
    private final int[] refCount;

    /**
     * jar の入れ子を辿る深さの上限。ear → war → jar で 2 段なので、それより十分深い値。
     * 上限は「jar が自分自身を含む」ような壊れた入力で無限に潜らないための安全策で、
     * 実在の配布形式で当たることは無い。
     */
    private static final int MAX_NESTING = 8;

    private ExternalUsageScanner(CallGraph graph, CallHierarchyCsvWriter out) {
        this.graph = graph;
        this.methods = graph.methods();
        this.out = out;
        this.ourTypesByBinaryName = binaryNameIndex(graph.hierarchy());
        this.refCount = new int[methods.size()];
    }

    /**
     * ソース上の型のバイナリ名 → FQN の索引（{@link #ourTypesByBinaryName}）。同じバイナリ名になる型が 2 つあれば
     * （トップレベルの {@code p.Outer$Inner} と入れ子の {@code p.Outer.Inner}。javac も同じ class ファイル名で衝突する形）、
     * 読んだ順に依らないよう綴りの小さい FQN を採る
     */
    private static Map<String, String> binaryNameIndex(jche.graph.TypeHierarchy hierarchy) {
        Map<String, String> index = new HashMap<>();
        for (String fqn : hierarchy.typeNames()) {
            String pkg = hierarchy.packageOf(fqn);
            String simple = (!pkg.isEmpty() && fqn.startsWith(pkg + ".")) ? fqn.substring(pkg.length() + 1) : fqn;
            String binary = (pkg.isEmpty() ? "" : pkg + ".") + simple.replace('.', '$');
            index.merge(binary, fqn, (a, b) -> (a.compareTo(b) <= 0) ? a : b);
        }
        return index;
    }

    public static Stats scan(CallGraph graph, Config config, CallHierarchyCsvWriter out)
            throws IOException {
        List<Path> jars = collectJars(config.externalLibraryFolders);
        Log.info(Messages.format("external.jarCount", jars.size()));
        ExternalUsageScanner scanner = new ExternalUsageScanner(graph, out);
        for (Path jar : jars) {
            scanner.scanJar(jar);
        }
        return scanner.stats;
    }

    private void scanJar(Path jarPath) throws IOException {
        String jarName = jarPath.getFileName().toString();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !isScanTarget(entry.getName())) {
                    continue;
                }
                try (InputStream is = jar.getInputStream(entry)) {
                    scanEntry(jarName, entry.getName(), is, 0);
                }
            }
        }
        stats.jars++;
    }

    /**
     * jar の中の 1 エントリを処理する。class ならその参照を拾い、jar（FatJar の内側）なら
     * 中身を {@link ZipInputStream} で読んで同じ処理を再帰的に行う。
     * それ以外（リソース、マニフェスト等）は読み飛ばす。
     *
     * @param jarLabel  出力の jar 名。中の jar は {@code 外側.jar!/中.jar} と連ねる
     * @param entryName jar 内のエントリ名（{@code BOOT-INF/lib/x.jar} 等）
     * @param is        エントリの内容。閉じるのは呼び出し側
     * @param depth     入れ子の深さ（最上位の jar の直下が 0）
     */
    private void scanEntry(String jarLabel, String entryName, InputStream is, int depth)
            throws IOException {
        if (entryName.endsWith(".class")) {
            ClassFileRefs refs;
            try {
                refs = ClassFileRefs.parse(is);
            } catch (Exception ex) {
                Log.warn(Messages.format("external.classFailed",
                        jarLabel + "!/" + entryName + " (" + ex.getMessage() + ")"));
                return;
            }
            // 自プロジェクトのクラスが混ざったjar（自分のビルド成果物が
            // 同じフォルダにある等）は「他リポジトリからの被参照」ではない。
            // 自分自身からの呼び出しを被参照として出さないよう読み飛ばす
            if (isOurType(refs.thisClass)) {
                stats.selfClasses++;
                return;
            }
            stats.classes++;
            scanClass(refs, jarLabel);
        } else if (isArchiveName(entryName)) {
            String nestedLabel = jarLabel + "!/" + entryName;
            if (depth >= MAX_NESTING) {
                Log.warn(Messages.format("external.nestingTooDeep", MAX_NESTING, nestedLabel));
                return;
            }
            scanNestedJar(nestedLabel, is, depth + 1);
        }
    }

    /**
     * jar の中の jar を、取り出さずにストリームのまま走査する。
     * {@link JarFile} はファイルにしか開けないため、中の jar は {@link ZipInputStream} で
     * 先頭から順に読む。Spring Boot の入れ子 jar は無圧縮（STORED）で格納されているが、
     * 圧縮されていても {@link ZipInputStream} はそのまま読める。
     * 中の jar が zip として壊れている・途中で切れている場合は、その jar だけ警告して読み飛ばす
     * （外側の jar の他のエントリには影響しない）。
     */
    private void scanNestedJar(String nestedLabel, InputStream is, int depth) throws IOException {
        // ZipInputStream を閉じると外側のストリームまで閉じるため、閉じない
        ZipInputStream zip = new ZipInputStream(is);
        while (true) {
            ZipEntry entry;
            try {
                // 次のエントリへ進む（前のエントリの残りは getNextEntry が読み飛ばす）。zip の側の失敗だけをここで受ける:
                // 壊れた zip は ZipException、途中で切れた jar は EOFException（どちらも IOException）。
                // エントリの中身の処理（scanEntry）の IOException は CSV の書き込みの失敗なので受けずに伝える
                entry = zip.getNextEntry();
            } catch (IOException ex) {
                Log.warn(Messages.format("external.nestedJarUnreadable", nestedLabel,
                        (ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getSimpleName()));
                return;
            }
            if (entry == null) {
                break;
            }
            if (!entry.isDirectory()) {
                scanEntry(nestedLabel, entry.getName(), zip, depth);
            }
        }
        stats.nestedJars++;
    }

    /** 読む価値のあるエントリか（class か、中の jar）。リソースやマニフェストは開かない */
    private static boolean isScanTarget(String name) {
        return name.endsWith(".class") || isArchiveName(name);
    }

    /** 走査対象のアーカイブか。war / ear は「jar を内包する jar」なので同じ扱い */
    private static boolean isArchiveName(String name) {
        return name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".ear");
    }

    /**
     * 1クラスが参照しているメソッドのうち、自分の型のものを行にする。
     * 命令列から見つけた呼び出し箇所は「どのメソッドの何行目」まで caller に書く。
     * 命令列から辿れなかった参照（実在の配布物ではまず無い）は、従来どおりクラス名だけを caller にして残す
     */
    private void scanClass(ClassFileRefs refs, String jarName) throws IOException {
        for (ClassFileRefs.CallSite site : refs.callSites) {
            String caller = CallHierarchyCsvWriter.stackTrace(refs.thisClass, site.callerMethod(),
                    refs.sourceFile, site.line());
            scanRef(site.callee(), caller, jarName);
        }
        for (ClassFileRefs.MethodEntry r : refs.unlocatedRefs) {
            scanRef(r, refs.thisClass, jarName);
        }
    }

    /** 参照先 1 件を自分のメソッドと照合し、一致すれば行にする */
    private void scanRef(ClassFileRefs.MethodEntry r, String caller, String jarName) throws IOException {
        String owner = r.ownerFqn();
        if (!isOurType(owner)) {
            return;   // JDKや第三者ライブラリへの参照は対象外
        }
        String sig = r.name() + "(" + r.paramSig() + ")";
        int id = resolveRef(owner, sig);
        if (id >= 0) {
            String kind = methods.typeFqn(id).equals(ourTypeOf(owner)) ? "EXACT" : "INHERITED";
            out.writeExternalUsageRow(caller, methods.shortLabel(id),
                    methods.shortLabel(id), jarName, kind);
            if (refCount[id]++ == 0) {
                stats.usedMethods++;
            }
            stats.hits++;
        } else if (MethodRef.CONSTRUCTOR.equals(r.name()) && r.paramSig().isEmpty()) {
            // 引数なしコンストラクタへの参照だが、ソース上に一致する宣言が無い。
            // 暗黙のデフォルトコンストラクタは解析時に D 行として合成されるので EXACT で
            // 照合される。ここに来るのは「相手jarのビルド時には引数なしで生成できたが、
            // 今のソースにはそのコンストラクタが無い」形で、版違いの可能性が高い。
            // 「誰がこのクラスを生成しているか」は影響調査で有用なので、行として残し注記で区別する。
            // 引数付きの <init> が一致しないものは、内部クラス（外側インスタンスが引数に付く）や
            // 版違いであり、生成箇所として表記できないので未照合に数える
            String typeFqn = ourTypeOf(owner);
            String simple = Names.simpleOf(typeFqn);
            out.writeExternalUsageRow(caller,
                    typeFqn + "." + simple + "()", simple + "." + simple,
                    jarName, "IMPLICIT_CTOR");
            stats.implicitCtors++;
        } else {
            // 自分の型への参照なのに一致するメソッドが無い。
            // 相手が古い版のjarに対してビルドされている可能性がある。
            // 「使われていない」と即断しないよう件数だけ残す
            stats.unmatched++;
        }
    }


    /**
     * シグネチャの引数型の内部クラスは bytecode が Outer$Inner、JDT 側が Outer.Inner なので、{@code $} を {@code .} に
     * 直した形でも照合する（{@link #resolveRef}）。受け手の型（owner）には使わず、索引（{@link #ourTypeOf}）で引く
     */
    private static String normalize(String sig) {
        return sig.replace('$', '.');
    }

    /** バイナリ名 {@code owner}（class ファイルの this_class・参照の owner）がソース上の型なら、その FQN。そうでなければ null */
    private String ourTypeOf(String owner) {
        return ourTypesByBinaryName.get(owner);
    }

    private boolean isOurType(String owner) {
        return ourTypesByBinaryName.containsKey(owner);
    }

    /**
     * 参照を自分のメソッドIDに解決する。
     *
     * シグネチャは classファイルのディスクリプタから作るため、引数の内部クラスが
     * Outer$Inner の形で入る。JDT側は Outer.Inner なので、そのままでは
     * 「内部クラスを引数に取るオーバーロード」だけが照合できず、未照合に落ちる。
     * まず生の形で引き、外れたら $ を . に直した形でもう一度引く
     * （クラス名に $ を含む型を誤って読み替えないよう、生の形を先に試す）。
     */
    private int resolveRef(String owner, String sig) {
        int id = lookupRef(owner, sig);
        if (id >= 0) {
            return id;
        }
        String normSig = normalize(sig);
        return normSig.equals(sig) ? -1 : lookupRef(owner, normSig);
    }

    /**
     * 参照（受け手の静的型 {@code owner} とディスクリプタ {@code sig}）を、JVM のメソッド解決（JVMS 5.4.3.3）が
     * 結び付けるソースの宣言に引く。完全一致で見つからなければ継承したメソッド（呼び出し側は子クラスを owner として
     * 記録する）を親から探す。
     *
     * <p>探す順（親クラスの連鎖 → 最も特定的な親インターフェース）と、javac がブリッジメソッドでディスクリプタをそろえる形
     * （型引数を具体化した上書き＝ O 行、親クラスから継承した実装＝ H 行の 8 列目）の照合は、選択と同じ
     * {@link MethodSelection#resolvedDeclaration} に任せる。以前はここに同じ順の別実装を持っていて、O 行と H 行の
     * 8 列目を見なかった（Issue #186。docs/external-usage-callsite-qa.md の Q9）。
     * コンストラクタ（{@code <init>}）は継承されないので、そちらが親へ辿らずその型自身の宣言だけを見る
     * （無ければ -1 で、呼び出し側が IMPLICIT_CTOR の注記で残す）。
     * 受け手の型はバイナリ名なので、索引（{@link #ourTypeOf}）で H 行の名前に直してから引く
     */
    private int lookupRef(String owner, String sig) {
        return graph.selection().resolvedDeclaration(ourTypeOf(owner), sig);
    }

    /**
     * 指定がファイルならそのjar、ディレクトリなら配下の *.jar を全部（サブフォルダも見る）。
     * war / ear もファイルとして受け付ける（中の jar と class を走査する）。
     * 並びはパス順に揃える。{@link Files#walk} の順はファイルシステム依存で、
     * jar が複数あると出力の行順が環境ごとに変わってしまうため。
     */
    private static List<Path> collectJars(List<Path> roots) throws IOException {
        Set<Path> out = new TreeSet<>();
        for (Path r : roots) {
            if (Files.isRegularFile(r) && isArchiveName(r.toString())) {
                out.add(r);
            } else if (Files.isDirectory(r)) {
                try (Stream<Path> walk = Files.walk(r)) {
                    walk.filter(p -> Files.isRegularFile(p) && isArchiveName(p.toString()))
                            .forEach(out::add);
                }
            } else {
                Warnings.warn(Warnings.Topic.CONFIG, Messages.format("external.folderMissing", r));
            }
        }
        return new ArrayList<>(out);
    }
}
