// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jche.cache.CallEdgeFact;
import jche.cache.CallSite;
import jche.cache.FileAnalysis;
import jche.cache.Guard;
import jche.cache.MethodRef;
import jche.cache.Origin;
import jche.cache.UnresolvedCallFact;
import jche.config.Config;
import jche.config.ProjectLayout;
import jche.util.Log;

/**
 * 指定した呼び出し箇所に効いている条件分岐を、その場で調べる（設定ファイルの {@code conditions.target}）。
 *
 * <h2>何のためにあるか</h2>
 * 呼び出し階層の打ち切り（{@link jche.graph.GuardEvaluator}）は「成立しないと言い切れる条件」だけを
 * 使う。裏を返すと、<b>打ち切られなかった呼び出しに、どんな条件が効いているのか</b>は出力に現れない。
 * 影響調査では「この呼び出しはどういうときに起きるのか」「なぜ判定できなかったのか」を
 * 知りたいことがあるので、呼び出しを1つ選んで条件を並べて見せる。
 *
 * <h2>キャッシュに触らない</h2>
 * 対象のファイルだけをその場でパースし、結果はどこにも保存しない。理由:
 * <ul>
 *   <li>判定できない条件まで載せるとキャッシュの形式（版）が変わり、
 *       打ち切りを使っていない利用者にまで全件再解析を強いる</li>
 *   <li>条件式のテキストはソースの断片なので、キャッシュに残さない方が扱いが軽い</li>
 *   <li>調べたいのは普通1〜数ファイルで、その場でパースしても数十msで済む</li>
 * </ul>
 * 全件を棚卸ししたくなったら、そのときに別キャッシュを設ければよい
 * （{@code docs/call-conditions.md} の「この先の拡張」）。
 *
 * <h2>結果の読み方</h2>
 * 並ぶ条件は「この呼び出しに到達するには、すべて成立していなければならない」もの（論理積）。
 * 判定できる条件（{@code decidable}）は経路ごとの値と突き合わせられ、打ち切りにも使われる。
 * 判定できない条件は「効いてはいるが、静的には値が決まらない」もので、
 * <b>調査で人が確認すべき箇所</b>を指す。
 */
public final class CallConditionScanner {

    /** 呼び出し1件に効いている条件1つ */
    public record Condition(String op, String subject, String values, String text) {

        /** 経路ごとの値と突き合わせて判定できる条件か */
        public boolean decidable() {
            return !Guard.UNKNOWN.equals(op) && !Guard.MORE.equals(op);
        }

        /** 上限に達して記録しきれなかったことを表す印か */
        public boolean truncationMark() {
            return Guard.MORE.equals(op);
        }

        /** 判定対象の由来（「引数1」「定数」「不明」） */
        public String subjectKind() {
            return switch (Origin.kindOf(subject)) {
                case Origin.PARAM -> "引数" + paramNumber();
                case Origin.CONST, Origin.LITERAL, Origin.CLASS -> "定数";
                default -> "不明";
            };
        }

        private String paramNumber() {
            try {
                return String.valueOf(Integer.parseInt(Origin.valueOf(subject)) + 1);
            } catch (NumberFormatException ignore) {
                return "?";
            }
        }

        /** 成立する条件を人が読める形にする（「= false」「∈ {1, 2}」など） */
        public String expectation() {
            return switch (op) {
                case Guard.EQ -> "= " + values;
                case Guard.NE -> "≠ " + values;
                case Guard.IN -> "∈ {" + String.join(", ", Guard.valuesOf(values)) + "}";
                case Guard.NOT_IN -> "∉ {" + String.join(", ", Guard.valuesOf(values)) + "}";
                default -> "";
            };
        }
    }

