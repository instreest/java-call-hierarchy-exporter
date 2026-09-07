// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.extension;

/**
 * フェーズA（抽出時）の拡張が拾った証拠。キーと値だけの汎用の箱にしてある。
 *
 * 例: ファクトリメソッド {@code DaoFactory.get("USER_DAO")} の文字列リテラルを
 * {@code kind="FACTORY_KEY", value="USER_DAO"} として残す。
 */
public record Hint(String kind, String value) {

    @Override
    public String toString() {
        return kind + "=" + value;
    }
}
