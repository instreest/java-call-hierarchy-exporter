// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import jche.AnalysisSnapshot;
import jche.config.Config;
import jche.graph.CallGraph;
import jche.graph.GuardTable;
import jche.graph.MethodTable;
import jche.graph.StoreInspector;
import jche.graph.StringPool;
import jche.graph.TypeHierarchy;
import jche.graph.ValueStore;

/**
 * 値の表（{@link ValueStore}・{@link GuardTable}）の決まりの検査。読み手（{@code DataflowResolver}・
 * {@code GuardEvaluator} など）はこの決まりを前提に、値を番号の比較と前からの 1 回の走査で読む。
 * 実際のプロジェクトを解析して、組み上がった表を全部見る。
 *
 * <pre>
 *   子 &lt; 親        項目の子（実引数・レシーバ）の参照は親の参照より小さい（前から 1 回で部分木を畳める）
 *   項目の並び      実引数の項目が先頭に位置の昇順で重なりなく並び、その後ろに n= r= s= がこの順で 1 つずつ。
 *                  new と実引数の並びは実引数の項目だけ。項目を持てるのは T・M・Z・実引数の並びだけ。
 *                  空の実引数の並びは作らない
 *   葉の一意        項目の無いノード（葉）は、種別と値の「文字列」の組ごとに 1 つ（頭どうしは参照の比較で済む）
 *   文字列の一意    文字列の置き場は同じ中身を 2 つ持たない（値どうしは番号の比較で済む）。メソッドキーと同じ中身の
 *                  文字列は、メソッド表の文字列そのもの（同じ文字列を 2 つ持たない）
 *   頭は葉          フィールドの値と条件の判定される式は、項目を持たない葉（頭だけを取り込む）
 *   戻り値          メソッドごとの戻り値の参照に重なりが無い
 *   修飾する型      エッジの修飾する型は文字列の置き場の番号で、置き場の範囲に収まる
 *   I0 / I1        new の値（型）、型階層の型名、メソッド表のキーの型の部分は ':' を含まない
 *                  （経路の値の枠 jche.graph.Slot が、型とそれ以外を取り違えないための前提）
 * </pre>
 * 最後に表の大きさ（ノード・項目・文字列の数と、列と文字列のおおよそのバイト数）を 1 行出す。
 * 使い方: test/dataflow/run.sh を参照
 */
public final class ValueStoreCheck {

    private ValueStoreCheck() {
    }

    /**
     * 引数なしなら {@link CheckProjects#ALL} のプロジェクトを全部検査する。
     * {@code <名前> <設定ファイル>} を渡すと、そのプロジェクトだけを検査する（手で測るとき用）
     */
    public static void main(String[] args) throws Exception {
        boolean ok = true;
        if (args.length >= 2) {
            ok = check(new CheckProjects.Project(args[0], Path.of(args[1]).toAbsolutePath().toString()));
        } else {
            for (CheckProjects.Project p : CheckProjects.ALL) {
                ok &= check(p);
            }
        }
        if (!ok) {
            System.exit(1);
        }
    }

    private static boolean check(CheckProjects.Project p) throws Exception {
        Config config = CheckProjects.configOf(p);
        AnalysisSnapshot snapshot = CheckProjects.analyze(config);
        CallGraph graph = snapshot.graph();
        ValueStore vs = graph.values();
        GuardTable gt = graph.guards();
        List<String> problems = new ArrayList<>();

        Shapes shapes = nodes(problems, vs);
        strings(problems, vs, graph.methods());
        headsAreLeaves(problems, vs, gt, StoreInspector.fieldHeads(graph));
        returnsUnique(problems, graph);
        qualifiers(problems, graph, vs.strings());
        typeNames(problems, graph);

        for (String s : problems.subList(0, Math.min(problems.size(), 10))) {
            System.out.println("  NG   " + p.name() + ": " + clean(s));
        }
        if (problems.size() > 10) {
            System.out.println("  NG   " + p.name() + ": ほか " + (problems.size() - 10) + " 件");
        }
        boolean ok = problems.isEmpty();
        System.out.println((ok ? "OK   " : "NG   ") + p.name() + ": edges=" + graph.edgeCount()
                + " nodes=" + vs.size() + " entries=" + StoreInspector.entryCount(vs)
                + " strings=" + vs.strings().size() + " guards=" + gt.size()
                + " fields=" + StoreInspector.fieldHeads(graph).size() + " " + shapes);
        System.out.println("     size " + p.name() + ": " + size(graph, vs, gt));
        return ok;
    }

    /** 項目の形の数（題材がどの形を踏んでいるかの目安） */
    private record Shapes(int leaves, int newWithArgs, int calls, int functionalWithReceiver, int argLists,
                          int counts, int receivers, int staticReceivers) {
        @Override
        public String toString() {
            return "leaves=" + leaves + " T(args)=" + newWithArgs + " M=" + calls + " Z(r=)=" + functionalWithReceiver
                    + " (=" + argLists + " n==" + counts + " r==" + receivers + " s==" + staticReceivers;
        }
    }

