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
 * レシーバ（呼び出しの受け手）の構文上の由来。
 *
 * CHAで実装を1つに絞れなかったとき、「なぜ絞れないのか」を説明するために使う。
 * 絞れない理由はレシーバがどこから来たかでほぼ決まる。
 * 1文字でキャッシュ（C行・U行の recvKind）に書く。
 */
public final class RecvKind {

    /** メソッドの戻り値（ファクトリメソッド等） */
    public static final char RETURN = 'M';
    /** 呼び出し元メソッドの引数（メソッド外からインスタンスが渡される） */
    public static final char PARAM = 'P';
    /** フィールド変数 */
    public static final char FIELD = 'F';
    /** ローカル変数（同一メソッド内の new は追跡済み。それでも絞れなかったもの） */
    public static final char LOCAL = 'L';
    /** レシーバなし（this / 暗黙） */
    public static final char THIS = 'T';
    /** 型名（static呼び出し） */
    public static final char TYPE = 'S';
    /** 配列要素・キャスト式・条件式など、上記に当てはまらないもの */
    public static final char OTHER = 'O';

    private RecvKind() {
    }

    /** キャッシュの列（1文字）から復元する。空なら OTHER */
    public static char parse(String column) {
        return column.isEmpty() ? OTHER : column.charAt(0);
    }

    /** 出力に載せる説明。CHAで絞れなかった理由として使う */
    public static String describe(char kind) {
        return switch (kind) {
            case RETURN -> "戻り値（ファクトリメソッド等）";
            case PARAM -> "引数（メソッド外から渡される）";
            case FIELD -> "フィールド変数";
            case LOCAL -> "ローカル変数";
            case THIS -> "自クラス（this）";
            case TYPE -> "型名（static）";
            default -> "レシーバ不明";
        };
    }
}
