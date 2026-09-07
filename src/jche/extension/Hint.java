/*
 * Copyright 2026 the java-call-hierarchy-exporter authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
