// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.Arrays;
import java.util.HashMap;

import jche.cache.Origin;
import jche.cache.ValueNode;

/**
 * {@link ValueStore} を組む（{@link CallGraphBuilder} のスキャンの中で使う）。
 *
 * <h2>ブロックの番号からグラフ全体の参照へ</h2>
 * キャッシュのノード番号はブロック（ファイル）の中だけで通じる。スキャンはブロックごとに
 * N 行を {@link #node} で受け取って手元に溜め（{@link #beginBlock} で捨てる）、戻り値・代入・条件・
 * 呼び出し箇所の行がノードを指したときに、そのノードと子を表へ取り込む（{@link #importValue} など）。
 * どこからも指されないノードは取り込まない。「ブロックの先頭の参照 + 番号」で一括に写さないのは、
 * 取り込むときに形をそろえ（下の表）、葉をグラフ全体でまとめるため。子を親より先に取り込むので、
 * 子の参照は必ず親の参照より小さい。
 *
 * <h2>形のそろえ方（以前の読み手が受け取っていた出所の文字列と同じ中身にする）</h2>
 * 読み手を文字列から表へ移すとき（{@code docs/cache-unification-qa.md} の「読み手が値の表を読む」）、
 * 以前の組み直しが文字列に書き出していた項目と同じものだけを持たせた。種別ごとの項目の有無が
 * 読み手の分かれ道（{@code n=} の有無など）を決めるので、それを変えないため。
 * <pre>
 *   T                          実引数（あるものだけ）。n= r= s= は付けない
 *   M                          実引数、n=（数が分かれば）、r=（レシーバがあれば）、s=（空でなければ）
 *   Z でレシーバの番号がある    M と同じ（番号がブロックの外を指していても。以前の組み直しは Z と M を区別しない）
 *   それ以外（A E F L K V C U、レシーバの無い Z）   葉（頭だけ）
 *   項目が 1 つも残らなかったノード                  葉（ほかの葉と同じくまとめる）
 * </pre>
 * ブロックの外を指す子、親より小さくない子は、無いものとして扱う（以前の組み直しでも外を指す子は現れない）。
 *
 * <h2>書き手が作らない行の扱い（手で書き換えたキャッシュ）</h2>
 * 次のものは書き手（{@code jche.analysis.ValueGraph}）が作らない。どれも「無い」「追跡できない」の側に倒す
 * （読み手は値の分からないものを判定に使わない。分からないことで呼び出しを落とす読み手は無い）。
 * <ul>
 *   <li>実引数の位置 … 書き手は {@code 0} 以上の数を {@link String#valueOf(int)} の形で書く。その形
 *       （{@code String.valueOf(位置).equals(書かれた文字列)}）で、{@code 0}〜{@link Short#MAX_VALUE} のものだけを
 *       位置とし、ほかの組は無いものとする。{@code 01}・{@code +1}・{@code -0}・ASCII でない数字・{@code 32768} 以上
 *       （Java の引数は 255 個まで）と、{@code r}・{@code n}・{@code s}・空の鍵がこれに当たる。以前の組み直しは
 *       鍵の文字列をそのまま写すので、{@code r=番号} がレシーバに、{@code 01=番号} がどの位置でもない実引数に
 *       読まれていたが、書き手の行には現れないので出力は変わらない（test/dataflow の StoreUnitCheck が
 *       この扱いそのものを確かめている）</li>
 *   <li>種別 … {@link Origin} の種別（T A M F L C K V Z E U）でない文字は「追跡できない」（U）にする。
 *       実引数の並びの印（{@link ValueStore#ARG_LIST}）と取り違えないため</li>
 * </ul>
 *
 * <h2>まとめ方</h2>
 * <ul>
 *   <li>文字列 … グラフ全体で 1 つ（{@link StringPoolBuilder}）。メソッド表にあるメソッドキーと同じ中身の文字列は、
 *       作り終えるときにメソッド表の文字列そのものに置き換える（{@link StringPoolBuilder#freeze(MethodTable)}。
 *       同じ文字列を 2 つ持たない。取り込んだときにまだメソッド表に無かったキーも、この時点ではそろっている）</li>
 *   <li>葉 … グラフ全体で、種別と値の組ごとに 1 つ（開番地法の表。{@link #freeze} で捨てる）</li>
 *   <li>項目を持つノード … ブロックの中では書き手がまとめた番号のまま 1 つ。ブロックをまたいではまとめない</li>
 *   <li>呼び出し箇所の実引数の並び … ブロックの中で、列の文字列が同じなら 1 つ</li>
 * </ul>
 * 構築中は表を読み返さない（葉は表の索引で、ほかはブロックの番号の対応で引く）。
 */
