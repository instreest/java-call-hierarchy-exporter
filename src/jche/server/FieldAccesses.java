// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jche.cache.CacheFormat;
import jche.cache.CacheReader;
import jche.cache.FieldAccessFact;
import jche.cache.FieldAssignFact;
import jche.cache.FieldDeclFact;
import jche.cache.MethodRef;
import jche.cache.SymbolTable;

/**
 * 1 つのフィールドの参照箇所を、キャッシュの A 行（{@link FieldAccessFact}）から拾う。
 *
 * <h2>グラフに入れず、要求されたときにキャッシュを読む</h2>
 * A 行はフィールドの参照 1 つにつき 1 行あり、呼び出し（C 行）と同じくらいの数になる。
 * グラフを組むとき（{@code jche.graph.CallGraphBuilder}）に全部をメモリに持つと、この機能を使わない
 * 解析（CLI と、メソッドしか見ない画面）にまでヒープを払わせることになる。そこで、フィールドを
 * 指定されたときにだけキャッシュを先頭から読み、そのフィールドの行だけを拾う。読むのは行の文字列だけで、
 * 1 回の走査はキャッシュの大きさに比例する（グラフは組まない）。何も持ち続けない
 * （{@code docs/field-callers-qa.md} の Q2）。
 *
 * <h2>キャッシュがグラフと同じ時点のものか</h2>
 * 拾った呼び出し元は、メモリにあるグラフ（{@link jche.AnalysisSnapshot}）のメソッドで引き直す。
 * 解析のやり直しが途中で失敗すると、キャッシュだけが新しくなってグラフは前のまま残ることがあるので、
 * グラフを組んだときのキャッシュの印（{@link jche.AnalysisSnapshot#cacheStamp}）と、読む前と読んだ後の
 * 印を突き合わせる。食い違えば {@link StaleCacheException} にする（古い結果と新しい行を混ぜて見せない）。
 */
final class FieldAccesses {

    /**
     * 参照 1 件。
     *
     * @param file   参照が書かれたファイル（プロジェクトルートからの相対。ブロックの F 行）
     * @param line   参照の行
     * @param caller 参照を囲むメソッド。特定できなければ null
     * @param access {@link FieldAccessFact#READ} / {@link FieldAccessFact#WRITE} / {@link FieldAccessFact#READ_WRITE}
     */
    record Access(String file, int line, MethodRef caller, String access) {

        boolean reads() {
            return !FieldAccessFact.WRITE.equals(access);
        }

        boolean writes() {
            return FieldAccessFact.WRITE.equals(access) || FieldAccessFact.READ_WRITE.equals(access);
        }
    }

    /**
     * 走査の結果。
     *
     * @param declFile    フィールドを宣言しているファイル（V 行のあるブロック）。ソースに宣言が無ければ null
     * @param initialized 宣言に初期化子がある（{@code int count = 0;}）。初期化子は参照の名前ではなく宣言なので
     *                    A 行に現れない。J 行の {@link FieldAssignFact#SITE_INITIALIZER} で知る
     * @param accesses    参照（ファイル上の順）
     */
    record Result(String declFile, boolean initialized, List<Access> accesses) {

        /** ソースに宣言も参照も無い（キーの綴りが違う・解析の後に足したフィールド） */
        boolean isUnknown() {
            return declFile == null && accesses.isEmpty();
        }
    }

    /** キャッシュがグラフを組んだときと違う（解析し直しが途中で終わった、など） */
    static final class StaleCacheException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private FieldAccesses() {
    }

    /**
     * @param cacheFile   キャッシュファイル
     * @param expected    グラフを組んだときのキャッシュの印（{@link jche.AnalysisSnapshot#cacheStamp}）
     * @param ownerFqn    フィールドを宣言している型（A 行の ownerTypeFqn と同じ綴り）
     * @param fieldName   フィールド名
     */
    static Result scan(Path cacheFile, String expected, String ownerFqn, String fieldName)
            throws IOException, StaleCacheException {
        checkStamp(cacheFile, expected);
        String declFile = null;
        boolean initialized = false;
        List<Access> accesses = new ArrayList<>();
        try (CacheReader in = CacheReader.open(cacheFile)) {
            String currentFile = "";
            // S 行は、そのブロックに拾う A 行があったときにだけ読み解く（大半のブロックには無い）
            List<String[]> symbolRows = new ArrayList<>();
            MethodRef[] symbols = null;
            while (in.next()) {
                switch (in.rowType()) {
                    case CacheFormat.ROW_FILE -> {
                        currentFile = in.filePath();
                        symbolRows.clear();
                        symbols = null;
                    }
                    case CacheFormat.ROW_SYMBOL -> symbolRows.add(in.columns());
                    case CacheFormat.ROW_FIELD_DECL -> {
                        // 列を分ける前に名前で篩う（V 行はフィールドの数だけある）
                        if (declFile == null && in.line().contains(fieldName)) {
                            FieldDeclFact v = FieldDeclFact.fromRow(in.columns());
                            if (v != null && ownerFqn.equals(v.typeFqn()) && fieldName.equals(v.fieldName())) {
                                declFile = currentFile;
                            }
                        }
                    }
                    case CacheFormat.ROW_FIELD_ASSIGN -> {
                        if (!initialized && in.line().contains(fieldName)) {
                            FieldAssignFact j = FieldAssignFact.fromRow(in.columns());
                            initialized = j != null && ownerFqn.equals(j.typeFqn())
                                    && fieldName.equals(j.fieldName())
                                    && FieldAssignFact.SITE_INITIALIZER.equals(j.site());
                        }
                    }
                    case CacheFormat.ROW_FIELD_ACCESS -> {
                        if (!in.line().contains(fieldName)) {
                            continue;
                        }
                        String[] cols = in.columns();
                        if (!ownerFqn.equals(CacheFormat.columnAt(cols, 3))
                                || !fieldName.equals(CacheFormat.columnAt(cols, 4))) {
                            continue;
                        }
                        if (symbols == null) {
                            symbols = symbolsOf(symbolRows);
                        }
                        FieldAccessFact a = FieldAccessFact.fromRow(cols, symbols);
                        if (a != null) {
                            accesses.add(new Access(currentFile, a.line(), a.caller(), a.access()));
                        }
                    }
                    default -> {
                        // ほかの行は読まない
                    }
                }
            }
        }
        // 読んでいる間に差し替えられていないか（読み始めと同じ印か）
        checkStamp(cacheFile, expected);
        return new Result(declFile, initialized, accesses);
    }

    private static MethodRef[] symbolsOf(List<String[]> rows) {
        SymbolTable.Reader reader = new SymbolTable.Reader();
        for (String[] cols : rows) {
            reader.add(cols);
        }
        return reader.array();
    }

    private static void checkStamp(Path cacheFile, String expected) throws StaleCacheException {
        if (expected == null || expected.isEmpty() || !expected.equals(jche.Exporter.cacheStampOf(cacheFile))) {
            throw new StaleCacheException();
        }
    }
}
