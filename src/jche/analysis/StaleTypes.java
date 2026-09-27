// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jche.cache.CacheFormat;
import jche.cache.FileAnalysis;
import jche.cache.LibraryFact;
import jche.cache.TypeFact;
import jche.config.ProjectLayout;

/**
 * 差分更新の<b>「どのファイルを解析し直すか」を決める材料</b>: 変更・削除されたファイルが宣言していた型
 * （「変わった型」）とそのパッケージ、追加・変更・削除された jar のパッケージ、新しい型、できた・無くなった
 * パッケージ、中身の分からないパッケージ、部分型の索引（親型の連鎖）。
 *
 * <p>キャッシュの再解析の健全性（差分更新の結果が全件解析と一致すること。{@code test/incremental}）は、
 * 有効なブロックの I 行（依存する型・解決できなかった名前・自分の宣言の指紋）を、ここが持つ集合と突き合わせる
 * {@link #touches}・{@link #matchesChangedType}・{@link #collidesWithChangedPackage} の判定にかかっている。「I 行に載らない依存」を見落とすと、そのファイルは
 * 古い事実のまま再利用され、出力が静かに食い違う。載らない依存の一覧と、それをどの集合で拾うかは
 * {@link jche.cache.CacheFormat} の I 行の説明と docs/cache-design.md の「差分更新」にある。
 *
 * <p>{@link CacheUpdater} がパス1〜4 で順に育てる（{@link #register}・{@link #endOfOldCache}・{@link #endOfSources}）。
 * 判定の結果は {@link ReanalysisReason}（集計の内訳）で返す。
 */
final class StaleTypes {

    private final Set<String> types = new HashSet<>();
    private final Set<String> packages = new HashSet<>();
    private final Set<String> libraryPackages;
    /**
     * パス1 の終わりの {@link #types}（無効になったブロックが宣言していた型）。パス2 で宣言された型が
     * これに無ければ新しい型。{@link #endOfOldCache} を呼ぶまでは null（新しい型を数えない）
     */
    private Set<String> declaredBefore;
    /** パッケージ -> そこに新しく宣言されたトップレベルの型の単純名（同じパッケージの名前の隠蔽を見る） */
    private final Map<String, Set<String>> newTopLevel = new HashMap<>();
    /**
     * 変わった型（{@link #types}。新しい型を含む）の単純名（FQN の最後の点より後。無名・ローカルの型は
     * {@code Main$1} の形なので、エラーの引数の名前には当たらない）。型解決に失敗していたブロックのうち、
     * 解決できなかった名前（I 行）のどれかの区切りがこれに当たるものを解析し直す（{@link #matchesChangedType}）。
     * 新しい型に限らないのは、見えなかった型を public にした（{@code The type vp.Hidden is not visible}）ときの
     * ように、前からあった型の変更でも失敗が解けるため（docs/cache-unification-qa.md の Q53）
     */
    private final Set<String> changedSimpleNames = new HashSet<>();
    /**
     * 変わった型の FQN の、点で区切った頭の部分（{@code org}・{@code org.acme}・{@code org.acme.New}）。
     * 解決できなかった名前がこれに当たるブロック（{@code import org cannot be resolved}）も解析し直す
     */
    private final Set<String> changedPrefixes = new HashSet<>();
    /**
     * 部分型の索引（親型の連鎖）。親型 -> それを親に持つ型（H 行の親型の列。子が 1 つなら {@code String}、
     * 2 つ以上なら {@code List<String>}）。旧キャッシュのすべてのブロックと、今回解析した・引き継いだブロックの
     * H 行から作る（{@link #register}）。差分更新のときだけ作り、型 1 つあたり文字列 1 つと参照 1 つで持つ
     */
    private final Map<String, Object> subtypes = new HashMap<>();
    /**
     * ソースのパッケージとその頭の部分。前回（旧キャッシュのすべてのブロックの H 行）と今回（有効なブロックと、今回
     * 解析した・引き継いだブロックの H 行）。片方にだけあるものは、できた・無くなったパッケージ。変わった jar の
     * パッケージとその頭の部分（{@link #libraryPrefixes}）も、できた・無くなったかもしれないものとして扱う。
     * パッケージがあるかどうかで、JDT が解決できなかった名前（{@code org.missing.pkg.Type}）をどこまでパッケージ
     * として読むか（エラーと、回復した型の名前 {@code org.missing}・{@code org.missing.pkg}）が変わる
     * （docs/cache-unification-qa.md の Q80）
     */
    private final Set<String> packagesBefore = new HashSet<>();
    private final Set<String> packagesNow = new HashSet<>();
    private final Set<String> libraryPrefixes = new HashSet<>();
    /**
     * 中身（宣言する型）の分からないソースのパッケージ。解析に失敗したファイル（{@link BlockWriter#failed}）と、
     * 無効になった・消えたブロックのうち型を 1 つも宣言していなかったもの（解析に失敗したファイルの印のブロックを含む）の
     * パッケージ（置き場所のフォルダから求める。{@link #packageOfUnit}）。どの型が増えた・減った・変わったか分からないので、
     * 変わった jar のパッケージと同じ決まりで扱う（{@link #touchesPackages}）。無名パッケージは
     * {@link LibraryFact#UNNAMED_PACKAGE}
     */
    private final Set<String> opaquePackages = new HashSet<>();
    private final Set<String> opaquePrefixes = new HashSet<>();
    /** {@link #newTopLevel} の単純名をパッケージを問わず集めたもの（自分のパッケージの分からないブロックに使う） */
    private final Set<String> newTopLevelNames = new HashSet<>();
    /**
     * できた・無くなったパッケージ（{@link #packagesBefore} と {@link #packagesNow} の片方にだけあるもの）。
     * 今のパッケージがそろうパス3 の最初に {@link #endOfSources} で決める。それまでは空
     */
    private final Set<String> changedSourcePackages = new HashSet<>();
    /**
     * {@link #changedSourcePackages} と {@link #libraryPrefixes} の親のパッケージ（{@link #collidesWithChangedPackage}）。
     * 前者は {@link #endOfSources} で、後者は作るときに決める
     */
    private final Set<String> parentsOfChangedSource = new HashSet<>();
    private final Set<String> parentsOfChangedLibrary = new HashSet<>();

