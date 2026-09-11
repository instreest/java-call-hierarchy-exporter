// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.util;

/** 完全修飾名の切り分け。何箇所にも同じ 1 行が書かれていたのでここにまとめる */
public final class Names {

    private Names() {
    }

    /** 最後のドットより後ろ（{@code jp.co.Outer.Inner} → {@code Inner}）。ドットが無ければそのまま */
    public static String simpleOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return (dot < 0) ? fqn : fqn.substring(dot + 1);
    }

    /** 最後のドットより前（{@code jp.co.Outer} → {@code jp.co}）。ドットが無ければ空文字 */
    public static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return (dot < 0) ? "" : fqn.substring(0, dot);
    }

    /** 整数として読めなければ fallback */
    public static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
