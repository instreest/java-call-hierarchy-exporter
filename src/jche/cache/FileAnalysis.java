// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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
    /**
     * エラー（{@link #errors}）の引数に現れた名前（{@code Foo}・{@code org.missing.Lib}・{@code q.Bar} のような
     * 点区切りの識別子。エラーの位置に書かれた名前の頭の部分なら、書かれた名前全体。
     * {@code jche.analysis.CallEdgeExtractor#namesOf}）。I 行に書き、差分更新で新しい型ができたとき、その名前に
     * 当たるブロックだけを解析し直すのに使う
     */
    public final Set<String> unresolvedNames = new TreeSet<>();
    /**
     * 同じメソッドの中で new された型の証拠（キャッシュの行にはしない）。書き手がブロックを書くときに、
     * 呼び出し元とレシーバの変数のキー（{@link CallSiteValues#recvKey}）でこのファイルの呼び出し箇所に
     * 結びつけ、C 行・U 行の hints 列に書く
     */
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
    /**
     * このファイルが宣言する型と、その型が宣言するメソッド・フィールドの、JDT のバインディングの鍵と修飾子
     * （継承したものは含めない。{@code jche.analysis.TypeContextTracker#recordDeclarations}）。行にはせず、
     * 書き手が {@link #constants} の指紋と合わせて 1 つの指紋（自分の宣言の指紋。I 行の 3 列目）にする。
     * 差分更新は、中身の変わっていないファイルを解析し直したとき、この指紋が前回と違えば宣言する型を
     * 「変わった型」にする（docs/cache-unification-qa.md の Q83）
     */
    public final List<String> declarationKeys = new ArrayList<>();
    /** フィールドへの代入（J 行）。値は {@link #valueNodes} のノード番号 */
    public final List<FieldAssignFact> fieldAssigns = new ArrayList<>();
    public final List<FieldAccessFact> fieldAccesses = new ArrayList<>();
    /** バインディング解決で参照した型のFQN（I行の元。自分が宣言する型は書き出し時に除く） */
    public final Set<String> referencedTypes = new LinkedHashSet<>();
    /** import 文の型（I行の元。オンデマンド import は "pkg.*"） */
    public final Set<String> imports = new LinkedHashSet<>();
    /** 呼び出し箇所（{@link CallEdgeFact} と {@link UnresolvedCallFact}）をソース上の順で */
    public final List<CallSite> callSites = new ArrayList<>();
    /** return の値（R 行）。値は {@link #valueNodes} のノード番号 */
    public final List<ReturnFact> returns = new ArrayList<>();
    /**
     * 値グラフのノード（N 行）。上限の無い形で値の流れを持つ。番号は並びの位置。
     * 戻り値・フィールドへの代入・条件の subject・呼び出し箇所のレシーバと実引数は、どれもここを指す
     */
    public final List<ValueNode> valueNodes = new ArrayList<>();
    /**
     * 呼び出し箇所ごとの値。{@link #callSites} と<b>同じ数・同じ順</b>で並び、同じ位置どうしが組になる。
     * キャッシュでは組にした 2 つを 1 行（C 行・U 行）に書く（条件のアトムは G 行の表にまとめ、番号で指す）。
     * 条件の調査（{@code jche.analysis.CallConditionScanner}）もキャッシュを通さず、同じ位置で組にして
     * アトムをそのまま読む（subject は {@link #valueNodes} で引く）
     */
    public final List<CallSiteValues> callSiteValues = new ArrayList<>();
    public final List<FunctionalImplFact> functionalImpls = new ArrayList<>();

    public FileAnalysis(String relativePath, long size) {
        this.relativePath = relativePath;
        this.size = size;
    }

    /**
     * 型解決できなかった呼び出しの数。読み手がエッジにできる U 行（import から推定した候補があり、
     * 呼び出し元も分かるもの。{@link UnresolvedCallFact#hasUsableCandidate}）は除く。
     * F 行の未解決数（{@link CacheFormat#unresolvedOf}）もこの数
     */
    public int unresolvedCount() {
        int n = 0;
        for (CallSite site : callSites) {
            if (site instanceof UnresolvedCallFact u && !u.hasUsableCandidate()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 型解決に失敗したか（エラーがある、または理由が BINDING_FAILED の U 行がある）。失敗したファイルは、I 行の
     * 2 列目に解決できなかった名前を書く（docs/cache-unification-qa.md の Q42）
     */
    public boolean resolutionFailed() {
        if (errors > 0) {
            return true;
        }
        for (CallSite site : callSites) {
            if (site instanceof UnresolvedCallFact u && UnresolvedCallFact.BINDING_FAILED.equals(u.reason())) {
                return true;
            }
        }
        return false;
    }
}
