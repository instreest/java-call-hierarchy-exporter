// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 設定ファイルを対話で新しく作る。
 *
 * README の Quick start で「書き換える」とされている項目（project.root / source.folders / library.folders /
 * source.encoding）と entry.packages だけを尋ね、残りは {@code config/config.properties}（既定の設定
 * ファイル）をひな形にしてそのまま写す。ひな形の行を置き換える方式なので、全項目の説明コメントが
 * 新しいファイルにも残り、あとから他の項目を編集するときに config/config.properties を見に行かなくて済む。
 *
 * 書き先は {@code config/<名前>.properties}（既定の設定ファイルと同じフォルダ）。相対パスの起点はその設定ファイルのフォルダなので、
 * project.root は config/ からの相対（近ければ）か絶対パスで書く（{@link #projectRootValue}）。
 * パスの区切りは常に {@code /}。.properties では {@code \} がエスケープ文字なので、Windows のパスを
 * そのまま書くと壊れる。
 */
public final class ConfigWizard {

    private static final String TEMPLATE_NAME = ConfigCatalog.CONFIGS_DIR_NAME + "/" + ConfigCatalog.DEFAULT_CONFIG_NAME;

    private final Terminal t;
    private final Path root;

    public ConfigWizard(Terminal t, Path root) {
        this.t = t;
        this.root = root;
    }

    /**
     * @return 作った設定ファイル。途中でやめたら null
     */
    public Path run() throws IOException {
        Path template = root.resolve(TEMPLATE_NAME);
        if (!Files.isRegularFile(template)) {
            t.println("ひな形の " + template + " がありません。");
            return null;
        }
        t.println("新しい設定ファイルを作ります。Enter で [ ] の既定値、q で中止。");
        t.println("ここで尋ねない項目（exclude.packages、max.depth 等）はひな形の既定値のままになります。");
        t.println();

        // --- project.root ---
        Path projectRoot;
        while (true) {
            String raw = t.ask("解析対象プロジェクトのフォルダ（project.root）", "");
            if (raw.equalsIgnoreCase("q")) {
                return null;
            }
            if (raw.isEmpty()) {
                t.println("  必須です。");
                continue;
            }
            Path p = Paths.get(expandHome(raw));
            projectRoot = (p.isAbsolute() ? p : root.resolve(p)).toAbsolutePath().normalize();
            if (Files.isDirectory(projectRoot)) {
                break;
            }
            t.println("  フォルダがありません: " + projectRoot);
        }
        Detected detected = detect(projectRoot);
        t.println("  → " + projectRoot);
        if (!detected.notes.isEmpty()) {
            for (String n : detected.notes) {
                t.println("     " + n);
            }
        }

        // --- 出力先のファイル名 ---
        String projectName = projectRoot.getFileName() == null ? "project" : projectRoot.getFileName().toString();
        Path configsDir = root.resolve(ConfigCatalog.CONFIGS_DIR_NAME);
        Path target;
        while (true) {
            String name = t.ask("設定ファイルの名前（" + ConfigCatalog.CONFIGS_DIR_NAME + "/ の下に作る）", projectName + ".properties");
            if (name.equalsIgnoreCase("q")) {
                return null;
            }
            if (!name.endsWith(".properties")) {
                name += ".properties";
            }
            target = configsDir.resolve(name).normalize();
            if (!target.startsWith(configsDir)) {
                t.println("  " + ConfigCatalog.CONFIGS_DIR_NAME + "/ の中の名前にしてください。");
                continue;
            }
            if (target.equals(template)) {
                t.println("  ひな形の " + TEMPLATE_NAME + " 自体は上書きできません。別の名前にしてください。");
                continue;
            }
            if (Files.exists(target)) {
                if (t.confirm("  " + relative(target) + " は既にあります。上書きしますか？", false)) {
                    break;
                }
                continue;
            }
            break;
        }

        // --- source.folders ---
        String sourceDefault = String.join(",", detected.sourceFolders);
        String sourceFolders;
        while (true) {
            sourceFolders = t.ask("ソースフォルダ（source.folders。project.root からの相対、カンマ区切り）", sourceDefault);
            if (sourceFolders.equalsIgnoreCase("q")) {
                return null;
            }
            if (sourceFolders.isEmpty()) {
                if (Files.isRegularFile(projectRoot.resolve(".classpath"))) {
                    t.println("  空欄なので .classpath の kind=\"src\" を使います。");
                    break;
                }
                t.println("  必須です（.classpath も無いため）。");
                continue;
            }
            List<String> missing = new ArrayList<>();
            for (String s : sourceFolders.split(",")) {
                String f = s.trim();
                if (!f.isEmpty() && !Files.isDirectory(projectRoot.resolve(f))) {
                    missing.add(f);
                }
            }
            if (missing.isEmpty()) {
                break;
            }
            t.println("  見つからないフォルダがあります: " + missing);
            if (t.confirm("  このまま使いますか？", false)) {
                break;
            }
        }

        // --- library.folders ---
        String libHint = detected.buildFile == null
                ? "依存 jar を集めたフォルダ（library.folders。project.root からの相対、カンマ区切り）"
                : "依存 jar を集めたフォルダ（library.folders。空欄なら " + detected.buildFile + " から自動取得）";
        String libraryFolders = t.ask(libHint, "");
        if (libraryFolders.equalsIgnoreCase("q")) {
            return null;
        }

        // --- source.encoding ---
        String encoding = t.ask("ソースの文字コード（source.encoding）", detected.encoding);
        if (encoding.equalsIgnoreCase("q")) {
            return null;
        }

        // --- entry.packages ---
        String entry = t.ask("起点のパッケージ（entry.packages。空欄なら全体モード＝呼び出し元が無いメソッドを起点にする）", "");
        if (entry.equalsIgnoreCase("q")) {
            return null;
        }

        Map<String, String> values = new LinkedHashMap<>();
        values.put("project.root", projectRootValue(projectRoot, configsDir));
        values.put("source.folders", sourceFolders.replace('\\', '/'));
        values.put("library.folders", libraryFolders.replace('\\', '/'));
        values.put("source.encoding", encoding);
        values.put("entry.packages", entry);

        t.println();
        t.println("--- " + relative(target) + " に書く内容 ---");
        for (Map.Entry<String, String> e : values.entrySet()) {
            t.println("  " + e.getKey() + "=" + e.getValue());
        }
        t.println("  （他の項目は " + TEMPLATE_NAME + " の既定値）");
        if (!t.confirm("この内容で作りますか？", true)) {
            return null;
        }

        Files.createDirectories(configsDir);
        Files.write(target, applyToTemplate(Files.readAllLines(template, StandardCharsets.UTF_8), values),
                StandardCharsets.UTF_8);
        t.println("作成しました: " + target);
        return target;
    }

    /**
     * ひな形の各行のうち、置き換える項目の {@code key=} 行を新しい値にする。
     * {@code \} で続く複数行の値（exclude.packages 等）は、置き換え対象なら続きの行ごと捨てる。
     * ひな形に無い項目は末尾に足す。
     */
    static List<String> applyToTemplate(List<String> template, Map<String, String> values) {
        List<String> out = new ArrayList<>();
        Map<String, String> remaining = new LinkedHashMap<>(values);
        boolean skippingContinuation = false;
        for (String line : template) {
            if (skippingContinuation) {
                skippingContinuation = line.trim().endsWith("\\");
                continue;
            }
            Matcher m = KEY_LINE.matcher(line);
            if (m.matches() && remaining.containsKey(m.group(1))) {
                String key = m.group(1);
                out.add(key + "=" + remaining.remove(key));
                skippingContinuation = line.trim().endsWith("\\");
                continue;
            }
            out.add(line);
        }
        for (Map.Entry<String, String> e : remaining.entrySet()) {
            out.add(e.getKey() + "=" + e.getValue());
        }
        return out;
    }

    private static final Pattern KEY_LINE = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9.]*)\\s*[=:].*$");

    /**
     * project.root の書き方。設定ファイルのフォルダ（config/）から上位へ 2 段以内で書ける相対パスならそれ
     * （ツールのリポジトリと解析対象を並べて置く構成なら、両方を一緒に移動しても壊れない）。
     * それより遠い場所や別ドライブは絶対パス。区切りは常に /
     */
    private static String projectRootValue(Path projectRoot, Path configsDir) {
        String value = projectRoot.toString();
        if (projectRoot.getRoot() != null && projectRoot.getRoot().equals(configsDir.getRoot())) {
            Path rel = configsDir.relativize(projectRoot);
            int ups = 0;
            for (Path n : rel) {
                if (n.toString().equals("..")) {
                    ups++;
                }
            }
            if (ups <= 2 && !rel.toString().isEmpty()) {
                value = rel.toString();
            }
        }
        return value.replace('\\', '/');
    }

    private String relative(Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    private static String expandHome(String raw) {
        String s = raw.trim();
        if (s.equals("~") || s.startsWith("~/") || s.startsWith("~\\")) {
            return System.getProperty("user.home") + s.substring(1);
        }
        return s;
    }

    /** project.root を見て分かること（ソースフォルダの候補、ビルドファイル、文字コード） */
    static final class Detected {
        final List<String> sourceFolders = new ArrayList<>();
        String buildFile;
        String encoding = "UTF-8";
        final List<String> notes = new ArrayList<>();
    }

    static Detected detect(Path projectRoot) {
        Detected d = new Detected();
        // ビルドファイル。文字コードは pom.xml の project.build.sourceEncoding があればそれ
        if (Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            d.buildFile = "pom.xml";
            String enc = pomEncoding(projectRoot.resolve("pom.xml"));
            if (enc != null) {
                d.encoding = enc;
                d.notes.add("pom.xml の project.build.sourceEncoding=" + enc);
            }
        } else if (Files.isRegularFile(projectRoot.resolve("build.gradle"))
                || Files.isRegularFile(projectRoot.resolve("build.gradle.kts"))
                || Files.isRegularFile(projectRoot.resolve("settings.gradle"))
                || Files.isRegularFile(projectRoot.resolve("settings.gradle.kts"))) {
            d.buildFile = "build.gradle";
        }
        if (d.buildFile != null) {
            d.notes.add(d.buildFile + " があります（library.folders を空欄にすると依存 jar を自動取得）");
        }
        // ソースフォルダの候補。Maven / Gradle の標準配置 → src → マルチモジュールの各モジュール
        for (String c : new String[] {"src/main/java", "src"}) {
            if (Files.isDirectory(projectRoot.resolve(c))) {
                d.sourceFolders.add(c);
                break;
            }
        }
        if (d.sourceFolders.isEmpty()) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(projectRoot)) {
                List<String> modules = new ArrayList<>();
                for (Path child : ds) {
                    if (Files.isDirectory(child) && Files.isDirectory(child.resolve("src/main/java"))) {
                        modules.add(child.getFileName() + "/src/main/java");
                    }
                }
                modules.sort(null);
                d.sourceFolders.addAll(modules);
            } catch (IOException e) {
                // 候補が出ないだけ
            }
        }
        if (!d.sourceFolders.isEmpty()) {
            d.notes.add("ソースフォルダの候補: " + String.join(", ", d.sourceFolders));
        } else if (Files.isRegularFile(projectRoot.resolve(".classpath"))) {
            d.notes.add(".classpath があります（source.folders を空欄にすると kind=\"src\" を使う）");
        }
        return d;
    }

    private static final Pattern POM_ENCODING =
            Pattern.compile("<project\\.build\\.sourceEncoding>\\s*([^<\\s]+)\\s*</project\\.build\\.sourceEncoding>");

    private static String pomEncoding(Path pom) {
        try {
            Matcher m = POM_ENCODING.matcher(Files.readString(pom, StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
