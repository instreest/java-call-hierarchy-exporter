// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package inc;

/** 呼び出し先。打ち切られた枝の先が階層から消えることを見るため、もう1段呼ぶ */
public final class Log {

    public static void alpha() {
        write("alpha");
    }

    public static void beta() {
        write("beta");
    }

    static void write(String message) {
        System.out.println(message);
    }

    private Log() {
    }
}
