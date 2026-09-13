// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.external;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import jche.cache.MethodRef;
import jche.cache.TypeFact;
import jche.config.Config;
import jche.graph.CallGraph;
import jche.graph.MethodTable;
import jche.graph.TypeHierarchy;
import jche.report.CallHierarchyCsvWriter;
import jche.util.Log;
import jche.util.Names;

/**
 * 他チームのjarを走査し、自分のメソッドがどこから参照されているかを出力する。
 *
 * 用途は改修時の影響調査。「このメソッドを直すと誰に影響するか」に答える。
 * classファイルの定数プールだけを読む（{@link ClassFileRefs}）ため、
 * 「どのjar・どのクラスが参照しているか」までが分かり、呼び出し元メソッドと行番号は分からない。
 *
 * <h2>FatJar（jar の中の jar）</h2>
 * Spring Boot の実行可能 jar（{@code BOOT-INF/lib/*.jar}）や war（{@code WEB-INF/lib/*.jar}）、
 * ear（war や jar を内包）のように、jar の中に jar が入っている形も、中の jar を順に開いて走査する。
 * 中の jar はファイルとして取り出さず、エントリのストリームを {@link ZipInputStream} で読む。
 * {@code BOOT-INF/classes/} や {@code WEB-INF/classes/} 直下の class は、パスに関係なく
 * 「.class で終わるエントリ」として最初から読めている。
 * 出力の jar 名は、中の jar なら {@code 外側.jar!/BOOT-INF/lib/中.jar} のように
 * jar URL と同じ {@code !/} 区切りでどこに入っていたかまで書く。
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
            return "jar=" + jars + (nestedJars > 0 ? " jar内のjar=" + nestedJars : "")
                    + " クラス=" + classes
                    + " 被参照=" + hits + "件（自分のメソッド " + usedMethods + " 個）"
                    + " 暗黙コンストラクタ=" + implicitCtors
                    + " 未照合=" + unmatched
                    + " 自プロジェクトクラスを除外=" + selfClasses;
        }
    }

    private final CallGraph graph;
    private final MethodTable methods;
    private final CallHierarchyCsvWriter out;
    private final Stats stats = new Stats();
    /** 自分の型かどうかの判定に使う（H行から得た、ソース上に宣言のある型） */
    private final Set<String> ourTypes;
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
        this.ourTypes = graph.hierarchy().typeNames();
        this.refCount = new int[methods.size()];
    }

    public static Stats scan(CallGraph graph, Config config, CallHierarchyCsvWriter out)
            throws IOException {
        List<Path> jars = collectJars(config.externalLibraryFolders);
        Log.info("外部jar: " + jars.size() + " 件");
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
                Log.warn("class解析に失敗（スキップ）: "
                        + jarLabel + "!/" + entryName + " (" + ex.getMessage() + ")");
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
                Log.warn("jar の入れ子が深すぎるため読み飛ばします（" + MAX_NESTING + " 段まで）: "
                        + nestedLabel);
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
     * 中の jar が zip として壊れている場合は、その jar だけ警告して読み飛ばす
     * （外側の jar の他のエントリには影響しない）。
     */
    private void scanNestedJar(String nestedLabel, InputStream is, int depth) throws IOException {
        // ZipInputStream を閉じると外側のストリームまで閉じるため、閉じない
        ZipInputStream zip = new ZipInputStream(is);
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    scanEntry(nestedLabel, entry.getName(), zip, depth);
                }
                zip.closeEntry();
            }
        } catch (java.util.zip.ZipException ex) {
            Log.warn("jar 内の jar を読めません（スキップ）: " + nestedLabel + " (" + ex.getMessage() + ")");
            return;
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

    /** 1クラスが参照しているメソッドのうち、自分の型のものを行にする */
    private void scanClass(ClassFileRefs refs, String jarName) throws IOException {
        for (ClassFileRefs.MethodEntry r : refs.methodRefs) {
            String owner = r.ownerFqn();
            if (!isOurType(owner)) {
                continue;   // JDKや第三者ライブラリへの参照は対象外
            }
            String sig = r.name() + "(" + r.paramSig() + ")";
            int id = resolveRef(owner, sig);
            if (id >= 0) {
                String kind = methods.typeFqn(id).equals(normalize(owner)) ? "EXACT" : "INHERITED";
                out.writeExternalUsageRow(refs.thisClass, methods.displayLabel(id),
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
                String typeFqn = normalize(owner);
                String simple = Names.simpleOf(typeFqn);
                out.writeExternalUsageRow(refs.thisClass,
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
    }


    /** 内部クラスは bytecode が Outer$Inner、JDT側が Outer.Inner なので両方で照合する */
    private static String normalize(String owner) {
        return owner.replace('$', '.');
    }

    private boolean isOurType(String owner) {
        return ourTypes.contains(owner) || ourTypes.contains(normalize(owner));
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
     * 完全一致で見つからない場合、継承したメソッドの呼び出し
     * （呼び出し側は子クラスを owner として記録する）を考慮して親を探す。
     */
    private int lookupRef(String owner, String sig) {
        int id = declaredWithSource(owner, sig);
        if (id < 0) {
            id = declaredWithSource(normalize(owner), sig);
        }
        return (id >= 0) ? id : inheritedFrom(normalize(owner), sig);
    }

    /** その型自身がソース上で宣言しているメソッドの ID。無ければ -1 */
    private int declaredWithSource(String typeFqn, String sig) {
        int id = methods.idOf(typeFqn + "#" + sig);
        return (id >= 0 && methods.hasSource(id)) ? id : -1;
    }

    /**
     * owner から親をたどり、最も近い宣言を返す。JVM のメソッド解決と同じく、
     * 親クラスの連鎖を根まで先に見て、次にそれらが実装するインターフェースを幅優先で見る。
     * 「シグネチャが一致する宣言のうち owner を子孫に持つもの」を先着で選ぶと、
     * 親クラスとインターフェースの両方に宣言がある場合にメソッドIDの並び（＝解析順）で
     * 結果が変わるので、型階層だけで決まるこの順にしている。
     */
    private int inheritedFrom(String owner, String sig) {
        TypeHierarchy hierarchy = graph.hierarchy();
        Set<String> seen = new HashSet<>();
        List<String> classChain = new ArrayList<>();
        String cur = owner;
        while (cur != null && seen.add(cur)) {
            classChain.add(cur);
            String superclass = null;
            for (String sup : hierarchy.directSupertypes(cur)) {
                char kind = hierarchy.kindOf(sup);
                if (kind == TypeFact.CONCRETE || kind == TypeFact.ABSTRACT) {
                    superclass = sup;
                    break;
                }
            }
            if (superclass != null) {
                int id = declaredWithSource(superclass, sig);
                if (id >= 0) {
                    return id;
                }
            }
            cur = superclass;
        }
        // インターフェース（およびソース外で種別の分からない親）を、近いクラスのものから順に
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (String c : classChain) {
            for (String sup : hierarchy.directSupertypes(c)) {
                if (seen.add(sup)) {
                    queue.add(sup);
                }
            }
        }
        while (!queue.isEmpty()) {
            String type = queue.poll();
            int id = declaredWithSource(type, sig);
            if (id >= 0) {
                return id;
            }
            for (String sup : hierarchy.directSupertypes(type)) {
                if (seen.add(sup)) {
                    queue.add(sup);
                }
            }
        }
        return -1;
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
                Log.warn("external.library.folders の指定が見つかりません: " + r);
            }
        }
        return new ArrayList<>(out);
    }
}
