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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ソース1ファイルから抽出した解析結果（キャッシュの1ブロック分）。
 * キャッシュへ書き出したら破棄される一時オブジェクトで、ヒープには残さない。
 */
public final class FileAnalysis {

    public final String relativePath;
    public final long lastModified;
    public final long size;

    public final List<TypeFact> types = new ArrayList<>();
    public final List<HintFact> hints = new ArrayList<>();
    public final List<MethodDeclFact> declarations = new ArrayList<>();
    public final List<FieldDeclFact> fieldDecls = new ArrayList<>();
    public final List<FieldAssignFact> fieldAssigns = new ArrayList<>();
    public final List<FieldAccessFact> fieldAccesses = new ArrayList<>();
    /** バインディング解決で参照した型のFQN（I行の元。自分が宣言する型は書き出し時に除く） */
    public final Set<String> referencedTypes = new LinkedHashSet<>();
    /** import 文の型（I行の元。オンデマンド import は "pkg.*"） */
    public final Set<String> imports = new LinkedHashSet<>();
    /** 呼び出し箇所（{@link CallEdgeFact} と {@link UnresolvedCallFact}）をソース上の順で */
    public final List<CallSite> callSites = new ArrayList<>();
    public final List<ReturnFact> returns = new ArrayList<>();
    public final List<FunctionalImplFact> functionalImpls = new ArrayList<>();

    public FileAnalysis(String relativePath, long lastModified, long size) {
        this.relativePath = relativePath;
        this.lastModified = lastModified;
        this.size = size;
    }

    /** 型解決できなかった呼び出しの数（import から推定した候補があるものは除く） */
    public int unresolvedCount() {
        int n = 0;
        for (CallSite site : callSites) {
            if (site instanceof UnresolvedCallFact u && u.candidate().isEmpty()) {
                n++;
            }
        }
        return n;
    }
}