final class ValueStoreBuilder {

    /** ブロックの番号がまだ取り込まれていない印 */
    private static final int UNSET = -2;

    private final StringPoolBuilder pool;
    private final MethodTable methods;

    // --- 表（グラフ全体。伸ばしながら足す） ---
    private byte[] kind = new byte[1024];
    private int[] value = new int[1024];
    /** 長さは常に「ノード数 + 1」以上。entryOff[ノード数] が項目の数 */
    private int[] entryOff = new int[1025];
    private short[] entryKey = new short[1024];
    private int[] entryVal = new int[1024];
    private int size;
    private int entries;

    // --- 葉の索引（種別 << 32 | 値の番号 -> 参照）。開番地法、埋まりは半分まで ---
    private long[] leafKeys = new long[1024];
    /** 参照 + 1（0 は空き） */
    private int[] leafRefs = new int[1024];
    private int leafCount;

    // --- 今のブロックの N 行（番号 = 並びの位置） ---
    private byte[] sKind = new byte[64];
    private String[] sValue = new String[64];
    private int[] sRecv = new int[64];
    private int[] sArgCount = new int[64];
    private String[] sStatic = new String[64];
    /** 実引数の組（位置, 番号）の範囲。sArgOff[番号] から sArgOff[番号 + 1] の手前まで */
    private int[] sArgOff = new int[65];
    private int[] sArgPos = new int[64];
    private int[] sArgLocal = new int[64];
    /** ブロックの番号 -&gt; 参照（{@link #UNSET} はまだ） */
    private int[] l2g = new int[64];
    private int n;
    private int sArgs;
    /** 呼び出し箇所の実引数の列の文字列 -&gt; 参照（ブロックの中だけ。作り終えるときに表ごと捨てる） */
    private HashMap<String, Integer> holders = new HashMap<>();
    /** 取り込みの明示的なスタック（深い入れ子で Java のスタックを使い切らない） */
    private int[] stack = new int[64];

    ValueStoreBuilder(StringPoolBuilder pool, MethodTable methods) {
        this.pool = pool;
        this.methods = methods;
    }

    /**
     * 種別の文字。{@link Origin} の種別でないもの（実引数の並びの印 {@link ValueStore#ARG_LIST}、ASCII でない文字を
     * 含む）は、壊れた行なので「追跡できない」（{@link Origin#UNKNOWN}）にする
     */
    static char knownKind(char k) {
        return switch (k) {
            case Origin.NEW, Origin.PARAM, Origin.RETURN, Origin.FIELD, Origin.OTHER_FIELD, Origin.LITERAL,
                 Origin.REFLECT, Origin.CLASS, Origin.CONST, Origin.FUNCTIONAL, Origin.CAPTURED, Origin.UNKNOWN -> k;
            default -> Origin.UNKNOWN;
        };
    }

    /** 新しいブロックを始める（前のブロックの N 行と対応は捨てる） */
    void beginBlock() {
        Arrays.fill(sValue, 0, n, null);
        Arrays.fill(sStatic, 0, n, null);
        n = 0;
        sArgs = 0;
        sArgOff[0] = 0;
        holders.clear();
    }

