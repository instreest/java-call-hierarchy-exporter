// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package app;

import dup.Shared;

/**
 * 依存 jar の並び順の検査用。dup.Shared は 2 つの jar に入っていて、どちらが勝つかで
 * run(1) の解決先が変わる（先に並んだ jar が勝つ）。
 */
public final class Main {

    public static void main(String[] args) {
        new Shared().run(1);
    }

    private Main() {
    }
}
