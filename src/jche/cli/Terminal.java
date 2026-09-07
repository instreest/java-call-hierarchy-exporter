// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * 対話モードの入出力。標準入力から 1 行ずつ読み、標準出力へ書く。
 *
 * 入力の文字コードは {@code stdin.encoding}（JDK 25 以降）→ {@code native.encoding} の順で決める。
 * Windows のコマンドプロンプトでは MS932 になるので、日本語を含むパスもそのまま読める。
 * 出力は {@code System.out} に任せる（コンソールの文字コードで書く。ツール全体の方針は
 * {@code src/CallHierarchyExporter.java} の冒頭）。
 *
 * {@link System#console()} は使わない。JDK 22 以降は端末でなくても Console が返ることがあり、
 * パイプから流し込んだ入力（自動テスト）と端末からの入力で挙動が変わるのを避けるため。
 *
 * 入力が尽きたら（Ctrl+Z / Ctrl+D、パイプの終端）{@link EndOfInput} を投げる。メニューのどこにいても
 * 静かに終了できるよう、呼び出し側は最上位で 1 回だけ捕まえればよい。
 */
public final class Terminal {

    /** 標準入力が尽きた（対話を続けられない） */
    public static final class EndOfInput extends RuntimeException {
        private static final long serialVersionUID = 1L;

        EndOfInput() {
            super("標準入力が終了しました");
        }
    }

    private final BufferedReader in;
    private final Charset charset;

    public Terminal() {
        this.charset = stdinCharset();
        this.in = new BufferedReader(new InputStreamReader(System.in, charset));
    }

    public Charset charset() {
        return charset;
    }

    public void println(Object line) {
        System.out.println(line);
    }

    public void println() {
        System.out.println();
    }

    public void print(Object text) {
        System.out.print(text);
        System.out.flush();
    }

    /** 1 行読む（前後の空白は取り除く）。入力が尽きたら {@link EndOfInput} */
    public String readLine(String prompt) {
        print(prompt);
        try {
            String line = in.readLine();
            if (line == null) {
                println();
                throw new EndOfInput();
            }
            return line.trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 質問して答えを返す。空 Enter なら既定値（既定値が空文字なら空のまま返す） */
    public String ask(String question, String defaultValue) {
        String shown = defaultValue.isEmpty() ? "" : " [" + defaultValue + "]";
        String answer = readLine(question + shown + ": ");
        return answer.isEmpty() ? defaultValue : answer;
    }

    /** はい / いいえ を尋ねる。空 Enter は既定値 */
    public boolean confirm(String question, boolean defaultYes) {
        String hint = defaultYes ? " [Y/n]: " : " [y/N]: ";
        while (true) {
            String a = readLine(question + hint).toLowerCase(Locale.ROOT);
            if (a.isEmpty()) {
                return defaultYes;
            }
            if (a.equals("y") || a.equals("yes")) {
                return true;
            }
            if (a.equals("n") || a.equals("no")) {
                return false;
            }
            println("  y か n で答えてください。");
        }
    }

    /** Enter を待つ（結果を読んでから次の画面に進むため） */
    public void pause() {
        readLine("Enter で戻る ");
    }

    private static Charset stdinCharset() {
        for (String key : new String[] {"stdin.encoding", "native.encoding"}) {
            String name = System.getProperty(key);
            if (name != null && !name.isEmpty()) {
                try {
                    return Charset.forName(name);
                } catch (RuntimeException e) {
                    // 次の候補へ
                }
            }
        }
        return Charset.defaultCharset();
    }
}
