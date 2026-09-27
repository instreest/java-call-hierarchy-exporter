// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import java.io.BufferedWriter;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jche.util.Messages;

/**
 * キャッシュを人が目で追える形（記号を 4 列に戻した形）で標準出力へ書き出す。
 *
 * <pre>
 *   java -cp &lt;ツールのクラス&gt;:&lt;JDT の jar&gt; jche.cache.CacheDump .cache/&lt;プロジェクト&gt;/analysis-cache.tsv
 * </pre>
 *
 * <h2>何のためにあるか</h2>
 * キャッシュの行はメソッドを記号表（S 行。{@link SymbolTable}）の番号で指すので、そのままでは
 * 「どのメソッドがどれを呼んでいるか」を grep で引けない。ここでは記号の列を、記号表を引いて
 * 4 列（pkg・typeFqn・名前・引数）に戻して出す。戻した列の位置は、記号表を持つ前の形式（v30）で
 * メソッドを 4 列で書いていた位置と同じになる（{@link CacheFormat#symbolColumnsOf}）。
 * <pre>
 *   C  呼び出し元(4列)  呼び出し先(4列)  callLine  calleeMods  recvKind  lambdaDepth  qualifier  recv  args  guard  hints
 *   U  line  呼び出し元(4列)  expr  reason  candidate  recvKind  lambdaDepth  recv  args  guard  hints
 *   D  メソッド(4列)  declLine  hasBody  mods  アノテーション  endLine
 *   O  メソッド(4列)  …      M / A  line  呼び出し元(4列)  …
 *   R  メソッド(4列)  戻り値の出所
 *   J  typeFqn  fieldName  site  代入された値の出所
 *   G  ガード番号  op  subject の出所  text  値…
 * </pre>
 * 値のノード番号（R 行・J 行の値と G 行の subject）は、同じブロックの N 行から出所の形
 * （{@link Origin}。{@code T:a.B|0=A:0} のような形）に組み直して出す（{@link #originOf}。-1 は {@code U}）。
 * 人が読むための形で、読み手はこの形を読まない（ノードを値の表 jche.graph.ValueStore に取り込んで読む）。
 * C 行・U 行の recv・args・guard は番号のまま出す（N 行・G 行と突き合わせて読む）。
 * S 行は出さない（戻した列に入っている）。ヘッダ行・F 行・記号も値も持たない行はファイルのまま出す。
 * 戻した行も列ごとに同じ規則（{@link CacheFormat#escape}）で符号化し直すので、1 行は 1 行のまま。
 * 呼び出し元が無い（{@link SymbolTable#NO_SYMBOL}）ところは空の 4 列、記号表の外を指す番号は
 * typeFqn の位置に {@code ?番号} と出す（壊れたキャッシュでも読めるところまで出す）。
 *
 * <p>検査スクリプト（{@code test/jls/run.sh}・{@code test/ctorbody/run.sh}）はこの出力を
 * 名前で照合する。
 */
public final class CacheDump {

