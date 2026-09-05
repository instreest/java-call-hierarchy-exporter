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
package jche.cache;

/**
 * そのメソッドが返しうる値の出所の1件（R行）。
 *
 * 追跡できない return も U として記録する。「追跡できない return が1つでもあれば
 * 戻り値は不定」という判定は読み手が行う。
 *
 * @param method return を含むメソッド
 * @param origin 返す値の出所（{@link Origin}）
 */
public record ReturnFact(MethodRef method, String origin) {

    public String toRow() {
        return CacheFormat.joinRow("R", method.pkg(), method.typeFqn(), method.name(),
                method.paramSig(), origin);
    }

    /** 列が足りなければ null */
    public static ReturnFact fromRow(String[] cols) {
        if (cols.length < 6) {
            return null;
        }
        MethodRef method = MethodRef.fromColumns(cols, 1);
        return (method == null) ? null : new ReturnFact(method, cols[5]);
    }
}