    /** 呼び出し1件と、そこに効いている条件 */
    public record CallSiteConditions(String file, int line, String caller, String callee,
                                     List<Condition> conditions) {

        public boolean anyUndecidable() {
            for (Condition c : conditions) {
                if (!c.decidable()) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 調べる対象。
     *
     * <pre>
     *   src/foo/Bar.java        ファイル全体
     *   src/foo/Bar.java:120    その行の呼び出しだけ
     *   foo.Bar                 型（ファイル名に読み替える）
     *   foo.Bar#method          その型の、そのメソッドの中の呼び出しだけ
     * </pre>
     * ファイルは「相対パスの末尾一致」で探すので、{@code Bar.java} のような短い指定でもよい
     * （複数当たればすべて調べる）。
     */
    public record Target(String pathSuffix, int line, String methodName) {

        public static Target parse(String spec) {
            String rest = spec.trim().replace('\\', '/');
            String method = null;
            int hash = rest.indexOf('#');
            if (hash >= 0) {
                method = rest.substring(hash + 1);
                rest = rest.substring(0, hash);
            }
            int line = -1;
            int colon = rest.lastIndexOf(':');
            // Windows のドライブレター（C:/…）を行番号と間違えないよう、数字だけのときに限る
            if (colon > 1 && isDigits(rest.substring(colon + 1))) {
                line = Integer.parseInt(rest.substring(colon + 1));
                rest = rest.substring(0, colon);
            }
            String path = rest.endsWith(".java") ? rest : typeToPath(rest);
            return new Target(path, line, (method == null || method.isEmpty()) ? null : method);
        }

        /** 型のFQNをソースのパスに読み替える。内部クラス（Outer.Inner / Outer$Inner）は外側のファイル */
        private static String typeToPath(String typeFqn) {
            String outer = typeFqn;
            int dollar = outer.indexOf('$');
            if (dollar > 0) {
                outer = outer.substring(0, dollar);
            }
            String[] parts = outer.split("\\.");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                // 型名（先頭が大文字）より後ろは内部クラスなので、ファイル名はそこで止める
                if (i > 0 && !parts[i - 1].isEmpty() && Character.isUpperCase(parts[i - 1].charAt(0))) {
                    break;
                }
                sb.append(i == 0 ? "" : "/").append(parts[i]);
            }
            return sb + ".java";
        }

        private static boolean isDigits(String s) {
            if (s.isEmpty()) {
                return false;
            }
            for (int i = 0; i < s.length(); i++) {
                if (!Character.isDigit(s.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        boolean matchesFile(String relativePath) {
            String p = relativePath.replace('\\', '/');
            return p.equals(pathSuffix) || p.endsWith("/" + pathSuffix);
        }

        boolean matchesSite(int callLine, MethodRef caller) {
            if (line >= 0 && callLine != line) {
                return false;
            }
            return methodName == null || (caller != null && methodName.equals(caller.name()));
        }
    }

    /** 調べた結果 */
    public record Result(List<String> files, List<CallSiteConditions> sites, int callSitesInFiles) {
    }

    private CallConditionScanner() {
    }

    /**
     * 対象のファイルだけをパースして、条件を集める。
     *
     * @param target {@link Target#parse} で解釈する指定
     * @throws IOException ソースフォルダを読めない場合
     */
    public static Result scan(Config config, ProjectLayout layout, String target) throws IOException {
        Target t = Target.parse(target);
        List<Path> matched = new ArrayList<>();
        for (Path java : layout.listJavaFiles()) {
            if (t.matchesFile(layout.relativeOf(java))) {
                matched.add(java);
            }
        }
        if (matched.isEmpty()) {
            return new Result(List.of(), List.of(), 0);
        }

        List<CallEdgeExtractor.SourceFile> files = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Path java : matched) {
            String rel = layout.relativeOf(java);
            names.add(rel);
            files.add(new CallEdgeExtractor.SourceFile(java, rel,
                    Files.size(java)));
        }

        // 判定できない条件も残すモードで解析する。結果はキャッシュへ書かず、ここで捨てる
        CallEdgeExtractor extractor = new CallEdgeExtractor(layout, config, true);
        List<CallSiteConditions> sites = new ArrayList<>();
        int[] total = {0};
        extractor.analyzeBatch(files, new CallEdgeExtractor.Sink() {
            @Override
            public void accept(CallEdgeExtractor.SourceFile file, FileAnalysis analysis) {
                total[0] += analysis.callSites.size();
                collect(t, file.relativePath(), analysis, sites);
            }

            @Override
            public void failed(CallEdgeExtractor.SourceFile file, Exception error) {
                Log.warn("解析に失敗しました: " + file.relativePath() + " (" + error + ")");
            }
        });
        return new Result(names, sites, total[0]);
    }

    /** 1ファイル分の呼び出しから、対象に合うものだけ取り出す */
    private static void collect(Target target, String file, FileAnalysis analysis,
                                List<CallSiteConditions> out) {
        for (CallSite site : analysis.callSites) {
            int line;
            MethodRef caller;
            String callee;
            String guard;
            if (site instanceof CallEdgeFact c) {
                line = c.callLine();
                caller = c.caller();
                callee = label(c.callee());
                guard = c.guard();
            } else if (site instanceof UnresolvedCallFact u) {
                line = u.line();
                caller = u.caller();
                callee = u.expression() + "（型解決できず）";
                guard = u.guard();
            } else {
                continue;
            }
            if (!target.matchesSite(line, caller)) {
                continue;
            }
            out.add(new CallSiteConditions(file, line, (caller == null) ? "(不明)" : label(caller),
                    callee, conditionsOf(guard)));
        }
    }

    private static List<Condition> conditionsOf(String guard) {
        List<Condition> conditions = new ArrayList<>();
        for (String atom : Guard.atomsOf(guard)) {
            conditions.add(new Condition(Guard.fieldOf(atom, 0), Guard.fieldOf(atom, 1),
                    Guard.fieldOf(atom, 2), Guard.fieldOf(atom, 3)));
        }
        return conditions;
    }

    /** 「型の単純名.メソッド名(引数型)」。ファイル内の話なので完全修飾名までは要らない */
    private static String label(MethodRef ref) {
        String type = ref.typeFqn();
        int dot = type.lastIndexOf('.');
        return (dot >= 0 ? type.substring(dot + 1) : type) + "." + ref.signature();
    }
}