    private CacheDump() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println(Messages.get("cache.dump.usage"));
            System.exit(2);
        }
        Writer out = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8));
        dump(Path.of(args[0]), out);
        out.flush();
    }

    /** キャッシュを記号を戻した形で書き出す（行は {@code '\n'} で区切る） */
    public static void dump(Path cacheFile, Writer out) throws IOException {
        try (CacheReader in = CacheReader.open(cacheFile)) {
            out.write(in.header());
            out.write('\n');
            SymbolTable.Reader symbols = new SymbolTable.Reader();
            List<ValueNode> nodes = new ArrayList<>();
            while (in.next()) {
                char rowType = in.rowType();
                if (rowType == CacheFormat.ROW_FILE) {
                    symbols.clear();
                    nodes.clear();
                } else if (rowType == CacheFormat.ROW_SYMBOL) {
                    symbols.add(in.columns());
                    continue;
                } else if (rowType == CacheFormat.ROW_VALUE_NODE) {
                    nodes.add(ValueNode.fromRow(in.columns()));
                }
                int[] at = CacheFormat.symbolColumnsOf(rowType);
                String[] cols = (at.length == 0) ? null : expand(in.columns(), at, symbols.array());
                int valueAt = valueColumnOf(rowType, at.length);
                if (valueAt >= 0) {
                    String[] c = (cols == null) ? in.columns() : cols;
                    if (valueAt < c.length) {
                        c[valueAt] = originOf(ValueNode.intOf(c[valueAt], ValueNode.NONE), nodes);
                    }
                    cols = c;
                }
                out.write((cols == null) ? in.line() : CacheFormat.joinRow(cols));
                out.write('\n');
            }
        }
    }

    /**
     * 値のノード番号を持つ列の位置（記号を 4 列に戻した後の位置）。無ければ -1。
     * R 行は戻り値、J 行は代入された値、G 行は subject
     */
    private static int valueColumnOf(char rowType, int symbolColumns) {
        return switch (rowType) {
            case CacheFormat.ROW_RETURN -> 1 + 4 * symbolColumns;
            case CacheFormat.ROW_FIELD_ASSIGN -> 4;
            case CacheFormat.ROW_GUARD -> 3;
            default -> -1;
        };
    }

    /** 人が読む形の出所の上限（文字数）。機械生成の深い式でも 1 行が際限なく伸びないように */
    private static final int MAX_RENDERED = 4096;

    /**
     * ノードを出所の形（{@link Origin}）にする。-1・範囲外は {@code U}（範囲外は {@code U?番号}）。
     * new・メソッド呼び出し・束縛したメソッド参照には、実引数（{@code 位置=出所}）・実引数の数（{@code n=}）・
     * レシーバ（{@code r=}）・書かれた型（{@code s=}）を付ける。ノード番号は子 &lt; 親なので循環しない
     */
    static String originOf(int id, List<ValueNode> nodes) {
        StringBuilder sb = new StringBuilder();
        render(id, nodes, sb, 0);
        return sb.toString();
    }

    private static void render(int id, List<ValueNode> nodes, StringBuilder sb, int depth) {
        if (id < 0 || id >= nodes.size() || nodes.get(id) == null) {
            sb.append(Origin.UNKNOWN_S);
            if (id >= 0) {
                sb.append('?').append(id);
            }
            return;
        }
        ValueNode n = nodes.get(id);
        sb.append(n.kind()).append(':').append(n.value());
        boolean call = n.kind() == Origin.NEW || n.kind() == Origin.RETURN || n.kind() == Origin.FUNCTIONAL;
        if (!call || depth > 64 || sb.length() > MAX_RENDERED) {
            return;
        }
        List<String> entries = new ArrayList<>();
        if (!n.args().isEmpty()) {
            for (String entry : n.args().split(String.valueOf(ValueNode.ARG_SEP))) {
                int eq = entry.indexOf('=');
                if (eq > 0) {
                    StringBuilder arg = new StringBuilder();
                    render(ValueNode.intOf(entry.substring(eq + 1), ValueNode.NONE), nodes, arg, depth + 1);
                    entries.add(entry.substring(0, eq) + "=" + Origin.nest(arg.toString()));
                }
            }
        }
        if (n.kind() == Origin.RETURN && n.argCount() >= 0) {
            entries.add(Origin.ARG_COUNT + "=" + n.argCount());
        }
        if (n.recv() != ValueNode.NONE) {
            StringBuilder recv = new StringBuilder();
            render(n.recv(), nodes, recv, depth + 1);
            entries.add(Origin.RECEIVER + "=" + Origin.nest(recv.toString()));
        }
        if (!n.staticRecv().isEmpty()) {
            entries.add(Origin.STATIC_RECV + "=" + n.staticRecv());
        }
        if (!entries.isEmpty()) {
            sb.append(Origin.ARGS).append(String.join(";", entries));
        }
    }

    /** 記号の列を 4 列に戻した行の列 */
    private static String[] expand(String[] cols, int[] symbolColumns, MethodRef[] symbols) {
        List<String> row = new ArrayList<>(cols.length + 3 * symbolColumns.length);
        int next = 0;
        for (int i = 0; i < cols.length; i++) {
            if (next < symbolColumns.length && symbolColumns[next] == i) {
                next++;
                String[] four;
                if (SymbolTable.isNone(cols[i])) {
                    four = MethodRef.emptyColumns();
                } else {
                    MethodRef ref = SymbolTable.resolve(symbols, cols[i]);
                    four = (ref == null) ? new String[] {"", "?" + cols[i], "", ""} : ref.toColumns();
                }
                for (String c : four) {
                    row.add(c);
                }
            } else {
                row.add(cols[i]);
            }
        }
        return row.toArray(new String[0]);
    }
}
