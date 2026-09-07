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
package jche.graph;

/**
 * 経路から確定している情報。どれも「1本の経路に対して」の値であり、
 * 別の経路では別の値になる。
 *
 * @param paramTypes 囲みメソッドの引数の具象型。型ではなく値（L:文字列 / K:クラス）が
 *                   渡っている引数にはその出所（':' を含む）が入る。分からない引数は null
 * @param ctorArgs   レシーバのオブジェクトの、コンストラクタ実引数の具象型
 * @param ctorOwner  ctorArgs が属する型。取り違え防止のため必ず突き合わせる
 */
public record DataflowContext(String[] paramTypes, String[] ctorArgs, String ctorOwner) {

    /** 何も分かっていなければ null（以降の深さで何もしないための印） */
    public static DataflowContext of(String[] paramTypes, String[] ctorArgs, String ctorOwner) {
        return (paramTypes == null && ctorArgs == null)
                ? null : new DataflowContext(paramTypes, ctorArgs, ctorOwner);
    }
}
