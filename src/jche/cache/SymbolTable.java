// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ブロック内のメソッドの記号表（S 行）。
 *
 * <h2>何のためにあるか</h2>
 * 宣言・上書き・戻り値・呼び出し元・呼び出し先はどれもメソッドを指す。以前はそのたびに 4 列
 * （pkg・typeFqn・名前・引数）を繰り返し書いていた。同じメソッドは 1 つのブロックの中で何度も
 * 現れる（呼び出し元は呼び出しの数だけ）ので、ブロックの先頭に 1 回だけ書き、行からは番号で指す。
 *
 * <h2>番号の決まり</h2>
 * <ul>
 *   <li>番号はブロックの中だけで通じる（0 から詰めて振る）。ブロックをまるごと書き写す差分更新でも
 *       参照が壊れない</li>
 *   <li>行を書く順（R・D・O・C/U・M・A）に、初めて現れた順に振る。同じ解析結果からは同じ番号になる</li>
 *   <li>メソッドが無い（呼び出し元を特定できない U 行）は {@link #NO_SYMBOL}</li>
 * </ul>
 * 読み手は S 行の時点ではメソッドを ID 化しない（{@code jche.graph.MethodTable} の ID は
 * 初めて intern した順なので、以前と同じく参照する行の順に intern する）。
 *
 * <p>書き手は {@link #idOf} で番号を取りながら行を組み、最後に {@link #rows} で S 行を得る
 * （S 行は参照する行より前に置くので、ブロックをメモリ上で組んでから書く）。
 * 読み手は {@link Reader} に S 行を積み、{@link Reader#array} で引く。
 */
public final class SymbolTable {

    /** メソッドが無いことを表す記号 */
    public static final int NO_SYMBOL = -1;

    private final Map<MethodRef, Integer> ids = new HashMap<>();
    private final List<MethodRef> refs = new ArrayList<>();

    /** メソッドの番号。初めてなら次の番号を振る。null なら {@link #NO_SYMBOL} */
    public int idOf(MethodRef ref) {
        if (ref == null) {
            return NO_SYMBOL;
        }
        Integer id = ids.get(ref);
        if (id == null) {
            id = refs.size();
            ids.put(ref, id);
            refs.add(ref);
        }
        return id;
    }

    /** {@link #idOf} を列の文字列にしたもの */
    public String columnOf(MethodRef ref) {
        return String.valueOf(idOf(ref));
    }

    /** S 行（番号の順） */
    public List<String> rows() {
        List<String> rows = new ArrayList<>(refs.size());
        for (int i = 0; i < refs.size(); i++) {
            MethodRef r = refs.get(i);
            rows.add(CacheFormat.joinRow(String.valueOf(CacheFormat.ROW_SYMBOL), String.valueOf(i),
                    r.pkg(), r.typeFqn(), r.name(), r.paramSig()));
        }
        return rows;
    }

    /**
     * 記号の列が指すメソッド。{@link #NO_SYMBOL}・数字でない・範囲の外・S 行が読めなかった番号なら null。
     * 壊れた参照を「メソッドが無い」と区別したいときは {@link #refsInRange} で先に確かめる
     */
    public static MethodRef resolve(MethodRef[] symbols, String column) {
        int id;
        try {
            id = Integer.parseInt(column);
        } catch (NumberFormatException e) {
            return null;
        }
        return (id >= 0 && id < symbols.length) ? symbols[id] : null;
    }

    /** 記号の列が {@link #NO_SYMBOL}（メソッドが無い）か */
    public static boolean isNone(String column) {
        return String.valueOf(NO_SYMBOL).equals(column);
    }

    /**
     * 行の記号の列（{@link CacheFormat#symbolColumnsOf}）がどれも記号表の読めた S 行を指すか
     * （{@link #NO_SYMBOL} は指せているとみなす。列が無い行も、列の数の検査は各 record に任せて指せているとみなす）。
     * 範囲の外の番号と、範囲の中でも S 行が読めなかった番号（{@link Reader#add} が null を積んだもの）は、
     * どちらも壊れた参照として false を返す。
     *
     * <p>ブロックの検査値が合っていれば外れることはない。それでも読み手が確かめるのは、
     * 壊れた番号を黙って「メソッドが無い」と読むと、呼び出しが静かに消えるため
     *
     * @param symbols ブロックの記号表（{@link Reader#array}）
     */
    public static boolean refsInRange(String[] cols, MethodRef[] symbols) {
        if (cols.length == 0 || cols[0].isEmpty()) {
            return true;
        }
        for (int at : CacheFormat.symbolColumnsOf(cols[0].charAt(0))) {
            if (at >= cols.length) {
                continue;
            }
            int id;
            try {
                id = Integer.parseInt(cols[at]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (id != NO_SYMBOL && (id < 0 || id >= symbols.length || symbols[id] == null)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 読み手の側。ブロックの S 行を積み、行からの参照を引く配列にする。
     * ブロックが変わるたびに {@link #clear} する。
     */
    public static final class Reader {

        private static final MethodRef[] EMPTY = new MethodRef[0];

        private final List<MethodRef> list = new ArrayList<>();
        private MethodRef[] array = EMPTY;

        /** 次のブロックのために空にする */
        public void clear() {
            list.clear();
            array = EMPTY;
        }

        /**
         * S 行を 1 行積む。番号は並びの位置とみなす（書き手は 0 から詰めて並べる）。
         * 列が足りなければ null を積む（検査値で捕まるはずの壊れ方。{@link #refsInRange} が
         * 範囲の外の番号と同じく壊れた参照として扱う）
         */
        public void add(String[] cols) {
            list.add(MethodRef.fromColumns(cols, 2));
            array = null;
        }

        /** ここまでに積んだ記号（番号で引く配列） */
        public MethodRef[] array() {
            if (array == null) {
                array = list.toArray(EMPTY);
            }
            return array;
        }
    }
}
