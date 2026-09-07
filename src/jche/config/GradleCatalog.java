// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gradle の版カタログ（gradle/libs.versions.toml）。build.gradle の {@code libs.foo.bar} を座標に戻すために読む。
 *
 * TOML のうちカタログで使われる範囲だけを読む: {@code [versions]} {@code [libraries]} {@code [bundles]} の
 * 文字列・インラインテーブル・配列。別名の {@code -} {@code _} {@code .} は Gradle と同じくどれも区切りとして
 * 同一視する（{@code foo-bar} は {@code libs.foo.bar}）。
 */
final class GradleCatalog {

    /** ライブラリの座標。version は空のこともある（platform が決める） */
    record Library(String group, String name, String version) {

        String gav() {
            return group + ":" + name + (version.isEmpty() ? "" : ":" + version);
        }
    }

    private final Map<String, String> versions = new HashMap<>();
    private final Map<String, Library> libraries = new HashMap<>();
    private final Map<String, List<String>> bundles = new HashMap<>();

    private static final Pattern PAIR = Pattern.compile(
            "([A-Za-z0-9_.-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|\\{([^{}]*)\\}|\\[([^\\]]*)\\])");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"|'([^']*)'");

    static GradleCatalog read(Path toml) throws IOException {
        GradleCatalog catalog = new GradleCatalog();
        String section = "";
        StringBuilder logical = new StringBuilder();
        int depth = 0;
        for (String raw : Files.readAllLines(toml, StandardCharsets.UTF_8)) {
            String line = stripComment(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (depth == 0 && line.startsWith("[") && line.endsWith("]") && !line.contains("=")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            // 配列やインラインテーブルが複数行にまたがるときは括弧が閉じるまで 1 行にまとめる
            logical.append(line).append(' ');
            depth += count(line, '[') + count(line, '{') - count(line, ']') - count(line, '}');
            if (depth > 0) {
                continue;
            }
            depth = 0;
            catalog.entry(section, logical.toString().trim());
            logical.setLength(0);
        }
        return catalog;
    }

    private void entry(String section, String line) {
        int eq = line.indexOf('=');
        if (eq < 0) {
            return;
        }
        String key = normalize(line.substring(0, eq).trim().replace("\"", "").replace("'", ""));
        String value = line.substring(eq + 1).trim();
        switch (section) {
            case "versions":
                versions.put(key, versionOf(value));
                break;
            case "libraries":
                Library lib = libraryOf(value);
                if (lib != null) {
                    libraries.put(key, lib);
                }
                break;
            case "bundles":
                List<String> members = new ArrayList<>();
                Matcher m = QUOTED.matcher(value);
                while (m.find()) {
                    members.add(normalize(m.group(1) != null ? m.group(1) : m.group(2)));
                }
                bundles.put(key, members);
                break;
            default:
                break;   // plugins などは見ない
        }
    }

    /** {@code "1.0"} か {@code { strictly = "1.0" }} / {@code { require = ... }} / {@code { prefer = ... }} */
    private static String versionOf(String value) {
        Matcher q = QUOTED.matcher(value);
        if (value.startsWith("\"") || value.startsWith("'")) {
            return q.find() ? unquote(q) : "";
        }
        Map<String, String> table = pairs(value);
        for (String k : new String[] {"strictly", "require", "prefer"}) {
            if (table.containsKey(k)) {
                return table.get(k);
            }
        }
        return "";
    }

    /**
     * {@code "g:a:v"}、{@code { module = "g:a", version.ref = "x" }}、{@code { group = "g", name = "a", version = "1.0" }}、
     * {@code { module = "g:a", version = { strictly = "1.0" } } }
     */
    private Library libraryOf(String value) {
        if (value.startsWith("\"") || value.startsWith("'")) {
            Matcher q = QUOTED.matcher(value);
            if (!q.find()) {
                return null;
            }
            String[] parts = unquote(q).split(":");
            if (parts.length < 2) {
                return null;
            }
            return new Library(parts[0], parts[1], parts.length > 2 ? parts[2] : "");
        }
        // version = { strictly = ... } の入れ子を先に取り出す
        String version = "";
        Matcher nested = Pattern.compile("version\\s*=\\s*\\{([^{}]*)\\}").matcher(value);
        String rest = value;
        if (nested.find()) {
            version = versionOf("{" + nested.group(1) + "}");
            rest = value.substring(0, nested.start()) + value.substring(nested.end());
        }
        Map<String, String> table = pairs(rest);
        String group = table.get("group");
        String name = table.get("name");
        if (table.containsKey("module")) {
            String[] parts = table.get("module").split(":");
            if (parts.length >= 2) {
                group = parts[0];
                name = parts[1];
            }
        }
        if (group == null || name == null) {
            return null;
        }
        if (version.isEmpty() && table.containsKey("version")) {
            version = table.get("version");
        }
        if (version.isEmpty() && table.containsKey("version.ref")) {
            version = versions.getOrDefault(normalize(table.get("version.ref")), "");
        }
        return new Library(group, name, version);
    }

    /** インラインテーブル {@code { a = "x", b.c = "y" }} の組 */
    private static Map<String, String> pairs(String inlineTable) {
        Map<String, String> out = new HashMap<>();
        String body = inlineTable.trim();
        if (body.startsWith("{")) {
            body = body.substring(1);
        }
        if (body.endsWith("}")) {
            body = body.substring(0, body.length() - 1);
        }
        Matcher m = PAIR.matcher(body);
        while (m.find()) {
            String v = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : "";
            out.put(m.group(1), v);
        }
        return out;
    }

    /** 別名を突き合わせる形にする（{@code -} {@code _} を {@code .} に） */
    static String normalize(String alias) {
        return alias.trim().replace('-', '.').replace('_', '.');
    }

    /** {@code libs.foo.bar} の {@code foo.bar} に当たるライブラリ。無ければ null */
    Library library(String accessor) {
        return libraries.get(normalize(accessor));
    }

    /** {@code libs.bundles.x} の中身。無ければ空 */
    List<Library> bundle(String accessor) {
        List<Library> out = new ArrayList<>();
        for (String alias : bundles.getOrDefault(normalize(accessor), List.of())) {
            Library lib = libraries.get(alias);
            if (lib != null) {
                out.add(lib);
            }
        }
        return out;
    }

    int size() {
        return libraries.size();
    }

    private static String unquote(Matcher q) {
        return q.group(1) != null ? q.group(1) : q.group(2);
    }

    private static String stripComment(String line) {
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (c == quote) {
                    inString = false;
                }
            } else if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }
}