    StaleTypes(Set<String> libraryPackages) {
        this.libraryPackages = new HashSet<>(libraryPackages);
        for (String p : libraryPackages) {
            addPrefixes(p, libraryPrefixes);
        }
        addParents(libraryPrefixes, parentsOfChangedLibrary);
    }

    /**
     * パス2 を終えた（今のソースのパッケージがそろった）。できた・無くなったパッケージを決める。
     * パス3・パス4 で解析し直すファイルは中身が変わっていないので、パッケージも変わらない
     */
    void endOfSources() {
        changedSourcePackages.clear();
        for (String p : packagesBefore) {
            if (!packagesNow.contains(p)) {
                changedSourcePackages.add(p);
            }
        }
        for (String p : packagesNow) {
            if (!packagesBefore.contains(p)) {
                changedSourcePackages.add(p);
            }
        }
        parentsOfChangedSource.clear();
        addParents(changedSourcePackages, parentsOfChangedSource);
    }

    /**
     * 中身の分からないパッケージを加える（{@link #opaquePackages}）。{@code pkg} は空文字なら無名パッケージ。
     * {@code null}（パッケージを求められない）なら無名パッケージとみなす（無名パッケージの型の名前には点が無いので、
     * 当たるのは点の無い名前だけ。多すぎても解析し直すファイルが増えるだけ）
     */
    void addOpaque(String pkg) {
        String p = (pkg == null || pkg.isEmpty()) ? LibraryFact.UNNAMED_PACKAGE : pkg;
        if (opaquePackages.add(p)) {
            addPrefixes(p, opaquePrefixes);
        }
    }

    /** 前回のソースにあった型のパッケージ（旧キャッシュの H 行） */
    void packageBefore(String pkg) {
        if (pkg != null && !pkg.isEmpty()) {
            addPrefixes(pkg, packagesBefore);
        }
    }

    /** 今のソースにある型のパッケージ（有効なブロックと、今回解析した・引き継いだブロックの H 行） */
    void packageNow(String pkg) {
        if (pkg != null && !pkg.isEmpty()) {
            addPrefixes(pkg, packagesNow);
        }
    }

    void add(String typeFqn, String pkg) {
        mark(typeFqn);
        packages.add(pkg == null ? "" : pkg);
    }

