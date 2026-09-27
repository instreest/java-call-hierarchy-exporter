// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package inc;

/**
 * OneLine・OneLineLambdas の同じ行に並ぶ宣言のうち、戻り値の出所（R 行）を持たない側を呼ぶ。
 * このファイルだけを書き換えて差分更新すると、このブロックがキャッシュの先頭へ移り、
 * 呼び出し先（OneLine.a・OneLineLambdas.pair）が宣言のブロックより先に ID 化される
 */
public class OneLineUser {

    int call() {
        new OneLineLambdas().pair();
        return new OneLine().a();
    }
}