    /**
     * N 行を 1 つ受け取る。番号は受け取った順（{@code 0, 1, 2, …}）。
     *
     * @param args 実引数（{@code 位置=番号} のカンマ区切り）。ここで組に分けておく
     */
    void node(char k, String v, int recv, String args, int argCount, String staticRecv) {
        int local = n;
        if (local == sKind.length) {
            growBlock();
        }
        sKind[local] = (byte) knownKind(k);
        sValue[local] = (v == null) ? "" : v;
        sRecv[local] = recv;
        sArgCount[local] = argCount;
        sStatic[local] = (staticRecv == null) ? "" : staticRecv;
        l2g[local] = UNSET;
        parseArgs(args);
        n++;
        sArgOff[n] = sArgs;
    }

    /** {@code 位置=番号} のカンマ区切りを組にして足す。形の崩れた組は足さない */
    private void parseArgs(String args) {
        if (args == null || args.isEmpty()) {
            return;
        }
        int start = 0;
        while (start <= args.length()) {
            int end = args.indexOf(ValueNode.ARG_SEP, start);
            if (end < 0) {
                end = args.length();
            }
            int eq = args.indexOf('=', start);
            if (eq >= 0 && eq < end) {
                int pos = positionOf(args.substring(start, eq));
                if (pos >= 0) {
                    if (sArgs == sArgPos.length) {
                        sArgPos = Arrays.copyOf(sArgPos, sArgs * 2);
                        sArgLocal = Arrays.copyOf(sArgLocal, sArgs * 2);
                    }
                    sArgPos[sArgs] = pos;
                    sArgLocal[sArgs] = ValueNode.intOf(args.substring(eq + 1, end), ValueNode.NONE);
                    sArgs++;
                }
            }
            if (end >= args.length()) {
                break;
            }
            start = end + 1;
        }
    }

    /**
     * 実引数の位置。書き手の書く形（{@link String#valueOf(int)} そのもの）で {@code 0}〜{@link Short#MAX_VALUE} の
     * ものだけを位置とする。それ以外（{@code 01}・{@code +1}・{@code r} など。クラスの説明）は -1
     */
    static int positionOf(String text) {
        int pos = ValueNode.intOf(text, -1);
        return (pos >= 0 && pos <= Short.MAX_VALUE && String.valueOf(pos).equals(text)) ? pos : -1;
    }

    private void growBlock() {
        int cap = sKind.length * 2;
        sKind = Arrays.copyOf(sKind, cap);
        sValue = Arrays.copyOf(sValue, cap);
        sRecv = Arrays.copyOf(sRecv, cap);
        sArgCount = Arrays.copyOf(sArgCount, cap);
        sStatic = Arrays.copyOf(sStatic, cap);
        sArgOff = Arrays.copyOf(sArgOff, cap + 1);
        l2g = Arrays.copyOf(l2g, cap);
    }

    /** ブロックの番号がノードを指しているか */
    private boolean inBlock(int local) {
        return local >= 0 && local < n;
    }

    /** そのブロックの番号のノードの種別。ブロックの外なら {@link Origin#UNKNOWN} */
    char localKind(int local) {
        return inBlock(local) ? (char) (sKind[local] & 0xFF) : Origin.UNKNOWN;
    }

    /** 参照のノードの種別（構築の途中で読む）。{@link ValueStore#NONE} なら {@link Origin#UNKNOWN} */
    char kindOf(int ref) {
        return (ref < 0 || ref >= size) ? Origin.UNKNOWN : (char) (kind[ref] & 0xFF);
    }

    /** ノードの頭（種別と値の葉）。値を丸ごと使わない読み手（フィールドへの代入・条件の subject）のため */
    int importHead(int local) {
        if (!inBlock(local)) {
            return ValueStore.NONE;
        }
        return leaf((char) (sKind[local] & 0xFF), pool.intern(sValue[local]));
    }

