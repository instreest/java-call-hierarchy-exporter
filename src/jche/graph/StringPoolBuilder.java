// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.Arrays;

/**
 * {@link StringPool} を組む（構築のときだけ使う）。
 *
 * <p>同じ中身の文字列に同じ番号を返すための索引は、番号の配列を使った開番地法の表にする
 * （文字列 1 つあたり 8 バイトほど。{@code HashMap<String, Integer>} だと 1 つあたり 48 バイトほどになる）。
 * 表は {@link #freeze} で捨てる。
 */
final class StringPoolBuilder {

    private String[] strings = new String[256];
    private int size;
    /** 開番地法の表。値は番号 + 1（0 は空き）。埋まりは半分まで */
    private int[] table = new int[512];

    /** その文字列の番号。初めてなら足す */
    int intern(String s) {
        int mask = table.length - 1;
        int i = mix(s.hashCode()) & mask;
        while (true) {
            int slot = table[i];
            if (slot == 0) {
                break;
            }
            if (strings[slot - 1].equals(s)) {
                return slot - 1;
            }
            i = (i + 1) & mask;
        }
        int id = size;
        if (id == strings.length) {
            strings = Arrays.copyOf(strings, id * 2);
        }
        strings[id] = s;
        size++;
        table[i] = id + 1;
        if (size * 2 > table.length) {
            rehash();
        }
        return id;
    }

    /** 番号の文字列（構築の途中で読む。範囲の外なら null） */
    String get(int id) {
        return (id < 0 || id >= size) ? null : strings[id];
    }

    int size() {
        return size;
    }

    private void rehash() {
        int[] next = new int[table.length * 2];
        int mask = next.length - 1;
        for (int id = 0; id < size; id++) {
            int i = mix(strings[id].hashCode()) & mask;
            while (next[i] != 0) {
                i = (i + 1) & mask;
            }
            next[i] = id + 1;
        }
        table = next;
    }

    /** 下位ビットに偏りが出ないよう混ぜる（String の hashCode は下位ビットが似やすい） */
    private static int mix(int h) {
        h *= 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    /**
     * 作り終える。索引は捨て、文字列の配列はちょうどの長さにする。
     *
     * <p>メソッド表のキーと同じ中身の文字列は、メソッド表の文字列そのものに置き換える（1 回の走査。
     * M・Z の値はメソッドキーなので、同じ文字列を 2 つ持たないため）。取り込んだ時点ではまだメソッド表に
     * 無かったキー（C 行より前の R 行が指すメソッドなど）も、スキャンを終えたこの時点ではそろっている。
     * 中身は同じなので番号は変わらない
     *
     * @param methods スキャンを終えたメソッド表
     */
    StringPool freeze(MethodTable methods) {
        table = new int[0];   // 索引は先に手放す（下の配列の写しと同時に持たない）
        for (int i = 0; i < size; i++) {
            int id = methods.idOf(strings[i]);
            if (id >= 0) {
                strings[i] = methods.key(id);
            }
        }
        StringPool pool = new StringPool(Arrays.copyOf(strings, size));
        strings = new String[0];
        size = 0;
        return pool;
    }
}
