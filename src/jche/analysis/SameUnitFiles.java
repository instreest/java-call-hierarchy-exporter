// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import jche.analysis.CallEdgeExtractor.SourceFile;
import jche.config.ProjectLayout;

/**
 * コンパイル単位の名前（ソースフォルダからの相対パス。{@code p/Dup.java}）が同じファイルの組。
 * 同じクラスを 2 つのソースフォルダに置いたとき（{@code src1/p/Dup.java} と {@code src2/p/Dup.java}）にできる。
 *
 * <h2>なぜ組にするのか</h2>
 * JDT は、同じ名前の型を宣言するファイルを<b>同じバッチで</b>渡されると、後のほうに「型が重複している」という
 * コンパイルエラーを出してその型を捨てる。別々のバッチなら、どちらもエラーなしで解析する。どちらになるかが
 * バッチの組み方で決まると、差分更新（変わったファイルだけを解析する）と全件解析とで、同じソースから違う事実が
 * できる（片方の型の呼び出しが出たり消えたりする。{@code docs/cache-unification-qa.md} の Q61）。
 * そこで、組のファイルは
 * <ul>
 *   <li>必ず同じバッチに、ソースフォルダの並びの順で並べる（{@link #batches}。全件解析でも差分更新でも）</li>
 *   <li>1 つでも解析するなら、組のほかのファイルも再利用せずに一緒に解析する（{@link #pullInto}・{@link #pullIntoPaths}）</li>
 * </ul>
 * これで、組のファイルはいつも同じ相手と同じ順で JDT に渡る。
 *
 * <p>名前の違うファイルで同じ型を宣言している場合（public でないトップレベルの型）は、ここでは組にならない
 * （ファイル名から分からない）。グラフを組むときに、同じメソッドが 2 つのファイルで宣言されていれば警告する
 * （{@code jche.graph.CallGraphBuilder}）。
 *
 * <p>{@code package-info.java}・{@code module-info.java} も組にする。メインとテストのソースフォルダに同じ名前で
 * よく置かれるが、JDT はアノテーションの付いたパッケージ宣言に {@code package-info} という型を作るので、同じバッチの
 * 2 つ目は「型が重複している」エラーになり、別々のバッチならエラーにならない。組にしないと、片方だけを書き換えた差分更新と
 * 全件解析とで warnings.txt（コンパイルエラーのファイルの一覧）が食い違った（{@code docs/cache-unification-qa.md} の Q66）。
 *
 * <p>ヒープに残るのは組になったファイルのぶんだけ（ふつうは空）。
 */
final class SameUnitFiles {

    /** 組が 1 つも無い */
    static final SameUnitFiles NONE = new SameUnitFiles(Map.of());

    /** 相対パス -> 同じコンパイル単位の名前のファイルの相対パス（ソース一覧の並びの順。自分も含む） */
    private final Map<String, List<String>> groups;

    private SameUnitFiles(Map<String, List<String>> groups) {
        this.groups = groups;
    }

    /** ソース一覧（ソースフォルダの並び → フォルダの中はパスの順）から組を作る */
    static SameUnitFiles of(Map<String, SourceFile> live, ProjectLayout layout) {
        Map<String, String> first = new HashMap<>();            // コンパイル単位の名前 -> 最初のファイル
        Map<String, List<String>> byUnit = new LinkedHashMap<>();  // 2 つ目が見つかった名前だけ
        for (SourceFile f : live.values()) {
            String unit = layout.unitNameOf(f.path());
            String seen = first.putIfAbsent(unit, f.relativePath());
            if (seen != null) {
                byUnit.computeIfAbsent(unit, k -> new ArrayList<>(List.of(seen))).add(f.relativePath());
            }
        }
        if (byUnit.isEmpty()) {
            return NONE;
        }
        Map<String, List<String>> groups = new HashMap<>();
        for (List<String> g : byUnit.values()) {
            List<String> fixed = List.copyOf(g);
            for (String rel : fixed) {
                groups.put(rel, fixed);
            }
        }
        return new SameUnitFiles(groups);
    }

