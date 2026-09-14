// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package inc;

/** Base の定数をそのまま持つ中継役（2段目）。コンパイル時定数なので値が焼き込まれる */
public final class Names {

    public static final String DAO = Base.IMPL;

    private Names() {
    }
}
