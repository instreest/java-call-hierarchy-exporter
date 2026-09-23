// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1 回の実行で「確認したほうがよいこと」を集め、出力フォルダの {@code warnings.txt} に書く。
 *
 * <p>このツールの正常な状態は「ビルドが通り、依存 jar がすべて解決できている」ことである。
 * そうでない状態でも解析は最後まで動いて CSV を出すので、利用者（特に初めて使う人）は
 * 出力に抜けがあることに気づきにくい。そこで、<b>{@code run.log} に {@code [WARN]} / {@code [ERROR]} の行が
 * 1 つでも出た実行でだけ</b>このファイルを作る。ファイルがあること自体が「想定と違う状態で動いた」印になる。
 * {@code run.log} は実行ごとに必ず作る経過の記録で、役割が違う（{@code docs/output-files-simplify-qa.md} の Q3）。
 *
 * <p>中身は 2 段にする。
 * <ol>
 *   <li>初心者がつまずく典型（{@link Topic}）ごとの「何が起きたか・影響・対処」と、該当する明細</li>
 *   <li>それ以外の {@code [WARN]} / {@code [ERROR]} の行（取りこぼさないため、{@link Log#warn} を通ったものは全部載る）</li>
 * </ol>
 * 典型に載せたい警告は、{@link Log#warn} の代わりに {@link #warn(Topic, String)} で出す。
 * ログへの出方は同じで、ファイルでは典型の項目の下に載り、2 段目には重ねて出さない。
 *
 * <p>集めるのは {@link #begin} から {@link #end} の間だけ（CLI の 1 設定ぶんの実行）。
 * サーバーモードのように {@code begin} を呼ばない使い方では何も溜めない。
 */
public final class Warnings {

    /** 出力フォルダ内のファイル名（固定） */
    public static final String FILE_NAME = "warnings.txt";

    /** 1 つの項目に並べる明細の上限。超えた分は件数だけ書き、run.log を見てもらう */
    static final int DETAIL_LIMIT = 50;
    /** 2 段目（典型に当てはまらない警告）に並べる上限。同じ種類の警告が大量に出ることがあるため */
    static final int OTHERS_LIMIT = 200;

    /** 典型の項目。並びがファイルでの並び（直すべき順）である。文言は {@link #title} / {@link #impact} / {@link #remedy} */
    public enum Topic {
        /** 実行そのものが失敗した */
        FAILED,
        /** 設定ファイルで指定したフォルダ・ファイルが無い */
        CONFIG,
        /** 依存 jar が解決できていない */
        DEPENDENCIES,
        /** ソースにコンパイルエラーがある（ビルドが通らない） */
        BUILD,
        /** 解析や出力が途中で打ち切られた */
        INCOMPLETE
    }

    /** 書き先。null なら集めていない */
    private static Path target;
    /** ログに出た [WARN] / [ERROR] の行（時刻なし）。出た順 */
    private static final List<String> logged = new ArrayList<>();
    /** logged に入れなかった行の数（典型に載せた行を除いた数が OTHERS_LIMIT を超えた分） */
    private static int loggedOmitted;
    /** 典型の項目に載せた行（2 段目に重ねて出さないため） */
    private static final Set<String> classified = new HashSet<>();
    private static final Map<Topic, List<String>> details = new EnumMap<>(Topic.class);
    private static final Map<Topic, Integer> omitted = new EnumMap<>(Topic.class);

    private Warnings() {
    }

    /** 集め始める。前の実行の分は捨てる */
    public static synchronized void begin(Path file) {
        clear();
        target = file;
    }

    /**
     * 集め終えて、何かあれば書く。書けなかったときは警告だけ出して続ける
     * （CSV は出来ており、案内のファイルが書けないことで実行を失敗にはしない）。
     *
     * @return 書いたファイル。書くものが無かった・書けなかったときは null
     */
    public static Path end() {
        Path file;
        String text;
        synchronized (Warnings.class) {
            file = target;
            target = null;
            text = (file == null || logged.isEmpty()) ? null : render();
            clear();
        }
        if (text == null) {
            return null;
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(text);
            }
            return file;
        } catch (IOException e) {
            Log.warn(Messages.format("exporter.warnings.writeFailed", file, e));
            return null;
        }
    }

    /** {@link Log#warn} と同じくログに出し、ファイルでは典型の項目の下に載せる */
    public static void warn(Topic topic, String message) {
        Log.warn(message);
        classify(topic, "[WARN] " + message, message);
    }

    /** {@link Log#error} と同じくログに出し、ファイルでは典型の項目の下に載せる */
    public static void error(Topic topic, String message, Throwable cause) {
        Log.error(message, cause);
        classify(topic, "[ERROR] " + message, message);
        if (cause != null) {
            detail(topic, "  " + cause);
        }
    }

    /**
     * 典型の項目に明細だけを足す（ログには呼び出し側が別に書く）。
     * 見出しの警告に続けて、無い jar の座標のような一覧を並べるときに使う
     */
    public static synchronized void detail(Topic topic, String line) {
        if (target == null) {
            return;
        }
        List<String> list = details.computeIfAbsent(topic, t -> new ArrayList<>());
        if (list.size() < DETAIL_LIMIT) {
            list.add(line);
        } else {
            omitted.merge(topic, 1, Integer::sum);
        }
    }

    /** {@link Log} から、ログに出た [WARN] / [ERROR] の行を受け取る */
    static synchronized void logged(String line) {
        if (target == null) {
            return;
        }
        // 典型に載せる行かどうかはこの時点では分からない（Log.warn の後で classify される）ので、
        // 上限は余裕を持たせて判定し、書くときに改めて OTHERS_LIMIT で切る
        if (logged.size() < OTHERS_LIMIT * 2 + DETAIL_LIMIT * Topic.values().length) {
            logged.add(line);
        } else {
            loggedOmitted++;
        }
    }

    private static synchronized void classify(Topic topic, String loggedLine, String message) {
        if (target == null) {
            return;
        }
        classified.add(loggedLine);
        if (loggedOmitted > 0 && !logged.contains(loggedLine)) {
            loggedOmitted--;   // 上限で落とした行が典型のほうだった。2 段目の「ほか N 件」に数えない
        }
        detail(topic, message);
    }

    private static void clear() {
        logged.clear();
        loggedOmitted = 0;
        classified.clear();
        details.clear();
        omitted.clear();
    }

    /** ファイルの本文。表示言語で書く（人が読んで対処を選ぶ案内なので。contracts-suggested.txt と同じ扱い） */
    private static String render() {
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append(Messages.get("exporter.warnings.head1")).append(nl);
        sb.append(Messages.get("exporter.warnings.head2")).append(nl);
        sb.append(Messages.get("exporter.warnings.head3")).append(nl);
        sb.append(Messages.get("exporter.warnings.head4")).append(nl);

        int no = 0;
        for (Topic topic : Topic.values()) {
            List<String> list = details.get(topic);
            if (list == null || list.isEmpty()) {
                continue;
            }
            no++;
            sb.append(nl).append("== ").append(no).append(". ").append(title(topic)).append(" ==").append(nl);
            for (String line : impact(topic)) {
                sb.append(line).append(nl);
            }
            sb.append(nl).append(Messages.get("exporter.warnings.remedy")).append(nl);
            for (String line : remedy(topic)) {
                sb.append("  ").append(line).append(nl);
            }
            sb.append(nl).append(Messages.get("exporter.warnings.details")).append(nl);
            for (String line : list) {
                sb.append("  ").append(line).append(nl);
            }
            Integer more = omitted.get(topic);
            if (more != null) {
                sb.append("  ").append(Messages.format("exporter.warnings.more", more)).append(nl);
            }
        }

        List<String> others = new ArrayList<>();
        int othersOmitted = loggedOmitted;
        for (String line : logged) {
            if (classified.contains(line)) {
                continue;
            }
            if (others.size() < OTHERS_LIMIT) {
                others.add(line);
            } else {
                othersOmitted++;
            }
        }
        if (!others.isEmpty() || othersOmitted > 0) {
            sb.append(nl).append("== ").append(Messages.get("exporter.warnings.others.title")).append(" ==").append(nl);
            sb.append(Messages.get("exporter.warnings.others.impact1")).append(nl).append(nl);
            for (String line : others) {
                sb.append("  ").append(line).append(nl);
            }
            if (othersOmitted > 0) {
                sb.append("  ").append(Messages.format("exporter.warnings.more", othersOmitted)).append(nl);
            }
        }
        return sb.toString();
    }

    // 項目ごとの文言。キーは組み立てずに Messages.get へそのまま書く
    // （使っているキーを test/nls/run.sh がソースから拾うため）

    private static String title(Topic topic) {
        return switch (topic) {
            case FAILED -> Messages.get("exporter.warnings.failed.title");
            case CONFIG -> Messages.get("exporter.warnings.config.title");
            case DEPENDENCIES -> Messages.get("exporter.warnings.deps.title");
            case BUILD -> Messages.get("exporter.warnings.build.title");
            case INCOMPLETE -> Messages.get("exporter.warnings.incomplete.title");
        };
    }

    /** 何に響くか */
    private static List<String> impact(Topic topic) {
        return switch (topic) {
            case FAILED -> List.of(Messages.get("exporter.warnings.failed.impact1"));
            case CONFIG -> List.of(Messages.get("exporter.warnings.config.impact1"),
                    Messages.get("exporter.warnings.config.impact2"));
            case DEPENDENCIES -> List.of(Messages.get("exporter.warnings.deps.impact1"),
                    Messages.get("exporter.warnings.deps.impact2"));
            case BUILD -> List.of(Messages.get("exporter.warnings.build.impact1"),
                    Messages.get("exporter.warnings.build.impact2"));
            case INCOMPLETE -> List.of(Messages.get("exporter.warnings.incomplete.impact1"));
        };
    }

    /** どう直すか */
    private static List<String> remedy(Topic topic) {
        return switch (topic) {
            case FAILED -> List.of(Messages.get("exporter.warnings.failed.remedy1"),
                    Messages.get("exporter.warnings.failed.remedy2"));
            case CONFIG -> List.of(Messages.get("exporter.warnings.config.remedy1"),
                    Messages.get("exporter.warnings.config.remedy2"));
            case DEPENDENCIES -> List.of(Messages.get("exporter.warnings.deps.remedy1"),
                    Messages.get("exporter.warnings.deps.remedy2"),
                    Messages.get("exporter.warnings.deps.remedy3"),
                    Messages.get("exporter.warnings.deps.remedy4"));
            case BUILD -> List.of(Messages.get("exporter.warnings.build.remedy1"),
                    Messages.get("exporter.warnings.build.remedy2"),
                    Messages.get("exporter.warnings.build.remedy3"),
                    Messages.get("exporter.warnings.build.remedy4"),
                    Messages.get("exporter.warnings.build.remedy5"),
                    Messages.get("exporter.warnings.build.remedy6"));
            case INCOMPLETE -> List.of(Messages.get("exporter.warnings.incomplete.remedy1"),
                    Messages.get("exporter.warnings.incomplete.remedy2"));
        };
    }
}
