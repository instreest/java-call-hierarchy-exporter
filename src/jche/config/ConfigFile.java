// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * 設定ファイル（{@code jche.properties}）の読み手。
 *
 * <p>見た目は Java の properties と同じ {@code 項目=値} だが、{@link Properties#load} は使わない。
 * properties ではバックスラッシュがエスケープなので、Windows のパスをそのまま書くと
 * {@code C:\temp} が {@code C:temp}（タブ + emp）に、{@code C:\\users} が「Malformed \\uxxxx encoding」になり、
 * 利用者は区切りを {@code /} にするかバックスラッシュを 2 つ重ねる必要があった。設定に書く値はほぼパスなので、
 * <b>バックスラッシュをそのまま読む</b>自前の読み方にしてある（{@code docs/config-file-format-qa.md}）。
 *
 * <p>読み方の決まり:
 * <ul>
 *   <li>UTF-8。先頭の BOM は読み飛ばす</li>
 *   <li>先頭（空白を除く）が {@code #} か {@code !} の行は注釈。空行は読み飛ばす</li>
 *   <li>{@code 項目=値}。区切りは {@code =} だけ（{@code :} は使えない。Windows のパスの {@code C:} と区別できないため）。
 *       項目名は英字で始まり英数字と {@code . _ -}。値は前後の空白を除いたそのままで、{@code \} もそのまま</li>
 *   <li>空白で始まる行のうち {@code 項目=} の形でないものは、直前の項目の値の続き（前の行との間に注釈・空行を挟まない）。
 *       前後の空白を除いて値につなぐ</li>
 *   <li>行末の {@code \} は除く（properties の続きの印。以前のひな形をコピーした設定がそのまま読める。
 *       パスの末尾の区切りとして書いてあっても、無くて同じ場所を指す）</li>
 *   <li>同じ項目が 2 回あれば後の行が勝つ</li>
 * </ul>
 * 読めない行は行番号つきの {@link SyntaxException} にして、黙って読み飛ばさない。
 *
 * <p>このクラスは {@code java.*} だけに依存し Java 11 の文法で書く。Eclipse プラグインの検査
 * （{@code test/plugin-config}）が、プラグインの書き出し（{@code EclipseProjectConfig#toFileText}）を
 * このクラスで読み戻して往復を確かめるので、{@code --release 11} でも単独でコンパイルできる必要がある。
 */
public final class ConfigFile {

    /** 項目名の形。{@code 項目=} で始まる行は、字下げされていても値の続きではなく項目の行 */
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9._-]*");
    private static final Pattern KEY_LINE = Pattern.compile("^\\s*[A-Za-z][A-Za-z0-9._-]*\\s*=.*$");

    /** 読めない行の種類 */
    public enum Problem {
        /** {@code =} が無い（値の続きの行が、項目の行のすぐ下に無い場合も） */
        NO_SEPARATOR,
        /** 項目名が空か、使えない文字を含む */
        BAD_KEY,
    }

    /** 読めない行。どの行の何が悪いかを持つ（文言は呼び出し側が表示言語に合わせて組む） */
    public static final class SyntaxException extends IOException {
        private static final long serialVersionUID = 1L;
        private final Problem problem;
        private final int lineNumber;
        private final String lineText;

        SyntaxException(Problem problem, int lineNumber, String lineText) {
            super(problem + " at line " + lineNumber + ": " + lineText);
            this.problem = problem;
            this.lineNumber = lineNumber;
            this.lineText = lineText;
        }

        public Problem problem() {
            return problem;
        }

        /** 1 から数えた行番号 */
        public int lineNumber() {
            return lineNumber;
        }

        /** その行の中身（前後の空白を除いたもの） */
        public String lineText() {
            return lineText;
        }
    }

    private ConfigFile() {
    }

    /** ファイルを UTF-8 で読む */
    public static Properties read(Path file) throws IOException {
        return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    /** 文字列（ファイルの中身）を読む */
    public static Properties parse(String text) throws SyntaxException {
        return parse(List.of(text.split("\r\n|\r|\n", -1)));
    }

    /** 行の一覧を読む。{@link Properties} にしているのは、拡張（{@code init(Properties, Path)}）に渡す形だから */
    public static Properties parse(List<String> lines) throws SyntaxException {
        Properties out = new Properties();
        String currentKey = null;
        StringBuilder currentValue = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == 0 && line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                // 注釈・空行は値の続きを切る。離れた字下げの行を前の項目につながない
                flush(out, currentKey, currentValue);
                currentKey = null;
                currentValue = null;
                continue;
            }
            boolean indented = line.length() > 0 && Character.isWhitespace(line.charAt(0));
            if (indented && currentKey != null && !KEY_LINE.matcher(line).matches()) {
                currentValue.append(withoutTrailingBackslash(trimmed));
                continue;
            }
            flush(out, currentKey, currentValue);
            currentKey = null;
            currentValue = null;
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                throw new SyntaxException(Problem.NO_SEPARATOR, i + 1, trimmed);
            }
            String key = trimmed.substring(0, eq).trim();
            if (!KEY.matcher(key).matches()) {
                throw new SyntaxException(Problem.BAD_KEY, i + 1, trimmed);
            }
            currentKey = key;
            currentValue = new StringBuilder(withoutTrailingBackslash(trimmed.substring(eq + 1).trim()));
        }
        flush(out, currentKey, currentValue);
        return out;
    }

    /** 行末の {@code \}（properties の続きの印）を除く */
    private static String withoutTrailingBackslash(String s) {
        return s.endsWith("\\") ? s.substring(0, s.length() - 1) : s;
    }

    private static void flush(Properties out, String key, StringBuilder value) {
        if (key != null) {
            out.setProperty(key, value.toString());
        }
    }
}
