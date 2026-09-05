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
package jche.cache;

import java.util.ArrayList;
import java.util.List;

/**
 * キャッシュを作ったときの依存 jar の1件（L行）。
 *
 * 呼び出し先・所有型・親型はバインディング解決の結果であり、依存 jar が変われば
 * ソースが同じでも変わりうる。そのため、どの jar に対して解析したかをキャッシュに残し、
 * 次回に jar の追加・変更・削除を検知して、影響するファイルだけを解析し直す
 * （{@link jche.analysis.CacheUpdater} 参照）。
 *
 * @param path     jar のパス。project.root 配下なら相対パス、外なら絶対パス（F行と同じ考え方）
 * @param size     サイズ（同一性の判定に使う。ソースファイルと同じ基準）
 * @param mtime    更新時刻（同上）
 * @param packages jar が含むクラスのパッケージ（重複なし・名前順）。jar が削除された後でも
 *                 「どのパッケージを参照していたファイルに影響するか」が分かるように持つ
 */
public record LibraryFact(String path, long size, long mtime, List<String> packages) {

    public LibraryFact {
        packages = List.copyOf(packages);
    }

    public String toRow() {
        return CacheFormat.joinRow("L", path, String.valueOf(size), String.valueOf(mtime),
                String.join(",", packages));
    }

    /** 列が足りない、または数値が壊れていれば null */
    public static LibraryFact fromRow(String[] cols) {
        if (cols.length < 4) {
            return null;
        }
        long size;
        long mtime;
        try {
            size = Long.parseLong(cols[2]);
            mtime = Long.parseLong(cols[3]);
        } catch (NumberFormatException ignore) {
            return null;
        }
        List<String> packages = new ArrayList<>();
        String csv = CacheFormat.columnAt(cols, 4);
        if (!csv.isEmpty()) {
            for (String p : csv.split(",")) {
                if (!p.isEmpty()) {
                    packages.add(p);
                }
            }
        }
        return new LibraryFact(cols[1], size, mtime, packages);
    }
}
