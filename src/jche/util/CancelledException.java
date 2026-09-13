// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.util;

/**
 * 呼び出し側の要求で解析を中止したことを表す。
 *
 * 失敗ではないので、受け取った側はエラーとして報告しないこと
 * （Eclipse プラグインは Job を CANCEL_STATUS で終える）。
 */
public class CancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CancelledException() {
        super("解析を中止しました");
    }
}
