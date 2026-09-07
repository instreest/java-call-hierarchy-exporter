// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.Arrays;

/** growableな int 配列（プリミティブのまま扱うことでボックス化を避ける） */
public final class IntArray {

    private int[] a;
    private int n;

    public IntArray(int capacity) {
        a = new int[Math.max(4, capacity)];
    }

    public void add(int v) {
        if (n == a.length) {
            a = Arrays.copyOf(a, a.length + (a.length >> 1) + 8);
        }
        a[n++] = v;
    }

    /** 重複を許さずに追加する */
    public void addIfAbsent(int v) {
        if (!contains(v)) {
            add(v);
        }
    }

    public boolean contains(int v) {
        for (int i = 0; i < n; i++) {
            if (a[i] == v) {
                return true;
            }
        }
        return false;
    }

    public int get(int i) {
        return a[i];
    }

    public void set(int i, int v) {
        a[i] = v;
    }

    public int size() {
        return n;
    }

    public boolean isEmpty() {
        return n == 0;
    }

    /** 要素数ちょうどの配列にコピーする */
    public int[] toArray() {
        return Arrays.copyOf(a, n);
    }
}
