// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package inc;

public final class Main {

    public static void main(String[] args) {
        new Client().run();
        new Branch().run();
        new Dispatch().run();
        new Tagged().go();
        new Awkward().query();
        new Awkward().separators();
    }

    private Main() {
    }
}