    /** そのファイルが組に入っているか（同じ名前のファイルがほかのソースフォルダにあるか） */
    boolean contains(String relativePath) {
        return groups.containsKey(relativePath);
    }

    /**
     * 解析するファイルの一覧（{@code first}・{@code second}）に入っているファイルの組のほかのファイルを、
     * 再利用する予定（{@code valid}）から外して同じ一覧に足す。組が 2 つの一覧に分かれていれば {@code first} に
     * まとめる（一覧ごとに別々にバッチを組むので、分かれていると同じバッチに並ばない）。
     */
    void pullInto(List<SourceFile> first, List<SourceFile> second, Set<String> valid,
                  Map<String, SourceFile> live) {
        pull(first, second, valid, SourceFile::relativePath, live::get);
    }

    /** {@link #pullInto} の、相対パスの一覧の版（パス3） */
    void pullIntoPaths(List<String> first, List<String> second, Set<String> valid) {
        pull(first, second, valid, Function.identity(), Function.identity());
    }

    private <T> void pull(List<T> first, List<T> second, Set<String> valid, Function<T, String> pathOf,
                          Function<String, T> fileOf) {
        if (groups.isEmpty()) {
            return;
        }
        Set<String> inFirst = pathsOf(first, pathOf);
        Set<String> inSecond = pathsOf(second, pathOf);
        List<String> seeds = new ArrayList<>(inFirst);
        seeds.addAll(inSecond);
        for (String seed : seeds) {
            List<String> group = groups.get(seed);
            if (group == null) {
                continue;
            }
            boolean toFirst = false;
            for (String member : group) {
                toFirst |= inFirst.contains(member);
            }
            for (String member : group) {
                if (inFirst.contains(member) || (!toFirst && inSecond.contains(member))) {
                    continue;
                }
                if (inSecond.remove(member)) {
                    second.removeIf(t -> pathOf.apply(t).equals(member));
                } else if (!valid.remove(member)) {
                    continue;   // 今は解析しない（もう解析した・ソースに無い）
                }
                if (toFirst) {
                    first.add(fileOf.apply(member));
                    inFirst.add(member);
                } else {
                    second.add(fileOf.apply(member));
                    inSecond.add(member);
                }
            }
        }
    }

    private static <T> Set<String> pathsOf(List<T> files, Function<T, String> pathOf) {
        Set<String> paths = new HashSet<>();
        for (T f : files) {
            paths.add(pathOf.apply(f));
        }
        return paths;
    }

    /**
     * {@code files} を {@code size} 件ずつのバッチに分ける。組のファイルは、組の最初のファイルの位置に
     * ソース一覧の並びの順でまとめ、1 つのバッチに収める（そのバッチは {@code size} を少し超えることがある）。
     * 組が無ければ、並びのまま {@code size} 件ずつに切るだけ
     */
    List<List<SourceFile>> batches(List<SourceFile> files, int size) {
        List<List<SourceFile>> batches = new ArrayList<>();
        if (groups.isEmpty()) {
            for (int from = 0; from < files.size(); from += size) {
                batches.add(files.subList(from, Math.min(files.size(), from + size)));
            }
            return batches;
        }
        // 組はそのリスト（同じ中身のリストは組ごとに 1 つ）、組でないファイルは相対パスを鍵にする
        Map<Object, List<SourceFile>> units = new LinkedHashMap<>();
        for (SourceFile f : files) {
            List<String> group = groups.get(f.relativePath());
            units.computeIfAbsent((group == null) ? f.relativePath() : group, k -> new ArrayList<>(1)).add(f);
        }
        List<SourceFile> batch = new ArrayList<>(size);
        for (Map.Entry<Object, List<SourceFile>> unit : units.entrySet()) {
            List<SourceFile> members = unit.getValue();
            if (unit.getKey() instanceof List<?> group) {
                members.sort(Comparator.comparingInt(f -> group.indexOf(f.relativePath())));
            }
            if (!batch.isEmpty() && batch.size() + members.size() > size) {
                batches.add(batch);
                batch = new ArrayList<>(size);
            }
            batch.addAll(members);
        }
        if (!batch.isEmpty()) {
            batches.add(batch);
        }
        return batches;
    }
}
