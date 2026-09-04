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

/**
 * 呼び出し箇所1件（C行またはU行）。
 * 解決できた呼び出しと失敗した呼び出しを、ソース上の順のまま1つの列に持つための共通型。
 */
public sealed interface CallSite permits CallEdgeFact, UnresolvedCallFact {

    String toRow();
}