    /**
     * 型を「変わった型」にし、索引にあるその部分型も（推移的に）変わった型にする。部分型は、親から継承したものが
     * 変わっているので、それを使う側の事実も変わりうる（クラスの説明「親型の連鎖」）
     */
    private void mark(String typeFqn) {
        ArrayDeque<String> work = new ArrayDeque<>();
        work.add(typeFqn);
        while (!work.isEmpty()) {
            String t = work.poll();
            if (!types.add(t)) {
                continue;
            }
            changedSimpleNames.add(t.substring(t.lastIndexOf('.') + 1));
            addPrefixes(t, changedPrefixes);
            Object children = subtypes.get(t);
            if (children instanceof String child) {
                work.add(child);
            } else if (children != null) {
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) children;
                work.addAll(list);
            }
        }
    }

    /**
     * H 行 1 つの親型の関係を部分型の索引に足す。親がすでに変わった型なら、この型も（その部分型も）変わった型に
     * する（親が変わった jar のパッケージの型なら、この型を宣言するファイルは親を I 行に持つので jar の変化で
     * 解析し直し、そのとき変わった型になる。{@link ReanalysisCascade#WHEN_DECLARATIONS_CHANGED}）。索引に足すのと
     * 変わった型を広げるのをどちらの向きでも行うので、変わった型はいつでも「索引に載った関係について部分型で
     * 閉じている」。H 行をどの順に読んでも、型をどの順に変わった型にしても、最後に同じ集合になる
     */
    void register(TypeFact t) {
        String child = t.typeFqn();
        for (String parent : t.superTypes()) {
            Object known = subtypes.get(parent);
            if (known == null) {
                subtypes.put(parent, child);
            } else if (known instanceof String one) {
                List<String> list = new ArrayList<>(2);
                list.add(one);
                list.add(child);
                subtypes.put(parent, list);
            } else {
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) known;
                list.add(child);
            }
            if (types.contains(parent)) {
                mark(child);
            }
        }
    }

    /** FQN の点で区切った頭の部分（{@code org}・{@code org.acme}）と FQN そのものを足す */
    private static void addPrefixes(String typeFqn, Set<String> out) {
        for (int dot = typeFqn.indexOf('.'); dot > 0; dot = typeFqn.indexOf('.', dot + 1)) {
            out.add(typeFqn.substring(0, dot));
        }
        out.add(typeFqn);
    }

    /** パス1 を読み終えた。ここまでの型を「前回宣言されていた型」として固定する */
    void endOfOldCache() {
        declaredBefore = new HashSet<>(types);
    }

    /**
     * 変わった（または新しい）ファイルが宣言する型を加える。前回宣言されていなかった型なら、
     * 新しい型としても覚える（クラスの説明「新しい型」）
     */
    void addDeclared(String typeFqn, String pkg) {
        add(typeFqn, pkg);
        if (declaredBefore == null || declaredBefore.contains(typeFqn)) {
            return;
        }
        String p = (pkg == null) ? "" : pkg;
        String rest = p.isEmpty() ? typeFqn
                : typeFqn.startsWith(p + ".") ? typeFqn.substring(p.length() + 1) : null;
        if (rest != null && !rest.isEmpty() && rest.indexOf('.') < 0) {
            // トップレベルの型だけ。入れ子の型（Main.Inner）は外側の型（前回もあった）を通してしか名前にならない
            newTopLevel.computeIfAbsent(p, k -> new HashSet<>()).add(rest);
            newTopLevelNames.add(rest);
        }
    }

    /**
     * 型解決に失敗していたブロックの、解決できなかった名前（I 行。{@link FileAnalysis#unresolvedNames}）が
     * 変わった型（新しい型を含む）に当たるか。名前の区切りのどれかが変わった型の単純名か（{@code Foo}・
     * {@code q.Foo}・{@code Foo.Inner}）、名前が変わった型の FQN の頭の部分か（{@code org}・{@code org.acme}）、
     * 名前の頭の部分（名前そのものは除く）が、できた・無くなったパッケージか（{@link #packagesBefore}。
     * {@code org.missing.pkg.Type} と、前回は無かったパッケージの {@code org.missing.Foo}）。
     * {@link CacheFormat#ANY_NAME}（名前を拾えなかった）は何にでも当たる
     *
     * @param namesCsv 名前のカンマ区切り
     */
    boolean matchesChangedType(String namesCsv) {
        if (changedSimpleNames.isEmpty() && libraryPackages.isEmpty() && changedSourcePackages.isEmpty()) {
            return false;   // どのファイルも変わっていない（パッケージもできていない・無くなっていない）
        }
        if (namesCsv == null || namesCsv.isEmpty() || namesCsv.equals(CacheFormat.ANY_NAME)) {
            return true;
        }
        for (String name : namesCsv.split(",")) {
            if (changedPrefixes.contains(name) || hasSegment(name, changedSimpleNames)
                    || underChangedPackage(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 名前の頭の部分（{@code org}・{@code org.missing}。名前そのものは除く）のどれかが、できた・無くなったパッケージか
     * （前回と今回の片方にだけある。変わった jar のパッケージとその頭の部分も）。今回のパッケージはパス2 を終えれば
     * そろう（有効なブロックはパス1、変わったファイルはパス2 で足す）ので、パス3 から使う
     */
    private boolean underChangedPackage(String name) {
        for (int dot = name.indexOf('.'); dot > 0; dot = name.indexOf('.', dot + 1)) {
            String p = name.substring(0, dot);
            if (libraryPrefixes.contains(p) || packagesBefore.contains(p) != packagesNow.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 「変わった型」も「変わった jar のパッケージ」も「中身の分からないパッケージ」も「できた・無くなったパッケージ」も
     * 無い（＝どのブロックも再解析に回らない）。できた・無くなったパッケージは {@link #endOfSources} のあとで数える
     */
    boolean isEmpty() {
        return types.isEmpty() && libraryPackages.isEmpty() && opaquePackages.isEmpty()
                && changedSourcePackages.isEmpty();
    }

    /**
     * これまでに加えた型と中身の分からないパッケージの数。連鎖の打ち切りに使う。
     * 1周しても増えていなければ、もう一周しても同じ結果になる（どちらも増える一方で減らない）
     */
    int mark() {
        return types.size() + opaquePackages.size();
    }

    /**
     * 自分のパッケージ（{@code ownPackage}）の中に、できた・無くなったパッケージ（とその頭の部分）か、変わった jar の
     * パッケージ（とその頭の部分）があるか。パッケージ {@code a} のトップレベルの型 {@code b} は、パッケージ
     * {@code a.b} と衝突する（JLS 7.1。JDT は型のファイルに「collides with a package」のエラーを出す）。JDT がパッケージが
     * あるかをソースフォルダのフォルダと jar から決めるので、このエラーは型のファイルをどのバッチで解析しても同じで、
     * 型のファイルを解析し直せば全件解析と同じになる（docs/cache-unification-qa.md の Q87 の逆向き）。
     * どの型が衝突するかは見ず、親のパッケージのファイルをすべて解析し直す（多すぎても解析し直すファイルが増えるだけ）。
     * 無名パッケージ（{@code ownPackage} が空文字）は見ない。JDT は無名パッケージの型とトップレベルのパッケージの衝突を
     * 報告しない
     *
     * @return 当たらなければ {@link ReanalysisReason#UNTOUCHED}。ソースのパッケージに当たれば {@link ReanalysisReason#BY_SOURCE}、
     *         jar のパッケージだけに当たれば {@link ReanalysisReason#BY_LIBRARY}
     */
    ReanalysisReason collidesWithChangedPackage(String ownPackage) {
        if (ownPackage == null || ownPackage.isEmpty()) {
            return ReanalysisReason.UNTOUCHED;
        }
        if (parentsOfChangedSource.contains(ownPackage)) {
            return ReanalysisReason.BY_SOURCE;
        }
        return parentsOfChangedLibrary.contains(ownPackage) ? ReanalysisReason.BY_LIBRARY : ReanalysisReason.UNTOUCHED;
    }

    /** パッケージの親（最後の点より前）を集める。点の無いもの（親が無名パッケージ）は入れない */
    private static void addParents(Set<String> packages, Set<String> out) {
        for (String p : packages) {
            int dot = p.lastIndexOf('.');
            if (dot > 0) {
                out.add(p.substring(0, dot));
            }
        }
    }

    /**
     * I行（依存する型のカンマ区切り）が、変わった型または変わった jar のパッケージに触れているか。
     * "pkg.*"（オンデマンド import）は、そのパッケージの型が1つでも変わっていれば触れているとみなす。
     * ソースの変更に触れていればそちらを理由として返す（集計の内訳のため）。
     *
     * <p>自分のパッケージ（{@code ownPackage}）に新しいトップレベルの型ができていて、I 行のどれかの型名に
     * その単純名が含まれていれば（{@code q.Helper} と新しい {@code p.Helper}、{@code java.lang.Math} と
     * 新しい {@code p.Math}）、名前が隠されて別の型に解決されうるので触れているとみなす（JLS 6.4.1）
     *
     * <p>自分のパッケージに変わった jar の型があれば（同じパッケージを jar とソースに分けて置く）、jar の型も
     * 同じパッケージの型として、オンデマンド import と {@code java.lang} の型を隠しうる（JLS 6.4.1）。jar のどの
     * 型が増えたかは分からないので、I 行にオンデマンド import か {@code java.lang} の型があれば触れているとみなす
     * （docs/cache-unification-qa.md の Q54）
     *
     * <p>I 行の型の名前の頭の部分が変わった型なら（パッケージ {@code a.b} と同じ名前の型 {@code a.b} を足した）、
     * {@code a.b.C} の解決が変わるので触れているとみなす（{@link #underChangedType}）
     *
     * <p>オンデマンド import（{@code p.*}）は、パッケージ {@code p} ができた・無くなったときも触れているとみなす。
     * JDT はパッケージ {@code a} がその下のパッケージ（{@code a.b}）だけでできていても {@code import a.*} を解決するので、
     * 最後の下のパッケージが無くなると import のエラーが出る（{@link #endOfSources}）
     *
     * <p>中身の分からないパッケージ（{@link #opaquePackages}。解析に失敗したファイルのパッケージ）には、変わった jar の
     * パッケージと同じ決まりで触れる（{@link #touchesPackages}）。理由はソースの変化にする
     *
     * @param ownPackage そのブロックが宣言する型のパッケージ。型を 1 つも宣言していない（{@code package-info.java}）
     *                   なら null で、そのときはどのパッケージのブロックでもありうるとみなす
     */
    ReanalysisReason touches(String depsCsv, String ownPackage) {
        if (depsCsv.isEmpty() || isEmpty()) {
            return ReanalysisReason.UNTOUCHED;
        }
        if (touchesSource(depsCsv, ownPackage) || touchesOpaque(depsCsv, ownPackage)) {
            return ReanalysisReason.BY_SOURCE;
        }
        return touchesLibrary(depsCsv, ownPackage) ? ReanalysisReason.BY_LIBRARY : ReanalysisReason.UNTOUCHED;
    }

    /** I 行がソースの変化（変わった型・パッケージ・名前を隠す新しい型）に触れるか。{@link #touches} のソースの側 */
    private boolean touchesSource(String depsCsv, String ownPackage) {
        // 自分のパッケージが分からない（型を宣言していない）ブロックは、どのパッケージの新しい型にも隠されうるとみなす
        Set<String> shadowing = (ownPackage == null)
                ? (newTopLevelNames.isEmpty() ? null : newTopLevelNames)
                : newTopLevel.get(ownPackage);
        for (String d : depsCsv.split(",")) {
            if (d.isEmpty()) {
                continue;
            }
            if (shadowing != null && !d.endsWith(".*") && hasSegment(d, shadowing)) {
                return true;
            }
            boolean onDemand = d.endsWith(".*");
            String p = onDemand ? d.substring(0, d.length() - 2) : d;
            // 型の名前（か頭の部分）ができた・無くなったパッケージと同じなら、型とパッケージの衝突（JLS 7.1）で
            // 解決が変わりうる（collidesWithChangedPackage の、使う側）
            if (types.contains(p) || underChangedType(p) || changedSourcePackages.contains(p)
                    || underPackages(p, changedSourcePackages) || (onDemand && packages.contains(p))) {
                return true;
            }
        }
        return false;
    }

    /**
     * I 行が変わった jar のパッケージに触れるか。{@link #touches} の jar の側で、ソースの変化に触れるかとは
     * 別に見る（解析し直したときに連鎖させるか。{@link BlockWriter#jarDriven}）
     */
    boolean touchesLibrary(String depsCsv, String ownPackage) {
        return touchesPackages(depsCsv, ownPackage, libraryPackages, libraryPrefixes);
    }

    /**
     * I 行が中身の分からないパッケージ（{@link #opaquePackages}）に触れるか。触れていたブロックは、変わった jar に
     * 触れていたブロックと同じく、解析し直したら宣言する型を変わった型にする（{@link BlockWriter#jarDriven}）
     */
    boolean touchesOpaque(String depsCsv, String ownPackage) {
        return touchesPackages(depsCsv, ownPackage, opaquePackages, opaquePrefixes);
    }

    /**
     * I 行が、中身の分からない変化のあったパッケージ（{@code pkgs}。変わった jar のパッケージか、解析に失敗したファイルの
     * パッケージ）に触れるか。どの型が増えた・減った・変わったか分からないので、次のどれかなら触れているとみなす。
     * <ul>
     *   <li>型（{@code a.b.C}・{@code a.b.C.Inner}）の頭の部分がそのパッケージ。点の無い型（無名パッケージの型）は、
     *       無名パッケージ（{@link LibraryFact#UNNAMED_PACKAGE}）がそこにあるとき。型の名前そのものがそのパッケージか
     *       その頭の部分（{@code prefixes}）のときも（型とパッケージの衝突。JLS 7.1）</li>
     *   <li>オンデマンド import（{@code p.*}）の {@code p} が、そのパッケージかその頭の部分（{@code prefixes}）か、
     *       頭の部分がそのパッケージ（{@code import static org.lib.K.*}・{@code import org.lib.Outer.*} の型
     *       {@code org.lib.K} のパッケージ {@code org.lib}。型のメンバーを持ち込む import も {@code 名前.*} の形で I 行に
     *       載るので、名前そのものではなく頭の部分で当てる）</li>
     *   <li>自分のパッケージがそこにある（同じパッケージを jar とソースに分けて置く・無名パッケージ。自分のパッケージが
     *       分からなければあるとみなす）。I 行に何かあれば当たる。そのパッケージの型が同じパッケージの型として、
     *       オンデマンド import・{@code java.lang} の型（JLS 6.4.1。docs/cache-unification-qa.md の Q54）だけでなく、
     *       完全修飾名の頭の区切り（{@code a.b.C} の {@code a}。JLS 6.5.2 で型が先に選ばれる）も隠しうる。以前は
     *       オンデマンド import と {@code java.lang} の型だけを見ていて、I 行にそれらの無いファイル（インターフェース）が
     *       {@code a.b.C.k()} を書いていると、同じパッケージにできた型 {@code a} による隠蔽を見落とした</li>
     * </ul>
     */
    private static boolean touchesPackages(String depsCsv, String ownPackage, Set<String> pkgs,
                                           Set<String> prefixes) {
        if (depsCsv.isEmpty() || pkgs.isEmpty()) {
            return false;
        }
        if (ownPackage == null || pkgs.contains(ownPackage.isEmpty() ? LibraryFact.UNNAMED_PACKAGE : ownPackage)) {
            return true;
        }
        for (String d : depsCsv.split(",")) {
            if (d.isEmpty()) {
                continue;
            }
            if (d.endsWith(".*")) {
                String p = d.substring(0, d.length() - 2);
                if (prefixes.contains(p) || underPackages(p, pkgs)) {
                    return true;
                }
            } else if (underPackages(d, pkgs) || prefixes.contains(d)
                    || (d.indexOf('.') < 0 && pkgs.contains(LibraryFact.UNNAMED_PACKAGE))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解決できなかった名前（I 行の 2 列目）が、変わった jar のパッケージ（とその頭の部分）に当たりうるか。
     * 名前を拾えなかった（空・{@link CacheFormat#ANY_NAME}）ときは、jar が変わっていれば当たるとみなす
     */
    boolean namesUnderLibrary(String namesCsv) {
        if (libraryPackages.isEmpty()) {
            return false;
        }
        if (namesCsv == null || namesCsv.isEmpty() || namesCsv.equals(CacheFormat.ANY_NAME)) {
            return true;
        }
        for (String name : namesCsv.split(",")) {
            for (int dot = name.indexOf('.'); dot > 0; dot = name.indexOf('.', dot + 1)) {
                if (libraryPrefixes.contains(name.substring(0, dot))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 型解決に失敗していたブロックの、解決できなかった名前（I 行の 2 列目）が、中身の分からないパッケージの型に
     * なりうるか。自分のパッケージがそこにある（分からなければあるとみなす）ときは、名前に依らず当たる。名前の頭の
     * 区切り（{@code Base2}・{@code Base2.Inner} の {@code Base2}）が同じパッケージの型の単純名でありうるため
     * （点の有無では分けない。以前は点の無い名前だけを見ていて、同じパッケージの失敗するファイルに足した型の入れ子の型
     * {@code Base2.Inner} を落とした）。そうでなければ、名前の頭の部分がそのパッケージ（とその頭の部分）のとき。
     * 名前を拾えなかった（空・{@link CacheFormat#ANY_NAME}）ときは当たるとみなす。オンデマンド import で単純名を
     * 持ち込むブロックは、I 行の {@code p.*} で当たる（{@link #touchesOpaque}）
     */
    boolean namesUnderOpaque(String namesCsv, String ownPackage) {
        if (opaquePackages.isEmpty()) {
            return false;
        }
        if (namesCsv == null || namesCsv.isEmpty() || namesCsv.equals(CacheFormat.ANY_NAME)) {
            return true;
        }
        if (ownPackage == null
                || opaquePackages.contains(ownPackage.isEmpty() ? LibraryFact.UNNAMED_PACKAGE : ownPackage)) {
            return true;
        }
        for (String name : namesCsv.split(",")) {
            if (underPackages(name, opaquePrefixes)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 名前の頭の部分（{@code a.b.C} の {@code a}・{@code a.b}。名前そのものは除く）のどれかが変わった型か。
     * パッケージ {@code a.b} と同じ名前の型 {@code a.b}（パッケージ {@code a} のクラス {@code b}）を足す・消すと、
     * {@code a.b.C} と書いたファイルの解決が変わる（JLS 6.5.2・7.1。docs/cache-unification-qa.md の Q87）。
     * 入れ子の型（{@code p.Outer.Inner} と変わった {@code p.Outer}）も当たる。入れ子の型はたいてい外側の型と
     * 一緒に変わった型になっている（同じファイル）ので、これで増えるのは外側の型だけが部分型の索引で変わった型に
     * なったときだけである（多すぎても解析し直すファイルが増えるだけ）。
     *
     * <p>{@code $} も区切りとみなす（{@link #isSeparator}）。jar のクラスが参照していたソースの入れ子の型を JDT が
     * 見つけられないと、クラスファイルの名前のまま（{@code app.Outer$Inner}）I 行に残る。あとで {@code app/Outer.java}
     * に {@code Inner} を足したら、そのファイルを解析し直さないと、全件解析では解決できる呼び出しが解決できないまま残る
     */
    private boolean underChangedType(String name) {
        for (int i = 1; i < name.length(); i++) {
            if (isSeparator(name.charAt(i)) && types.contains(name.substring(0, i))) {
                return true;
            }
        }
        return false;
    }

    /** 型名を "." か "$" で区切ったどれかが、names に含まれるか（{@code $} は {@link #underChangedType} と同じ理由） */
    private static boolean hasSegment(String typeFqn, Set<String> names) {
        int from = 0;
        for (int i = 0; i <= typeFqn.length(); i++) {
            if (i == typeFqn.length() || isSeparator(typeFqn.charAt(i))) {
                if (names.contains(typeFqn.substring(from, i))) {
                    return true;
                }
                from = i + 1;
            }
        }
        return false;
    }

    /**
     * 型名の区切り。{@code .} のほか、見つからなかった入れ子の型をクラスファイルの名前のまま書いた {@code $}
     * （{@code app.Outer$Inner}）。ソースの型の名前に {@code $} を書いていれば余分に区切るが、解析し直すファイルが
     * 増えるだけ
     */
    private static boolean isSeparator(char c) {
        return c == '.' || c == '$';
    }

    /**
     * 名前の頭の部分（名前そのものは除く）のどれかが {@code pkgs} にあるか。型名なら、そのパッケージが
     * {@code pkgs} にあるか（名前だけではどこまでがパッケージか（内部クラスかどうか）分からないので、
     * "." で区切った前方部分を全部試す）
     */
    private static boolean underPackages(String name, Set<String> pkgs) {
        if (pkgs.isEmpty()) {
            return false;
        }
        for (int i = name.indexOf('.'); i > 0; i = name.indexOf('.', i + 1)) {
            if (pkgs.contains(name.substring(0, i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * ソースフォルダから見たコンパイル単位の名前（{@code a/b/C.java}。{@link ProjectLayout#unitNameOf}）から、
     * 置き場所のフォルダのパッケージ（{@code a.b}）を求める。フォルダの直下なら空文字（無名パッケージ）
     */
    static String packageOfUnit(String unitName) {
        int slash = unitName.lastIndexOf('/');
        return (slash <= 0) ? "" : unitName.substring(0, slash).replace('/', '.');
    }
}
