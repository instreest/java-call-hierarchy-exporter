// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package inc;

/** 値を書かずに注釈を付ける。既定値がこのファイルの H 行に焼き込まれる */
@Tag
public class Tagged {

    public void go() {
        Log.write("tagged");
    }
}
