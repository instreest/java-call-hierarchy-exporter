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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import jche.cache.CacheFormat;
import jche.cache.UnresolvedCallFact;
import jche.config.Config;
import jche.graph.CallGraph;

/**
 * 型解決に失敗した呼び出しを call-hierarchy.csv に書き出す。
 *
 * これらは呼び出し先の型が特定できていないため、呼び出し階層としては辿れない。
 * しかし「解決できなかったせいで階層から抜け落ちている」こと自体が
 * 重要な情報（依存jarの不足を示す）なので、静かに消さずに行として残す。
 *
 * キャッシュのU行を読み直して出力する。件数ぶんをヒープに載せないための
 * ストリーミング処理。
 */
public final class UnresolvedReport {

    private UnresolvedReport() {
    }

    /** @return 書き出した行数 */
    public static long write(CallGraph g, Config config, CallHierarchyCsvWriter out) throws IOException {
        if (!Files.isRegularFile(config.cacheFile)) {
            return 0L;
        }
        long rows = 0L;
        try (BufferedReader in = Files.newBufferedReader(config.cacheFile, StandardCharsets.UTF_8)) {
            in.readLine();   // バージョン行
            String currentFile = null;
            String line;
            while ((line = in.readLine()) != null) {
                char rowType = CacheFormat.rowTypeOf(line);
                if (rowType == CacheFormat.ROW_FILE) {
                    currentFile = CacheFormat.columnAt(CacheFormat.columnsOf(line), 1);
                    continue;
                }
                if (rowType != CacheFormat.ROW_UNRESOLVED) {
                    continue;
                }
                UnresolvedCallFact u = UnresolvedCallFact.fromRow(CacheFormat.columnsOf(line));
                if (u == null || u.hasUsableCandidate()) {
                    // import 推定でエッジになっている。call-hierarchy.csv 側に
                    // 「外部ライブラリ（import推定・未検証）」の注記付きで出ているので、ここでは出さない
                    continue;
                }
                // 呼び出し元メソッドのキーはD行と同じ形式なので、そのままIDを引ける。
                // 引ければ caller 列をスタックトレース形式にでき、Eclipseから飛べる
                String callerKey = (u.caller() == null) ? "" : u.caller().key();
                int callerId = callerKey.isEmpty() ? -1 : g.methods().idOf(callerKey);
                String location = (currentFile == null)
                        ? (callerKey.isEmpty() ? "(unknown)" : callerKey)
                        : currentFile + ":" + u.line();
                out.writeUnresolvedRow(g.methods(), callerId, location,
                        u.line(), u.expression(), reasonText(u.reason()));
                rows++;
            }
        }
        return rows;
    }

    /** 理由コードの文言（読み手の判断。キャッシュには文言を入れない） */
    static String reasonText(String code) {
        if (UnresolvedCallFact.BINDING_FAILED.equals(code)) {
            return "型解決に失敗（クラスパス不足・動的呼び出し等の可能性）";
        }
        if (UnresolvedCallFact.OUTSIDE_METHOD.equals(code)) {
            return "メソッド本体の外からの呼び出し";
        }
        return code;
    }
}
