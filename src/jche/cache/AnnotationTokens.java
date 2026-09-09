// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import java.util.List;

/**
 * 宣言に付いていたアノテーション（H行・D行・V行の annotations 列）。
 *
 * 形式はカンマ区切りで、値を1つだけ持つものは "=値" を付ける。
 * <pre>
 *   org.springframework.stereotype.Service
 *   org.springframework.stereotype.Repository=userDao
 *   org.springframework.beans.factory.annotation.Qualifier=userDao
 * </pre>
 * 「どのアノテーションがDIの印か」「値をどう解釈するか」は読み手の判断
 * （{@link jche.graph.SpringBeans}）で、ここには持たせない。
 *
 * 値は単一メンバ（{@code @Qualifier("userDao")}）と、名前付きの value / name の
 * 文字列だけを残す。それ以外（クラス参照・配列・数値）は「付いていた」事実だけを残す。
 * DIコンテナがBeanを見分けるのに使うのは名前の文字列だけのため。
 */
public final class AnnotationTokens {

    public static final String SEP = ",";
    private static final char VALUE_SEP = '=';

    private AnnotationTokens() {
    }

    /** 1件分の項目を作る。値が無ければFQNだけ */
    public static String token(String annotationFqn, String value) {
        if (value == null || value.isEmpty()) {
            return annotationFqn;
        }
        return annotationFqn + VALUE_SEP + value.replace(SEP, " ").replace(VALUE_SEP, ' ');
    }

    public static String join(List<String> tokens) {
        return String.join(SEP, tokens);
    }

    /** そのアノテーションが付いているか。fqn の完全一致か、単純名の一致で判定する */
    public static boolean has(String annotations, String fqnOrSimpleName) {
        return indexOf(annotations, fqnOrSimpleName) >= 0;
    }

    /**
     * そのアノテーションの値。付いていない、または値が無ければ null。
     * 「付いているが値なし」と「付いていない」を区別できるよう {@link #has} と分ける
     */
    public static String valueOf(String annotations, String fqnOrSimpleName) {
        int i = indexOf(annotations, fqnOrSimpleName);
        if (i < 0) {
            return null;
        }
        String entry = entryAt(annotations, i);
        int eq = entry.indexOf(VALUE_SEP);
        return (eq < 0) ? null : entry.substring(eq + 1);
    }

    /** FQNの最後のドットより後ろ（入れ子のアノテーションは '$' より後ろ） */
    public static String simpleNameOf(String fqn) {
        int cut = Math.max(fqn.lastIndexOf('.'), fqn.lastIndexOf('$'));
        return (cut < 0) ? fqn : fqn.substring(cut + 1);
    }

    /** 一致する項目の開始位置。無ければ -1 */
    private static int indexOf(String annotations, String fqnOrSimpleName) {
        if (annotations == null || annotations.isEmpty() || fqnOrSimpleName.isEmpty()) {
            return -1;
        }
        int start = 0;
        while (start < annotations.length()) {
            String entry = entryAt(annotations, start);
            int eq = entry.indexOf(VALUE_SEP);
            String fqn = (eq < 0) ? entry : entry.substring(0, eq);
            if (fqn.equals(fqnOrSimpleName) || simpleNameOf(fqn).equals(fqnOrSimpleName)) {
                return start;
            }
            start += entry.length() + 1;
        }
        return -1;
    }

    /** 指定位置から次のカンマまで */
    private static String entryAt(String annotations, int start) {
        int end = annotations.indexOf(SEP, start);
        return (end < 0) ? annotations.substring(start) : annotations.substring(start, end);
    }
}
