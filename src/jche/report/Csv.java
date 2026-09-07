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
     */
    public static String esc(String s) {
        if (s == null) {
            return "";
        }
        if (s.indexOf(',') >= 0 || s.indexOf('\t') >= 0
                || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0
                || s.indexOf('\r') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
