// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package inc;

/** Switches 経由で Base の定数を使う（条件分岐の打ち切り） */
public class Branch {

    public void run() {
        if (Switches.MODE.equals("ALPHA")) {
            Log.alpha();
        }
        if (Switches.MODE.equals("BETA")) {
            Log.beta();
        }
    }
}
