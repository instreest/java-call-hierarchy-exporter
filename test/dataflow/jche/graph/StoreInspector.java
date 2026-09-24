// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * 検査用: 値の表と、グラフのパッケージの中だけで見える事実を test/dataflow の検査に見せる
 * （test/dataflow/run.sh が src と一緒にコンパイルするので、同じパッケージとして読める）。
 * stage B の間だけ使う。
 */
public final class StoreInspector {

    private StoreInspector() {
    }

    /** 項目の範囲の始まり（n= r= s= を含む） */
    public static int entryBegin(ValueStore values, int ref) {
        return values.entryBegin(ref);
    }

    /** 項目の範囲の終わり（n= r= s= を含む） */
    public static int entryEnd(ValueStore values, int ref) {
        return values.entryEnd(ref);
    }

    /** 項目の鍵（0 以上は位置。-1 は n=、-2 は r=、-3 は s=） */
    public static int entryKey(ValueStore values, int k) {
        return values.entryKey(k);
    }

    /** 項目の値 */
    public static int entryValue(ValueStore values, int k) {
        return values.entryValue(k);
    }

    /** 項目の総数 */
    public static int entryCount(ValueStore values) {
        return values.entryCount();
    }

    /** 値の表の列のバイト数 */
    public static long columnBytes(ValueStore values) {
        return values.columnBytes();
    }

    /** 条件の表の列のバイト数 */
    public static long columnBytes(GuardTable guards) {
        return guards.columnBytes();
    }

    /** コンストラクタ注入されたフィールドの出所（文字列の側） */
    public static Map<String, String> fieldOrigins(CallGraph graph) {
        return graph.fieldOrigins;
    }

    /** コンストラクタ注入されたフィールドの値の頭（値の表の側） */
    public static Map<String, Integer> fieldHeads(CallGraph graph) {
        return graph.fieldHeads;
    }

    /** 出所・条件・修飾する型の共有プール（文字列の側。読むだけ） */
    public static List<?> originPool(CallGraph graph) {
        try {
            Field f = CallGraph.class.getDeclaredField("originPool");
            f.setAccessible(true);
            return (List<?>) f.get(graph);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 戻り値の参照の CSR の範囲の配列の長さ（値の表の側。メソッドの数 + 1） */
    public static int returnOffsetCount(CallGraph graph) {
        return graph.returnOff.length;
    }

    /** 戻り値の参照の数（値の表の側。メソッドごとに重なりを除いたもの） */
    public static int returnRefCount(CallGraph graph) {
        return graph.returnRef.length;
    }

    /** 戻り値の出所の配列（文字列の側。読むだけ） */
    public static String[][] returnOrigins(CallGraph graph) {
        return graph.returnOrigins;
    }

    /** DI コンテナに登録された型と Bean 名（読むだけ） */
    public static Map<?, ?> beanNames(SpringBeans beans) {
        try {
            Field f = SpringBeans.class.getDeclaredField("beanNames");
            f.setAccessible(true);
            return (Map<?, ?>) f.get(beans);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
