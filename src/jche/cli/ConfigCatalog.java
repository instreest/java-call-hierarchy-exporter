// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cli;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 対話モードで選べる設定ファイル（config.properties 形式）の一覧。
 *
 * 探す場所は {@code config/} の下（サブフォルダも含む）。既定の設定ファイル {@code config/config.properties} が
 * ここにあり、対話モードの「設定ファイルを新しく作る」もここに書く。実行ごとの出力フォルダ
 * （{@code config/<解析開始日時>_<プロジェクト名>/}。output.folder の既定）もこの下にできるが、
 * その中の設定ファイルの複製は一覧に出さない（出力フォルダ名の形で見分ける）。
 * それ以外の場所にある設定ファイルは、パスを直接入力すれば使える。
 *
 * 「前回使った設定」は {@code .cache/recent-configs.txt} に残し、次回の既定にする
 * （ツール自身の作業ファイルなので、解析キャッシュと同じ {@code .cache/} に置く）。
 */
public final class ConfigCatalog {

    public static final String CONFIGS_DIR_NAME = "config";
    /** 既定の設定ファイル（config/ の下）。一覧の先頭に出し、作成ウィザードのひな形にする */
    public static final String DEFAULT_CONFIG_NAME = "config.properties";
    private static final String RECENT_FILE = ".cache/recent-configs.txt";

    /** 一覧の 1 件。{@code display} はプロジェクトフォルダからの相対パス（表示用） */
    public record Entry(Path path, String display, String projectRoot) {
    }

    private ConfigCatalog() {
    }

    public static List<Entry> scan(Path root) throws IOException {
        List<Path> found = new ArrayList<>();
        Path configs = root.resolve(CONFIGS_DIR_NAME);
        if (Files.isDirectory(configs)) {
            try (Stream<Path> s = Files.walk(configs)) {
                s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".properties"))
                        .filter(p -> !isInsideOutputDir(configs, p))
                        .forEach(found::add);
            }
        }
        // 既定の設定ファイルを先頭に、あとは名前順（OS のファイル列挙順に依存させない）
        Path defaultConfig = configs.resolve(DEFAULT_CONFIG_NAME);
        found.sort(Comparator.comparing((Path p) -> p.equals(defaultConfig) ? 0 : 1)
                .thenComparing(p -> root.relativize(p).toString()));
        List<Entry> out = new ArrayList<>();
        for (Path p : found) {
            out.add(new Entry(p, root.relativize(p).toString().replace('\\', '/'), projectRootOf(p)));
        }
        return out;
    }

    /** 出力フォルダ名の形（yyyyMMdd-HHmmss_…）のフォルダの下にあるか */
    private static boolean isInsideOutputDir(Path configs, Path file) {
        for (Path d = file.getParent(); d != null && !d.equals(configs); d = d.getParent()) {
            if (OUTPUT_DIR_NAME.matcher(d.getFileName().toString()).matches()) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern OUTPUT_DIR_NAME = Pattern.compile("\\d{8}-\\d{6}_.*");

    /** project.root の値（表示用。読めなければ空文字） */
    public static String projectRootOf(Path config) {
        try (Reader r = new InputStreamReader(Files.newInputStream(config), StandardCharsets.UTF_8)) {
            Properties p = new Properties();
            p.load(r);
            return p.getProperty("project.root", "").trim();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * 設定ファイルの中身のうち、設定行だけ（コメントと空行を除く。{@code \} で続く行は繋げる）。
     * 実行前に「この設定で合っているか」を確かめるための表示用
     */
    public static List<String> settingLines(Path config) throws IOException {
        List<String> out = new ArrayList<>();
        StringBuilder pending = null;
        for (String raw : Files.readAllLines(config, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (pending != null) {
                if (line.endsWith("\\")) {
                    pending.append(line, 0, line.length() - 1);
                    continue;
                }
                pending.append(line);
                out.add(pending.toString());
                pending = null;
                continue;
            }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            if (line.endsWith("\\")) {
                pending = new StringBuilder(line.substring(0, line.length() - 1));
                continue;
            }
            out.add(line);
        }
        if (pending != null) {
            out.add(pending.toString());
        }
        return out;
    }

    /** 前回使った設定ファイル（存在するものだけ。無ければ空） */
    public static List<Path> loadRecent(Path root) {
        List<Path> out = new ArrayList<>();
        Path f = root.resolve(RECENT_FILE);
        if (!Files.isRegularFile(f)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty()) {
                    continue;
                }
                Path p = root.resolve(s).normalize();
                if (Files.isRegularFile(p)) {
                    out.add(p);
                }
            }
        } catch (IOException e) {
            // 履歴は無くても困らない
        }
        return out;
    }

    public static void saveRecent(Path root, List<Path> configs) {
        Path f = root.resolve(RECENT_FILE);
        List<String> lines = new ArrayList<>();
        for (Path p : configs) {
            Path abs = p.toAbsolutePath().normalize();
            String s = abs.startsWith(root) ? root.relativize(abs).toString() : abs.toString();
            lines.add(s.replace('\\', '/'));
        }
        try {
            Files.createDirectories(f.getParent());
            Files.write(f, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 履歴は無くても困らない
        }
    }
}
