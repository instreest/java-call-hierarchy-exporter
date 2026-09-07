/*
 * Copyright 2026 the java-call-hierarchy-exporter authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jche.cache;

/**
 * メソッドを一意に指す4つ組。キャッシュの各行で「呼び出し元」「呼び出し先」「宣言」として現れる。
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

    /** キャッシュの4列 {pkg, typeFqn, name, paramSig} にする */
    String[] toColumns() {
        return new String[] {pkg, typeFqn, name, paramSig};
    }

    /** 呼び出し元を特定できなかった行のための空の4列 */
    static String[] emptyColumns() {
        return new String[] {"", "", "", ""};
    }

    /** キャッシュの列から復元する。typeFqn が空（呼び出し元不明）なら null */
    static MethodRef fromColumns(String[] cols, int from) {
        if (cols.length < from + 4 || cols[from + 1].isEmpty()) {
            return null;
        }
        return new MethodRef(cols[from], cols[from + 1], cols[from + 2], cols[from + 3]);
    }
}
