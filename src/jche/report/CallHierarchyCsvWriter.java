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
import java.nio.charset.Charset;
import java.nio.file.Path;

import jche.graph.MethodTable;

/**
 * 呼び出し階層のCSV（call-hierarchy.csv）を1行ずつ書き出す。
 *
 * ヘッダー:
 * <pre>
 *   caller,callee,root,call-hierarchy...
 * </pre>
 * callee は「完全修飾クラス名.メソッド名(引数型略名)」。パッケージ違いの同名
 * クラスとオーバーロードを、この1列だけで見分けられるようにするため。
 * <ul>
 *   <li>呼び出し1件につき1行（起点自身は呼び出し元が無いため出力しない）</li>
 *   <li>caller は Eclipse の Java Stack Trace Console が認識する
 *       "at Class.method(File.java:行)" 形式。貼り付けるだけでソースへ飛べる。
 *       行番号は、呼び出し元が「このノードを呼んでいる行」＝呼び出し箇所</li>
 *   <li>call-hierarchy 以降は起点の次のノードから現ノードまでを1ノード1列で
 *       展開するため、ヘッダー行とデータ行の列数は一致しない（意図した仕様）</li>
 *   <li>call-hierarchy より後ろに列を追加してはならない（行末マッチが壊れるため）</li>
 * </ul>
 * 型解決に失敗した呼び出し（{@link #writeUnresolvedRow}）と外部jarからの被参照
 * （{@link #writeExternalUsageRow}）も同じファイルに出すが、列の詰め方が異なる。
 */
public final class CallHierarchyCsvWriter implements AutoCloseable {

    /** 型解決に失敗した行の root 列。起点が無いことを示す固定マーカー */
    static final String UNRESOLVED_ROOT = "(型解決失敗)";

    private final BufferedWriter writer;
    private final StringBuilder buf = new StringBuilder(512);

    public CallHierarchyCsvWriter(Path outputCsv, Charset encoding, boolean bom) throws IOException {
        this.writer = Csv.writer(outputCsv, encoding, bom);
        writer.write(String.join(Csv.DELIM, "caller", "callee", "root", "call-hierarchy"));
        writer.newLine();
    }

    /**
     * 呼び出し階層の1行。path[depth] のノードを、path[depth-1] が呼んでいる。
     *
     * @param depth 1以上（起点自身は出力しない）
     */
    void writeRow(MethodTable mt, int rootId, PathFrame[] path, int depth) throws IOException {
        buf.setLength(0);

        // caller: 呼び出し元が「このノードを呼んでいる行」を指すスタックトレース形式。
        buf.append(Csv.esc(stackTrace(mt, path[depth - 1].methodId, path[depth].callLine)))
                .append(Csv.DELIM);

        // callee: 完全修飾クラス名 + メソッド名 + 引数型略名。
        // Excelのフィルタで選べるよう、行番号は含めない安定した表記にする
        // （行番号を混ぜるとフィルタの選択肢が呼び出し箇所ごとに散らばる）。
        buf.append(Csv.esc(mt.displayLabel(path[depth].methodId))).append(Csv.DELIM);

        // root: 起点メソッド。これもフィルタで使えるよう短縮表記にする
        buf.append(Csv.esc(mt.shortLabel(rootId)));

        // call-hierarchy: 起点の次のノードから現ノードまでを1ノード1列で展開。
        // 必ず最終列に置く（後ろに固定列を足すと可変長の階層が途中で切れるため）。
        for (int i = 1; i <= depth; i++) {
            buf.append(Csv.DELIM).append(Csv.esc(mt.shortLabel(path[i].methodId)));
        }
        // 注記（[CYCLE]・深さ制限・CHA候補・import推定 等）は階層の最後に付ける
        if (path[depth].note != null) {
            buf.append(Csv.DELIM).append(Csv.esc(path[depth].note));
        }
        writer.write(buf.toString());
        writer.newLine();
    }

