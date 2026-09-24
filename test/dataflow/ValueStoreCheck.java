// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

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
 * 値の表（{@link ValueStore}・{@link GuardTable}）が、今の読み手が受け取っている出所の文字列と
 * 同じ中身を持つことの検査（stage B。値の読み手を文字列から表へ移す前の証明）。
 *
 * <pre>
 *   (1) 組み直しの一致  … 表から組み直した文字列（{@link LegacyRender}）が、エッジごとのレシーバ・実引数・
 *                        条件、メソッドごとの戻り値の並び、フィールドの値の頭と 1 文字も違わない
 *   (2) 読み方の一致    … 組み直した文字列を今の読み方（{@link LegacyOrigin}）で読むと、表の構造（種別・値・
 *                        レシーバ・実引数の数・書かれた型・位置ごとの実引数・実引数の並び・引数を含むか）と
 *                        同じものが返る。返らない参照を「あいまい」と数える
 *   (3) 表の決まり      … 子の参照 &lt; 親の参照、実引数の項目が先頭に位置の昇順で重なりなく並ぶ、
 *                        new と実引数の並びは実引数の項目だけ、葉は種別と値の文字列の組ごとに 1 つ、
 *                        文字列の置き場は同じ中身を 2 つ持たずメソッドキーはメソッド表の文字列を共有する、
 *                        フィールドの値と条件の判定される式は項目の無い葉（頭だけ）、
 *                        メソッドごとの戻り値の参照に重なりが無い、
 *                        new の値（型）とメソッド表・型階層の型名が ':' を含まない（I0 / I1）、
 *                        組み直しが予算・深さの安全弁に当たっていない
 *   (4) 合格の条件      … 値に文法の文字を含まないプロジェクト（{@link CheckProjects#STRICT}）はあいまいな参照が
 *                        0。含むプロジェクト（values）はあいまいな参照があり、どれも部分木の値が
 *                        {@code ; | { }} のどれか（か、引数を使う印の {@code =A:} / {@code =E:}）を含む
 *                        （読み違いの原因がその文字であること）
 * </pre>
 * 使い方: test/dataflow/run.sh を参照
 */
public final class ValueStoreCheck {

    private ValueStoreCheck() {
    }

    /**
     * 引数なしなら {@link CheckProjects} のプロジェクトを全部検査する。
     * {@code <名前> <設定ファイル> [--loose]} を渡すと、そのプロジェクトだけを検査する（手で測るとき用。
     * {@code --loose} は値に文法の文字を含むプロジェクトとして扱う）
     */
    public static void main(String[] args) throws Exception {
        boolean ok = true;
        if (args.length >= 2) {
            ok = check(new CheckProjects.Project(args[0], Path.of(args[1]).toAbsolutePath().toString(),
                    !(args.length > 2 && "--loose".equals(args[2]))));
        } else {
            for (CheckProjects.Project p : CheckProjects.STRICT) {
                ok &= check(p);
            }
            ok &= check(CheckProjects.VALUES);
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
        LegacyRender lr = new LegacyRender(vs);
        List<String> problems = new ArrayList<>();

        // (1) 組み直しの一致
        MethodTable methods = graph.methods();
        for (int e = 0; e < graph.edgeCount(); e++) {
            same(problems, "edge " + e + " recv", graph.recvOrigin(e), lr.render(graph.recvNode(e)));
            same(problems, "edge " + e + " args", graph.argOrigins(e), lr.renderArgs(graph.argsNode(e)));
            same(problems, "edge " + e + " guard", graph.guard(e),
                    LegacyRender.renderGuard(vs, gt, graph.guardOf(e)));
        }
        int returnLists = 0;
        for (int m = 0; m < graph.methodCount(); m++) {
            List<String> typed = new ArrayList<>();
            for (int k = 0; k < graph.returnCount(m); k++) {
                String s = returnText(vs, lr, graph.returnAt(m, k));
                if (!typed.contains(s)) {
                    typed.add(s);
                }
            }
            String[] legacy = graph.returnOriginsOf(m);
            List<String> expected = (legacy == null) ? List.of() : List.of(legacy);
            if (!expected.isEmpty()) {
                returnLists++;
            }
            if (!expected.equals(typed)) {
                problems.add("returns of " + methods.key(m) + ": 文字列=" + LegacyOrigin.clean(expected.toString())
                        + " / 表=" + LegacyOrigin.clean(typed.toString()));
            }
        }
        Map<String, String> fieldOrigins = StoreInspector.fieldOrigins(graph);
        Map<String, Integer> fieldHeads = StoreInspector.fieldHeads(graph);
        same(problems, "field keys", String.valueOf(new TreeSet<>(fieldOrigins.keySet())),
                String.valueOf(new TreeSet<>(fieldHeads.keySet())));
        for (Map.Entry<String, Integer> f : fieldHeads.entrySet()) {
            int h = f.getValue();
            same(problems, "field " + f.getKey(), fieldOrigins.get(f.getKey()), vs.kind(h) + ":" + vs.value(h));
        }

        // (2) 読み方の一致
        boolean[] hasParam = subtreeHas(vs, 'A');
        boolean[] hasCaptured = subtreeHas(vs, 'E');
        Set<Integer> ambiguous = new TreeSet<>();
        for (int ref = 0; ref < vs.size(); ref++) {
            if (!readsAgree(vs, lr, ref, hasParam[ref], hasCaptured[ref])) {
                ambiguous.add(ref);
            }
        }
        Set<Integer> ambiguousGuards = new TreeSet<>();
        for (int e = 0; e < graph.edgeCount(); e++) {
            if (LegacyOrigin.guardsOnParam(graph.guard(e)) != gt.onParam(graph.guardOf(e))) {
                ambiguousGuards.add(graph.guardOf(e));
            }
        }

        // (3) 表の決まり
        invariants(problems, vs);
        sharedWithMethods(problems, vs, methods);
        headsAreLeaves(problems, vs, gt, fieldHeads);
        returnsUnique(problems, graph);
        typeNames(problems, graph);
        if (lr.cutOffs > 0) {
            problems.add("組み直しが予算・深さの安全弁に当たった: " + lr.cutOffs + " 回");
        }

        // (4) 合格の条件
        boolean ok = problems.isEmpty();
        for (String s : problems.subList(0, Math.min(problems.size(), 10))) {
            System.out.println("  NG   " + p.name() + ": " + s);
        }
        if (problems.size() > 10) {
            System.out.println("  NG   " + p.name() + ": ほか " + (problems.size() - 10) + " 件");
        }
        if (p.strict()) {
            for (int ref : ambiguous) {
                System.out.println("  NG   " + p.name() + ": あいまいな参照 " + ref + " = "
                        + LegacyOrigin.clean(describe(vs, lr, ref)));
                ok = false;
            }
            if (!ambiguousGuards.isEmpty()) {
                System.out.println("  NG   " + p.name() + ": 引数を見ているかの判定が食い違う条件 " + ambiguousGuards);
                ok = false;
            }
        } else {
            if (ambiguous.isEmpty()) {
                System.out.println("  NG   " + p.name() + ": あいまいな参照が 1 つも無い（文法の文字を含む値が取り込まれていない）");
                ok = false;
            }
            for (int ref : ambiguous) {
                System.out.println("     あいまい（" + p.name() + "）: " + LegacyOrigin.clean(describe(vs, lr, ref)));
                if (!subtreeHasGrammar(vs, ref)) {
                    System.out.println("  NG   " + p.name() + ": 文法の文字を含まないのにあいまいな参照 " + ref + " = "
                            + LegacyOrigin.clean(describe(vs, lr, ref)));
                    ok = false;
                }
            }
            if (!ambiguousGuards.isEmpty()) {
                System.out.println("  NG   " + p.name() + ": 引数を見ているかの判定が食い違う条件 " + ambiguousGuards);
                ok = false;
            }
        }
        System.out.println((ok ? "OK   " : "NG   ") + p.name() + (p.strict() ? "" : "（文法の文字を含む）")
                + ": edges=" + graph.edgeCount() + " nodes=" + vs.size()
                + " entries=" + StoreInspector.entryCount(vs) + " strings=" + vs.strings().size()
                + " guards=" + gt.size() + " returnLists=" + returnLists + " fields=" + fieldHeads.size()
                + " ambiguous=" + ambiguous.size());
        System.out.println("     heap " + p.name() + ": " + heap(graph, vs, gt));
        return ok;
    }

    /** 戻り値 1 つを、今の文字列の側と同じ規則で文字列にする（追跡できない・暫定の読めない文字列は U） */
    private static String returnText(ValueStore vs, LegacyRender lr, int ref) {
        if (ref == ValueStore.NONE) {
            return "U";
        }
        if (vs.kind(ref) == 'L' && !jche.cache.Origin.isNameShaped(vs.value(ref))) {
            return "U";   // CallGraphBuilder#unreadableLiteral（暫定。値を正確に読むようになったら外す）
        }
        return lr.render(ref);
    }

    private static void same(List<String> problems, String what, String legacy, String typed) {
        if (!Objects.equals(legacy, typed)) {
            problems.add(what + ": 文字列=" + LegacyOrigin.clean(legacy) + " / 表=" + LegacyOrigin.clean(typed));
        }
    }

    /** 組み直した文字列を今の読み方で読んだ結果が、表の構造と一致するか */
    private static boolean readsAgree(ValueStore vs, LegacyRender lr, int ref, boolean param, boolean captured) {
        char kind = vs.kind(ref);
        if (kind == ValueStore.ARG_LIST) {
            String r = lr.renderArgs(ref);
            return positionalAgree(vs, lr, ref, r)
                    && LegacyOrigin.mentions(r, 'A') == param && LegacyOrigin.mentions(r, 'E') == captured;
        }
        String r = lr.render(ref);
        if (LegacyOrigin.kindOf(r) != kind || !LegacyOrigin.valueOf(r).equals(vs.value(ref))
                || !LegacyOrigin.head(r).equals(kind + ":" + vs.value(ref))) {
            return false;
        }
        if (!Objects.equals(LegacyOrigin.unnest(LegacyOrigin.receiverOf(r)), lr.render(vs.receiver(ref)))
                || LegacyOrigin.argCountOf(r) != vs.argCount(ref)
                || !Objects.equals(LegacyOrigin.staticReceiverOf(r), vs.staticReceiver(ref))) {
            return false;
        }
        if (!positionalAgree(vs, lr, ref, LegacyOrigin.argsOf(r))) {
            return false;
        }
        boolean a = LegacyOrigin.kindOf(r) == 'A' || LegacyOrigin.mentions(r, 'A');
        boolean e = LegacyOrigin.kindOf(r) == 'E' || LegacyOrigin.mentions(r, 'E');
        return a == param && e == captured;
    }

    /**
     * 位置ごとの実引数（{@code Origin.argAt}）と、実引数の並び（{@code Origin.entriesOf} の数字で始まる要素。
     * {@code FactoryCalls} の読み方）が、表の実引数の項目と一致するか
     */
    private static boolean positionalAgree(ValueStore vs, LegacyRender lr, int ref, String args) {
        List<String> typed = new ArrayList<>();
        for (int k = vs.argBegin(ref); k < vs.argEnd(ref); k++) {
            int pos = vs.argPos(k);
            if (!Objects.equals(LegacyOrigin.argAt(args, pos), lr.render(vs.argAt(ref, pos)))) {
                return false;
            }
            typed.add(pos + "=" + lr.render(vs.argRef(k)));
        }
        List<String> legacy = new ArrayList<>();
        for (String entry : LegacyOrigin.entriesOf(args)) {
            int eq = entry.indexOf('=');
            if (eq <= 0 || !Character.isDigit(entry.charAt(0))) {
                continue;
            }
            int pos = jche.util.Names.parseIntOr(entry.substring(0, eq), -1);
            if (pos >= 0) {
                legacy.add(pos + "=" + LegacyOrigin.unnest(entry.substring(eq + 1)));
            }
        }
        return typed.equals(legacy);
    }

    /** 参照ごとに、自分か部分木（実引数・レシーバ）にその種別の葉があるか。子 &lt; 親なので前から 1 回で決まる */
    private static boolean[] subtreeHas(ValueStore vs, char kind) {
        boolean[] has = new boolean[vs.size()];
        for (int ref = 0; ref < vs.size(); ref++) {
            boolean h = vs.kind(ref) == kind;
            for (int k = vs.argBegin(ref); !h && k < vs.argEnd(ref); k++) {
                h = vs.argRef(k) >= 0 && vs.argRef(k) < ref && has[vs.argRef(k)];
            }
            int r = vs.receiver(ref);
            if (!h && r >= 0 && r < ref) {
                h = has[r];
            }
            has[ref] = h;
        }
        return has;
    }

    /**
     * 自分か部分木の値が、出所の文法の文字（{@code ; | { }}）を含むか。今の読み手が文字列を探して
     * 引数を使うかを決める印（{@code =A:} / {@code =E:}）を含む値も、同じく読み違いの原因として数える
     */
    private static boolean subtreeHasGrammar(ValueStore vs, int ref) {
        String v = vs.value(ref);
        if (v.indexOf(';') >= 0 || v.indexOf('|') >= 0 || v.indexOf('{') >= 0 || v.indexOf('}') >= 0
                || v.contains("=A:") || v.contains("=E:")) {
            return true;
        }
        for (int k = vs.argBegin(ref); k < vs.argEnd(ref); k++) {
            if (subtreeHasGrammar(vs, vs.argRef(k))) {
                return true;
            }
        }
        int r = vs.receiver(ref);
        return r != ValueStore.NONE && subtreeHasGrammar(vs, r);
    }

    private static String describe(ValueStore vs, LegacyRender lr, int ref) {
        return (vs.kind(ref) == ValueStore.ARG_LIST) ? "(" + lr.renderArgs(ref) + ")" : lr.render(ref);
    }

    /** (3) の表の決まり */
    private static void invariants(List<String> problems, ValueStore vs) {
        // 文字列の置き場は同じ中身を 2 つ持たない（番号の比較で値を比べられることの前提）
        StringPool pool = vs.strings();
        Set<String> strings = new HashSet<>();
        for (int i = 0; i < pool.size(); i++) {
            if (!strings.add(pool.get(i))) {
                problems.add("文字列の置き場に同じ中身が 2 つある（" + LegacyOrigin.clean(pool.get(i)) + "）");
            }
        }
        // 葉は種別と値の「文字列」の組で 1 つ（番号で見ると、置き場が同じ中身に 2 つの番号を振ったときに見逃す）
        Set<String> leaves = new HashSet<>();
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
                    if (key == -2 && (val < 0 || val >= ref)) {
                        problems.add("参照 " + ref + ": レシーバの子 " + val + " が親より小さくない");
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
            } else if (kind != 'T' && kind != 'M' && kind != 'Z' && kind != ValueStore.ARG_LIST) {
                problems.add("参照 " + ref + ": 項目を持てない種別 " + kind);
            }
            if (kind == 'T' && vs.value(ref).indexOf(':') >= 0) {
                problems.add("I0: new の型が ':' を含む: " + vs.value(ref));
            }
        }
    }

    /** 文字列の置き場の、メソッドキーと同じ中身の文字列は、メソッド表の文字列そのもの（同じ文字列を 2 つ持たない） */
    private static void sharedWithMethods(List<String> problems, ValueStore vs, MethodTable methods) {
        StringPool pool = vs.strings();
        for (int i = 0; i < pool.size(); i++) {
            int id = methods.idOf(pool.get(i));
            if (id >= 0 && methods.key(id) != pool.get(i)) {
                problems.add("文字列の置き場のメソッドキーが、メソッド表の文字列を共有していない（" + pool.get(i) + "）");
            }
        }
    }

    /**
     * フィールドの値と条件の判定される式は、頭だけの葉（項目を持たない）。J 行と G 行の subject は
     * ノードの頭だけを取り込む（{@code ValueStoreBuilder#importHead}）。丸ごと取り込むと、束縛したレシーバ（r=）や
     * 実引数を持ったままになり、頭だけを見ていた以前の読み手と食い違う
     */
    private static void headsAreLeaves(List<String> problems, ValueStore vs, GuardTable gt,
                                       Map<String, Integer> fieldHeads) {
        for (Map.Entry<String, Integer> f : new java.util.TreeMap<>(fieldHeads).entrySet()) {
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
     * おおよそのヒープ（バイト）。表の側は列（値の表・条件の表・戻り値の参照の CSR）と
     * 文字列の置き場（メソッド表と共有した文字列は除く）とフィールドの値の頭の表、文字列の側は共有プールの
     * 文字列（索引は除く）と戻り値の出所の配列とフィールドの出所の表。フィールドの表の鍵は両方の表が
     * 同じ文字列を指すので数えない（HashMap の 1 件は、表の枠 4 B と Node 32 B で数える）
     */
    private static String heap(CallGraph graph, ValueStore vs, GuardTable gt) {
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
        Map<String, Integer> fieldHeads = StoreInspector.fieldHeads(graph);
        long fields = 0;
        for (int h : fieldHeads.values()) {
            // Integer は -128〜127 なら共有のもの、それを超えると 1 つ 16 B
            fields += 4 + 32 + ((h >= -128 && h <= 127) ? 0 : 16);
        }
        long legacy = 0;
        for (Object o : StoreInspector.originPool(graph)) {
            legacy += 4 + stringBytes((String) o);
        }
        for (String origin : StoreInspector.fieldOrigins(graph).values()) {
            legacy += 4 + 32 + stringBytes(origin);
        }
        String[][] returns = StoreInspector.returnOrigins(graph);
        if (returns != null) {
            legacy += 16L + 4L * returns.length;
            for (String[] r : returns) {
                if (r != null) {
                    legacy += 16L + 4L * r.length;
                    for (String s : r) {
                        legacy += stringBytes(s);
                    }
                }
            }
        }
        return "表=" + (columns + poolBytes + fields) + " B（列 " + columns + " + 文字列 " + poolBytes
                + " + フィールド " + fields + "。メソッド表と共有 " + shared + " 個） / 文字列の側=" + legacy + " B";
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
}
