// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 検査用: 値の表を組む側（{@link ValueStoreBuilder} / {@link GuardTableBuilder} / {@link StringPoolBuilder}）を、
 * 書き手が作らない行で直接たたく（test/dataflow/run.sh が src と一緒にコンパイルして動かす）。
 *
 * <p>ValueStoreCheck は実際のプロジェクトのキャッシュ（書き手が作った行）で、組み上がった表の決まりを見る。
 * 書き手が作らない行（手で書き換えたキャッシュ）の扱いは、そこでは見えないので、ここで表の中身を直接見る。
 * <pre>
 *   条件の値   … EQ / NE は値 1 つ（丸ごと）、IN / NOT_IN は区切りで分けたもの、値の無い行は空文字 1 つ、
 *                制御文字は空白（以前の文字列の読み手が受け取っていた値のまま。{@code GuardTableBuilder} の説明）
 *   実引数の位置 … 書き手の書く形（String.valueOf）で 0〜32767 のものだけ。01・+1・-0・ASCII でない数字・
 *                32768 以上・r・n・s・空の鍵は無いものとする
 *   種別        … Origin の種別でない文字（実引数の並びの印 '(' を含む）は U
 *   文字列      … メソッドキーと同じ中身の文字列は、取り込んだ順によらずメソッド表の文字列そのもの
 * </pre>
 */
public final class StoreUnitCheck {

    private final List<String> problems = new ArrayList<>();
    private int checks;

    private StoreUnitCheck() {
    }

    public static void main(String[] args) {
        StoreUnitCheck c = new StoreUnitCheck();
        c.guardValues();
        c.positions();
        c.kinds();
        c.sharedMethodKeys();
        for (String p : c.problems) {
            System.out.println("  NG   store-unit: " + p);
        }
        if (!c.problems.isEmpty()) {
            System.out.println("NG   store-unit: " + c.problems.size() + " 件（" + c.checks + " 件のうち）");
            System.exit(1);
        }
        System.out.println("OK   store-unit: " + c.checks + " 件の確認（条件の値・実引数の位置・種別・メソッドキーの共有）");
    }

    private void same(String what, Object expected, Object actual) {
        checks++;
        if (!Objects.equals(expected, actual)) {
            problems.add(what + ": 期待=" + visible(String.valueOf(expected)) + " / 実際=" + visible(String.valueOf(actual)));
        }
    }

