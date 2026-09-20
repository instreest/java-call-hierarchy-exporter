// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
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
 *   caller,callee,level,resolved-by,root,call-hierarchy...
 * </pre>
 * callee は「クラス名.メソッド名」。Excel のフィルタで呼び出し先を選びやすくする
 * ため、引数型は付けない（オーバーロードは同じ表記にまとまる）。
 * <ul>
 *   <li>呼び出し1件につき1行（起点自身は呼び出し元が無いため出力しない）</li>
 *   <li>caller は Eclipse の Java Stack Trace Console が認識する
 *       "at Class.method(File.java:行)" 形式。貼り付けるだけでソースへ飛べる。
 *       行番号は、呼び出し元が「このノードを呼んでいる行」＝呼び出し箇所</li>
 *   <li>level は起点からの階層の深さ（起点が0、その呼び出し先が1）。
 *       call-hierarchy 列に並ぶノード数と必ず一致する</li>
 *   <li>resolved-by は解決方法（{@link ResolvedBy}）。注記と違って必ず値が入るので、
 *       Excel のフィルタで確度・手法ごとに行を選べる</li>
 *   <li>call-hierarchy 以降は起点の次のノードから現ノードまでを1ノード1列で
 *       展開するため、ヘッダー行とデータ行の列数は一致しない（意図した仕様）</li>
 *   <li>call-hierarchy より後ろに列を追加してはならない（行末マッチが壊れるため）。
 *       固定列を足すときは root の左に入れる（docs/call-hierarchy-columns-qa.md）</li>
 * </ul>
 * 型解決に失敗した呼び出し（{@link #writeUnresolvedRow}）と外部jarからの被参照
 * （{@link #writeExternalUsageRow}）も同じファイルに出すが、列の詰め方が異なる。
 */
public final class CallHierarchyCsvWriter implements AutoCloseable {

    /** 型解決に失敗した行の root 列。起点が無いことを示す固定マーカー */
    static final String UNRESOLVED_ROOT = "(unresolved)";

    private final BufferedWriter writer;
    private final StringBuilder buf = new StringBuilder(512);

    public CallHierarchyCsvWriter(Path outputCsv, Charset encoding, boolean bom) throws IOException {
        this.writer = Csv.writer(outputCsv, encoding, bom);
        writer.write(String.join(Csv.DELIM,
                "caller", "callee", "level", "resolved-by", "root", "call-hierarchy"));
        writer.newLine();
    }

    /**
     * 呼び出し階層の1行。path[depth] のノードを、path[depth-1] が呼んでいる。
     *
     * @param depth 1以上（起点自身は出力しない）。そのまま level 列になる
     */
    void writeRow(MethodTable mt, int rootId, PathFrame[] path, int depth) throws IOException {
        buf.setLength(0);

        // caller: 呼び出し元が「このノードを呼んでいる行」を指すスタックトレース形式。
        buf.append(Csv.esc(stackTrace(mt, path[depth - 1].methodId, path[depth].callLine)))
                .append(Csv.DELIM);

        // callee: クラス名 + メソッド名（引数は付けない）。
        // Excelのフィルタで選べるよう、行番号は含めない安定した表記にする
        // （行番号を混ぜるとフィルタの選択肢が呼び出し箇所ごとに散らばる）。
        buf.append(Csv.esc(mt.shortLabel(path[depth].methodId))).append(Csv.DELIM);

        // level: 起点からの深さ。call-hierarchy 列のノード数と一致する
        buf.append(depth).append(Csv.DELIM);

        // resolved-by: 解決方法。注記と違い、確定した呼び出しでも必ず値が入る
        buf.append(Csv.esc(path[depth].resolvedBy)).append(Csv.DELIM);

        // root: 起点メソッド。これもフィルタで使えるよう短縮表記にする
        buf.append(Csv.esc(mt.shortLabel(rootId)));

        // call-hierarchy: 起点の次のノードから現ノードまでを1ノード1列で展開。
        // 必ず最終列に置く（後ろに固定列を足すと可変長の階層が途中で切れるため）。
        for (int i = 1; i <= depth; i++) {
            buf.append(Csv.DELIM).append(Csv.esc(mt.shortLabel(path[i].methodId)));
        }
        // 注記（[UNEXPANDED:*]・[EXTERNAL]・[UNREACHABLE]・[RESOLVED:*]）は階層の最後に付ける
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
     * @param resolvedBy resolved-by 列（{@link ResolvedBy#UNRESOLVED} + 理由コード）
     */
    void writeUnresolvedRow(MethodTable mt, int callerId, String location,
                            int line, String expression, String reason, String resolvedBy)
            throws IOException {
        buf.setLength(0);
        String caller = (callerId >= 0) ? stackTrace(mt, callerId, line) : location;
        buf.append(Csv.esc(caller)).append(Csv.DELIM);
        buf.append(Csv.esc(expression)).append(Csv.DELIM);
        // 階層は無いが、階層列には式を1つ置くので level は1。
        // 「level = call-hierarchy 列のノード数」をどの種類の行でも保つ
        buf.append(1).append(Csv.DELIM);
        buf.append(Csv.esc(resolvedBy)).append(Csv.DELIM);
        buf.append(Csv.esc(UNRESOLVED_ROOT));
        buf.append(Csv.DELIM).append(Csv.esc(expression));
        buf.append(Csv.DELIM).append(Csv.esc(reason));
        writer.write(buf.toString());
        writer.newLine();
    }

    /**
     * 被参照スキャンの1行。呼び出し階層とは意味が違うため専用の詰め方をする。
     *
     * caller は呼び出し階層の行と同じスタックトレース形式（{@link #stackTrace(String, String, String, int)}）。
     * 起点も呼び出し階層も無いので、root には「どのjarから参照されているか」を入れる。
     *
     * @param caller      参照している側（外部jar内）。スタックトレース形式。命令列から辿れなかった
     *                    参照はクラス名だけ
     * @param callee      参照されている自分のメソッド（callee列と同じ表記）
     * @param shortCallee 階層列に置く短縮表記
     * @param jarName     参照元のjar名
     * @param note        照合の種類（EXACT / INHERITED / IMPLICIT_CTOR）
     */
    public void writeExternalUsageRow(String caller, String callee,
                                      String shortCallee, String jarName, String note)
            throws IOException {
        buf.setLength(0);
        buf.append(Csv.esc(caller)).append(Csv.DELIM);
        buf.append(Csv.esc(callee)).append(Csv.DELIM);
        buf.append(1).append(Csv.DELIM);
        buf.append(Csv.esc(ResolvedBy.EXTERNAL_USAGE + note)).append(Csv.DELIM);
        buf.append(Csv.esc(jarName));
        buf.append(Csv.DELIM).append(Csv.esc(shortCallee));
        buf.append(Csv.DELIM).append(Csv.esc("external-ref:" + note));
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
    static String stackTrace(MethodTable mt, int id, int line) {
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
        return stackTrace(binaryType, mt.methodName(id), fileName, line);
    }

    /**
     * 同じ形式を、class ファイルから読んだ値（被参照スキャン）で組み立てる。
     * ファイル名か行番号が無いときは JVM のスタックトレースと同じ {@code (Unknown Source)}。
     * Eclipse の Java Stack Trace Console はこの形も受け付け、クラス名とメソッド名で飛ぶ
     *
     * @param binaryType バイナリ名（内部クラスは {@code Outer$Inner}）
     * @param method     メソッド名（コンストラクタは {@code <init>}）
     * @param fileName   SourceFile 属性のファイル名。無ければ null
     * @param line       行番号。無ければ -1
     */
    public static String stackTrace(String binaryType, String method, String fileName, int line) {
        String where = (fileName != null && line >= 0) ? fileName + ":" + line : "Unknown Source";
        return "at " + binaryType + "." + method + "(" + where + ")";
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
