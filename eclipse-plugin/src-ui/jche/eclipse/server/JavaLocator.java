// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析に使う JDK を探す。
 *
 * <p>解析は Eclipse とは別のプロセス・別の JDK で走らせる。どの JDK を使うかで解析結果が変わる
 * （JDT は動作中の JVM の標準クラスを解析対象のクラスパスに含める）ので、
 * <b>CLI と同じ 25 を優先し、無ければ 17 以上で一番新しいもの</b>を選ぶ。
 *
 * <p>このクラスは Eclipse の API を使わない。設定値や環境変数の「読み方」は呼び出し側が決め、
 * ここは渡された候補を調べて選ぶだけにしてある（Eclipse 無しで検査できるようにするため）。
 */
public final class JavaLocator {

    /** 解析に使いたい版。CLI（//JAVA 25）と結果を揃えるため */
    public static final int PREFERRED = 25;
    /** 動かせる下限。解析本体は release 17 でコンパイルしている */
    public static final int MINIMUM = 17;

    /** 選んだ java と、その版 */
    public static final class Found {
        private final File executable;
        private final int version;

        Found(File executable, int version) {
            this.executable = executable;
            this.version = version;
        }

        public File executable() {
            return executable;
        }

        public int version() {
            return version;
        }

        /** 望みの版（25）より古いか。画面に「結果が CLI と少しずれうる」と出すために使う */
        public boolean isOlderThanPreferred() {
            return version < PREFERRED;
        }

        @Override
        public String toString() {
            return executable + "（Java " + version + "）";
        }
    }

    private JavaLocator() {
    }

    /**
     * 候補の中から使えるものを選ぶ。
     *
     * @param candidates JDK のホーム、または java の実行ファイル。前にあるものほど優先する
     * @return 選んだもの。17 以上が1つも無ければ null
     */
    public static Found choose(List<File> candidates) {
        Found best = null;
        for (File candidate : normalize(candidates)) {
            int version = versionOf(candidate);
            if (version < MINIMUM) {
                continue;
            }
            if (version >= PREFERRED) {
                return new Found(candidate, version);   // 望みどおりなら即決
            }
            if (best == null || version > best.version()) {
                best = new Found(candidate, version);
            }
        }
        return best;
    }

    /** ホームを渡されたら bin/java に直す。重複は取り除く（同じものを何度も起動しないため） */
    private static List<File> normalize(List<File> candidates) {
        Set<String> seen = new LinkedHashSet<String>();
        List<File> result = new ArrayList<File>();
        for (File candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            File executable = candidate.isDirectory() ? executableIn(candidate) : candidate;
            if (executable == null || !executable.isFile()) {
                continue;
            }
            if (seen.add(executable.getAbsolutePath())) {
                result.add(executable);
            }
        }
        return result;
    }

    /** JDK のホームから java の実行ファイル */
    public static File executableIn(File javaHome) {
        return new File(new File(javaHome, "bin"), isWindows() ? "java.exe" : "java");
    }

    /** その java の主要バージョン。動かせなければ 0 */
    public static int versionOf(File executable) {
        if (executable == null) {
            return 0;
        }
        try {
            Process process = new ProcessBuilder(executable.getPath(), "-version")
                    .redirectErrorStream(true).start();
            StringBuilder text = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.forName("UTF-8")));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    text.append(line).append('\n');
                }
            } finally {
                reader.close();
            }
            process.waitFor();
            return parseVersion(text.toString());
        } catch (IOException e) {
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    /** {@code openjdk version "25.0.3"} のような出力から 25 を取り出す。1.8 形式は 8 にする */
    public static int parseVersion(String text) {
        Matcher matcher = Pattern.compile("version \"(\\d+)(?:\\.(\\d+))?").matcher(text);
        if (!matcher.find()) {
            return 0;
        }
        int major = Integer.parseInt(matcher.group(1));
        if (major == 1 && matcher.group(2) != null) {
            return Integer.parseInt(matcher.group(2));
        }
        return major;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH).contains("win");
    }
}
