// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gradle のビルドスクリプト（Groovy / Kotlin DSL）をテキストとして扱う小道具。
 * コメントの除去、ブロックの切り出し、複数行にまたがる呼び出しの結合など、
 * {@link GradleBuild} が依存の宣言を正規表現で拾う前の下ごしらえをまとめる。
 */
final class GradleScripts {

    private GradleScripts() {
    }

    /**
     * 括弧が閉じていない行を次の行と結合して、1 つの呼び出しを 1 行にする。
     * <pre>
     *   implementation(
     *       "g:a:v"
     *   )
     * </pre>
     * のような書き方は行単位の走査では「読めない宣言」になるため、先に 1 行にしておく。
     * 数えるのは丸括弧だけ（文字列の中は数えない）。波括弧は {@code version { strictly "1.0" }} のように
     * 1 つの宣言の中に閉じた形で現れうるが、ブロックの中身は宣言そのものではないので結合しない。
     */
    static String joinLogicalLines(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int depth = 0;
        for (String line : text.split("\\R", -1)) {
            if (depth > 0) {
                out.append(' ').append(line.trim());
            } else {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(line);
            }
            depth = Math.max(0, depth + parenBalance(line));
        }
        return out.toString();
    }

    /** 行の中の「開き丸括弧 − 閉じ丸括弧」（文字列の中は数えない） */
    private static int parenBalance(String line) {
        int depth = 0;
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    inString = false;
                }
            } else if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        return depth;
    }

    /** 最初に見つかったファイルをコメントを除いて読む。無ければ空 */
    static String readScript(Path dir, String... names) {
        for (String name : names) {
            Path f = dir.resolve(name);
            if (Files.isRegularFile(f)) {
                try {
                    return stripComments(Files.readString(f, StandardCharsets.UTF_8));
                } catch (IOException ignore) {
                    return "";
                }
            }
        }
        return "";
    }

    static List<String> readLines(Path f) {
        try {
            return Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }

    /** ブロックコメントと行コメントを除く（URL の "://" は行コメントではない） */
    static String stripComments(String text) {
        String noBlock = text.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder out = new StringBuilder();
        for (String line : noBlock.split("\\R", -1)) {
            out.append(line.replaceFirst("(?<![:\\w])//.*$", "")).append('\n');
        }
        return out.toString();
    }

    /** {@code name {} } ブロックの中身（入れ子は数える。文字列の中の括弧は見ない） */
    static List<String> blocks(String text, String name) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*(?:\\([^)]*\\)\\s*)?\\{").matcher(text);
        while (m.find()) {
            int end = closingBrace(text, m.end() - 1);
            if (end < 0) {
                break;
            }
            out.add(text.substring(m.end(), end));
        }
        return out;
    }

    static String removeBlocks(String text, String name) {
        StringBuilder out = new StringBuilder(text);
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*(?:\\([^)]*\\)\\s*)?\\{").matcher(out);
        while (m.find()) {
            int end = closingBrace(out, m.end() - 1);
            if (end < 0) {
                break;
            }
            out.delete(m.start(), end + 1);
            m.reset(out);
        }
        return out.toString();
    }

    static int closingBrace(CharSequence text, int open) {
        int depth = 0;
        boolean inString = false;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    inString = false;
                }
            } else if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }}
