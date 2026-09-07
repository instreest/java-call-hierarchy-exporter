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
package jche.graph;

import jche.config.Config;
import jche.config.PackagePattern;

/** call-hierarchy.csv の起点（エントリポイント）を選ぶ */
public final class EntryPoints {

    private EntryPoints() {
    }

    /**
     * 設定にマッチする、ソース上に宣言のあるメソッドを起点として選ぶ。
     * entry.packages が空なら全体モード（{@link #autoEntryPoints}）。
     * 並びはどちらも {@link SourceOrder#sortedBySource}。
     */
    public static int[] select(CallGraph g, CallResolver resolver, Config config) {
        if (config.entryPatterns.isEmpty()) {
            return autoEntryPoints(g, resolver);
        }
        MethodTable methods = g.methods;
        IntArray hits = new IntArray(256);
        for (int id = 0; id < methods.size(); id++) {
            if (!methods.hasSource(id)) {
                continue;   // ソースが無いメソッドは起点にしない
            }
            if (PackagePattern.matchesAny(config.entryPatterns,
                    methods.pkg(id), methods.typeFqn(id), methods.methodName(id))) {
                hits.add(id);
            }
        }
        return SourceOrder.sortedBySource(g, hits);
    }

    /**
     * 全体モードの起点。呼び出し元が1件も無く、ソース上に本体を持つメソッド。
     *
     * これは「真の入口」ではない点に注意。実際には次のものが混ざる。
     * <ul>
     *   <li>独自フレームワークがディスパッチする画面入口（本来ほしいもの）</li>
     *   <li>デッドコード・旧版の残骸</li>
     *   <li>テストクラスのメソッド</li>
     *   <li>リフレクション経由でのみ呼ばれるもの</li>
     * </ul>
     * methods.csv の role 列で仕分けできるようにしてある。
     */
    private static int[] autoEntryPoints(CallGraph g, CallResolver resolver) {
        MethodTable methods = g.methods;
        int[] in = resolver.inDegrees();
        IntArray hits = new IntArray(256);
        for (int id = 0; id < methods.size(); id++) {
            if (in[id] == 0 && methods.hasSource(id) && methods.hasBody(id)) {
                hits.add(id);
            }
        }
        return SourceOrder.sortedBySource(g, hits);
    }
}
