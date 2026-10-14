// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

/**
 * メソッドを一意に指す4つ組。キャッシュではブロックの記号表（S 行。{@link SymbolTable}）に
 * 4 列で書き、「呼び出し元」「呼び出し先」「宣言」の各行からはその番号で指す。
 *
 * @param pkg      パッケージ名（デフォルトパッケージなら空）
 * @param typeFqn  宣言型の完全修飾名（内部クラスは Outer.Inner、匿名クラスは Outer$1）
 * @param name     メソッド名。コンストラクタは {@link #CONSTRUCTOR}、静的初期化子は {@link #STATIC_INITIALIZER}
 * @param paramSig 消去型の引数型をカンマ区切りにしたもの（引数なしは空）
 */
public record MethodRef(String pkg, String typeFqn, String name, String paramSig) {

    public static final String CONSTRUCTOR = "<init>";
    public static final String STATIC_INITIALIZER = "<clinit>";

    public MethodRef {
        pkg = (pkg == null) ? "" : pkg;
        paramSig = (paramSig == null) ? "" : paramSig;
    }

    /** ラムダ本体の合成メソッド名の接頭辞（{@link #lambdaName}） */
    private static final String LAMBDA_PREFIX = "lambda$";
    /** 合成名の中で {@link #CONSTRUCTOR} を表す語（javac に合わせる） */
    private static final String LAMBDA_IN_CONSTRUCTOR = "new";
    /** 合成名の中で {@link #STATIC_INITIALIZER} を表す語（javac に合わせる） */
    private static final String LAMBDA_IN_STATIC_INITIALIZER = "static";

    /**
     * ラムダ本体の合成メソッド名 {@code lambda$囲みメソッド名$通し番号}。javac に似せて、{@link #CONSTRUCTOR} は {@code new}、
     * {@link #STATIC_INITIALIZER} は {@code static} と書く。名前の付け方と、その逆（{@link #lambdaEnclosingName}）を
     * ここ 1 か所に置く。片方だけ変えると、CSV の caller 列がラムダを書いたメソッドを指せなくなる
     *
     * @param enclosingName 囲みメソッドの名前（{@link #CONSTRUCTOR} / {@link #STATIC_INITIALIZER} を含む）
     * @param index         型ごとの通し番号
     */
    public static String lambdaName(String enclosingName, int index) {
        String base = switch (enclosingName) {
            case CONSTRUCTOR -> LAMBDA_IN_CONSTRUCTOR;
            case STATIC_INITIALIZER -> LAMBDA_IN_STATIC_INITIALIZER;
            default -> enclosingName;
        };
        return LAMBDA_PREFIX + base + "$" + index;
    }

    /**
     * {@link #lambdaName} の逆。合成メソッド名から、囲みメソッドの名前（ソースに書いたメソッド名。コンストラクタ・
     * フィールド初期化子は {@link #CONSTRUCTOR}、static 初期化子は {@link #STATIC_INITIALIZER}）を戻す。
     * {@link #lambdaName} の形でなければ null
     */
    public static String lambdaEnclosingName(String name) {
        int last = name.lastIndexOf('$');
        if (!name.startsWith(LAMBDA_PREFIX) || last < LAMBDA_PREFIX.length()) {
            return null;
        }
        String base = name.substring(LAMBDA_PREFIX.length(), last);
        if (base.isEmpty()) {
            return null;
        }
        return switch (base) {
            case LAMBDA_IN_CONSTRUCTOR -> CONSTRUCTOR;
            case LAMBDA_IN_STATIC_INITIALIZER -> STATIC_INITIALIZER;
            default -> base;
        };
    }

    /** グラフ全体でメソッドを識別するキー: typeFqn#name(paramSig) */
    public String key() {
        return typeFqn + "#" + signature();
    }

    /** 型を除いた部分: name(paramSig)。同名同引数の照合に使う */
    public String signature() {
        return name + "(" + paramSig + ")";
    }

    public boolean isConstructor() {
        return CONSTRUCTOR.equals(name);
    }

    /** キャッシュの4列 {pkg, typeFqn, name, paramSig} にする（S 行と {@link CacheDump} が使う） */
    String[] toColumns() {
        return new String[] {pkg, typeFqn, name, paramSig};
    }

    /** メソッドが無い（{@link SymbolTable#NO_SYMBOL}）ところを 4 列に戻すときの空の4列（{@link CacheDump}） */
    static String[] emptyColumns() {
        return new String[] {"", "", "", ""};
    }

    /** キャッシュの列（S 行なら位置 2 から）から復元する。列が足りない・typeFqn が空なら null */
    static MethodRef fromColumns(String[] cols, int from) {
        if (cols.length < from + 4 || cols[from + 1].isEmpty()) {
            return null;
        }
        return new MethodRef(cols[from], cols[from + 1], cols[from + 2], cols[from + 3]);
    }
}
