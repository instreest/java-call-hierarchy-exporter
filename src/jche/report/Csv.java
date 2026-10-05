// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.report;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;

/** CSVエスケープと、Excel向け出力ライタの生成 */
public final class Csv {

    /** 出力の区切り文字。CSV固定（Excelでそのまま開ける形にするため） */
    public static final String DELIM = ",";

    private Csv() {
    }

    /**
     * 指定文字コードでCSVを書くライタを作る。
     *
     * MS932(Shift_JIS)に変換できない文字（一部のUnicode文字や、匿名クラスの
     * 内部キーに紛れ込む記号など）が来ても落ちないよう、置換動作にしている。
     * 既定の Files.newBufferedWriter は変換不可文字で例外を投げるため、
     * ここでエンコーダを明示的に組み立てている。
     *
     * bom=true のときは、ExcelがUTF-8と正しく認識できるよう
     * ファイル先頭にUTF-8のBOM（EF BB BF）を書く。
     */
    public static BufferedWriter writer(Path path, Charset cs, boolean bom) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        CharsetEncoder enc = cs.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        OutputStream os = Files.newOutputStream(path);
        if (bom) {
            os.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        }
        return new BufferedWriter(new OutputStreamWriter(os, enc));
    }

    /**
     * CSV/TSVエスケープ。区切り文字がカンマ・タブのどちらであっても安全なように、
     * カンマ・タブ・ダブルクォート・改行のいずれかを含む場合はダブルクォートで囲む。
     *
     * <p>先頭が {@code =} {@code +} {@code -} {@code @} のセルは、Excel が数式として評価しうる
     * （いわゆる CSV インジェクション。解析対象のソースに書かれた条件式がそのまま出力に載るので、
     * 利用者が開く前に中身を選別できない）。そこで先頭に {@code '} を 1 つ付けて引用符で囲み、
     * 文字列として表示させる。識別子はこれらの文字で始まらないので、影響するのは
     * {@code call-conditions.csv} の条件・期待値の列と、未解決の呼び出しの式の列だけ。
     */
    public static String esc(String s) {
        if (s == null) {
            return "";
        }
        if (startsWithFormulaChar(s)) {
            return "\"'" + s.replace("\"", "\"\"") + "\"";
        }
        if (s.indexOf(',') >= 0 || s.indexOf('\t') >= 0
                || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0
                || s.indexOf('\r') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** 先頭の文字を Excel が数式の始まりとみなすか（{@code = + - @}） */
    private static boolean startsWithFormulaChar(String s) {
        if (s.isEmpty()) {
            return false;
        }
        char c = s.charAt(0);
        return c == '=' || c == '+' || c == '-' || c == '@';
    }
}
