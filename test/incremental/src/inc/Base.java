// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package inc;

/**
 * 大元の定数。テストはこのファイルだけを書き換える。
 *
 * ここの値は Names / Switches に焼き込まれ、さらにそれを使う Client / Branch にも焼き込まれる。
 * つまり Client / Branch は Base を「参照する型」として持っていないのに、値だけは依存している。
 */
public final class Base {

    /** 生成する具象クラスの名前。Names 経由で Client に焼き込まれる */
    public static final String IMPL = "inc.AlphaDao";

    /** 分岐の条件に使う値。Switches 経由で Branch に焼き込まれる */
    public static final String MODE = "ALPHA";

    private Base() {
    }
}
