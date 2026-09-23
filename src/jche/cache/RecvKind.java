// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
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
    /**
     * 型名。static 呼び出しと、型名で書いたメソッド参照（{@code Dao::describe}）。
     * static 呼び出しは静的束縛なので CHA にならず、CHA の理由として出るのは後者だけ
     */
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
            case RETURN -> "return value (factory method etc.)";
            case PARAM -> "parameter (passed in from outside the method)";
            case FIELD -> "field";
            case LOCAL -> "local variable";
            case THIS -> "own class (this)";
            // CHA の理由として出るのは、型名で書いたメソッド参照（Dao::describe）だけ。
            // レシーバは呼び出し時の第1引数で、static 呼び出しではない（JLS 15.13.1）
            case TYPE -> "type name (unbound method reference)";
            default -> "receiver unknown";
        };
    }
}
