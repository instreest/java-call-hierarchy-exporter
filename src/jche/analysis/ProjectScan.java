// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jche.analysis.CallEdgeExtractor.SourceFile;
import jche.config.ProjectLayout;
import jche.util.Messages;
import jche.util.Warnings;

/**
 * 解析するソースの全体を、型を解決せずに構文だけで読んだ結果（{@link CallEdgeExtractor#prepare}）。
 * ファイルの中身だけで決まるので、全件解析でも差分更新でも同じになる。
 *
 * <h2>何に使うか</h2>
 * JDT は、一緒に渡された（同じ {@code createASTs} の）ファイルの型はどれも見つけるが、渡されていない型は
 * ソースパスから「パッケージのフォルダ／型の名前.java」で探す。この探し方で見つからない型は、宣言したファイルが
 * 同じバッチにいるときだけ見つかる。バッチの組み方は全件解析（{@link CallEdgeExtractor#BATCH_SIZE} 件ずつ）と
 * 差分更新（変わったファイルだけ）とで違うので、見つかるかどうかで事実が食い違う。そこで
 * <ul>
 *   <li>名前の違うファイルで宣言したトップレベルの型（{@code Result.java} の中の {@code record Ok}）を持つファイルは、
 *       どのバッチにも添える（{@link #context}）。JDT はこの型をパッケージのファイルを読み直して探すが、その読み直しを
 *       Java 8 の文法で行う（{@code ASTParser} がソースパスに渡す設定を引き継がない）ため、{@code record}・
 *       {@code sealed} の宣言と、同じファイルでそれより後ろの型を見落とす。どの型を見落とすかを JDT の作りに合わせて
 *       絞ることはせず、名前の違う型を宣言するファイルはすべて添える（docs/cache-unification-qa.md の「名前の違うファイルで宣言した型」の Q）。
 *       ただし、どのバッチにもすべてを添えるのではなく、バッチのファイルから<b>名前でたどって届くファイル</b>のうちの
 *       添えるファイルだけを添える（{@link #reach}・{@link #contextIn}）。各ファイルが同じファイルに補助クラスを持つ
 *       書き方では、添えるファイルの数もバッチの数もプロジェクトの大きさに比例し、すべてを添えると費用が二乗で
 *       増えていた（docs/cache-unification-qa.md の Q142）</li>
 *   <li>パッケージの宣言がフォルダと合わないファイルは警告する（{@link #warnPackageMismatches}）。ソースパスからは
 *       見つからないので、ほかのファイルがその型を解決できるかはバッチの組み方で変わる。フォルダを直すのが本筋で、
 *       どのバッチにも添えることはしない（source.folders が 1 段ずれていると、すべてのファイルが当たる）</li>
 * </ul>
 * 添えたファイルは JDT に型を知らせるためだけに渡し、事実は書かない。JDT は渡された順にファイルを解析するので、
 * 添えるファイルは解析するファイルの後ろに並べ、解析するファイルを受け取り終えたら止める
 * （{@link CallEdgeExtractor}。添えたファイルの本体は読まないので、ほとんど費用がかからない）。
 */
final class ProjectScan {

    /**
     * 1 ファイルを構文だけで読んだ結果（{@link CallEdgeExtractor#prepare}）。
     *
     * @param packageName    宣言したパッケージ（既定のパッケージは空）
     * @param topLevelTypes  トップレベルの型の名前
     * @param onDemand       オンデマンド import（{@code import a.b.*;} の {@code a.b}。static も型の import も）
     * @param signatureNames メソッド・コンストラクタ・初期化ブロックの本体の外（import・宣言・フィールドの初期化子・注釈）に
     *                       書いた名前。単純名と、点でつないだ名前（{@code a.b.C.m}）
     * @param bodyNames      本体の中に書いた名前（同じ形）
     */
    record Info(String packageName, List<String> topLevelTypes, Set<String> onDemand, Set<String> signatureNames,
                Set<String> bodyNames) {
    }

    /** パッケージの宣言がフォルダと合わないファイル */
    record Mismatch(String relativePath, String declared, String expected) {
    }

