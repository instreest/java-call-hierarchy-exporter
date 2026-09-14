// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package inc;

/** クラス名の文字列から実装を作るファクトリ。データフロー解析の対象 */
public final class Factory {

    public static Dao create(String name) {
        try {
            return (Dao) Class.forName(name).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Factory() {
    }
}
