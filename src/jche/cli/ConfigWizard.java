// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cli;

import jche.config.ProjectDetector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jche.util.Messages;
import jche.util.UserHome;

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
        Path template = ConfigCatalog.defaultConfig(root);
        if (!Files.isRegularFile(template)) {
            t.println(Messages.format("cli.wizard.noTemplate", template));
            return null;
        }
        String templateName = relative(template);
        t.println(Messages.get("cli.wizard.intro"));
        t.println(Messages.get("cli.wizard.introDefaults"));
        t.println();

        // --- project.root ---
        Path projectRoot;
        while (true) {
            String raw = t.ask(Messages.get("cli.wizard.ask.projectRoot"), "");
            if (raw.equalsIgnoreCase("q")) {
                return null;
            }
            if (raw.isEmpty()) {
                t.println(Messages.get("cli.wizard.required"));
                continue;
            }
            Path p = Paths.get(UserHome.expand(raw));
            projectRoot = (p.isAbsolute() ? p : root.resolve(p)).toAbsolutePath().normalize();
            if (Files.isDirectory(projectRoot)) {
                break;
            }
            t.println(Messages.format("cli.wizard.noFolder", projectRoot));
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
            String name = t.ask(Messages.format("cli.wizard.ask.name", ConfigCatalog.CONFIGS_DIR_NAME),
                    projectName + ".properties");
            if (name.equalsIgnoreCase("q")) {
                return null;
            }
            if (!name.endsWith(".properties")) {
                name += ".properties";
            }
            target = configsDir.resolve(name).normalize();
            if (!target.startsWith(configsDir)) {
                t.println(Messages.format("cli.wizard.nameOutside", ConfigCatalog.CONFIGS_DIR_NAME));
                continue;
            }
            if (target.equals(template)) {
                t.println(Messages.format("cli.wizard.nameIsTemplate", templateName));
                continue;
            }
            if (Files.exists(target)) {
                if (t.confirm(Messages.format("cli.wizard.overwrite", relative(target)), false)) {
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
            sourceFolders = t.ask(Messages.get("cli.wizard.ask.sourceFolders"), sourceDefault);
            if (sourceFolders.equalsIgnoreCase("q")) {
                return null;
            }
            if (sourceFolders.isEmpty()) {
                if (Files.isRegularFile(projectRoot.resolve(".classpath"))) {
                    t.println(Messages.get("cli.wizard.useClasspath"));
                    break;
                }
                t.println(Messages.get("cli.wizard.requiredNoClasspath"));
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
            t.println(Messages.format("cli.wizard.missingFolders", missing));
            if (t.confirm(Messages.get("cli.wizard.useAnyway"), false)) {
                break;
            }
        }

        // --- library.folders ---
        String libHint = (detected.buildFile == null)
                ? Messages.get("cli.wizard.ask.libraryFolders")
                : Messages.format("cli.wizard.ask.libraryFoldersAuto", detected.buildFile);
        String libraryFolders = t.ask(libHint, "");
        if (libraryFolders.equalsIgnoreCase("q")) {
            return null;
        }

        // --- source.encoding ---
        String encoding = t.ask(Messages.get("cli.wizard.ask.encoding"), detected.encoding);
        if (encoding.equalsIgnoreCase("q")) {
            return null;
        }

        // --- entry.packages ---
        String entry = t.ask(Messages.get("cli.wizard.ask.entryPackages"), "");
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
        t.println(Messages.format("cli.wizard.preview", relative(target)));
        for (Map.Entry<String, String> e : values.entrySet()) {
            t.println("  " + e.getKey() + "=" + e.getValue());
        }
        t.println(Messages.format("cli.wizard.previewRest", templateName));
        if (!t.confirm(Messages.get("cli.wizard.createConfirm"), true)) {
            return null;
        }

        Files.createDirectories(configsDir);
        Files.write(target, applyToTemplate(Files.readAllLines(template, StandardCharsets.UTF_8), values),
                StandardCharsets.UTF_8);
        t.println(Messages.format("cli.wizard.created", target));
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
        d.buildFile = ProjectDetector.buildFile(projectRoot);
        if (d.buildFile != null) {
            String enc = ProjectDetector.pomEncoding(projectRoot.resolve("pom.xml"));
            if (enc != null) {
                d.encoding = enc;
                d.notes.add(Messages.format("cli.wizard.note.pomEncoding", enc));
            }
            d.notes.add(Messages.format("cli.wizard.note.buildFile", d.buildFile));
        }
        // ソースフォルダの候補。Maven / Gradle の標準配置 → src → マルチモジュールの各モジュール
        d.sourceFolders.addAll(ProjectDetector.sourceFolderCandidates(projectRoot));
        if (!d.sourceFolders.isEmpty()) {
            d.notes.add(Messages.format("cli.wizard.note.sourceCandidates", String.join(", ", d.sourceFolders)));
        } else if (Files.isRegularFile(projectRoot.resolve(".classpath"))) {
            d.notes.add(Messages.get("cli.wizard.note.classpath"));
        }
        return d;
    }
}
