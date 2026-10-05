// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiConsumer;
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
 *   <li>行末の {@code \} は次の行に続く印（properties と同じ）。ただし次の内容行が {@code 項目=} の形か
 *       ファイルの末尾なら続けず、末尾の {@code \} を捨てる（パスの末尾の区切り {@code C:\work\app\} は
 *       無くても同じ場所を指す）。続きの途中の注釈行・空行は読み飛ばす（一覧の 1 要素を {@code #} で外せる）</li>
 *   <li>空白で始まる行のうち {@code 項目=} の形でないものも、直前の項目の値の続き（{@code \} が無くてもよい。
 *       ただし空行を挟むと続きではない）。続きは前後の空白を除いて値につなぐ</li>
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

    /**
     * ファイルを UTF-8 で読む。
     * UTF-8 として読めないバイト列（Shift_JIS 等で保存したファイル）は {@link java.nio.charset.MalformedInputException}
     * （{@link IOException} の一種。文言は "Input length = 1" だけなので、呼ぶ側が「UTF-8 で保存する」案内に言い換える）
     */
    public static Properties read(Path file) throws IOException {
        return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    /**
     * ファイルの項目を<b>書かれた順</b>に読む（表示用）。読み方は {@link #read} とまったく同じで、
     * 同じ項目が 2 回あれば後の値が最初の位置に入る。{@link Properties} は順を持たないので、別に用意してある
     */
    public static Map<String, String> readOrdered(Path file) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        parse(Files.readAllLines(file, StandardCharsets.UTF_8), out::put);
        return out;
    }

    /** 文字列（ファイルの中身）を読む */
    public static Properties parse(String text) throws SyntaxException {
        return parse(List.of(text.split("\r\n|\r|\n", -1)));
    }

    /** 行の一覧を読む。{@link Properties} にしているのは、拡張（{@code init(Properties, Path)}）に渡す形だから */
    public static Properties parse(List<String> lines) throws SyntaxException {
        Properties out = new Properties();
        parse(lines, out::setProperty);
        return out;
    }

    /** 行の一覧を読み、項目と値を書かれた順に {@code sink} へ渡す */
    private static void parse(List<String> lines, BiConsumer<String, String> sink) throws SyntaxException {
        String currentKey = null;
        StringBuilder currentValue = null;
        boolean pending = false;  // 直前の内容行が \ で終わっている（次の内容行に続く）
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == 0 && line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
                // 注釈は続きの途中でも読み飛ばす（一覧の 1 要素を # で外せる）
                continue;
            }
            if (trimmed.isEmpty()) {
                // 空行は字下げによる続きを切る。\ で続けている途中なら読み飛ばす
                if (!pending) {
                    flush(sink, currentKey, currentValue);
                    currentKey = null;
                    currentValue = null;
                }
                continue;
            }
            boolean indented = Character.isWhitespace(line.charAt(0));
            boolean keyLike = KEY_LINE.matcher(line).matches();
            if (currentKey != null && !keyLike && (pending || indented)) {
                // 値の続き。項目=の形の行は、\ の後ろでも新しい項目（末尾が \ のパスの次の行）
                pending = trimmed.endsWith("\\");
                currentValue.append(withoutTrailingBackslash(trimmed));
                continue;
            }
            flush(sink, currentKey, currentValue);
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
            String value = trimmed.substring(eq + 1).trim();
            pending = value.endsWith("\\");
            currentKey = key;
            currentValue = new StringBuilder(withoutTrailingBackslash(value));
        }
        flush(sink, currentKey, currentValue);
    }

    /** 行末の {@code \}（続きの印）を除き、その前の空白も落とす */
    private static String withoutTrailingBackslash(String s) {
        return s.endsWith("\\") ? s.substring(0, s.length() - 1).trim() : s;
    }

    private static void flush(BiConsumer<String, String> sink, String key, StringBuilder value) {
        if (key != null) {
            sink.accept(key, value.toString());
        }
    }
}
