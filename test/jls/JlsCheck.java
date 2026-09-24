import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.MethodParameterInfo;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Java 言語仕様（JLS SE 26）への適合と、javac（JDK 26）との整合を見る検査（test/jls/run.sh から呼ぶ）。
 *
 * <pre>
 *   java JlsCheck.java expect.tsv 出力フォルダ analysis-cache.tsv javacのクラスフォルダ
 * </pre>
 *
 * 検査は 2 段ある。
 *
 * <h2>1. 期待値の検査（expect.tsv）</h2>
 * 1 行が 1 つのテストで、JLS の節番号・ID・検査の種類・引数・説明を持つ。節ごとの意味（何が呼ばれるはずか）を
 * 人が書いた期待値で、ツールの出力（call-hierarchy.csv / methods.csv）とキャッシュ（analysis-cache.tsv）に
 * 当てる。種類は {@link #check} の switch を参照。
 *
 * <h2>2. javac のバイトコードとの突き合わせ</h2>
 * 同じソースを javac 26 でコンパイルしたクラスファイルを {@code java.lang.classfile} で読み、ツールの
 * キャッシュの事実と突き合わせる。人が期待値を書き漏らした呼び出しも、ここで拾える。
 * <ul>
 *   <li>型: javac が作ったクラス（合成のものを除く）と H 行が 1 対 1 であること。名前は JLS 6.7 の正準名、
 *       正準名を持たない型（匿名・ローカル）は JLS 13.1 のバイナリ名で照合する</li>
 *   <li>宣言: javac のメソッド（合成・ブリッジを除く）と D 行が 1 対 1 であること。JLS が「暗黙に宣言される」
 *       と定めるメンバのうち、ソースに本体が無いもの（enum の values/valueOf、record の書かれなかった
 *       アクセサと toString/hashCode/equals）は D 行を持たない作りなので、INFO として数だけ出す</li>
 *   <li>呼び出し: javac の invoke 命令のうち、呼び出し先がこのソースで宣言されたメソッドのものが、すべて
 *       C 行にあること。逆に C 行のうち同じ条件のものが、すべて javac にあること。
 *       照合は（呼び出し元・行・呼び出し先）の組で行う。呼び出し先の宣言は JVMS 5.4.3.3 の手順
 *       （まず親クラス、次に親インターフェース）で所有型から引き直す。javac は JLS 13.1 の通り
 *       「修飾する型」を書くので、宣言した型とは限らないため</li>
 *   <li>ラムダ本体の合成メソッドの番号は javac の版で違い、JLS も決めていないので、ラムダの中の呼び出しは
 *       それを囲む本物のメソッドの呼び出しとして照合する（ラムダを生成した側へ辿る）。
 *       ラムダそのものは（囲む本物のメソッド・行）で照合する</li>
 *   <li>ブリッジメソッド: javac が消去後のシグネチャの違う上書きのために作ったブリッジ（JLS 15.12.4.5）には、
 *       対応する O 行があること</li>
 * </ul>
 * javac がソースに無い呼び出しを足す翻訳のうち、呼び出し先が JDK のもの（文字列連結・ボックス化・
 * enum の switch の ordinal・null 検査など）は突き合わせの外にある。呼び出し先がこのソースで宣言された
 * ものだけを見るからで、ツールもそれらは JDK の呼び出しとして出力から除く（exclude.packages）。
 */
public final class JlsCheck {

    private static int failures;

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            System.err.println("usage: java JlsCheck.java expect.tsv outDir analysis-cache.tsv javacClasses");
            System.exit(2);
        }
        Tool tool = Tool.load(Path.of(args[2]), Path.of(args[1]));
        Javac javac = Javac.load(Path.of(args[3]));

        System.out.println("== 期待値の検査（JLS SE 26 の節ごと。test/jls/expect.tsv）");
        checkExpectations(Path.of(args[0]), tool);

        System.out.println("== javac 26 のバイトコードとの突き合わせ（節＝パッケージごと）");
        crossCheck(tool, javac);

        System.out.println(failures == 0 ? "PASS" : "FAIL (" + failures + ")");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void ok(String section, String id, String text) {
        System.out.printf("  OK   JLS %-9s %-28s %s%n", section, id, text);
    }

    private static void ng(String section, String id, String text, List<String> details) {
        failures++;
        System.out.printf("  NG   JLS %-9s %-28s %s%n", section, id, text);
        for (String d : details) {
            System.out.println("         " + d);
        }
    }

    // ================================================================
    // 1. 期待値の検査
    // ================================================================

    private static void checkExpectations(Path expect, Tool tool) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        for (String line : Files.readAllLines(expect, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] c = line.split("\t", -1);
            if (c.length != 7) {
                ng("?", "?", "expect.tsv の列数が 7 ではない: " + line, List.of());
                continue;
            }
            String section = c[0];
            String id = c[1];
            if (!ids.add(id)) {
                ng(section, id, "ID が重複している", List.of());
                continue;
            }
            List<String> why = new ArrayList<>();
            if (check(tool, c[2], arg(c[3]), arg(c[4]), arg(c[5]), why)) {
                ok(section, id, c[6]);
            } else {
                ng(section, id, c[6], why);
            }
        }
    }

    private static String arg(String s) {
        return "-".equals(s) ? "" : s;
    }

    /**
     * 1 行の検査。引数のメソッドのキーは {@code 型#名前(引数型,…)}（キャッシュと同じ形。コンストラクタは
     * {@code <init>}）、CSV の呼び出し元は {@code call-hierarchy.csv} の caller 列の {@code at } と
     * {@code (} の間（スタックトレースと同じ形）、呼び出し先は callee 列の表示名。
     */
    private static boolean check(Tool t, String kind, String a, String b, String c, List<String> why) {
        switch (kind) {
            case "call" -> {                    // C 行がある（行は問わない）
                if (t.hasCall(a, b)) {
                    return true;
                }
                why.add("C 行が無い: " + a + " -> " + b);
                why.addAll(t.callsFrom(a));
                return false;
            }
            case "nocall" -> {
                if (!t.hasCall(a, b)) {
                    return true;
                }
                why.add("C 行があってはならない: " + a + " -> " + b);
                return false;
            }
            case "csv" -> {                     // call-hierarchy.csv の行がある（c は resolved-by の前方一致）
                if (t.hasCsv(a, b, c)) {
                    return true;
                }
                why.add("call-hierarchy.csv に行が無い: " + a + " -> " + b
                        + (c.isEmpty() ? "" : " (" + c + ")"));
                why.addAll(t.csvFrom(a));
                return false;
            }
            case "nocsv" -> {
                if (!t.hasCsv(a, b, "")) {
                    return true;
                }
                why.add("call-hierarchy.csv に行があってはならない: " + a + " -> " + b);
                return false;
            }
            case "callcount" -> {               // C 行の数（ソース上の呼び出し箇所の数）
                long n = t.calls.stream().filter(x -> x[0].equals(a) && x[1].equals(b)).count();
                if (String.valueOf(n).equals(c)) {
                    return true;
                }
                why.add("C 行が " + n + " 件（期待は " + c + " 件）: " + a + " -> " + b);
                return false;
            }
            case "path" -> {                    // root 列から先に、「 > 」区切りのノードがこの順で連続して現れる
                if (t.hasPath(a)) {
                    return true;
                }
                why.add("call-hierarchy 列にこの並びが無い: " + a);
                return false;
            }
            case "nopath" -> {
                if (!t.hasPath(a)) {
                    return true;
                }
                why.add("call-hierarchy 列にこの並びがあってはならない: " + a);
                return false;
            }
            case "decl" -> {                    // D 行がある（b は修飾子の語。空なら問わない）
                String mods = t.decls.get(a);
                if (mods != null && (b.isEmpty() || List.of(mods.split(",")).contains(b))) {
                    return true;
                }
                why.add(mods == null ? "D 行が無い: " + a : "D 行の修飾子に " + b + " が無い: " + mods);
                return false;
            }
            case "nodecl" -> {
                if (!t.decls.containsKey(a)) {
                    return true;
                }
                why.add("D 行があってはならない: " + a);
                return false;
            }
            case "type" -> {
                if (t.types.containsKey(a)) {
                    return true;
                }
                why.add("H 行が無い: " + a);
                return false;
            }
            case "override" -> {                // O 行で a が b を上書きしている
                if (t.overrides.getOrDefault(a, List.of()).contains(b)) {
                    return true;
                }
                why.add("O 行が無い: " + a + " -> " + b + "（実際: " + t.overrides.get(a) + "）");
                return false;
            }
            case "nooverride" -> {
                if (!t.overrides.containsKey(a)) {
                    return true;
                }
                why.add("O 行があってはならない: " + a + " -> " + t.overrides.get(a));
                return false;
            }
            case "role" -> {                    // methods.csv の role 列
                String role = t.roles.get(a);
                if (b.equals(role)) {
                    return true;
                }
                why.add("methods.csv の " + a + " の role が " + role + "（期待は " + b + "）");
                return false;
            }
            case "noerror" -> {                 // F 行のエラー数が 0（JDT が構文・型の誤りを報告していない）
                Integer n = t.fileErrors.get(a);
                if (n != null && n == 0) {
                    return true;
                }
                why.add(n == null ? "F 行が無い: " + a : "JDT が報告したエラーが " + n + " 件");
                return false;
            }
            default -> {
                why.add("検査の種類が不明: " + kind);
                return false;
            }
        }
    }

    // ================================================================
    // 2. javac のバイトコードとの突き合わせ
    // ================================================================

    /** 突き合わせる呼び出しの 1 件。呼び出し元はラムダを畳んだ本物のメソッド */
    private record Edge(String caller, int line, String callee) implements Comparable<Edge> {
        @Override
        public int compareTo(Edge o) {
            return toString().compareTo(o.toString());
        }

        @Override
        public String toString() {
            return caller + " -> " + callee + " (行 " + line + ")";
        }
    }

    private static void crossCheck(Tool tool, Javac javac) {
        // 節（＝パッケージの先頭の2段目）ごとに集める
        Map<String, List<String>> problems = new TreeMap<>(JlsCheck::compareSections);
        Map<String, int[]> counts = new TreeMap<>(JlsCheck::compareSections);
        Map<String, List<String>> infos = new TreeMap<>(JlsCheck::compareSections);
        for (String pkg : javac.packages()) {
            problems.put(sectionOf(pkg), new ArrayList<>());
            counts.put(sectionOf(pkg), new int[4]);
            infos.put(sectionOf(pkg), new ArrayList<>());
        }

        // --- 型 ---
        Set<String> javacTypes = javac.toolTypeNames();
        for (String t : javacTypes) {
            String s = sectionOf(javac.packageOfTool(t));
            counts.get(s)[0]++;
            if (!tool.types.containsKey(t)) {
                problems.get(s).add("javac にある型が H 行に無い: " + t);
            }
        }
        for (String t : tool.types.keySet()) {
            if (!javacTypes.contains(t)) {
                problems.computeIfAbsent(sectionOf(tool.types.get(t)), k -> new ArrayList<>())
                        .add("H 行の型が javac に無い: " + t);
            }
        }

        // --- 宣言 ---
        Map<String, Javac.Method> javacDecls = javac.declaredMethods();
        Map<String, Set<String>> implicitMembers = new TreeMap<>();
        for (Map.Entry<String, Javac.Method> e : javacDecls.entrySet()) {
            String s = sectionOf(e.getValue().owner.pkg);
            counts.get(s)[1]++;
            if (tool.decls.containsKey(e.getKey())) {
                continue;
            }
            String implicit = e.getValue().implicitMember();
            if (implicit != null) {
                implicitMembers.computeIfAbsent(s, k -> new TreeSet<>())
                        .add(shortKey(e.getKey()) + "（" + implicit + "）");
            } else {
                problems.get(s).add("javac にあるメソッドが D 行に無い: " + e.getKey());
            }
        }
        for (Map.Entry<String, String> e : tool.decls.entrySet()) {
            if (tool.isLambda(e.getKey()) || javacDecls.containsKey(e.getKey())) {
                continue;
            }
            String type = e.getKey().substring(0, e.getKey().indexOf('#'));
            problems.computeIfAbsent(sectionOf(tool.types.getOrDefault(type, "")), k -> new ArrayList<>())
                    .add("D 行のメソッドが javac に無い: " + e.getKey());
        }

        // --- 呼び出し ---
        Set<Edge> javacEdges = javac.edges();
        Set<Edge> toolEdges = tool.edges(javacTypes);
        for (Edge e : javacEdges) {
            String s = sectionOf(javac.packageOfKey(e.caller));
            counts.get(s)[2]++;
            if (toolEdges.contains(e)) {
                continue;
            }
            if (emittedTwice(e, javacEdges, toolEdges)) {
                // try-with-resources の close() や finally の中の呼び出しは、javac が正常終了の経路と
                // 例外の経路に 1 つずつ書く。ソースの 1 か所がバイトコードの 2 か所（別の行）になる
                infos.get(s).add("javac が同じ呼び出しを別の行にも書いている（finally・try-with-resources の翻訳）: "
                        + e);
            } else if (lineOnly(e, toolEdges)) {
                problems.get(s).add("行番号が違う（javac は " + e.line + " 行）: " + e.caller + " -> " + e.callee
                        + "（ツールは " + linesOf(e, toolEdges) + " 行）");
            } else {
                problems.get(s).add("javac が呼ぶのに C 行に無い: " + e);
            }
        }
        for (Edge e : toolEdges) {
            if (javacEdges.contains(e) || lineOnly(e, javacEdges)) {
                continue;
            }
            String s = sectionOf(javac.packageOfKey(e.caller));
            String jdkOwner = javac.qualifiedBySupertype(e, javacDecls.get(e.callee));
            if (jdkOwner != null) {
                // JLS の変換どおりの静的な型で呼び出し先を引いたもの。javac は同じ呼び出しを親の型
                // （JDK の型）で修飾して書くので、呼び出し先がこのソースに無いように見えるだけで、
                // 実行時に動くメソッドは同じ（JLS 15.12.4.4）
                infos.computeIfAbsent(s, k -> new ArrayList<>()).add("javac は " + jdkOwner
                        + " で修飾して呼ぶ（実行時に動くのは同じメソッド。§15.12.4.4）: " + e);
            } else {
                problems.computeIfAbsent(s, k -> new ArrayList<>()).add("C 行にあるのに javac は呼ばない: " + e);
            }
        }

        // --- ラムダ（囲む本物のメソッドと行で照合） ---
        Set<String> javacLambdas = javac.lambdaSites();
        Set<String> toolLambdas = tool.lambdaSites();
        Set<String> toolRefs = tool.methodRefSites();
        for (String site : javacLambdas) {
            String s = sectionOf(javac.packageOfKey(site.substring(0, site.lastIndexOf('@'))));
            counts.get(s)[3]++;
            if (!toolLambdas.contains(site) && !toolRefs.contains(site)) {
                problems.get(s).add("javac のラムダが D 行（合成メソッド）に無い: " + site);
            }
        }
        for (String site : toolLambdas) {
            if (!javacLambdas.contains(site)) {
                String s = sectionOf(javac.packageOfKey(site.substring(0, site.lastIndexOf('@'))));
                problems.computeIfAbsent(s, k -> new ArrayList<>())
                        .add("ラムダの合成メソッドが javac に無い: " + site);
            }
        }

        // --- ブリッジメソッドと O 行 ---
        for (Javac.Bridge br : javac.bridges()) {
            String s = sectionOf(br.pkg());
            List<String> ov = tool.overrides.getOrDefault(br.target(), List.of());
            boolean found = ov.stream().anyMatch(k -> k.endsWith("#" + br.bridgeSignature()));
            if (!found) {
                problems.get(s).add("javac のブリッジ " + br.bridgeSignature() + " に対応する O 行が無い: "
                        + br.target() + "（O 行: " + ov + "）");
            }
        }

        for (Map.Entry<String, List<String>> e : problems.entrySet()) {
            String s = e.getKey();
            int[] n = counts.getOrDefault(s, new int[4]);
            String summary = String.format("型 %d・宣言 %d・呼び出し %d・ラムダ %d 件が javac と一致",
                    n[0], n[1], n[2], n[3]);
            String id = "javac-" + s.replace('.', '_');
            if (e.getValue().isEmpty()) {
                ok(s, id, summary);
            } else {
                ng(s, id, "javac と食い違う（" + e.getValue().size() + " 件）", e.getValue());
            }
            for (String info : infos.getOrDefault(s, List.of())) {
                System.out.println("         INFO " + info);
            }
            Set<String> implicit = implicitMembers.get(s);
            if (implicit != null) {
                System.out.println("         INFO JLS が暗黙に宣言し、ソースに本体の無いメンバは D 行を持たない（"
                        + implicit.size() + " 件）: " + String.join(", ", implicit));
            }
        }
    }

    /** キーの型を単純名にしたもの（{@code a.b.T#m(java.lang.Object)} → {@code T#m(java.lang.Object)}） */
    private static String shortKey(String key) {
        int hash = key.indexOf('#');
        return key.substring(key.lastIndexOf('.', hash) + 1);
    }

    /** javac が同じ呼び出しを別の行にも書いていて、そちらの行はツールと一致している */
    private static boolean emittedTwice(Edge e, Set<Edge> javacEdges, Set<Edge> toolEdges) {
        return toolEdges.stream().anyMatch(t -> t.caller.equals(e.caller) && t.callee.equals(e.callee)
                && t.line != e.line && javacEdges.contains(t));
    }

    private static boolean lineOnly(Edge e, Set<Edge> others) {
        return others.stream().anyMatch(o -> o.caller.equals(e.caller) && o.callee.equals(e.callee));
    }

    private static String linesOf(Edge e, Set<Edge> others) {
        return String.join(",", others.stream()
                .filter(o -> o.caller.equals(e.caller) && o.callee.equals(e.callee))
                .map(o -> String.valueOf(o.line)).sorted().toList());
    }

    /**
     * パッケージ名から JLS の節番号を作る（{@code jls.s08_04_08.a} → {@code 8.4.8}）。
     * 無名パッケージはコンパクトなコンパイル単位（§7.3）だけが置かれている。
     */
    static String sectionOf(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return "7.3";
        }
        String[] parts = pkg.split("\\.");
        if (parts.length < 2 || !parts[1].startsWith("s")) {
            return pkg;
        }
        StringBuilder sb = new StringBuilder();
        for (String n : parts[1].substring(1).split("_")) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(Integer.parseInt(n));
        }
        return sb.toString();
    }

    private static int compareSections(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = Integer.compare(num(x[i]), num(y[i]));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    // ================================================================
    // ツールの出力とキャッシュ
    // ================================================================

    private static final class Tool {
        /** H 行: 型 → パッケージ */
        final Map<String, String> types = new LinkedHashMap<>();
        /** D 行: キー → 修飾子 */
        final Map<String, String> decls = new LinkedHashMap<>();
        /** D 行の宣言行 */
        final Map<String, Integer> declLines = new HashMap<>();
        /** O 行: キー → 上書き先のキー */
        final Map<String, List<String>> overrides = new HashMap<>();
        /** C 行（呼び出し元, 呼び出し先, 行, 呼び出し先の修飾子） */
        final List<String[]> calls = new ArrayList<>();
        /** M 行（呼び出し元, 行, 種別） */
        final List<String[]> functionals = new ArrayList<>();
        /** F 行: 相対パス → エラー数 */
        final Map<String, Integer> fileErrors = new HashMap<>();
        /** call-hierarchy.csv（caller, callee, resolved-by） */
        final List<String[]> csv = new ArrayList<>();
        /** call-hierarchy.csv の root 列と call-hierarchy 列（起点から先のノード。注記は除く） */
        final List<List<String>> paths = new ArrayList<>();
        /** methods.csv: method → role */
        final Map<String, String> roles = new HashMap<>();

        static Tool load(Path cache, Path outDir) throws IOException {
            Tool t = new Tool();
            for (String line : Files.readAllLines(cache, StandardCharsets.UTF_8)) {
                String[] c = line.split("\t", -1);
                switch (c[0]) {
                    case "H" -> t.types.put(c[1], c[4]);
                    case "D" -> {
                        String key = key(c[2], c[3], c[4]);
                        t.decls.put(key, c[7]);
                        t.declLines.put(key, Integer.parseInt(c[5]));
                    }
                    case "O" -> t.overrides.put(key(c[2], c[3], c[4]), List.of(c[5].split(";")));
                    case "C" -> t.calls.add(new String[] {
                            key(c[2], c[3], c[4]), key(c[6], c[7], c[8]), c[9], c[10]});
                    case "M" -> t.functionals.add(new String[] {key(c[3], c[4], c[5]), c[1], c[7]});
                    case "F" -> t.fileErrors.put(c[1], Integer.parseInt(c[3]));
                    default -> {
                    }
                }
            }
            for (List<String> row : readCsv(outDir.resolve("call-hierarchy.csv"))) {
                String caller = row.get(0);
                if (caller.startsWith("at ") && caller.indexOf('(') > 0) {
                    caller = caller.substring(3, caller.indexOf('('));
                }
                t.csv.add(new String[] {caller, row.get(1), row.get(2)});
                t.paths.add(row.subList(4, row.size()).stream().filter(n -> !n.startsWith("[")).toList());
            }
            for (List<String> row : readCsv(outDir.resolve("methods.csv"))) {
                t.roles.put(row.get(0), row.get(8));
            }
            return t;
        }

        static String key(String type, String method, String params) {
            return type + "#" + method + "(" + params + ")";
        }

        boolean hasCall(String caller, String callee) {
            return calls.stream().anyMatch(c -> c[0].equals(caller) && c[1].equals(callee));
        }

        List<String> callsFrom(String caller) {
            return calls.stream().filter(c -> c[0].equals(caller))
                    .map(c -> "  実際の C 行: " + c[1] + " (行 " + c[2] + ")").toList();
        }

        boolean hasCsv(String caller, String callee, String resolvedBy) {
            return csv.stream().anyMatch(r -> r[0].equals(caller) && r[1].equals(callee)
                    && r[2].startsWith(resolvedBy));
        }

        List<String> csvFrom(String caller) {
            return csv.stream().filter(r -> r[0].equals(caller)).map(r -> "  実際の行: " + r[1] + " " + r[2])
                    .distinct().toList();
        }

        boolean hasPath(String arrows) {
            List<String> want = List.of(arrows.split(" > "));
            for (List<String> path : paths) {
                for (int i = 0; i + want.size() <= path.size(); i++) {
                    if (path.subList(i, i + want.size()).equals(want)) {
                        return true;
                    }
                }
            }
            return false;
        }

        boolean isLambda(String key) {
            String mods = decls.get(key);
            return mods != null && List.of(mods.split(",")).contains("lambda");
        }

        /** ラムダの合成メソッド → それを生成した側（C 行の呼び出し先がラムダのもの） */
        private Map<String, Set<String>> lambdaParents() {
            Map<String, Set<String>> parents = new HashMap<>();
            for (String[] c : calls) {
                if (isLambda(c[1])) {
                    parents.computeIfAbsent(c[1], k -> new TreeSet<>()).add(c[0]);
                }
            }
            return parents;
        }

        /** ラムダを畳んだ本物のメソッド（ラムダでなければ自分） */
        private Set<String> roots(String key, Map<String, Set<String>> parents) {
            if (!isLambda(key)) {
                return Set.of(key);
            }
            Set<String> out = new TreeSet<>();
            for (String p : parents.getOrDefault(key, Set.of())) {
                out.addAll(roots(p, parents));
            }
            return out;
        }

        /** 呼び出し先がソースの型（javac が作った型）の C 行。ラムダの生成の辺は除く */
        Set<Edge> edges(Set<String> sourceTypes) {
            Map<String, Set<String>> parents = lambdaParents();
            Set<Edge> out = new TreeSet<>();
            for (String[] c : calls) {
                String calleeType = c[1].substring(0, c[1].indexOf('#'));
                if (isLambda(c[1]) || !sourceTypes.contains(calleeType)) {
                    continue;
                }
                for (String root : roots(c[0], parents)) {
                    out.add(new Edge(root, Integer.parseInt(c[2]), c[1]));
                }
            }
            return out;
        }

        /** ラムダの合成メソッドを「囲む本物のメソッド@行」で表したもの */
        Set<String> lambdaSites() {
            Map<String, Set<String>> parents = lambdaParents();
            Set<String> out = new TreeSet<>();
            for (String key : decls.keySet()) {
                if (!isLambda(key)) {
                    continue;
                }
                for (String root : roots(key, parents)) {
                    out.add(root + "@" + declLines.get(key));
                }
            }
            return out;
        }

        /**
         * メソッド参照を書いた場所（M 行の種別 methodref / ctorref）。javac はメソッド参照の一部
         * （super::m・配列の ::new など）を合成メソッドにするので、javac のラムダとの照合で使う
         */
        Set<String> methodRefSites() {
            Map<String, Set<String>> parents = lambdaParents();
            Set<String> out = new TreeSet<>();
            for (String[] m : functionals) {
                if (!"lambda".equals(m[2])) {
                    for (String root : roots(m[0], parents)) {
                        out.add(root + "@" + m[1]);
                    }
                }
            }
            return out;
        }
    }

    /** 引用符に対応した最小限の CSV の読み取り（BOM を除く） */
    static List<List<String>> readCsv(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows.isEmpty() ? rows : rows.subList(1, rows.size());   // 見出し行を除く
    }

    // ================================================================
    // javac のクラスファイル
    // ================================================================

    private static final class Javac {
        /** 内部名（q/A$B）→ クラス */
        final Map<String, Cls> classes = new TreeMap<>();

        final class Cls {
            final ClassModel model;
            final String internal;
            final String pkg;
            final boolean synthetic;
            final List<Method> methods = new ArrayList<>();

            Cls(ClassModel model) {
                this.model = model;
                this.internal = model.thisClass().asInternalName();
                int slash = internal.lastIndexOf('/');
                this.pkg = (slash < 0) ? "" : internal.substring(0, slash).replace('/', '.');
                this.synthetic = model.flags().has(AccessFlag.SYNTHETIC);
            }

            boolean isEnum() {
                return model.flags().has(AccessFlag.ENUM);
            }

            /**
             * ツールの型名。JLS 6.7 の正準名があればそれ（メンバ型は外側の型名 + "." + 単純名）、
             * 無ければ（匿名・ローカルと、その中のメンバ）JLS 13.1 のバイナリ名
             */
            String toolName() {
                String canonical = canonicalName(internal);
                return canonical != null ? canonical : internal.replace('/', '.');
            }

            List<String> recordComponents() {
                return model.findAttribute(Attributes.record())
                        .map(r -> r.components().stream().map(RecordComponentInfo::name)
                                .map(u -> u.stringValue()).toList())
                        .orElse(List.of());
            }
        }

        final class Method {
            final Cls owner;
            final MethodModel model;
            final String name;
            final MethodTypeDesc type;

            Method(Cls owner, MethodModel model) {
                this.owner = owner;
                this.model = model;
                this.name = model.methodName().stringValue();
                this.type = model.methodTypeSymbol();
            }

            boolean synthetic() {
                return model.flags().has(AccessFlag.SYNTHETIC);
            }

            boolean bridge() {
                return model.flags().has(AccessFlag.BRIDGE);
            }

            boolean lambda() {
                return synthetic() && name.startsWith("lambda$");
            }

            /**
             * ツールのキー。コンストラクタは javac が足した引数（外側のインスタンスは mandated、enum の
             * name/ordinal と取り込んだ変数は synthetic。MethodParameters に印がある）を除いた
             * ソース上の引数で作る
             */
            String key() {
                List<ClassDesc> params = type.parameterList();
                List<String> names = new ArrayList<>();
                List<MethodParameterInfo> infos = model.findAttribute(Attributes.methodParameters())
                        .map(a -> a.parameters()).orElse(List.of());
                for (int i = 0; i < params.size(); i++) {
                    if ("<init>".equals(name) && i < infos.size() && compilerAdded(infos.get(i))) {
                        continue;
                    }
                    names.add(typeName(params.get(i)));
                }
                if (lambda()) {
                    names.clear();   // ツールは合成メソッドに引数を持たせない
                }
                return owner.toolName() + "#" + name + "(" + String.join(",", names) + ")";
            }

            /**
             * コンストラクタの引数のうち javac が足したもの。取り込んだ変数と enum の name/ordinal は
             * synthetic。外側のインスタンス（{@code this$0}）は mandated（JLS 8.8.1 で暗黙に宣言される）。
             * コンパクトな正準コンストラクタの引数も mandated だが、こちらはソース上の成分なので残す
             */
            private static boolean compilerAdded(MethodParameterInfo p) {
                int f = p.flagsMask();
                if ((f & 0x1000) != 0) {
                    return true;
                }
                return (f & 0x8000) != 0
                        && p.name().map(n -> n.stringValue().startsWith("this$")).orElse(false);
            }

            /** JLS が暗黙に宣言し、ソースに本体の無いメンバなら、その根拠の節。違えば null */
            String implicitMember() {
                String d = type.descriptorString();
                if (owner.isEnum() && model.flags().has(AccessFlag.STATIC)
                        && ("values".equals(name) && d.startsWith("()")
                            || "valueOf".equals(name) && d.startsWith("(Ljava/lang/String;)"))) {
                    return "§8.9.3";
                }
                List<String> comps = owner.recordComponents();
                if (!comps.isEmpty()) {
                    if (comps.contains(name) && d.startsWith("()")) {
                        return "§8.10.3";
                    }
                    if ("toString".equals(name) && d.equals("()Ljava/lang/String;")
                            || "hashCode".equals(name) && d.equals("()I")
                            || "equals".equals(name) && d.equals("(Ljava/lang/Object;)Z")) {
                        return "§8.10.3";
                    }
                }
                return null;
            }
        }

        record Bridge(String pkg, String bridgeSignature, String target) {
        }

        static Javac load(Path dir) throws IOException {
            Javac j = new Javac();
            List<Path> files;
            try (Stream<Path> s = Files.walk(dir)) {
                files = s.filter(p -> p.toString().endsWith(".class")).sorted().toList();
            }
            for (Path p : files) {
                ClassModel cm = ClassFile.of().parse(Files.readAllBytes(p));
                Cls c = j.new Cls(cm);
                for (MethodModel mm : cm.methods()) {
                    c.methods.add(j.new Method(c, mm));
                }
                j.classes.put(c.internal, c);
            }
            return j;
        }

        /** 正準名。無ければ null（JLS 6.7: ローカル・匿名クラスとそのメンバは正準名を持たない） */
        String canonicalName(String internal) {
            Cls c = classes.get(internal);
            if (c == null) {
                return internal.replace('/', '.').replace('$', '.');   // JDK の型（メンバ型だけが $ を持つ）
            }
            Optional<InnerClassInfo> self = c.model.findAttribute(Attributes.innerClasses())
                    .flatMap(a -> a.classes().stream()
                            .filter(i -> i.innerClass().asInternalName().equals(internal)).findFirst());
            if (self.isEmpty()) {
                return internal.replace('/', '.');   // トップレベル
            }
            InnerClassInfo info = self.get();
            if (info.outerClass().isEmpty() || info.innerName().isEmpty()) {
                return null;   // 匿名・ローカル
            }
            String outer = canonicalName(info.outerClass().get().asInternalName());
            return (outer == null) ? null : outer + "." + info.innerName().get().stringValue();
        }

        String typeName(ClassDesc d) {
            if (d.isPrimitive()) {
                return d.displayName();
            }
            if (d.isArray()) {
                return typeName(d.componentType()) + "[]";
            }
            String internal = d.descriptorString().substring(1, d.descriptorString().length() - 1);
            Cls c = classes.get(internal);
            return (c != null) ? c.toolName() : canonicalName(internal);
        }

        List<String> packages() {
            return classes.values().stream().filter(c -> !c.synthetic).map(c -> c.pkg).distinct().toList();
        }

        Set<String> toolTypeNames() {
            Set<String> out = new TreeSet<>();
            for (Cls c : classes.values()) {
                if (!c.synthetic) {
                    out.add(c.toolName());
                }
            }
            return out;
        }

        String packageOfTool(String toolType) {
            for (Cls c : classes.values()) {
                if (c.toolName().equals(toolType)) {
                    return c.pkg;
                }
            }
            return "";
        }

        String packageOfKey(String key) {
            return packageOfTool(key.substring(0, key.indexOf('#')));
        }

        /** 合成・ブリッジでないメソッド（キー → メソッド） */
        Map<String, Method> declaredMethods() {
            Map<String, Method> out = new TreeMap<>();
            for (Cls c : classes.values()) {
                if (c.synthetic) {
                    continue;
                }
                for (Method m : c.methods) {
                    if (!m.synthetic() && !m.bridge()) {
                        out.put(m.key(), m);
                    }
                }
            }
            return out;
        }

        /**
         * 呼び出し先の宣言を引き直す（JVMS 5.4.3.3 / 5.4.3.4: 所有型、その親クラスを順に、次に親インターフェース）。
         * このソースの型で見つからなければ null（JDK のメソッド）
         */
        Method resolve(String owner, String name, String desc) {
            List<Cls> chain = new ArrayList<>();
            for (Cls c = classes.get(owner); c != null;
                    c = c.model.superclass().map(e -> classes.get(e.asInternalName())).orElse(null)) {
                chain.add(c);
                Method m = find(c, name, desc);
                if (m != null) {
                    return m;
                }
            }
            ArrayList<Cls> queue = new ArrayList<>(chain);
            Set<String> seen = new java.util.HashSet<>();
            while (!queue.isEmpty()) {
                Cls c = queue.remove(0);
                for (ClassEntry ie : c.model.interfaces()) {
                    Cls i = classes.get(ie.asInternalName());
                    if (i != null && seen.add(i.internal)) {
                        Method m = find(i, name, desc);
                        if (m != null) {
                            return m;
                        }
                        queue.add(i);
                    }
                }
            }
            return null;
        }

        private static Method find(Cls c, String name, String desc) {
            for (Method m : c.methods) {
                if (m.name.equals(name) && m.type.descriptorString().equals(desc)) {
                    return m;
                }
            }
            return null;
        }

        /** 呼び出し 1 件（呼び出し元のメソッド・行・呼び出し先）と、ラムダの生成 1 件 */
        private record Site(Method caller, int line, Method callee, boolean lambda) {
        }

        /** 呼び出し先が JDK のメソッドの invoke 命令（所有型の内部名・名前・記述子） */
        private record JdkSite(Method caller, int line, String owner, String name, String desc) {
        }

        private List<Site> sites;
        private final List<JdkSite> jdkSites = new ArrayList<>();

        /**
         * ツールにだけある呼び出しが、javac では JDK の親の型で修飾された同じ呼び出しなら、その型の内部名。
         * 同じ呼び出し元・同じ行に、同じ名前・同じ引数の記述子の JDK の invoke があり、
         * 呼び出し先の型がその JDK の型の部分型であるとき。違えば null
         */
        String qualifiedBySupertype(Edge e, Method callee) {
            if (callee == null) {
                return null;
            }
            Map<Method, Set<Method>> parents = lambdaParents();
            String params = callee.type.descriptorString();
            params = params.substring(0, params.indexOf(')') + 1);
            for (JdkSite j : jdkSites) {
                if (j.line == e.line && j.name.equals(callee.name) && j.desc.startsWith(params)
                        && roots(j.caller, parents).contains(e.caller)
                        && isSubtype(callee.owner, j.owner)) {
                    return j.owner;
                }
            }
            return null;
        }

        private boolean isSubtype(Cls c, String ancestor) {
            if (c == null) {
                return false;
            }
            Optional<ClassEntry> sup = c.model.superclass();
            if (sup.isPresent() && (sup.get().asInternalName().equals(ancestor)
                    || isSubtype(classes.get(sup.get().asInternalName()), ancestor))) {
                return true;
            }
            for (ClassEntry i : c.model.interfaces()) {
                if (i.asInternalName().equals(ancestor) || isSubtype(classes.get(i.asInternalName()), ancestor)) {
                    return true;
                }
            }
            return false;
        }

        List<Site> sites() {
            if (sites != null) {
                return sites;
            }
            sites = new ArrayList<>();
            for (Cls c : classes.values()) {
                if (c.synthetic) {
                    continue;   // enum の switch の表を持つ合成クラスなど
                }
                for (Method m : c.methods) {
                    if (m.bridge() || (m.synthetic() && !m.lambda())) {
                        continue;   // ブリッジ・$values など、ソースに無いメソッドの中身
                    }
                    Optional<CodeModel> code = m.model.code();
                    if (code.isEmpty()) {
                        continue;
                    }
                    int line = -1;
                    for (CodeElement e : code.get()) {
                        if (e instanceof LineNumber ln) {
                            line = ln.line();
                        } else if (e instanceof InvokeInstruction ii) {
                            Method target = resolve(ii.owner().asInternalName(), ii.name().stringValue(),
                                    ii.typeSymbol().descriptorString());
                            if (target != null && !target.synthetic()) {
                                sites.add(new Site(m, line, target, false));
                            } else if (target == null) {
                                jdkSites.add(new JdkSite(m, line, ii.owner().asInternalName(),
                                        ii.name().stringValue(), ii.typeSymbol().descriptorString()));
                            }
                        } else if (e instanceof InvokeDynamicInstruction indy) {
                            addIndy(m, line, indy);
                        }
                    }
                }
            }
            return sites;
        }

        /**
         * invokedynamic のうち LambdaMetafactory のもの。実装メソッドが合成のラムダ本体ならラムダの生成、
         * そうでなければメソッド参照（§15.13.3）なので、参照先への呼び出しとして数える
         */
        private void addIndy(Method m, int line, InvokeDynamicInstruction indy) {
            if (!indy.bootstrapMethod().owner().descriptorString()
                    .equals("Ljava/lang/invoke/LambdaMetafactory;")) {
                return;
            }
            List<ConstantDesc> bsArgs = indy.bootstrapArgs();
            if (bsArgs.size() < 2 || !(bsArgs.get(1) instanceof DirectMethodHandleDesc impl)) {
                return;
            }
            String owner = impl.owner().descriptorString();
            owner = owner.substring(1, owner.length() - 1);
            Method target = resolve(owner, impl.methodName(), impl.lookupDescriptor());
            if (target == null) {
                return;
            }
            if (target.lambda()) {
                sites.add(new Site(m, line, target, true));
            } else if (!target.synthetic()) {
                sites.add(new Site(m, line, target, false));
            }
        }

        private Map<Method, Set<Method>> lambdaParents() {
            Map<Method, Set<Method>> parents = new HashMap<>();
            for (Site s : sites()) {
                if (s.lambda) {
                    parents.computeIfAbsent(s.callee, k -> new LinkedHashSet<>()).add(s.caller);
                }
            }
            return parents;
        }

        private Set<String> roots(Method m, Map<Method, Set<Method>> parents) {
            if (!m.lambda()) {
                return Set.of(m.key());
            }
            Set<String> out = new TreeSet<>();
            for (Method p : parents.getOrDefault(m, Set.of())) {
                out.addAll(roots(p, parents));
            }
            return out;
        }

        Set<Edge> edges() {
            Map<Method, Set<Method>> parents = lambdaParents();
            Set<Edge> out = new TreeSet<>();
            for (Site s : sites()) {
                if (s.lambda) {
                    continue;
                }
                for (String root : roots(s.caller, parents)) {
                    out.add(new Edge(root, s.line, s.callee.key()));
                }
            }
            return out;
        }

        Set<String> lambdaSites() {
            Map<Method, Set<Method>> parents = lambdaParents();
            Set<String> out = new TreeSet<>();
            for (Site s : sites()) {
                if (s.lambda) {
                    for (String root : roots(s.caller, parents)) {
                        out.add(root + "@" + s.line);
                    }
                }
            }
            return out;
        }

        /** ブリッジメソッドと、それが呼ぶ本物の上書き（引数の型が違うものだけ。共変の戻り値だけのものは除く） */
        List<Bridge> bridges() {
            List<Bridge> out = new ArrayList<>();
            for (Cls c : classes.values()) {
                for (Method m : c.methods) {
                    if (!m.bridge() || m.model.code().isEmpty()) {
                        continue;
                    }
                    for (CodeElement e : m.model.code().get()) {
                        if (e instanceof InvokeInstruction ii && ii.name().stringValue().equals(m.name)) {
                            Method target = resolve(ii.owner().asInternalName(), ii.name().stringValue(),
                                    ii.typeSymbol().descriptorString());
                            String bridgeKey = m.key();
                            String sig = bridgeKey.substring(bridgeKey.indexOf('#') + 1);
                            if (target != null && !target.key().endsWith("#" + sig)) {
                                out.add(new Bridge(c.pkg, sig, target.key()));
                            }
                        }
                    }
                }
            }
            return out;
        }
    }
}