    /** 何も読んでいない（{@link CallEdgeExtractor#prepare} を呼んでいない使い方） */
    static final ProjectScan EMPTY = new ProjectScan(Map.of(), Map.of(), Map.of(), List.of(), List.of(),
            List.of(), new String[0], new int[0][], new int[0][], new BitSet(), new BitSet());

    /** 読んだファイルでいちばん多く挙げる、パッケージの合わないファイルの数 */
    static final int MISMATCH_LIMIT = 20;

    /** 相対パス -> ソース一覧での位置 */
    private final Map<String, Integer> order;
    /** コンパイル単位の名前（{@link ProjectLayout#unitNameOf}） -> そのファイル（ソース一覧の並び） */
    private final Map<String, List<SourceFile>> byUnit;
    /** コンパイル単位のフォルダ（{@code p/q}。既定のパッケージは空） -> そのファイル（ソース一覧の並び。module-info は除く） */
    private final Map<String, List<SourceFile>> byFolder;
    /** どのバッチにも添えるファイル（ソース一覧の並び） */
    final List<SourceFile> context;
    /** パッケージの宣言がフォルダと合わないファイル（ソース一覧の並び） */
    final List<Mismatch> mismatches;
    /** ソース一覧（位置 = {@link #order} の値） */
    private final List<SourceFile> files;
    /** 位置 -> コンパイル単位の名前（{@link #byUnit} の鍵） */
    private final String[] unitNames;
    /** 位置 -> 本体も含めてそのファイルが名前で届くファイルの位置（バッチで解析するファイルから出るとき） */
    private final int[][] bodyEdges;
    /** 位置 -> 本体の外の名前で届くファイルの位置（名前でたどって届いたファイルから出るとき） */
    private final int[][] signatureEdges;
    /** 添えるファイル（{@link #context}）の位置 */
    private final BitSet contextBits;
    /** 構文だけでは読めなかったファイルの位置（名前が分からないので、届いたらすべての添えるファイルに届くとみなす） */
    private final BitSet unreadable;

    private ProjectScan(Map<String, Integer> order, Map<String, List<SourceFile>> byUnit,
                        Map<String, List<SourceFile>> byFolder, List<SourceFile> context,
                        List<Mismatch> mismatches, List<SourceFile> files, String[] unitNames, int[][] bodyEdges,
                        int[][] signatureEdges, BitSet contextBits, BitSet unreadable) {
        this.order = order;
        this.byUnit = byUnit;
        this.byFolder = byFolder;
        this.context = context;
        this.mismatches = mismatches;
        this.files = files;
        this.unitNames = unitNames;
        this.bodyEdges = bodyEdges;
        this.signatureEdges = signatureEdges;
        this.contextBits = contextBits;
        this.unreadable = unreadable;
    }

