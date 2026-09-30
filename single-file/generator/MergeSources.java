// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code src/jche} 配下の全ソースを、パッケージ {@code jche} の 1 ファイル {@code single-file/CallHierarchyExporterSingle.java}
 * に束ねる生成器。{@code single-file/generate.sh} から動かす（JDK 17 以上の {@code java} で直接実行する）。
 *
 * <p>やること（本体のソースは書き換えない。読み替えは機械的なものだけ）:
 * <ol>
 *   <li>{@code package} 行と {@code import jche.…} 行を捨て、それ以外の {@code import} を 1 か所に集める
 *       （単純名の衝突が無いことは先に検査する）。入れ子の型の import（{@code jche.pkg.Outer.Inner}）だけは
 *       外側のクラス経由の import（{@code jche.CallHierarchyExporterSingle.Outer.Inner}）に書き直す</li>
 *   <li>各ファイルの唯一のトップレベル型に {@code static} を付け、外側のクラス {@code CallHierarchyExporterSingle}
 *       の入れ子にする。同じパッケージのように名前で見えるので、本体のコードはそのまま通る</li>
 *   <li>コードに書かれた完全修飾名 {@code jche.パッケージ.型} を単純名にする（文字列リテラルのある行は触らない）</li>
 *   <li>JBang の指示行（{@code //DEPS} / {@code //JAVA}）は {@code src/jche/CallHierarchyExporter.java} のものを
 *       先頭に 1 組だけ置き、本体の中のものは捨てる（{@code //SOURCES} は要らない）</li>
 *   <li>利用者の拡張が import する API（{@code jche.extension}）は入れ子にせず、本物のパッケージのまま
 *       {@code single-file/jche/extension/} に写す（{@code //SOURCES} で一緒にコンパイルする）。Javadoc の
 *       {@code {@link jche.…}} だけは入れ子の型を指せないので {@code {@code}} にする</li>
 *   <li>同梱の拡張の読み込み（{@code jche.config.Plugins}）だけは、設定に書く名前 {@code jche.builtin.X} を
 *       入れ子のクラス名に読み替える 1 行を差し込む（差し込み先が無くなっていれば失敗させる）</li>
 * </ol>
 * 期待した形のソースでなければ（トップレベル型が 1 つでない・単純名が重なる・差し込み先が無い）例外で止める。
 * 黙って壊れた 1 ファイル版を作らないため。
 */
public final class MergeSources {

    /** 出力する外側のクラス名（＝ファイル名） */
    static final String OUTER = "CallHierarchyExporterSingle";
    /**
     * 1 ファイル版のパッケージ。既定パッケージにしないのは、入れ子の型の import
     * （{@code import jche.analysis.CallEdgeExtractor.SourceFile;} など）を書き直す先が要るため。
     * 既定パッケージの型は import できない（JLS 7.5）。
     */
    static final String PACKAGE = "jche";
    /**
     * 入れ子にせず、本物のパッケージのまま別ファイルに写す API（{@code src/jche/} からの相対のフォルダ）。
     * 利用者が Java で書く拡張（{@code plugin.folders}）は {@code import jche.extension.TypeCandidateProvider;} と
     * 書くので、この型だけは 1 ファイル版でも同じ名前で存在しなければならない。{@code java.*} にしか依存しないことは
     * 生成のときに検査する（依存すると入れ子の型を指すことになり、別ファイルにできない）。
     */
    static final String API_DIR = "extension";
    /** 本体の各ファイルの 1 行目 */
    static final String COPYRIGHT = "// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0";
    /** JBang の指示行と //DEPS の出典 */
    static final String MAIN_SOURCE = "CallHierarchyExporter.java";

    /** トップレベル型の宣言行（列 0 から始まる） */
    private static final Pattern TYPE_DECL = Pattern.compile(
            "^((?:(?:public|protected|private|abstract|final|sealed|non-sealed|strictfp)\\s+)*)"
            + "(class|interface|enum|record|@interface)\\s+([A-Za-z_$][\\w$]*)");
    /** JBang が読む指示行。行頭に空白無しで //KEY の形 */
    private static final Pattern JBANG_DIRECTIVE = Pattern.compile(
            "^//(DEPS|JAVA|SOURCES|FILES|JAVA_OPTIONS|RUNTIME_OPTIONS|COMPILE_OPTIONS|NATIVE_OPTIONS|MAIN|MODULE|"
            + "MANIFEST|REPOS|GAV|DESCRIPTION|PREVIEW|CDS|KOTLIN|GROOVY|JAVAAGENT|DOCS)\\b.*");
    /** コードの中の完全修飾名 jche[.pkg].Type（別ファイルに写す API のパッケージは除く） */
    private static final Pattern JCHE_FQN = Pattern.compile("\\bjche\\.(?!" + API_DIR + "\\.)(?:[a-z][\\w]*\\.)*([A-Z][\\w]*)");

    private MergeSources() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: java MergeSources.java <src/jche> <output .java>");
            System.exit(2);
        }
        Path srcDir = Paths.get(args[0]).toAbsolutePath().normalize();
        Path out = Paths.get(args[1]).toAbsolutePath().normalize();
        String text = merge(srcDir);
        Files.createDirectories(out.getParent());
        Files.writeString(out, text, StandardCharsets.UTF_8);
        System.out.println("wrote " + out + " (" + text.lines().count() + " lines)");
        // 拡張 API はそのまま写す（出力先の隣の jche/extension/。古いものが残らないよう先に空にする）
        Path apiOut = out.getParent().resolve(PACKAGE).resolve(API_DIR);
        if (Files.isDirectory(apiOut)) {
            try (Stream<Path> s = Files.list(apiOut)) {
                for (Path old : s.filter(q -> q.toString().endsWith(".java")).toList()) {
                    Files.delete(old);
                }
            }
        }
        Files.createDirectories(apiOut);
        for (Path api : apiFiles(srcDir)) {
            // Javadoc の {@link jche.…} は 1 ファイル版では入れ子の型を指して解決できない（doclint が落とす）ので {@code} にする
            String apiText = Files.readString(api, StandardCharsets.UTF_8)
                    .replaceAll("\\{@link(plain)? (" + PACKAGE + "\\.(?!" + API_DIR + "\\.)[^}]*)\\}", "{@code $2}");
            Files.writeString(apiOut.resolve(api.getFileName().toString()), apiText, StandardCharsets.UTF_8);
        }
        System.out.println("copied " + apiFiles(srcDir).size() + " API files to " + apiOut);
    }

    /** 1 つのソースファイルを読み替えた結果 */
    private static final class Unit {
        final String relPath;
        final String typeName;
        final List<String> imports = new ArrayList<>();
        final List<String> body = new ArrayList<>();

        Unit(String relPath, String typeName) {
            this.relPath = relPath;
            this.typeName = typeName;
        }
    }

    static String merge(Path srcDir) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(srcDir)) {
            files = s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("package-info.java"))
                    .filter(p -> !rel(srcDir, p).startsWith(API_DIR + "/"))
                    .sorted((a, b) -> rel(srcDir, a).compareTo(rel(srcDir, b)))
                    .toList();
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("no sources under " + srcDir);
        }

        checkApiFiles(srcDir);

        List<Unit> units = new ArrayList<>();
        Map<String, String> typeOwner = new TreeMap<>();   // 単純名 → ファイル（衝突の検査）
        for (Path f : files) {
            Unit u = convert(srcDir, f);
            String prev = typeOwner.put(u.typeName, u.relPath);
            if (prev != null) {
                throw new IllegalStateException("top-level type name clashes: " + u.typeName
                        + " in " + prev + " and " + u.relPath);
            }
            units.add(u);
        }

        // import を集める。同じ単純名が別のパッケージから来ていれば、1 ファイルにはできない
        TreeSet<String> imports = new TreeSet<>();
        Map<String, String> simpleToFqn = new TreeMap<>();
        for (Unit u : units) {
            for (String imp : u.imports) {
                String fqn = imp.replaceAll("^import\\s+(static\\s+)?", "").replaceAll("\\s*;\\s*$", "");
                if (fqn.startsWith(PACKAGE + "." + API_DIR + ".")) {
                    // 別ファイルに写す API。そのまま import する
                } else if (fqn.startsWith(PACKAGE + ".")) {
                    // 本体の型。トップレベル型は同じクラスの入れ子になるので import は要らない。
                    // 入れ子の型（jche.pkg.Outer.Inner）だけは外側のクラス経由の import に書き直す
                    String nested = nestedPath(fqn, typeOwner);
                    if (nested == null) {
                        continue;
                    }
                    imp = "import " + (imp.startsWith("import static ") ? "static " : "")
                            + PACKAGE + "." + OUTER + "." + nested + ";";
                    fqn = PACKAGE + "." + OUTER + "." + nested;
                }
                imports.add(imp);
                String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
                if (simple.equals("*")) {
                    throw new IllegalStateException("on-demand import is not supported: " + imp + " in " + u.relPath);
                }
                String prev = simpleToFqn.put(simple, fqn);
                if (prev != null && !prev.equals(fqn)) {
                    throw new IllegalStateException("import simple name clashes: " + prev + " vs " + fqn
                            + " (" + u.relPath + ")");
                }
                if (typeOwner.containsKey(simple)) {
                    throw new IllegalStateException("import " + fqn + " hides top-level type " + simple
                            + " of " + typeOwner.get(simple));
                }
            }
        }

        // 同梱の拡張の読み込みに、入れ子のクラス名への読み替えを差し込む
        patchPlugins(units);

        List<String> directives = jbangDirectives(srcDir.resolve(MAIN_SOURCE));

        StringBuilder sb = new StringBuilder(4 << 20);
        sb.append(COPYRIGHT).append('\n');
        sb.append(header(units.size()));
        for (String d : directives) {
            sb.append(d).append('\n');
        }
        sb.append("//SOURCES ").append(PACKAGE).append('/').append(API_DIR).append("/*.java\n");
        sb.append("package ").append(PACKAGE).append(";\n\n");
        String group = null;
        for (String imp : imports) {
            String g = imp.startsWith("import static ") ? "static" : imp.split("\\s+")[1].split("\\.")[0];
            if (group != null && !group.equals(g)) {
                sb.append('\n');
            }
            group = g;
            sb.append(imp).append('\n');
        }
        sb.append('\n');
        sb.append(outerHead());
        for (Unit u : units) {
            sb.append('\n');
            sb.append("    // ").append("=".repeat(96)).append('\n');
            sb.append("    // src/jche/").append(u.relPath).append('\n');
            sb.append("    // ").append("=".repeat(96)).append('\n');
            for (String line : u.body) {
                if (line.isEmpty()) {
                    sb.append('\n');
                } else {
                    sb.append("    ").append(line).append('\n');
                }
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 本体の型の完全修飾名から、トップレベル型以下の部分（{@code Outer.Inner}）を返す。
     * トップレベル型そのもの（入れ子でない）なら null。トップレベル型が見つからなければ失敗させる
     */
    private static String nestedPath(String fqn, Map<String, String> typeOwner) {
        String[] parts = fqn.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            if (typeOwner.containsKey(parts[i])) {
                return (i == parts.length - 1) ? null : String.join(".", Arrays.copyOfRange(parts, i, parts.length));
            }
        }
        throw new IllegalStateException("import of unknown type: " + fqn);
    }

    /** 別ファイルに写す API のソース（名前順） */
    private static List<Path> apiFiles(Path srcDir) throws IOException {
        try (Stream<Path> s = Files.list(srcDir.resolve(API_DIR))) {
            return s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("package-info.java"))
                    .sorted().toList();
        }
    }

    /** API のソースが java.* と自分のパッケージにしか依存しないこと（入れ子の型を指していれば別ファイルにできない） */
    private static void checkApiFiles(Path srcDir) throws IOException {
        for (Path api : apiFiles(srcDir)) {
            for (String line : Files.readAllLines(api, StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (t.startsWith("import ") && !t.matches("import (static )?(java\\.|" + PACKAGE + "\\." + API_DIR + "\\.).*")) {
                    throw new IllegalStateException("API file " + api.getFileName() + " imports " + t
                            + " which is nested in the single file; cannot keep it as a separate file");
                }
            }
        }
    }

    private static String rel(Path base, Path p) {
        return base.relativize(p).toString().replace('\\', '/');
    }

    /** 1 ファイルを読み、package / import / JBang の指示行を外し、トップレベル型に static を付ける */
    private static Unit convert(Path srcDir, Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String relPath = rel(srcDir, file);
        // 1 行目の著作権表示は先頭に 1 つだけ置くので捨てる（無いファイルもある）
        int first = (!lines.isEmpty() && lines.get(0).equals(COPYRIGHT)) ? 1 : 0;
        String typeName = null;
        List<String> imports = new ArrayList<>();
        List<String> body = new ArrayList<>();
        boolean inBlockComment = false;
        boolean seenPackage = false;
        for (int i = first; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();
            boolean startsInComment = inBlockComment;
            inBlockComment = endsInBlockComment(line, inBlockComment);
            if (!startsInComment) {
                if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                    seenPackage = true;
                    continue;
                }
                if (trimmed.startsWith("import ") && trimmed.endsWith(";")) {
                    imports.add(trimmed.replaceAll("\\s+", " "));
                    continue;
                }
                if (JBANG_DIRECTIVE.matcher(line).matches()) {
                    continue;
                }
                Matcher m = TYPE_DECL.matcher(line);
                if (m.find()) {
                    if (typeName != null) {
                        throw new IllegalStateException("more than one top-level type in " + relPath
                                + ": " + typeName + ", " + m.group(3));
                    }
                    typeName = m.group(3);
                    line = m.group(1) + "static " + line.substring(m.start(2));
                }
            }
            // 完全修飾名を単純名にする。文字列リテラルのある行は触らない（コメントの行は文字列でないので触る）
            if (line.indexOf('"') < 0 || trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) {
                line = JCHE_FQN.matcher(line).replaceAll("$1");
            }
            body.add(line);
        }
        if (!seenPackage) {
            throw new IllegalStateException("no package declaration in " + relPath);
        }
        if (typeName == null) {
            throw new IllegalStateException("no top-level type found in " + relPath);
        }
        String expected = file.getFileName().toString().replaceAll("\\.java$", "");
        if (!typeName.equals(expected)) {
            throw new IllegalStateException("top-level type " + typeName + " does not match file name " + relPath);
        }
        // 先頭と末尾の空行は要らない
        while (!body.isEmpty() && body.get(0).isBlank()) {
            body.remove(0);
        }
        while (!body.isEmpty() && body.get(body.size() - 1).isBlank()) {
            body.remove(body.size() - 1);
        }
        Unit u = new Unit(relPath, typeName);
        u.imports.addAll(imports);
        u.body.addAll(body);
        return u;
    }

    /** 行の終わりでブロックコメントの中にいるか。文字列リテラルの中の /* は考えない（本体にはそういう行が無い前提で、宣言行の判定にしか使わない） */
    private static boolean endsInBlockComment(String line, boolean in) {
        int i = 0;
        while (i < line.length()) {
            if (in) {
                int end = line.indexOf("*/", i);
                if (end < 0) {
                    return true;
                }
                in = false;
                i = end + 2;
            } else {
                int lineComment = line.indexOf("//", i);
                int start = line.indexOf("/*", i);
                if (start < 0 || (lineComment >= 0 && lineComment < start)) {
                    return false;
                }
                in = true;
                i = start + 2;
            }
        }
        return in;
    }

    /** {@code Plugins} の Class.forName に、同梱の拡張の名前の読み替えを差し込む */
    private static void patchPlugins(List<Unit> units) {
        String needle = "Class.forName(className, true, loader)";
        String replacement = "Class.forName(bundledClassName(className), true, loader)";
        int hits = 0;
        for (Unit u : units) {
            if (!u.relPath.equals("config/Plugins.java")) {
                continue;
            }
            for (int i = 0; i < u.body.size(); i++) {
                String line = u.body.get(i);
                if (line.contains(needle)) {
                    u.body.set(i, line.replace(needle, replacement));
                    hits++;
                }
            }
        }
        if (hits != 1) {
            throw new IllegalStateException("expected exactly one '" + needle + "' in config/Plugins.java, found " + hits);
        }
    }

    /** src/jche/CallHierarchyExporter.java の //DEPS と //JAVA（//SOURCES は 1 ファイルなので要らない） */
    private static List<String> jbangDirectives(Path mainSource) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(mainSource, StandardCharsets.UTF_8)) {
            if (line.startsWith("//DEPS ") || line.startsWith("//JAVA ")) {
                out.add(line);
            }
        }
        if (out.stream().noneMatch(l -> l.startsWith("//DEPS ")) || out.stream().noneMatch(l -> l.startsWith("//JAVA "))) {
            throw new IllegalStateException("//DEPS or //JAVA not found in " + mainSource);
        }
        return out;
    }

    private static String header(int fileCount) {
        return String.join("\n",
                "",
                "// ---------------------------------------------------------------------------",
                "// java-call-hierarchy-exporter の 1 ファイル版（完全版）。",
                "//",
                "// このファイルは single-file/generate.sh が src/jche 配下の全ソース（" + fileCount + " ファイル）から",
                "// 機械的に生成したもの。手で編集しない。本体（src/jche）を直したら生成し直す",
                "// （test/single-file/run.sh が、生成し直していないことと、ビルド・実行できないことを検出する）。",
                "//",
                "// 本体の各ファイルのトップレベル型を、パッケージ " + PACKAGE + " の外側のクラス " + OUTER + " の",
                "// 入れ子（static）にしてある。機能は本体と同じ（キャッシュ・差分更新・データフロー解析・被参照スキャン・",
                "// ビルドファイルからの依存解決・対話モード・サーバーモード）。違うのはクラス名だけで、",
                "//   - 同梱の拡張は設定に本体と同じ名前（jche.builtin.TypeMappingProvider）で書けば読み替える",
                "//   - 利用者が Java で書く拡張（plugin.folders）が import する jche.extension.* だけは入れ子にせず、",
                "//     本物のパッケージのまま隣の jche/extension/ に置く（//SOURCES）。ビルドのときは一緒に渡す",
                "//   - ツールのプロジェクトフォルダ（キャッシュの置き場所 .cache/）は、リポジトリの中で動かすか",
                "//     JCHE_ROOT で示す。分からなければ作業ディレクトリを使い、その旨を警告する（本体と同じ）",
                "//",
                "// ビルドと実行（引数があれば対話なしで解析、無ければ対話モード。src/jche/Jche.java と同じ）:",
                "//   javac -encoding UTF-8 -cp \"lib/*\" -d bin single-file/" + OUTER + ".java single-file/jche/extension/*.java",
                "//   java  -cp \"bin:lib/*\" " + PACKAGE + "." + OUTER + " config/jche.properties   (Windows は ; 区切り)",
                "//",
                "// JBang なら jar を自分で集めずに直接（初回は JDK 25 と JDT を取得する）:",
                "//   jbang single-file/" + OUTER + ".java config/jche.properties",
                "// ---------------------------------------------------------------------------",
                "");
    }

    private static String outerHead() {
        return String.join("\n",
                "/**",
                " * 1 ファイル版の外側のクラス。本体のすべての型をこの中に入れ子で持つ。",
                " * 入口は {@link Jche#main}（引数があれば対話なしで解析、無ければ対話モード）。",
                " */",
                "public final class " + OUTER + " {",
                "",
                "    private " + OUTER + "() {",
                "    }",
                "",
                "    public static void main(String[] args) throws Exception {",
                "        Jche.main(args);",
                "    }",
                "",
                "    /**",
                "     * 同梱の拡張の名前を、この 1 ファイル版でのクラス名に読み替える。",
                "     * 設定（resolver.candidate.providers など）には本体と同じ {@code jche.builtin.X} と書けるようにするため。",
                "     * 同梱でない名前はそのまま返す。",
                "     */",
                "    static String bundledClassName(String className) {",
                "        String prefix = \"jche.builtin.\";",
                "        if (className.startsWith(prefix) && className.indexOf('.', prefix.length()) < 0) {",
                "            return " + OUTER + ".class.getName() + \"$\" + className.substring(prefix.length());",
                "        }",
                "        return className;",
                "    }",
                "");
    }
}
