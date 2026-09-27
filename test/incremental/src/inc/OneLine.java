// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package inc;

/**
 * 同じ行に 2 つのメソッド。methods.csv では同じファイル・同じ宣言行の同着になる。
 * b だけが戻り値の出所（R 行）を持つので、読み手は b を a より先に ID 化する。一方、差分更新で
 * 呼び出し側（OneLineUser）のブロックが先頭へ移ると、そこから呼ばれる a が先に ID 化される。
 * 同着を ID で決めていると、全件解析（b, a）と差分更新（a, b）で methods.csv の行の前後が入れ替わる
 */
public class OneLine {

    int a() { return 1; } Object b() { return new Object(); }
}