    /**
     * 型解決に失敗した呼び出しの1行。
     *
     * 呼び出し「元」はソース上のメソッドなので分かるが、呼び出し「先」の型が
     * 特定できていない。よって callee にはソースに書かれていた式（メソッド名）を
     * そのまま置き、root には起点が無いことを示す固定マーカーを入れる。
     * root でフィルタすれば、型解決に失敗した箇所だけをまとめて見られる。
     *
     * @param mt         呼び出し元の解決に使うメソッド表
     * @param callerId   呼び出し元メソッドのID。-1 なら特定できていない
     * @param location   callerId が -1 のときに caller 列へ出す位置情報
     * @param line       呼び出し箇所の行番号
     * @param expression ソースに書かれていた呼び出しの式（メソッド名）
     * @param reason     失敗の理由
     */
    void writeUnresolvedRow(MethodTable mt, int callerId, String location,
                            int line, String expression, String reason) throws IOException {
        buf.setLength(0);
        String caller = (callerId >= 0) ? stackTrace(mt, callerId, line) : location;
        buf.append(Csv.esc(caller)).append(Csv.DELIM);
        buf.append(Csv.esc(expression)).append(Csv.DELIM);
        buf.append(Csv.esc(UNRESOLVED_ROOT));
        buf.append(Csv.DELIM).append(Csv.esc(expression));
        buf.append(Csv.DELIM).append(Csv.esc(reason));
        writer.write(buf.toString());
        writer.newLine();
    }

    /**
     * 被参照スキャンの1行。呼び出し階層とは意味が違うため専用の詰め方をする。
     *
     * classファイルの定数プールしか読まないため、呼び出し元のメソッドも行番号も
     * 分からない。よって caller はスタックトレース形式にはせず、参照している
     * クラス名をそのまま置く。起点も呼び出し階層も無いので、root には
     * 「どのjarから参照されているか」を入れる。
     *
     * @param referencingClass 参照している側のクラス（外部jar内）
     * @param callee           参照されている自分のメソッド（callee列と同じ表記）
     * @param shortCallee      階層列に置く短縮表記
     * @param jarName          参照元のjar名
     * @param note             照合の種類（EXACT / INHERITED / IMPLICIT_CTOR）
     */
    public void writeExternalUsageRow(String referencingClass, String callee,
                                      String shortCallee, String jarName, String note)
            throws IOException {
        buf.setLength(0);
        buf.append(Csv.esc(referencingClass)).append(Csv.DELIM);
        buf.append(Csv.esc(callee)).append(Csv.DELIM);
        buf.append(Csv.esc(jarName));
        buf.append(Csv.DELIM).append(Csv.esc(shortCallee));
        buf.append(Csv.DELIM).append(Csv.esc("被参照:" + note));
        writer.write(buf.toString());
        writer.newLine();
    }

    /**
     * Java のスタックトレースと同じ "at バイナリ名.メソッド(ファイル:行)" 形式。
     *
     * typeFqn() はソース上の正規名（内部クラスも Outer.Inner のようにドット区切り）
     * を返すが、実際のJVMスタックトレースやEclipseの「Javaスタック・トレース・
     * コンソール」が期待するのは内部クラスを $ で区切ったバイナリ名（Outer$Inner）。
     * ドットのままだと内部クラスのメソッドへのジャンプが解決できない。
     */
    private static String stackTrace(MethodTable mt, int id, int line) {
        String file = mt.declFile(id);
        if (file == null || line < 0) {
            return mt.shortLabel(id) + " (unknown)";
        }
        String fileName = file.substring(file.lastIndexOf('/') + 1);
        String pkg = mt.pkg(id);
        String binaryType = mt.simpleTypeName(id).replace('.', '$');
        if (!pkg.isEmpty()) {
            binaryType = pkg + "." + binaryType;
        }
        return "at " + binaryType + "." + mt.methodName(id) + "(" + fileName + ":" + line + ")";
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
