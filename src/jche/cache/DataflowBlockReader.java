// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jche.util.Log;

/**
 * dataflow-cache.tsv を、analysis-cache.tsv と<b>歩調を合わせてブロック単位で</b>読む。
 *
 * <h2>なぜ歩調を合わせるのか</h2>
 * 値に関わる事実は dataflow 側にあるが、そのうち
 * <ul>
 *   <li>P 行（呼び出し箇所の値）は analysis 側の C 行・U 行と 1 対 1</li>
 *   <li>J 行（フィールドへの代入）は、同じブロックの V 行（フィールド宣言）と組で判定する</li>
 * </ul>
 * ため、ファイル（ブロック）ごとの対応が要る。2 つのキャッシュは常に同じブロックを同じ順で持つ
 * （{@code docs/cache-split-qa.md} の Q2 の不変条件）ので、前へ読み進めるだけで対応が取れる。
 *
 * <p>ヒープに載るのは<b>1 ブロック分だけ</b>。読み終わったブロックは次の {@link #advanceTo} で捨てる。
 * これで「キャッシュ全体をヒープに載せない」という既存の作りを崩さずに済む。
 *
 * <h2>使い方</h2>
 * <pre>
 *   try (DataflowBlockReader flow = DataflowBlockReader.open(dataflowCacheFile)) {
 *       // analysis 側を読みながら、F 行に出会うたびに
 *       Block block = flow.advanceTo(relativePath);
 *   }
 * </pre>
 * 探しているブロックが無ければ空のブロックを返す（値が無いものとして扱われ、
 * 呼び出し階層は CHA 止まりになるだけで、呼び出しが消えることはない）。
 * 対の不変条件が守られていれば起きないので、起きたら1度だけ警告を出す。
 */
public final class DataflowBlockReader implements Closeable {

    /**
     * 1ファイル分の値。
     *
     * @param path            ブロックの相対パス
     * @param callSiteValues  呼び出し箇所の値（P 行）。analysis 側の C 行・U 行と同じ順
     * @param fieldAssigns    フィールドへの代入（J 行）
     * @param returns         戻り値の出所（R 行）
     * @param hints           フェーズAの拡張が拾った証拠（X 行）
     */
    public record Block(String path, List<CallSiteValues> callSiteValues,
                        List<FieldAssignFact> fieldAssigns, List<ReturnFact> returns,
                        List<HintFact> hints) {

        static final Block EMPTY = new Block("", List.of(), List.of(), List.of(), List.of());
    }

    private final CacheReader in;
    /** 読み進めた先で待っている F 行の相対パス。null なら次のブロックを探していない */
    private String pendingPath;
    private boolean exhausted;
    private boolean warned;

    private DataflowBlockReader(CacheReader in) {
        this.in = in;
    }

    /** ファイルを開き、最初のブロックの手前まで読み進める */
    public static DataflowBlockReader open(Path dataflowCacheFile) throws IOException {
        CacheReader in = CacheReader.open(dataflowCacheFile);
        try {
            DataflowBlockReader reader = new DataflowBlockReader(in);
            reader.seekNextBlock();
            return reader;
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    /** 次の F 行まで読み進める（その行のパスを {@link #pendingPath} に置く） */
    private void seekNextBlock() throws IOException {
        while (in.next()) {
            if (in.is(CacheFormat.ROW_FILE)) {
                pendingPath = in.column(1);
                return;
            }
        }
        pendingPath = null;
        exhausted = true;
    }

    /**
     * 指定した相対パスのブロックまで読み進めて、その中身を返す。
     *
     * ブロックの並びは analysis 側と同じなので、ふつうは次のブロックがそれに当たる。
     * 違っていれば、見つかるまで読み飛ばす（analysis 側にしか無いブロックがあった場合）。
     * 最後まで探して無ければ空のブロックを返す
     */
    public Block advanceTo(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isEmpty()) {
            return Block.EMPTY;
        }
        while (!exhausted) {
            if (relativePath.equals(pendingPath)) {
                return readCurrentBlock(relativePath);
            }
            skipCurrentBlock();
        }
        if (!warned) {
            warned = true;
            Log.warn("[cache] データフローのキャッシュに " + relativePath
                    + " のブロックがありません。このファイルの値は使いません（具象クラスの解決は CHA まで）");
        }
        return Block.EMPTY;
    }

    /** 今の F 行から次の F 行（または最終行）までを読み込む */
    private Block readCurrentBlock(String path) throws IOException {
        List<CallSiteValues> callSiteValues = new ArrayList<>();
        List<FieldAssignFact> fieldAssigns = new ArrayList<>();
        List<ReturnFact> returns = new ArrayList<>();
        List<HintFact> hints = new ArrayList<>();
        boolean more = false;
        while (in.next()) {
            if (in.is(CacheFormat.ROW_FILE)) {
                pendingPath = in.column(1);
                more = true;
                break;
            }
            switch (in.rowType()) {
                case CacheFormat.ROW_CALL_VALUES -> add(callSiteValues, CallSiteValues.fromRow(in.columns()));
                case CacheFormat.ROW_FIELD_ASSIGN -> add(fieldAssigns, FieldAssignFact.fromRow(in.columns()));
                case CacheFormat.ROW_RETURN -> add(returns, ReturnFact.fromRow(in.columns()));
                case CacheFormat.ROW_HINT -> add(hints, HintFact.fromRow(in.columns()));
                default -> {
                    // A 行・N 行・K 行・Z 行は、呼び出し階層を組むのには使わない
                }
            }
        }
        if (!more) {
            pendingPath = null;
            exhausted = true;
        }
        return new Block(path, callSiteValues, fieldAssigns, returns, hints);
    }

    private static <T> void add(List<T> list, T value) {
        if (value != null) {
            list.add(value);
        }
    }

    /** 今のブロックを読み捨てて、次の F 行まで進む */
    private void skipCurrentBlock() throws IOException {
        seekNextBlock();
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
