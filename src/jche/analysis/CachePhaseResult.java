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
package jche.analysis;

/** フェーズ1（ソース解析とキャッシュ更新）の集計 */
public final class CachePhaseResult {
    public int reused;
    public int parsed;
    public int failed;
    /** 自分は変わっていないが、依存する型が変わったので解析し直したファイル数（parsed に含む） */
    public int dependents;
    /** 型解決できなかった呼び出しの件数。クラスパス不足の検知に使う */
    public long unresolved;
}