    /** 子 &lt; 親・項目の並び・葉の一意・I0 */
    private static Shapes nodes(List<String> problems, ValueStore vs) {
        Set<String> leaves = new HashSet<>();
        int newWithArgs = 0;
        int calls = 0;
        int functional = 0;
        int argLists = 0;
        int counts = 0;
        int receivers = 0;
        int statics = 0;
        for (int ref = 0; ref < vs.size(); ref++) {
            char kind = vs.kind(ref);
            int begin = StoreInspector.entryBegin(vs, ref);
            int end = StoreInspector.entryEnd(vs, ref);
            boolean tail = false;
            int lastPos = -1;
            int lastTail = 0;
            for (int k = begin; k < end; k++) {
                int key = StoreInspector.entryKey(vs, k);
                int val = StoreInspector.entryValue(vs, k);
                if (key >= 0) {
                    if (tail) {
                        problems.add("参照 " + ref + ": 実引数の項目が n= r= s= の後ろにある");
                    }
                    if (key <= lastPos) {
                        problems.add("参照 " + ref + ": 実引数の位置が昇順でない・重なる（" + key + "）");
                    }
                    lastPos = key;
                    if (val < 0 || val >= ref) {
                        problems.add("参照 " + ref + ": 実引数の子 " + val + " が親より小さくない");
                    }
                } else {
                    tail = true;
                    if (key >= lastTail) {
                        problems.add("参照 " + ref + ": n= r= s= の並びが違う・重なる");
                    }
                    lastTail = key;
                    if (key == -1) {
                        counts++;
                    } else if (key == -2) {
                        receivers++;
                        if (val < 0 || val >= ref) {
                            problems.add("参照 " + ref + ": レシーバの子 " + val + " が親より小さくない");
                        }
                    } else if (key == -3) {
                        statics++;
                        if (vs.strings().get(val) == null) {
                            problems.add("参照 " + ref + ": 書かれた型の番号 " + val + " が文字列の置き場の外");
                        }
                    } else {
                        problems.add("参照 " + ref + ": 知らない項目の鍵 " + key);
                    }
                }
            }
            if ((kind == 'T' || kind == ValueStore.ARG_LIST) && tail) {
                problems.add("参照 " + ref + ": " + kind + " が n= r= s= を持つ");
            }
            if (begin == end) {
                if (kind == ValueStore.ARG_LIST) {
                    problems.add("参照 " + ref + ": 空の実引数の並び");
                }
                if (!leaves.add(kind + ":" + vs.value(ref))) {
                    problems.add("参照 " + ref + ": 同じ葉が 2 つある（" + kind + ":" + vs.value(ref) + "）");
                }
            } else {
                switch (kind) {
                    case 'T' -> newWithArgs++;
                    case 'M' -> calls++;
                    case 'Z' -> functional++;
                    case ValueStore.ARG_LIST -> argLists++;
                    default -> problems.add("参照 " + ref + ": 項目を持てない種別 " + kind);
                }
            }
            if (kind == 'T' && vs.value(ref).indexOf(':') >= 0) {
                problems.add("I0: new の型が ':' を含む: " + vs.value(ref));
            }
        }
        return new Shapes(leaves.size(), newWithArgs, calls, functional, argLists, counts, receivers, statics);
    }

