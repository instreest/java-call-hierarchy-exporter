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
    public final long size;
    /**
     * JDT が報告したエラーの数（型が見つからない、import が解決できない等）。
     * 0 でなければこのファイルの解決結果は不完全で、依存 jar が増えたときに解析し直す対象になる
     * （{@link jche.analysis.CacheUpdater}）。数えるだけで、何のエラーかは判断しない
     */
    public int errors;
    /**
     * JDT が報告した<b>構文</b>エラーの数（{@code errors} の内数）。
     *
     * <p>{@code errors} と分けて持つのは、意味がまるで違うからである。型が見つからない類の
     * エラーは依存 jar の不足で、AST は最後まで組み上がっていて<b>呼び出しは全部拾えている</b>。
     * 一方、構文エラーが出たファイルは<b>本体を読めていない</b>ので、そこに書かれた呼び出しは
     * まるごと出力に出ない。後者だけは利用者に伝えないと、影響調査の結果が静かに欠ける
     * （docs/syntax-error-report-qa.md）。
     */
    public int syntaxErrors;
    /**
     * 内容のハッシュ（{@link jche.util.FileHash}）。F行の最後の列で、差分更新の同一性判定の本体。
     * 書き手（{@link jche.analysis.CacheUpdater}）がキャッシュへ書く直前に入れる。
     * 空文字なら「不明」で、そのブロックは次回かならず解析し直される（安全側）
     */
    public String hash = "";

    public final List<TypeFact> types = new ArrayList<>();
    public final List<HintFact> hints = new ArrayList<>();
    public final List<MethodDeclFact> declarations = new ArrayList<>();
    /**
     * メソッド宣言が上書きしている宣言（O行。{@link OverrideFact} 参照）。
     * シグネチャが自分と同じ上書きは持たない（キーの照合で引けるため）
     */
    public final List<OverrideFact> overrides = new ArrayList<>();
    public final List<FieldDeclFact> fieldDecls = new ArrayList<>();
    /** このファイルが宣言するコンパイル時定数（K行。{@link ConstantFact} 参照） */
    public final List<ConstantFact> constants = new ArrayList<>();
    public final List<FieldAssignFact> fieldAssigns = new ArrayList<>();
    public final List<FieldAccessFact> fieldAccesses = new ArrayList<>();
    /** バインディング解決で参照した型のFQN（I行の元。自分が宣言する型は書き出し時に除く） */
    public final Set<String> referencedTypes = new LinkedHashSet<>();
    /** import 文の型（I行の元。オンデマンド import は "pkg.*"） */
    public final Set<String> imports = new LinkedHashSet<>();
    /** 呼び出し箇所（{@link CallEdgeFact} と {@link UnresolvedCallFact}）をソース上の順で */
    public final List<CallSite> callSites = new ArrayList<>();
    public final List<ReturnFact> returns = new ArrayList<>();
    /**
     * 値グラフのノード（dataflow 側の N 行）。上限の無い形で値の流れを持つ。
     * {@link CallSite} の出所（上限付き）と同じ式から作られ、両方が書き出される
     * （読み手が移るまでの並走。{@code docs/cache-split-qa.md}）
     */
    public final List<ValueNode> valueNodes = new ArrayList<>();
    /**
     * 呼び出し箇所ごとの値（dataflow 側の P 行）。{@link #callSites} と同じ数・同じ順で並ぶ
     * （1 対 1 で結びつけられるようにするため）
     */
    public final List<CallSiteValues> callSiteValues = new ArrayList<>();
    public final List<FunctionalImplFact> functionalImpls = new ArrayList<>();

    public FileAnalysis(String relativePath, long size) {
        this.relativePath = relativePath;
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
