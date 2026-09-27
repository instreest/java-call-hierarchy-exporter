// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jche.cache.LibraryFact;
import jche.cache.TypeFact;
import jche.config.ProjectLayout;

/**
 * 差分更新の<b>「どのファイルを解析し直すか」を決める材料</b>: 変更・削除されたファイルが宣言していた型
 * （「変わった型」）とそのパッケージ、追加・変更・削除された jar のパッケージ、新しい型ができたパッケージ、
 * できた・無くなったパッケージ、中身の分からないパッケージ、部分型の索引（親型の連鎖）。
 *
 * <p>キャッシュの再解析の健全性（差分更新の結果が全件解析と一致すること。{@code test/incremental}）は、
 * 有効なブロックの I 行（依存する型・自分の宣言の指紋）を、ここが持つ集合と突き合わせる
 * {@link #touches}・{@link #collidesWithChangedPackage} の判定にかかっている。「I 行に載らない依存」を見落とすと、そのファイルは
 * 古い事実のまま再利用され、出力が静かに食い違う。載らない依存の一覧と、それをどの集合で拾うかは
 * {@link jche.cache.CacheFormat} の I 行の説明と docs/cache-design.md の「差分更新」にある。
 *
 * <p>判定は<b>型かパッケージの単位</b>で、名前の一部（単純名・エラーの引数に現れた名前）は照合しない。
 * 型解決に失敗していたブロックは、名前を照合せず、何かが変わった実行では必ず解析し直す
 * （{@link CacheUpdater#reanalyzeDependents}）。新しい型による名前の隠蔽（JLS 6.4.1）は、その型ができたパッケージ
 * （自分のパッケージ・オンデマンド import のパッケージ）で当てる（{@link #hidesNamesIn}）。名前を切って照合する規則は、
 * 何を区切りにするか・何を拾うかの入れ忘れがそのまま静かな取りこぼしになったので、やめた
 * （docs/cache-unification-qa.md の Q131）。
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
    /**
     * 新しいトップレベルの型ができたパッケージ（無名パッケージは空文字）。同じパッケージの型は、そのパッケージの
     * ファイルのオンデマンド import と {@code java.lang} の型を隠す（JLS 6.4.1）ので、そのパッケージのブロックと、
     * そのパッケージをオンデマンド import するブロックを解析し直す（{@link #hidesNamesIn}）
     */
    private final Set<String> newTopLevelPackages = new HashSet<>();
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
     * 変わった（または新しい）ファイルが宣言する型を加える。前回宣言されていなかったトップレベルの型なら、
     * そのパッケージを「新しい型ができたパッケージ」として覚える（クラスの説明「新しい型」）。
     * 入れ子の型（{@code Main.Inner}）は外側の型（前回もあった）を通してしか名前にならないので数えない
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
            newTopLevelPackages.add(p);
        }
    }

    /**
     * 「変わった型」も「変わった jar のパッケージ」も「中身の分からないパッケージ」も「できた・無くなったパッケージ」も
     * 無い（＝どのブロックも再解析に回らない）。できた・無くなったパッケージは {@link #endOfSources} のあとで数える
     */
    boolean isEmpty() {
        return types.isEmpty() && libraryPackages.isEmpty() && opaquePackages.isEmpty()
                && changedSourcePackages.isEmpty();
    }

    /** ソースの側の変化（変わった型・中身の分からないパッケージ・できた・無くなったパッケージ）があるか */
    boolean hasSourceChanges() {
        return !types.isEmpty() || !opaquePackages.isEmpty() || !changedSourcePackages.isEmpty();
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
     * <p>自分のパッケージ（{@code ownPackage}）か、オンデマンド import したパッケージに新しいトップレベルの型ができて
     * いれば、I 行に何かあるブロックは触れているとみなす（{@link #hidesNamesIn}。同じパッケージの型はオンデマンド import と
     * {@code java.lang} の型を隠す。JLS 6.4.1）。どの名前が隠されるかは照合しない
     *
     * <p>自分のパッケージに変わった jar の型があれば（同じパッケージを jar とソースに分けて置く）、jar の型も
     * 同じパッケージの型として、オンデマンド import と {@code java.lang} の型を隠しうる（JLS 6.4.1）。jar のどの
     * 型が増えたかは分からないので、I 行に何かあれば触れているとみなす（docs/cache-unification-qa.md の Q54）
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
        if (hidesNamesIn(ownPackage)) {
            return true;
        }
        for (String d : depsCsv.split(",")) {
            if (d.isEmpty()) {
                continue;
            }
            boolean onDemand = d.endsWith(".*");
            String p = onDemand ? d.substring(0, d.length() - 2) : d;
            // 型の名前（か頭の部分）ができた・無くなったパッケージと同じなら、型とパッケージの衝突（JLS 7.1）で
            // 解決が変わりうる（collidesWithChangedPackage の、使う側）
            if (types.contains(p) || underChangedType(p) || changedSourcePackages.contains(p)
                    || underPackages(p, changedSourcePackages)
                    || (onDemand && (packages.contains(p) || newTopLevelPackages.contains(p)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 自分のパッケージに新しいトップレベルの型ができたか（JLS 6.4.1 の隠蔽で、このブロックのどの名前の解決先も
     * 変わりうる）。自分のパッケージが分からない（型を宣言していない）ブロックは、どのパッケージの新しい型にも
     * 隠されうるとみなす
     */
    private boolean hidesNamesIn(String ownPackage) {
        if (newTopLevelPackages.isEmpty()) {
            return false;
        }
        return ownPackage == null || newTopLevelPackages.contains(ownPackage);
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
     *       完全修飾名の頭の区切り（{@code a.b.C} の {@code a}。JLS 6.5.2 で型が先に選ばれる）も隠しうる</li>
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
     * 名前の頭の部分（{@code a.b.C} の {@code a}・{@code a.b}。名前そのものは除く）のどれかが変わった型か。
     * パッケージ {@code a.b} と同じ名前の型 {@code a.b}（パッケージ {@code a} のクラス {@code b}）を足す・消すと、
     * {@code a.b.C} と書いたファイルの解決が変わる（JLS 6.5.2・7.1。docs/cache-unification-qa.md の Q87）。
     * 入れ子の型（{@code p.Outer.Inner} と変わった {@code p.Outer}）も当たる。入れ子の型はたいてい外側の型と
     * 一緒に変わった型になっている（同じファイル）ので、これで増えるのは外側の型だけが部分型の索引で変わった型に
     * なったときだけである（多すぎても解析し直すファイルが増えるだけ）。
     *
     * <p>{@code $} も区切りとみなす。jar のクラスが参照していたソースの入れ子の型を JDT が
     * 見つけられないと、クラスファイルの名前のまま（{@code app.Outer$Inner}）I 行に残る。あとで {@code app/Outer.java}
     * に {@code Inner} を足したら、そのファイルを解析し直さないと、全件解析では解決できる呼び出しが解決できないまま残る
     */
    private boolean underChangedType(String name) {
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c == '.' || c == '$') && types.contains(name.substring(0, i))) {
                return true;
            }
        }
        return false;
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