    /** 制御文字を見える形にする */
    private static String visible(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch < ' ') {
                sb.append(String.format("\\u%04x", (int) ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** 1 つのアトムの、期待する形 */
    private record GuardCase(String op, List<String> values, byte expectedOp, List<String> expectedValues) {
    }

    /**
     * 条件の値。期待する値は、以前の文字列の読み手が受け取っていた値（値を {@code Guard.values} で 1 つの項目に
     * 並べ、EQ / NE は項目を丸ごと、IN / NOT_IN は {@code Guard.VALUE_SEP} で分けたもの）
     */
    private void guardValues() {
        List<GuardCase> cases = List.of(
                new GuardCase("EQ", List.of("a"), GuardTable.EQ, List.of("a")),
                new GuardCase("EQ", List.of("a|b;c"), GuardTable.EQ, List.of("a|b;c")),
                new GuardCase("EQ", List.of("a", "b"), GuardTable.EQ, List.of("a\u0003b")),
                new GuardCase("EQ", List.of(), GuardTable.EQ, List.of("")),
                new GuardCase("NE", List.of(), GuardTable.NE, List.of("")),
                new GuardCase("NE", List.of("x", "y", "z"), GuardTable.NE, List.of("x\u0003y\u0003z")),
                new GuardCase("EQ", List.of("x\ty"), GuardTable.EQ, List.of("x y")),
                new GuardCase("IN", List.of("a", "b"), GuardTable.IN, List.of("a", "b")),
                new GuardCase("IN", List.of(), GuardTable.IN, List.of("")),
                new GuardCase("IN", List.of(""), GuardTable.IN, List.of("")),
                new GuardCase("IN", List.of("a\u0003b"), GuardTable.IN, List.of("a b")),
                new GuardCase("NI", List.of(), GuardTable.NOT_IN, List.of("")),
                new GuardCase("NI", List.of("1", "2"), GuardTable.NOT_IN, List.of("1", "2")),
                new GuardCase("UK", List.of("a", "b"), GuardTable.OTHER, List.of("a", "b")),
                new GuardCase("MORE", List.of(), GuardTable.OTHER, List.of("")));
        MethodTable methods = new MethodTable();
        StringPoolBuilder pool = new StringPoolBuilder();
        ValueStoreBuilder vb = new ValueStoreBuilder(pool, methods);
        GuardTableBuilder gb = new GuardTableBuilder(pool, vb);
        vb.beginBlock();
        gb.beginBlock();
        vb.node('A', "0", -1, "", -1, "");
        int subject = vb.importHead(0);
        for (int i = 0; i < cases.size(); i++) {
            gb.atom(i, cases.get(i).op(), subject, cases.get(i).values(), "t\t" + i);
        }
        int[] global = new int[cases.size()];
        for (int i = 0; i < cases.size(); i++) {
            global[i] = gb.global(i);
        }
        StringPool strings = pool.freeze(methods);
        ValueStore vs = vb.freeze(strings);
        GuardTable gt = gb.freeze(strings);
        for (int i = 0; i < cases.size(); i++) {
            GuardCase c = cases.get(i);
            String what = "条件 " + c.op() + " " + c.values();
            int g = global[i];
            same(what + " のアトムの数", 1, gt.atomEnd(g) - gt.atomBegin(g));
            int a = gt.atomBegin(g);
            same(what + " の種別", c.expectedOp(), gt.op(a));
            List<String> values = new ArrayList<>();
            for (int k = gt.valueBegin(a); k < gt.valueEnd(a); k++) {
                values.add(gt.value(k));
                same(what + " の値の番号", strings.get(gt.valueId(k)), gt.value(k));
            }
            same(what + " の値", c.expectedValues(), values);
            same(what + " のテキスト（制御文字は空白）", "t " + i, gt.text(a));
            same(what + " の判定される式", "A:0", vs.kind(gt.subject(a)) + ":" + vs.value(gt.subject(a)));
            same(what + " の引数を見る印", true, gt.onParam(g));
        }
    }

    /** 実引数の位置。書き手の書く形で 0〜32767 のものだけ。ほかの組は無いものとする */
    private void positions() {
        String[] rejected = {"01", "+1", "-0", "-1", "١", "32768", "2147483648", "r", "n", "s", "", " 1", "1 "};
        for (String text : rejected) {
            same("位置の文字列 '" + text + "'", -1, ValueStoreBuilder.positionOf(text));
        }
        String[] accepted = {"0", "7", "254", "32767"};
        for (String text : accepted) {
            same("位置の文字列 '" + text + "'", Integer.parseInt(text), ValueStoreBuilder.positionOf(text));
        }
        MethodTable methods = new MethodTable();
        StringPoolBuilder pool = new StringPoolBuilder();
        ValueStoreBuilder vb = new ValueStoreBuilder(pool, methods);
        vb.beginBlock();
        String spec = "0=0,01=0,+1=0,-0=0,١=0,32768=0,r=0,n=0,s=0,=0,7=0,32767=0";
        vb.node('L', "x", -1, "", -1, "");                  // 0
        vb.node('M', "p.Q#m()", -1, spec, 3, "");           // 1
        vb.node('T', "p.Q", -1, spec, -1, "");               // 2
        vb.node('M', "p.Q#n()", -1, "01=0,r=0", 2, "");      // 3（位置として読める実引数が無い）
        int m = vb.importValue(1);
        int t = vb.importValue(2);
        int onlyBad = vb.importValue(3);
        int holder = vb.importArgs(spec);
        int noArgs = vb.importArgs("01=0,r=0,n=0");
        StringPool strings = pool.freeze(methods);
        ValueStore vs = vb.freeze(strings);
        List<Integer> expected = List.of(0, 7, 32767);
        same("M の実引数の位置", expected, positionsOf(vs, m));
        same("M の実引数の数（n=）", 3, vs.argCount(m));
        same("M のレシーバ（r= の鍵の実引数はレシーバにならない）", ValueStore.NONE, vs.receiver(m));
        same("T の実引数の位置", expected, positionsOf(vs, t));
        same("呼び出し箇所の実引数の並びの位置", expected, positionsOf(vs, holder));
        same("呼び出し箇所の実引数の並びの種別", ValueStore.ARG_LIST, vs.kind(holder));
        same("位置として読める実引数が無い M の実引数", List.of(), positionsOf(vs, onlyBad));
        same("位置として読める実引数が無い M の実引数の数", 2, vs.argCount(onlyBad));
        same("位置として読める実引数が無い呼び出し箇所", ValueStore.NONE, noArgs);
    }

    private static List<Integer> positionsOf(ValueStore vs, int ref) {
        List<Integer> out = new ArrayList<>();
        for (int k = vs.argBegin(ref); k < vs.argEnd(ref); k++) {
            out.add(vs.argPos(k));
        }
        return out;
    }

    /** 種別。Origin の種別でない文字は U（実引数の並びの印と取り違えない） */
    private void kinds() {
        MethodTable methods = new MethodTable();
        StringPoolBuilder pool = new StringPoolBuilder();
        ValueStoreBuilder vb = new ValueStoreBuilder(pool, methods);
        vb.beginBlock();
        char[] bad = {ValueStore.ARG_LIST, 'X', 'a', 'é', 'あ', ' '};
        for (char k : bad) {
            vb.node(k, "v", -1, "", -1, "");
        }
        String known = "TAMFLCKVZEU";
        for (char k : known.toCharArray()) {
            vb.node(k, "w", -1, "", -1, "");
        }
        int[] badRefs = new int[bad.length];
        for (int i = 0; i < bad.length; i++) {
            same("ブロックの種別 '" + bad[i] + "'", 'U', vb.localKind(i));
            badRefs[i] = vb.importValue(i);
        }
        int[] knownRefs = new int[known.length()];
        for (int i = 0; i < known.length(); i++) {
            knownRefs[i] = vb.importHead(bad.length + i);
        }
        StringPool strings = pool.freeze(methods);
        ValueStore vs = vb.freeze(strings);
        for (int i = 0; i < bad.length; i++) {
            same("種別 '" + bad[i] + "' の取り込み", "U:v", vs.kind(badRefs[i]) + ":" + vs.value(badRefs[i]));
            same("種別 '" + bad[i] + "' の値の番号（実引数の並びの -1 でない）", true, vs.valueId(badRefs[i]) >= 0);
        }
        for (int i = 0; i < known.length(); i++) {
            same("種別 '" + known.charAt(i) + "' の取り込み", known.charAt(i) + ":w",
                    vs.kind(knownRefs[i]) + ":" + vs.value(knownRefs[i]));
        }
        for (int ref = 0; ref < vs.size(); ref++) {
            checks++;
            if (vs.kind(ref) == ValueStore.ARG_LIST) {
                problems.add("参照 " + ref + " が実引数の並びの種別になっている（N 行から作ったノード）");
            }
        }
    }

    /**
     * メソッドキーと同じ中身の文字列は、取り込んだ順によらずメソッド表の文字列そのものになる
     * （取り込んだ後でメソッド表に足されたキーも。{@link StringPoolBuilder#freeze(MethodTable)}）
     */
    private void sharedMethodKeys() {
        MethodTable methods = new MethodTable();
        int early = methods.intern("p", "p.Q", "early", "");
        StringPoolBuilder pool = new StringPoolBuilder();
        ValueStoreBuilder vb = new ValueStoreBuilder(pool, methods);
        vb.beginBlock();
        // 行から読んだ文字列と同じく、メソッド表のキーとは別の String を渡す
        vb.node('M', new String("p.Q#early()".toCharArray()), -1, "", 0, "");
        vb.node('Z', new String("p.Q#late(int)".toCharArray()), -1, "", -1, "");
        vb.node('L', new String("p.Q#late(int)".toCharArray()), -1, "", -1, "");
        int e = vb.importValue(0);
        int l = vb.importValue(1);
        int lit = vb.importValue(2);
        int late = methods.intern("p", "p.Q", "late", "int");   // 取り込んだ後で足されたメソッド
        StringPool strings = pool.freeze(methods);
        ValueStore vs = vb.freeze(strings);
        checks++;
        if (vs.value(e) != methods.key(early)) {
            problems.add("先にメソッド表にあったキーの値が、メソッド表の文字列を共有していない");
        }
        checks++;
        if (vs.value(l) != methods.key(late)) {
            problems.add("取り込んだ後でメソッド表に足されたキーの値が、メソッド表の文字列を共有していない");
        }
        same("同じ中身の文字列は 1 つの番号", vs.valueId(l), vs.valueId(lit));
        same("メソッドキーの値", "p.Q#late(int)", vs.value(l));
    }
}