    /**
     * @param all    解析するソースの全体（ソース一覧の並び）
     * @param infos  相対パス -> 構文だけで読んだ結果。読めなかったファイルは入っていない（添えない・警告しない）
     */
    static ProjectScan of(Collection<SourceFile> all, ProjectLayout layout, Map<String, Info> infos) {
        Map<String, Integer> order = new HashMap<>();
        Map<String, List<SourceFile>> byUnit = new HashMap<>();
        Map<String, List<SourceFile>> byFolder = new HashMap<>();
        for (SourceFile f : all) {
            order.put(f.relativePath(), order.size());
            String unit = layout.unitNameOf(f.path());
            byUnit.computeIfAbsent(unit, k -> new ArrayList<>(1)).add(f);
            if (!isModuleInfo(f)) {
                byFolder.computeIfAbsent(folderOf(unit), k -> new ArrayList<>()).add(f);
            }
        }
        Set<SourceFile> context = new LinkedHashSet<>();
        List<Mismatch> mismatches = new ArrayList<>();
        for (SourceFile f : all) {
            Info info = infos.get(f.relativePath());
            if (info == null) {
                continue;
            }
            String base = f.path().getFileName().toString();
            base = base.substring(0, base.length() - ".java".length());
            for (String type : info.topLevelTypes()) {
                if (!type.equals(base)) {
                    // 同じ名前のファイルの組（SameUnitFiles）は組ごと添える。片方だけを添えると、ソースパスなら
                    // 先のフォルダのファイルが勝つのに、添えたほうが勝ってしまう
                    context.addAll(byUnit.get(layout.unitNameOf(f.path())));
                    break;
                }
            }
            String expected = expectedPackage(f.path(), layout, info.packageName());
            if (expected != null) {
                mismatches.add(new Mismatch(f.relativePath(), info.packageName(), expected));
            }
        }
        List<SourceFile> sorted = new ArrayList<>(context);
        sorted.sort(Comparator.comparingInt(f -> order.get(f.relativePath())));

        // 名前で届くファイルの辺。どのパッケージにどの名前の型があるか（名前の違うファイルの型も）を先に引けるようにする
        List<SourceFile> files = new ArrayList<>(all);
        Map<String, Map<String, List<Integer>>> declared = new HashMap<>();
        BitSet unreadable = new BitSet();
        BitSet contextBits = new BitSet();
        String[] unitNames = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            SourceFile f = files.get(i);
            unitNames[i] = layout.unitNameOf(f.path());
            Info info = infos.get(f.relativePath());
            if (context.contains(f)) {
                contextBits.set(i);
            }
            if (info == null) {
                unreadable.set(i);
                String unit = unitNames[i];
                String base = f.path().getFileName().toString().replaceFirst("\\.java$", "");
                declared.computeIfAbsent(folderOf(unit).replace('/', '.'), k -> new HashMap<>())
                        .computeIfAbsent(base, k -> new ArrayList<>()).add(i);
                continue;
            }
            for (String type : info.topLevelTypes()) {
                declared.computeIfAbsent(info.packageName(), k -> new HashMap<>())
                        .computeIfAbsent(type, k -> new ArrayList<>()).add(i);
            }
        }
        int[][] bodyEdges = new int[files.size()][];
        int[][] signatureEdges = new int[files.size()][];
        for (int i = 0; i < files.size(); i++) {
            Info info = infos.get(files.get(i).relativePath());
            if (info == null) {
                bodyEdges[i] = new int[0];
                signatureEdges[i] = bodyEdges[i];
                continue;
            }
            Set<Integer> sig = new LinkedHashSet<>();
            for (String name : info.signatureNames()) {
                addDeclaring(name, info, declared, sig);
            }
            Set<Integer> body = new LinkedHashSet<>(sig);
            for (String name : info.bodyNames()) {
                addDeclaring(name, info, declared, body);
            }
            signatureEdges[i] = toArray(sig);
            bodyEdges[i] = toArray(body);
        }
        return new ProjectScan(order, byUnit, byFolder, List.copyOf(sorted), List.copyOf(mismatches),
                List.copyOf(files), unitNames, bodyEdges, signatureEdges, contextBits, unreadable);
    }

    /**
     * {@code name}（単純名か、点でつないだ名前）が指しうる型を宣言するファイルの位置を {@code out} に足す。
     * 単純名は、自分のパッケージとオンデマンド import したパッケージの型。点でつないだ名前は、頭の部分のどれか
     * （{@code a.b.C.m} の {@code a.b.C}・{@code a.b}…）を「パッケージ.型」と読んだ型と、最初の部分を単純名と読んだ型。
     * 名前だけでは型か変数か・パッケージかを決めないので余分に当たるが、添えるファイルが増えるだけ
     */
    private static void addDeclaring(String name, Info info, Map<String, Map<String, List<Integer>>> declared,
                                     Set<Integer> out) {
        int dot = name.indexOf('.');
        String first = (dot < 0) ? name : name.substring(0, dot);
        addType(declared, info.packageName(), first, out);
        for (String pkg : info.onDemand()) {
            addType(declared, pkg, first, out);
        }
        for (String p = name; p.indexOf('.') > 0; p = p.substring(0, p.lastIndexOf('.'))) {
            int last = p.lastIndexOf('.');
            addType(declared, p.substring(0, last), p.substring(last + 1), out);
        }
    }

    private static void addType(Map<String, Map<String, List<Integer>>> declared, String pkg, String type,
                                Set<Integer> out) {
        Map<String, List<Integer>> types = declared.get(pkg);
        if (types != null) {
            out.addAll(types.getOrDefault(type, List.of()));
        }
    }

    private static int[] toArray(Set<Integer> set) {
        int[] a = new int[set.size()];
        int i = 0;
        for (int v : set) {
            a[i++] = v;
        }
        return a;
    }

    /**
     * 解析するファイル（{@code analyzed}）から名前でたどって届くファイル（位置の集合。解析するファイル自身も含む）。
     *
     * <p>JDT は、解析するファイルの本体まで解決し、そこで名指した型をソースパスから読むと、その型のファイルの
     * 本体の外（import・宣言のシグネチャ・親型）の名前を解決する。その名前の型をまた読み、…と、本体の外の名前で
     * 届くファイルを読む（本体は読まない）。名前の違うファイルで宣言した型を JDT が探すのは、この届く範囲の
     * ファイルが、その型のパッケージでその名前を探すときだけなので、届かないファイルの添えるファイルを添えても
     * 事実は変わらない（docs/cache-unification-qa.md の Q142）。そこで、解析するファイルからは本体も含めた名前で、
     * 届いたファイルからは本体の外の名前で、閉包までたどる。構文だけでは読めなかったファイルは、名前が分からない
     * ので、届いたらすべての添えるファイルに届くとみなす（{@link #contextIn}）。
     *
     * <p>ソース一覧に無いファイル（{@link CallEdgeExtractor#prepare} を呼んでいない使い方）は何にも届かない
     */
    BitSet reach(Collection<SourceFile> analyzed) {
        BitSet reached = new BitSet(files.size());
        Deque<Integer> todo = new ArrayDeque<>();
        for (SourceFile f : analyzed) {
            Integer i = order.get(f.relativePath());
            if (i == null) {
                continue;
            }
            reached.set(i);
            for (int next : bodyEdges[i]) {
                if (!reached.get(next)) {
                    reached.set(next);
                    todo.add(next);
                }
            }
        }
        follow(reached, todo);
        return reached;
    }

    /**
     * {@code reached} に、{@code roots}（事実に現れた型を宣言するファイルなど）とそこから本体の外の名前でたどって届く
     * ファイルを足す。新しく届いたファイルがあれば true
     */
    boolean extend(BitSet reached, Collection<SourceFile> roots) {
        Deque<Integer> todo = new ArrayDeque<>();
        for (SourceFile f : roots) {
            Integer i = order.get(f.relativePath());
            if (i != null && !reached.get(i)) {
                reached.set(i);
                todo.add(i);
            }
        }
        if (todo.isEmpty()) {
            return false;
        }
        follow(reached, todo);
        return true;
    }

    private void follow(BitSet reached, Deque<Integer> todo) {
        while (!todo.isEmpty()) {
            for (int next : signatureEdges[todo.poll()]) {
                if (!reached.get(next)) {
                    reached.set(next);
                    todo.add(next);
                }
            }
        }
    }

    /**
     * 届くファイル（{@link #reach}）のうちの添えるファイル（ソース一覧の並び）。組（{@link SameUnitFiles}）は組ごと
     * 添えるファイルになっているので、片方だけが届いても組ごと返す。読めなかったファイルに届いていれば、すべての
     * 添えるファイル
     */
    List<SourceFile> contextIn(BitSet reached) {
        if (contextBits.isEmpty()) {
            return List.of();
        }
        if (reached.intersects(unreadable)) {
            return context;
        }
        BitSet hit = (BitSet) contextBits.clone();
        hit.and(reached);
        List<SourceFile> out = new ArrayList<>();
        Set<SourceFile> seen = new HashSet<>();
        for (int i = hit.nextSetBit(0); i >= 0; i = hit.nextSetBit(i + 1)) {
            for (SourceFile m : byUnit.get(unitNames[i])) {
                if (seen.add(m)) {
                    out.add(m);
                }
            }
        }
        out.sort(Comparator.comparingInt(this::orderOf));
        return out;
    }

    /**
     * 完全修飾名（{@code a.b.C}・{@code a.b.C.Inner}・{@code a.b.C$Inner}）の型を宣言しうるソースのファイル
     * （頭の部分のどれかを「フォルダ／型の名前.java」と読んだファイル。組ごと）
     */
    List<SourceFile> declaringFiles(String typeName) {
        List<SourceFile> out = new ArrayList<>();
        String path = typeName.replace('$', '.').replace('.', '/');
        for (String p = path; !p.isEmpty(); p = p.substring(0, Math.max(0, p.lastIndexOf('/')))) {
            out.addAll(unit(p + ".java"));
        }
        return out;
    }

    /**
     * フォルダから決まるパッケージ（ファイルを含むソースフォルダが複数あれば、そのどれか）が宣言と合わなければ、
     * 最初のソースフォルダから決まるパッケージ。合えば null
     */
    private static String expectedPackage(Path file, ProjectLayout layout, String declared) {
        String first = null;
        for (Path sf : layout.sourceFolders) {
            if (!file.startsWith(sf)) {
                continue;
            }
            Path parent = sf.relativize(file).getParent();
            String pkg = (parent == null) ? "" : parent.toString().replace('\\', '/').replace('/', '.');
            if (pkg.equals(declared)) {
                return null;
            }
            if (first == null) {
                first = pkg;
            }
        }
        return first;
    }

    static boolean isModuleInfo(SourceFile f) {
        return f.path().getFileName().toString().equals("module-info.java");
    }

    /** コンパイル単位の名前（{@code p/q/A.java}）のフォルダ（{@code p/q}） */
    private static String folderOf(String unitName) {
        int slash = unitName.lastIndexOf('/');
        return (slash < 0) ? "" : unitName.substring(0, slash);
    }

    /** ソース一覧での位置。一覧に無いファイルは後ろ */
    int orderOf(SourceFile f) {
        Integer i = order.get(f.relativePath());
        return (i == null) ? Integer.MAX_VALUE : i;
    }

    /** 同じコンパイル単位の名前のファイル（組。ソース一覧の並び）。一覧に無ければ空 */
    List<SourceFile> unit(String unitName) {
        return byUnit.getOrDefault(unitName, List.of());
    }

    /** 同じフォルダ（同じパッケージのはずのファイル）のファイル（ソース一覧の並び。module-info は除く） */
    List<SourceFile> folder(String unitName) {
        return byFolder.getOrDefault(folderOf(unitName), List.of());
    }

    /**
     * パッケージの宣言がフォルダと合わないファイルを警告する（{@link Warnings.Topic#BUILD}）。構文だけで読んだ結果から
     * 決めるので、全件解析でも差分更新でも同じ行が出る
     */
    void warnPackageMismatches() {
        int shown = 0;
        for (Mismatch m : mismatches) {
            if (shown++ == MISMATCH_LIMIT) {
                Warnings.warn(Warnings.Topic.BUILD, Messages.format("analysis.packageMismatch.more",
                        MISMATCH_LIMIT, mismatches.size()));
                break;
            }
            Warnings.warn(Warnings.Topic.BUILD, Messages.format("analysis.packageMismatch", m.relativePath(),
                    m.declared().isEmpty() ? Messages.get("analysis.packageMismatch.default") : m.declared(),
                    m.expected().isEmpty() ? Messages.get("analysis.packageMismatch.default") : m.expected()));
        }
    }

    /** 解析に添える候補を並べ直す（ソース一覧の並び。重複と {@code exclude} を除く） */
    List<SourceFile> sorted(Collection<SourceFile> files, Set<String> exclude) {
        Map<String, SourceFile> unique = new LinkedHashMap<>();
        for (SourceFile f : files) {
            if (!exclude.contains(f.path().toString())) {
                unique.putIfAbsent(f.path().toString(), f);
            }
        }
        List<SourceFile> list = new ArrayList<>(unique.values());
        list.sort(Comparator.comparingInt(this::orderOf));
        return list;
    }
}
