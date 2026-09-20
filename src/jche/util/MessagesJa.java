// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.util;

/**
 * 日本語の文言。{@link MessagesEn}（英語）の上に重ねる。
 *
 * <p>ここに無い項目は英語のまま出る。英語に無いキーをここだけに足しても使われないので、
 * 追加は必ず英語と対にする（{@code test/nls/run.sh} が片側だけのキーを検出する）。
 *
 * <p>キーは出どころのパッケージに合わせた接頭辞で分けてある
 * （{@code cli.} … 対話モード、{@code config.} … 設定の読み取り、{@code analysis.} … AST 解析、
 * {@code graph.} … 呼び出しグラフ、{@code report.} … CSV 出力、{@code cache.} … キャッシュ、
 * {@code external.} … jar からの被参照、{@code common.} … どこからでも使うもの）。
 * 分野ごとにメソッドを分けているのは、メソッド1つの大きさの上限（64KB）に当たらないようにするため。
 *
 * <p>差し込みは {@code {0}} {@code {1}} …。番号と個数は英語と日本語でそろえる。
 */
final class MessagesJa {

    private MessagesJa() {
    }

    /** 分野ごとの表を並べたもの。1つの表はキーと値が交互に並ぶ */
    static String[][] table() {
        return new String[][] {
            common(),
        };
    }

    private static String[] common() {
        return new String[] {
        };
    }
}
