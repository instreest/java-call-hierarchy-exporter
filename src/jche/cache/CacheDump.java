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
 *   C  呼び出し元(4列)  呼び出し先(4列)  callLine  calleeMods  recvKind  lambdaDepth  qualifier  recv  args  recvKey  guard
 *   U  line  呼び出し元(4列)  expr  reason  candidate  recvKind  lambdaDepth  recv  args  recvKey  guard
 *   D  メソッド(4列)  declLine  hasBody  mods  アノテーション  endLine
 *   O / R  メソッド(4列)  …      M / A  line  呼び出し元(4列)  …
 * </pre>
 * S 行は出さない（戻した列に入っている）。ヘッダ行・F 行・記号を持たない行はファイルのまま出す。
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
            while (in.next()) {
                char rowType = in.rowType();
                if (rowType == CacheFormat.ROW_FILE) {
                    symbols.clear();
                } else if (rowType == CacheFormat.ROW_SYMBOL) {
                    symbols.add(in.columns());
                    continue;
                }
                int[] at = CacheFormat.symbolColumnsOf(rowType);
                out.write((at.length == 0) ? in.line() : expand(in.columns(), at, symbols.array()));
                out.write('\n');
            }
        }
    }

    /** 記号の列を 4 列に戻した行 */
    private static String expand(String[] cols, int[] symbolColumns, MethodRef[] symbols) {
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
        return CacheFormat.joinRow(row.toArray(new String[0]));
    }
}
