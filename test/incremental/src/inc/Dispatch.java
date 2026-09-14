// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package inc;

/** 列挙定数による打ち切り。Mode を書き換えたときに追随することを見る */
public class Dispatch {

    public void run() {
        dispatch(Mode.FULL);
    }

    private void dispatch(Mode mode) {
        switch (mode) {
            case FULL -> Log.alpha();
            case LITE -> Log.beta();
            default -> Log.write("none");
        }
    }
}
