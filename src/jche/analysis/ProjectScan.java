// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
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
 *       絞ることはせず、名前の違う型を宣言するファイルはすべて添える（docs/cache-unification-qa.md の「名前の違うファイルで宣言した型」の Q）</li>
 *   <li>パッケージの宣言がフォルダと合わないファイルは警告する（{@link #warnPackageMismatches}）。ソースパスからは
 *       見つからないので、ほかのファイルがその型を解決できるかはバッチの組み方で変わる。フォルダを直すのが本筋で、
 *       どのバッチにも添えることはしない（source.folders が 1 段ずれていると、すべてのファイルが当たる）</li>
 * </ul>
 * 添えたファイルは JDT に型を知らせるためだけに渡し、事実は書かない。JDT は渡された順にファイルを解析するので、
 * 添えるファイルは解析するファイルの後ろに並べ、解析するファイルを受け取り終えたら止める
 * （{@link CallEdgeExtractor}。添えたファイルの本体は読まないので、ほとんど費用がかからない）。
 */
final class ProjectScan {

    /** 1 ファイルを構文だけで読んだ結果 */
    record Info(String packageName, List<String> topLevelTypes) {
    }

    /** パッケージの宣言がフォルダと合わないファイル */
    record Mismatch(String relativePath, String declared, String expected) {
    }

    /** 何も読んでいない（{@link CallEdgeExtractor#prepare} を呼んでいない使い方） */
    static final ProjectScan EMPTY = new ProjectScan(Map.of(), Map.of(), Map.of(), List.of(), List.of());

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

    private ProjectScan(Map<String, Integer> order, Map<String, List<SourceFile>> byUnit,
                        Map<String, List<SourceFile>> byFolder, List<SourceFile> context,
                        List<Mismatch> mismatches) {
        this.order = order;
        this.byUnit = byUnit;
        this.byFolder = byFolder;
        this.context = context;
        this.mismatches = mismatches;
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
        return new ProjectScan(order, byUnit, byFolder, List.copyOf(sorted), List.copyOf(mismatches));
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