    /**
     * 文字列の置き場は同じ中身を 2 つ持たない（番号の比較で値を比べられることの前提）。メソッドキーと同じ中身の
     * 文字列は、メソッド表の文字列そのもの
     */
    private static void strings(List<String> problems, ValueStore vs, MethodTable methods) {
        StringPool pool = vs.strings();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < pool.size(); i++) {
            String s = pool.get(i);
            if (!seen.add(s)) {
                problems.add("文字列の置き場に同じ中身が 2 つある（" + s + "）");
            }
            int id = methods.idOf(s);
            if (id >= 0 && methods.key(id) != s) {
                problems.add("文字列の置き場のメソッドキーが、メソッド表の文字列を共有していない（" + s + "）");
            }
        }
    }

    /**
     * フィールドの値と条件の判定される式は、頭だけの葉（項目を持たない）。J 行と G 行の subject は
     * ノードの頭だけを取り込む（{@code ValueStoreBuilder#importHead}）。丸ごと取り込むと、束縛したレシーバ（r=）や
     * 実引数を持ったままになり、頭だけで比べる読み手（{@code FieldFacts}・{@code GuardEvaluator}）と食い違う
     */
    private static void headsAreLeaves(List<String> problems, ValueStore vs, GuardTable gt,
                                       Map<String, Integer> fieldHeads) {
        for (Map.Entry<String, Integer> f : new TreeMap<>(fieldHeads).entrySet()) {
            int h = f.getValue();
            if (h < 0 || h >= vs.size() || StoreInspector.entryBegin(vs, h) != StoreInspector.entryEnd(vs, h)) {
                problems.add("フィールド " + f.getKey() + " の値が頭だけの葉でない（参照 " + h + "）");
            }
        }
        for (int g = 0; g < gt.size(); g++) {
            for (int a = gt.atomBegin(g); a < gt.atomEnd(g); a++) {
                int subject = gt.subject(a);
                if (subject == ValueStore.NONE) {
                    continue;
                }
                if (subject < 0 || subject >= vs.size()
                        || StoreInspector.entryBegin(vs, subject) != StoreInspector.entryEnd(vs, subject)) {
                    problems.add("条件 " + g + " のアトム " + a + " の判定される式が頭だけの葉でない（参照 " + subject + "）");
                }
            }
        }
    }

    /** メソッドごとの戻り値の参照に重なりが無い（同じ参照は最初の 1 つだけ残す。{@code CallGraphBuilder#freezeValues}） */
    private static void returnsUnique(List<String> problems, CallGraph graph) {
        for (int m = 0; m < graph.methodCount(); m++) {
            Set<Integer> seen = new HashSet<>();
            for (int k = 0; k < graph.returnCount(m); k++) {
                if (!seen.add(graph.returnAt(m, k))) {
                    problems.add("returns of " + graph.methods().key(m) + ": 同じ参照が 2 つある（" + graph.returnAt(m, k) + "）");
                }
            }
        }
    }

    /** 修飾する型は文字列の置き場の番号で、範囲に収まり、空でない */
    private static void qualifiers(List<String> problems, CallGraph graph, StringPool pool) {
        for (int e = 0; e < graph.edgeCount(); e++) {
            int id = StoreInspector.qualifierId(graph, e);
            if (id != -1 && (pool.get(id) == null || pool.get(id).isEmpty())) {
                problems.add("エッジ " + e + " の修飾する型の番号 " + id + " が文字列の置き場の外か空");
            }
        }
    }

    /** I1: 型階層の型名とメソッド表のキーの型の部分が ':' を含まない */
    private static void typeNames(List<String> problems, CallGraph graph) {
        TypeHierarchy h = graph.hierarchy();
        for (String t : h.typeNames()) {
            if (t.indexOf(':') >= 0) {
                problems.add("I1: 型名が ':' を含む: " + t);
            }
            for (String s : h.directSupertypes(t)) {
                if (s.indexOf(':') >= 0) {
                    problems.add("I1: 親の型名が ':' を含む: " + s);
                }
            }
        }
        MethodTable methods = graph.methods();
        for (int id = 0; id < methods.size(); id++) {
            if (methods.typeFqn(id).indexOf(':') >= 0) {
                problems.add("I1: メソッドの型が ':' を含む: " + methods.key(id));
            }
        }
    }

    /**
     * おおよそのヒープ（バイト）。列（値の表・条件の表・戻り値の参照の CSR）と、文字列の置き場（メソッド表と
     * 共有した文字列は除く）と、フィールドの値の頭の表（HashMap の 1 件は表の枠 4 B と Node 32 B で数え、
     * 鍵の文字列は数えない）
     */
    private static String size(CallGraph graph, ValueStore vs, GuardTable gt) {
        StringPool pool = vs.strings();
        MethodTable methods = graph.methods();
        long poolBytes = 16L + 4L * pool.size();
        int shared = 0;
        for (int i = 0; i < pool.size(); i++) {
            String s = pool.get(i);
            int id = methods.idOf(s);
            if (id >= 0 && methods.key(id) == s) {
                shared++;
                continue;
            }
            poolBytes += stringBytes(s);
        }
        long columns = StoreInspector.columnBytes(vs) + StoreInspector.columnBytes(gt)
                + 16L + 4L * StoreInspector.returnOffsetCount(graph)
                + 16L + 4L * StoreInspector.returnRefCount(graph);
        long fields = 0;
        for (int h : StoreInspector.fieldHeads(graph).values()) {
            // Integer は -128〜127 なら共有のもの、それを超えると 1 つ 16 B
            fields += 4 + 32 + ((h >= -128 && h <= 127) ? 0 : 16);
        }
        return (columns + poolBytes + fields) + " B（列 " + columns + " + 文字列 " + poolBytes
                + " + フィールド " + fields + "。メソッド表と共有した文字列 " + shared + " 個）";
    }

    /** String 1 つのおおよそのバイト数（Latin-1 なら 1 文字 1 バイト） */
    private static long stringBytes(String s) {
        boolean latin1 = true;
        for (int i = 0; i < s.length() && latin1; i++) {
            latin1 = s.charAt(i) < 256;
        }
        long body = 16L + (latin1 ? s.length() : 2L * s.length());
        return 24L + ((body + 7) / 8) * 8;
    }

    /** 制御文字を空白にする（1 行に収める） */
    private static String clean(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append((c < ' ') ? ' ' : c);
        }
        return sb.toString();
    }
}