    /** ノードを子も含めて取り込み、その参照を返す。ブロックの外を指していれば {@link ValueStore#NONE} */
    int importValue(int local) {
        if (!inBlock(local)) {
            return ValueStore.NONE;
        }
        if (l2g[local] != UNSET) {
            return l2g[local];
        }
        int top = 0;
        stack[top++] = local;
        while (top > 0) {
            int x = stack[top - 1];
            if (l2g[x] != UNSET) {
                top--;
                continue;
            }
            boolean ready = true;
            char k = (char) (sKind[x] & 0xFF);
            if (hasArgs(k, x)) {
                for (int a = sArgOff[x]; a < sArgOff[x + 1]; a++) {
                    int c = sArgLocal[a];
                    if (isChild(c, x) && l2g[c] == UNSET) {
                        if (top == stack.length) {
                            stack = Arrays.copyOf(stack, top * 2);
                        }
                        stack[top++] = c;
                        ready = false;
                    }
                }
                int r = sRecv[x];
                if (k != Origin.NEW && isChild(r, x) && l2g[r] == UNSET) {
                    if (top == stack.length) {
                        stack = Arrays.copyOf(stack, top * 2);
                    }
                    stack[top++] = r;
                    ready = false;
                }
            }
            if (ready) {
                l2g[x] = build(x, k);
                top--;
            }
        }
        return l2g[local];
    }

    /**
     * 呼び出し箇所の実引数（{@code 位置=番号} のカンマ区切り）を取り込み、実引数の並びのノードの参照を返す。
     * 取り込めた実引数が 1 つも無ければ {@link ValueStore#NONE}（「実引数の値なし」）
     */
    int importArgs(String args) {
        if (args == null || args.isEmpty()) {
            return ValueStore.NONE;
        }
        Integer known = holders.get(args);
        if (known != null) {
            return known;
        }
        int mark = sArgs;
        parseArgs(args);   // 組を一時的にブロックの組の後ろに置く（下で元に戻す）
        // 子を先にすべて取り込む。取り込みは表にノードと項目を足すので、並びの項目を足し始めてから
        // 取り込むと、子の項目と並びの項目が混ざる。取り込んだ参照は一時的な組の番号の上に書く
        for (int a = mark; a < sArgs; a++) {
            sArgLocal[a] = importValue(sArgLocal[a]);
        }
        int begin = entries;
        for (int a = mark; a < sArgs; a++) {
            if (sArgLocal[a] != ValueStore.NONE) {
                addEntry((short) sArgPos[a], sArgLocal[a]);
            }
        }
        sArgs = mark;
        int ref = (entries == begin) ? ValueStore.NONE : appendNode(ValueStore.ARG_LIST, -1);
        holders.put(args, ref);
        return ref;
    }

    /** 実引数・レシーバの項目を持ちうる種別か（T・M と、レシーバの番号がある Z。クラスの説明の表） */
    private boolean hasArgs(char k, int local) {
        return k == Origin.NEW || k == Origin.RETURN
                || (k == Origin.FUNCTIONAL && sRecv[local] != ValueNode.NONE);
    }

    /** その番号が、親 {@code parent} の子として使えるか（ブロックの中で、親より前） */
    private static boolean isChild(int child, int parent) {
        return child >= 0 && child < parent;
    }

    /** 子を取り込み済みのノードを 1 つ作る */
    private int build(int x, char k) {
        int valueId = pool.intern(sValue[x]);
        if (!hasArgs(k, x)) {
            return leaf(k, valueId);
        }
        int begin = entries;
        for (int a = sArgOff[x]; a < sArgOff[x + 1]; a++) {
            int c = sArgLocal[a];
            if (isChild(c, x)) {
                addEntry((short) sArgPos[a], l2g[c]);
            }
        }
        if (k != Origin.NEW) {
            if (sArgCount[x] >= 0) {
                addEntry(ValueStore.ENTRY_COUNT, sArgCount[x]);
            }
            if (isChild(sRecv[x], x)) {
                addEntry(ValueStore.ENTRY_RECEIVER, l2g[sRecv[x]]);
            }
            if (!sStatic[x].isEmpty()) {
                addEntry(ValueStore.ENTRY_STATIC, pool.intern(sStatic[x]));
            }
        }
        if (entries == begin) {
            return leaf(k, valueId);
        }
        return appendNode(k, valueId);
    }

