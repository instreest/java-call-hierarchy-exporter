// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
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
    /**
     * JDT が報告したエラーの数（型が見つからない、import が解決できない等）。
     * 0 でなければこのファイルの解決結果は不完全で、依存 jar が増えたときに解析し直す対象になる
     * （{@link jche.analysis.CacheUpdater}）。数えるだけで、何のエラーかは判断しない
     */
    public int errors;
    /**
     * 内容のハッシュ（{@link jche.util.FileHash}）。F行の 6 列目。更新時刻が変わってもサイズと
     * 内容が同じなら再利用できるようにするためのもの。書き手（{@link jche.analysis.CacheUpdater}）が
     * キャッシュへ書く直前に入れる。空文字なら「不明」で、更新時刻とサイズだけで判定される
     */
    public String hash = "";

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
