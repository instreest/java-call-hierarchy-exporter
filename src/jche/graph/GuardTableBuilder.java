// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import jche.cache.Guard;
import jche.cache.Origin;

/**
 * {@link GuardTable} を組む（{@link CallGraphBuilder} のスキャンの中で使う）。
 *
 * <p>ブロックの G 行のアトムを {@link #atom} で受け取って手元に溜め（{@link #beginBlock} で捨てる）、
 * C 行・U 行がブロックのガード番号で指したときに、そのガードを表へ写してグラフ全体の番号を振る
 * （{@link #global}。初めて指された順。同じブロックの同じガードは同じ番号）。ブロックをまたいでは
 * まとめない。値と条件式のテキストはノードの値と同じ文字列の置き場に置く。
 *
 * <h2>値の持ち方（以前の読み手が見ていたとおり）</h2>
 * 値は切り詰めない。そのうえで、以前の読み手（{@link GuardEvaluator} が受け取る guard の文字列。
 * {@code CallGraphBuilder.BlockGuards#guardOf}）が見ていたとおりの並びにする（{@link #valuesAsRead}）。
 * 以前の読み手は G 行の値を {@link Guard#values} で 1 つの項目に並べ（制御文字は空白に置き換わる）、
 * EQ / NE はその項目を丸ごと 1 つの値として、IN / NOT_IN は {@link Guard#VALUE_SEP} で分けて読んでいた。
 * <pre>
 *   EQ / NE          値 1 つ（項目そのもの。値が 2 つある行は区切りでつないだ 1 つ、値の無い行は空文字 1 つ）
 *   IN / NOT_IN      項目を区切りで分けたもの（値の無い行は空文字 1 つ）
 *   知らない種別      IN と同じ形（判定に使わないのでどちらでもよいが、そろえておく）
 * </pre>
 * 書き手は EQ / NE に値を 1 つ、IN / NOT_IN に 1 つ以上、制御文字を含まない値だけを書く
 * （{@code jche.analysis.GuardCollector}）ので、書き手が作った行では G 行の値そのものになる。
 * 違いが出るのは手で書き換えたキャッシュだけで、そのときも以前の読み手と同じ値を持つ。
 * 条件式のテキストも同じく {@link Guard#clean} を通す（書き手は通してから書く）。判定される式は値の表の葉を
 * そのまま指す（以前の読み手は頭の文字列にも {@link Guard#clean} を通していたが、書き手が subject に置く
 * A・V のノードの値は位置の数か制御文字を含まない定数なので、書き手が作った行では同じになる）。
 */
final class GuardTableBuilder {

    /** ブロックのアトム 1 つ（判定される式は取り込んだ葉の参照） */
    private record Atom(byte op, int subject, List<String> values, String text) {
    }

    private final StringPoolBuilder pool;
    private final ValueStoreBuilder values;

    // --- 表（グラフ全体） ---
    private int[] atomOff = new int[65];
    private byte[] op = new byte[64];
    private int[] subject = new int[64];
    private int[] text = new int[64];
    private int[] valueOff = new int[65];
    private int[] valueIds = new int[64];
    private final BitSet onParam = new BitSet();
    private int guards;
    private int atoms;
    private int valueCount;

    // --- 今のブロック（作り終えるときに作り直して捨てる） ---
    private List<List<Atom>> block = new ArrayList<>();
    /** ブロックのガード番号 -&gt; グラフ全体の番号（まだなら {@link GuardTable#NONE}） */
    private int[] globalOf = new int[16];

    GuardTableBuilder(StringPoolBuilder pool, ValueStoreBuilder values) {
        this.pool = pool;
        this.values = values;
    }

    /** 新しいブロックを始める（前のブロックのガードは捨てる） */
    void beginBlock() {
        block.clear();
    }

    /**
     * アトムを 1 つ足す。ブロックのガード番号は、今のガード（続きのアトム）か次のガード（新しい番号）の
     * どちらかであること（呼び出し側 {@code CallGraphBuilder.BlockGuards#add} が確かめてから渡す）
     *
     * @param subjectRef 判定される式の葉（{@link ValueStoreBuilder#importHead}）
     */
    void atom(int localGuard, String opText, int subjectRef, List<String> atomValues, String atomText) {
        if (localGuard == block.size()) {
            block.add(new ArrayList<>(2));
            if (localGuard == globalOf.length) {
                globalOf = Arrays.copyOf(globalOf, localGuard * 2);
            }
            globalOf[localGuard] = GuardTable.NONE;
        }
        byte code = GuardTable.opOf(opText);
        block.get(localGuard).add(new Atom(code, subjectRef, valuesAsRead(code, atomValues), Guard.clean(atomText)));
    }

    /**
     * G 行の値を、以前の読み手が見ていたとおりの並びにする（クラスの説明の表）。
     * 以前の読み手の読み方（{@link Guard#values} で並べてから、EQ / NE は丸ごと、ほかは {@link Guard#valuesOf}
     * で分ける）をそのまま使う
     */
    static List<String> valuesAsRead(byte op, List<String> values) {
        String field = Guard.values(values);
        if (op == GuardTable.EQ || op == GuardTable.NE) {
            return List.of(field);
        }
        return List.of(Guard.valuesOf(field));
    }

    /** ブロックのガード番号の、グラフ全体の番号。条件なし（-1）・ブロックの外なら {@link GuardTable#NONE} */
    int global(int localGuard) {
        if (localGuard < 0 || localGuard >= block.size()) {
            return GuardTable.NONE;
        }
        if (globalOf[localGuard] != GuardTable.NONE) {
            return globalOf[localGuard];
        }
        int g = guards;
        for (Atom a : block.get(localGuard)) {
            appendAtom(a);
            if (values.kindOf(a.subject()) == Origin.PARAM) {
                onParam.set(g);
            }
        }
        guards++;
        if (guards + 1 > atomOff.length) {
            atomOff = Arrays.copyOf(atomOff, atomOff.length * 2);
        }
        atomOff[guards] = atoms;
        globalOf[localGuard] = g;
        return g;
    }

    private void appendAtom(Atom a) {
        if (atoms == op.length) {
            int cap = atoms * 2;
            op = Arrays.copyOf(op, cap);
            subject = Arrays.copyOf(subject, cap);
            text = Arrays.copyOf(text, cap);
            valueOff = Arrays.copyOf(valueOff, cap + 1);
        }
        op[atoms] = a.op();
        subject[atoms] = a.subject();
        text[atoms] = pool.intern(a.text());
        for (String v : a.values()) {
            if (valueCount == valueIds.length) {
                valueIds = Arrays.copyOf(valueIds, valueCount * 2);
            }
            valueIds[valueCount++] = pool.intern(v);
        }
        atoms++;
        valueOff[atoms] = valueCount;
    }

    /** 作り終える。表はちょうどの長さにし、ブロックの手元は捨てる（clear せずに作り直し、配列ごと手放す） */
    GuardTable freeze(StringPool strings) {
        GuardTable table = new GuardTable(strings, Arrays.copyOf(atomOff, guards + 1),
                Arrays.copyOf(op, atoms), Arrays.copyOf(subject, atoms), Arrays.copyOf(text, atoms),
                Arrays.copyOf(valueOff, atoms + 1), Arrays.copyOf(valueIds, valueCount),
                (BitSet) onParam.clone());
        block = new ArrayList<>();
        globalOf = new int[0];
        return table;
    }
}