    /** 葉（項目の無いノード）。種別と値が同じ葉は 1 つだけ */
    private int leaf(char k, int valueId) {
        long key = ((long) k << 32) | (valueId & 0xFFFFFFFFL);
        int mask = leafKeys.length - 1;
        int i = mixLong(key) & mask;
        while (leafRefs[i] != 0) {
            if (leafKeys[i] == key) {
                return leafRefs[i] - 1;
            }
            i = (i + 1) & mask;
        }
        int ref = appendNode(k, valueId);
        leafKeys[i] = key;
        leafRefs[i] = ref + 1;
        leafCount++;
        if (leafCount * 2 > leafKeys.length) {
            rehashLeaves();
        }
        return ref;
    }

    private void rehashLeaves() {
        long[] keys = new long[leafKeys.length * 2];
        int[] refs = new int[keys.length];
        int mask = keys.length - 1;
        for (int j = 0; j < leafKeys.length; j++) {
            if (leafRefs[j] == 0) {
                continue;
            }
            int i = mixLong(leafKeys[j]) & mask;
            while (refs[i] != 0) {
                i = (i + 1) & mask;
            }
            keys[i] = leafKeys[j];
            refs[i] = leafRefs[j];
        }
        leafKeys = keys;
        leafRefs = refs;
    }

    private static int mixLong(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }

    private void addEntry(short k, int v) {
        if (entries == entryKey.length) {
            entryKey = Arrays.copyOf(entryKey, entries * 2);
            entryVal = Arrays.copyOf(entryVal, entries * 2);
        }
        entryKey[entries] = k;
        entryVal[entries] = v;
        entries++;
    }

    /** 表にノードを足す。項目は直前に {@link #addEntry} で足したもの（前のノードの終わりから今まで） */
    private int appendNode(char k, int valueId) {
        int ref = size;
        if (ref == kind.length) {
            kind = Arrays.copyOf(kind, ref * 2);
            value = Arrays.copyOf(value, ref * 2);
            entryOff = Arrays.copyOf(entryOff, ref * 2 + 1);
        }
        kind[ref] = (byte) k;
        value[ref] = valueId;
        size++;
        entryOff[size] = entries;
        return ref;
    }

    /**
     * 作り終える。表はちょうどの長さにし、構築のときだけ使う索引とブロックの手元（葉の索引・
     * ブロックの N 行・番号の対応・実引数の並びの覚え書き）は捨てる。覚え書きは clear せずに作り直す
     * （clear は一番大きいブロックの大きさの表を残し、エッジ配列を確保する間も持ち続けてしまう）
     *
     * @param strings 作り終えた文字列の置き場（{@link StringPoolBuilder#freeze(MethodTable)}。条件の表と共有する）
     */
    ValueStore freeze(StringPool strings) {
        ValueStore store = new ValueStore(strings, methods, Arrays.copyOf(kind, size),
                Arrays.copyOf(value, size), Arrays.copyOf(entryOff, size + 1),
                Arrays.copyOf(entryKey, entries), Arrays.copyOf(entryVal, entries));
        kind = new byte[0];
        value = new int[0];
        entryOff = new int[1];
        entryKey = new short[0];
        entryVal = new int[0];
        leafKeys = new long[0];
        leafRefs = new int[0];
        sKind = new byte[0];
        sValue = new String[0];
        sRecv = new int[0];
        sArgCount = new int[0];
        sStatic = new String[0];
        sArgOff = new int[1];
        sArgPos = new int[0];
        sArgLocal = new int[0];
        l2g = new int[0];
        stack = new int[0];
        holders = new HashMap<>();
        n = 0;
        return store;
    }
}
