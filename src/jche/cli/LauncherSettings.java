// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cli;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 起動コマンド（java-call-hierarchy-exporter.sh / java-call-hierarchy-exporter.cmd）が読む設定ファイル {@code launcher.properties} の読み書き。
 *
 * JDK / JBang の置き場所や JVM のオプションは、Java が起動する前に起動コマンドが環境変数として決める。
 * そのため Java 側（対話モードの「環境設定」）ではこのファイルを書き換えるだけで、効くのは次回の起動から。
 * 書き換えたあとに {@link #requestRestart()} で再起動の目印を置くと、起動コマンドがもう一度やり直す。
 *
 * ファイルの形は {@code KEY=VALUE}（キーがそのまま環境変数名）。起動コマンドは bash の read と cmd の
 * {@code for /f} で読むので、{@link java.util.Properties} の書式（{@code \} のエスケープ等）は使わない。
 * 相対パスはツールのプロジェクトフォルダが起点。
 *
 * 文字コードは {@code native.encoding}（Windows のコマンドプロンプトでは MS932、Linux では UTF-8）。
 * cmd がこのファイルを読むときの文字コードと合わせないと、日本語を含むパスが壊れるため。
 *
 * 知らないキー（利用者が手で足した {@code JBANG_JDK_VENDOR} 等）は保持する。
 */
public final class LauncherSettings {

    public static final String FILE_NAME = "launcher.properties";
    /** 起動コマンドが「アプリの終了後にもう一度起動する」合図として見るファイル（ツールのプロジェクトフォルダからの相対） */
    public static final String RESTART_MARKER = ".cache/launcher.restart";

    public static final String KEY_JBANG_DIR = "JBANG_DIR";
    public static final String KEY_JBANG_REPO = "JBANG_REPO";
    public static final String KEY_JAVA_OPTS = "JCHE_JAVA_OPTS";
    public static final String KEY_JBANG_OPTS = "JCHE_JBANG_OPTS";
    private static final String[] KNOWN_KEYS = {KEY_JBANG_DIR, KEY_JBANG_REPO, KEY_JAVA_OPTS, KEY_JBANG_OPTS};

    /** 「このプロジェクトの中」を選んだときの置き場所（ツールのプロジェクトフォルダからの相対） */
    public static final String PROJECT_LOCAL_JBANG_DIR = ".jbang";
    public static final String PROJECT_LOCAL_REPO = ".jbang/repository";

    public final Path root;
    public final Path file;
    private final Map<String, String> values = new LinkedHashMap<>();

    private LauncherSettings(Path root) {
        this.root = root;
        this.file = root.resolve(FILE_NAME);
    }

    /** ファイルを読む。無ければ空（すべて既定）の状態 */
    public static LauncherSettings load(Path root) throws IOException {
        LauncherSettings s = new LauncherSettings(root.toAbsolutePath().normalize());
        if (Files.isRegularFile(s.file)) {
            for (String raw : Files.readAllLines(s.file, fileCharset())) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                if (!key.matches("[A-Z_][A-Z0-9_]*")) {
                    continue;
                }
                s.values.put(key, line.substring(eq + 1).trim());
            }
        }
        return s;
    }

    public boolean exists() {
        return Files.isRegularFile(file);
    }

    /** 値。無ければ空文字 */
    public String get(String key) {
        String v = values.get(key);
        return (v == null) ? "" : v;
    }

    public void set(String key, String value) {
        values.put(key, value == null ? "" : value.trim());
    }

    /** 既知のキーを決まった順に、知らないキーをその後ろに書く。コメントは書くたびに付け直す */
    public void save() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# java-call-hierarchy-exporter.sh / java-call-hierarchy-exporter.cmd が起動時に読む設定（アプリの「環境設定」からも書き換えられる）。");
        lines.add("# キーはそのまま環境変数になる。相対パスはこのファイルのあるフォルダが起点。空欄は既定値。");
        lines.add("#   JBANG_DIR       JBang 本体・JDK の置き場所（既定 ~/.jbang）");
        lines.add("#   JBANG_REPO      依存 jar の置き場所（既定 ~/.m2/repository）");
        lines.add("#   JCHE_JAVA_OPTS  解析を動かす JVM のオプション（例: -Xmx4g）");
        lines.add("#   JCHE_JBANG_OPTS jbang run に足すオプション（例: --offline）");
        for (String key : KNOWN_KEYS) {
            lines.add(key + "=" + get(key));
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!isKnown(e.getKey())) {
                lines.add(e.getKey() + "=" + e.getValue());
            }
        }
        try (BufferedWriter w = Files.newBufferedWriter(file, fileCharset())) {
            for (String line : lines) {
                w.write(line);
                w.newLine();
            }
        }
    }

    /** 知らないキー（このクラスが管理しないもの）の一覧 */
    public List<String> extraKeys() {
        List<String> out = new ArrayList<>();
        for (String k : values.keySet()) {
            if (!isKnown(k)) {
                out.add(k);
            }
        }
        return out;
    }

    private static boolean isKnown(String key) {
        for (String k : KNOWN_KEYS) {
            if (k.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** 相対パスをプロジェクトフォルダ起点で絶対パスにする。空なら null */
    public Path resolvePath(String value) {
        String v = value.trim();
        if (v.isEmpty()) {
            return null;
        }
        Path p = Paths.get(v);
        return (p.isAbsolute() ? p : root.resolve(p)).normalize();
    }

    /** 設定ファイルで決まる JBang の置き場所（次回の起動で効く値）。空欄なら JBang の既定（~/.jbang） */
    public Path configuredJbangDir() {
        Path fromFile = resolvePath(get(KEY_JBANG_DIR));
        return (fromFile != null) ? fromFile : defaultJbangDir();
    }

    /**
     * 今の実行で実際に効いている JBang の置き場所。起動コマンドが設定ファイルから環境変数にした値
     * （設定ファイルを書き換えた直後は {@link #configuredJbangDir()} と食い違う。再起動すると揃う）。
     * 環境変数が無ければ（jbang で直接動かしたとき）設定ファイルの値
     */
    public Path currentJbangDir() {
        String env = System.getenv(KEY_JBANG_DIR);
        if (env != null && !env.trim().isEmpty()) {
            return Paths.get(env.trim()).toAbsolutePath().normalize();
        }
        return configuredJbangDir();
    }

    /** 設定ファイルで決まる依存 jar の置き場所。空欄なら Maven の既定（~/.m2/repository） */
    public Path configuredRepo() {
        Path fromFile = resolvePath(get(KEY_JBANG_REPO));
        return (fromFile != null) ? fromFile : Paths.get(System.getProperty("user.home"), ".m2", "repository");
    }

    /** 今の実行で実際に効いている依存 jar の置き場所（{@link #currentJbangDir()} と同じ考え方） */
    public Path currentRepo() {
        String env = System.getenv(KEY_JBANG_REPO);
        if (env != null && !env.trim().isEmpty()) {
            return Paths.get(env.trim()).toAbsolutePath().normalize();
        }
        return configuredRepo();
    }

    public static Path defaultJbangDir() {
        return Paths.get(System.getProperty("user.home"), ".jbang");
    }

    /** JBang の置き場所が「このプロジェクトの中」か */
    public boolean isProjectLocal() {
        Path dir = resolvePath(get(KEY_JBANG_DIR));
        return dir != null && dir.startsWith(root);
    }

    /** 起動コマンド（java-call-hierarchy-exporter.sh / java-call-hierarchy-exporter.cmd）から起動されたか。直接 jbang で動かしたときは再起動できない */
    public static boolean launchedByLauncher() {
        String v = System.getenv("JCHE_ROOT");
        return v != null && !v.isEmpty();
    }

    /** 起動コマンドに「終了したらもう一度起動する」よう伝える */
    public void requestRestart() throws IOException {
        Path marker = root.resolve(RESTART_MARKER);
        Files.createDirectories(marker.getParent());
        Files.write(marker, new byte[0]);
    }

    /** 起動コマンド（bash / cmd）が読むときの文字コードに合わせる */
    public static Charset fileCharset() {
        String name = System.getProperty("native.encoding");
        if (name != null) {
            try {
                return Charset.forName(name);
            } catch (RuntimeException e) {
                // 既定へ
            }
        }
        return Charset.defaultCharset();
    }
}
