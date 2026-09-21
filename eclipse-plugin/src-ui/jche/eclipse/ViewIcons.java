// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;

/**
 * ツールバーのアイコン。絵は 16×16 の<b>文字盤</b>としてここに書いてある。
 *
 * <p>画像ファイル（png）を同梱しないのは、このリポジトリに二進のファイルを持ち込まないためである。
 * 文字盤なら差分が読め、大きさの検査（16 行 × 16 文字、使う文字は 4 種類）も
 * {@code test/plugin/run.sh} が文字列として行える。倍率（HiDPI）では 1 マスを
 * そのまま整数倍に描くので、拡大してもぼやけない。
 *
 * <p>文字の意味は次の 4 つ。
 * <ul>
 *   <li>{@code .} … 透明</li>
 *   <li>{@code g} … 灰（対象や木の線。動かない部分）</li>
 *   <li>{@code a} … 青（その機能を表す部分。矢印・記号）</li>
 *   <li>{@code w} … 白（青の上に抜く記号）</li>
 * </ul>
 *
 * <p>色は明るいテーマでも暗いテーマでも読めるよう、中間の明度で選んである。
 * 再解析（⟳）だけは Eclipse 本体の共有アイコンを使う（同じ絵を下手に描き直さない）。
 */
final class ViewIcons {

    /** 1辺の升目の数 */
    static final int SIZE = 16;

    /** 呼び出し元＝上へ辿る。上向きの矢印と、起点を表す横棒 */
    static final String[] CALLERS = {
        "................",
        ".......aa.......",
        "......aaaa......",
        ".....aaaaaa.....",
        "....aaaaaaaa....",
        "...aaaaaaaaaa...",
        ".......aa.......",
        ".......aa.......",
        ".......aa.......",
        ".......aa.......",
        ".......aa.......",
        "................",
        "...gggggggggg...",
        "...gggggggggg...",
        "................",
        "................",
    };

    /** 呼び出し先＝下へ辿る。起点の横棒と、下向きの矢印 */
    static final String[] CALLEES = {
        "................",
        "................",
        "...gggggggggg...",
        "...gggggggggg...",
        "................",
        ".......aa.......",
        ".......aa.......",
        ".......aa.......",
        ".......aa.......",
        ".......aa.......",
        "...aaaaaaaaaa...",
        "....aaaaaaaa....",
        ".....aaaaaa.....",
        "......aaaa......",
        ".......aa.......",
        "................",
    };

    /** カーソル位置のメソッド。狙いを定める輪と、いまの位置を表す点 */
    static final String[] AT_CURSOR = {
        "................",
        "......gggg......",
        "....gggggggg....",
        "...ggg....ggg...",
        "..gg........gg..",
        "..gg........gg..",
        ".gg...aaaa...gg.",
        ".gg...aaaa...gg.",
        ".gg...aaaa...gg.",
        ".gg...aaaa...gg.",
        "..gg........gg..",
        "..gg........gg..",
        "...ggg....ggg...",
        "....gggggggg....",
        "......gggg......",
        "................",
    };

    /** リセット（解析結果を捨てて解析前に戻す）。ごみ箱 */
    static final String[] RESET = {
        "................",
        "......gggg......",
        "................",
        "..gggggggggggg..",
        "..gggggggggggg..",
        "...g..g..g..g...",
        "...g..g..g..g...",
        "...g..g..g..g...",
        "...g..g..g..g...",
        "...g..g..g..g...",
        "...g..g..g..g...",
        "...g..g..g..g...",
        "...gggggggggg...",
        "....gggggggg....",
        "................",
        "................",
    };

    /** すべて展開。木と ＋ の札 */
    static final String[] EXPAND_ALL = {
        "................",
        ".gggggggggggggg.",
        "..g.............",
        "..g.............",
        "..ggggggggggggg.",
        "..g.............",
        "..g.............",
        "..ggggggggggg...",
        "..g.....aaaaaaa.",
        "..g.....aaawaaa.",
        "..ggggggaaawaaa.",
        "........awwwwwa.",
        "........aaawaaa.",
        "........aaawaaa.",
        "........aaaaaaa.",
        "................",
    };

    /** 折りたたむ。木と − の札（展開と対になる絵） */
    static final String[] COLLAPSE_ALL = {
        "................",
        ".gggggggggggggg.",
        "..g.............",
        "..g.............",
        "..ggggggggggggg.",
        "..g.............",
        "..g.............",
        "..ggggggggggg...",
        "..g.....aaaaaaa.",
        "..g.....aaaaaaa.",
        "..ggggggaaaaaaa.",
        "........awwwwwa.",
        "........aaaaaaa.",
        "........aaaaaaa.",
        "........aaaaaaa.",
        "................",
    };

    /** 24 ビット RGB。透明は {@code alphaData} で表す（色そのものは持たない） */
    private static final PaletteData PALETTE = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);

    private static final int GRAY = 0x808A94;
    private static final int BLUE = 0x3B82C4;
    private static final int WHITE = 0xFFFFFF;

    private ViewIcons() {
    }

    /** 文字盤から、ツールバーに渡せる画像の作り方を作る */
    static ImageDescriptor of(final String[] map) {
        return ImageDescriptor.createFromImageDataProvider(zoom -> {
            // 端数の倍率（125% など）は升目が整数にならない。SWT に 100% の絵を拡大させる
            return (zoom % 100 == 0) ? dataOf(map, zoom / 100) : null;
        });
    }

    /** 文字盤を {@code scale} 倍で描く。1マスを scale×scale の正方形にするのでぼやけない */
    private static ImageData dataOf(String[] map, int scale) {
        int side = SIZE * scale;
        ImageData data = new ImageData(side, side, 24, PALETTE);
        data.alphaData = new byte[side * side];
        for (int row = 0; row < SIZE; row++) {
            for (int column = 0; column < SIZE; column++) {
                int color = colorOf(map[row].charAt(column));
                if (color < 0) {
                    continue;   // 透明。alphaData は 0 のままでよい
                }
                fill(data, column * scale, row * scale, scale, color);
            }
        }
        return data;
    }

    private static void fill(ImageData data, int left, int top, int scale, int color) {
        for (int y = top; y < top + scale; y++) {
            for (int x = left; x < left + scale; x++) {
                data.setPixel(x, y, color);
                data.alphaData[y * data.width + x] = (byte) 255;
            }
        }
    }

    /** 文字盤の1文字の色。透明なら -1 */
    private static int colorOf(char cell) {
        switch (cell) {
            case 'g':
                return GRAY;
            case 'a':
                return BLUE;
            case 'w':
                return WHITE;
            default:
                return -1;
        }
    }
}
