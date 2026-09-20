// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jche.cache.Origin;
import jche.util.Log;

/**
 * 具象型の契約表（種類 C）。「この宣言型（またはこの型のこのメソッド、このファクトリのこのキー）は、
 * この具象型」を契約表の 1 行で書く（docs/contracts-unification-design.md）。
 *
 * <h2>契約の1行</h2>
 * <pre>
 *   jp.co.xxx.dao.UserDao            =&gt; jp.co.xxx.dao.UserDaoImpl     … C-1 宣言型
 *   jp.co.xxx.dao.UserDao#find       =&gt; jp.co.xxx.dao.CachedUserDao    … C-2 宣言型#メソッド名
 *   jp.co.xxx.DaoFactory#get("USER") =&gt; jp.co.xxx.dao.UserDaoImpl      … C-3 ファクトリ＋キー
 * </pre>
 * C-3 のキーは 3 通り書ける（読み口は {@link FactoryCalls} の 1 か所）。
 * <pre>
 *   #get("USER")                  文字列。コンパイル時定数は値まで評価される
 *   #get(jp.co.app.Kind.USER)     列挙定数。引用符を付けず定数の FQN で書く
 *   #getDao(jp.co.xxx.UserDao.class)  Class リテラル。型の FQN に .class を付ける
 * </pre>
 * 右辺はカンマ区切りで複数書ける。ただし 1 件に絞れたときだけ展開される規則は変わらないので、
 * 複数書いた箇所は {@code [UNEXPANDED:CHA]} になる。
 *
 * <p>型名は完全修飾名でも<b>単純名</b>でもよい（{@link TypeNames}）。単純名が複数の型に当たる
 * ときは使わず、どれのことか分からないと知らせる。誤った型に静かに解決するのを避けるため。
 *
 * <p>引く順番は C-3（ファクトリ＋キー）→ C-2（型＋メソッド）→ C-1（型）。
 * 呼び出しごとに狭いほうを先に見る（同梱の {@link jche.builtin.TypeMappingProvider} と同じ考え方）。
 *
 * <h2>C-3 のキーはどこから来るか</h2>
 * 呼び出し箇所を走査し直す必要は無い。
 * {@code Dao dao = DaoFactory.get("USER"); dao.find();} の {@code dao} の出所は、値グラフから
 * 組み直した {@code M:jp.co.xxx.DaoFactory#get(java.lang.String)|n=1;0=L:USER} の形で既に手元にあり、
 * ここからファクトリのメソッドキーと実引数の値の両方が読める（{@link OriginRenderer}）。
 * 変数に受けずに続けて呼ぶ形（{@code DaoFactory.get("USER").find()}）でも同じ。
 *
 * <p>キーの値は {@link DataflowResolver#literalValueOf} で引くので、文字列リテラルのほか
 * コンパイル時定数（{@code static final String} の参照、リテラルの連結）も値まで評価される。
 * 表に書くのは<b>定数の単純名ではなく値</b>のほう。
 *
 * <p>列挙定数をキーにしているファクトリ（{@code get(Kind.USER)}）は、引用符を付けずに
 * <b>定数の FQN</b> で書く（{@code #get(jp.co.app.Kind.USER)}）。Java のソースに書く形と同じで、
 * 文字列のキー（引用符あり）と見分けがつく。引用符も修飾名も無い形は読めない行として弾く。
 *
 * <p>実引数は先頭から順に見て、<b>最初に表に載っているキー</b>を使う。多引数のファクトリ
 * （{@code get(scope, "USER")}）でも位置を書かずに済ませるための規則で、どの引数が当たったかで
 * 結果が揺れないよう「先頭から最初の 1 つ」に固定している。
 *
 * <p>効かせる位置は段3（拡張と同じ）で、拡張より先に引く。利用者が表に書いた条件を、
 * ツールの推測（段4 データフロー・段5 Spring DI）より先に効かせるという既存の判断
 * （docs/instance-analysis-plugin-qa.md の Q24）を引き継ぐ。段0（静的束縛）には効かせない。
 */
public final class TypeContracts {

    /** Class リテラルのキーの書き方（{@code jp.co.app.UserDao.class}） */
    private static final String CLASS_SUFFIX = ".class";

    /**
     * 契約の1行。
     *
     * @param declaredType 左辺の型。C-1 / C-2 は呼び出し先を宣言している型、C-3 はファクトリの型
     * @param methodName   左辺のメソッド名。C-1（型だけ）なら空文字
     * @param key          C-3 のキー（ファクトリに渡す値）。C-1 / C-2 なら空文字
     * @param keyKind      キーの種別。{@link Origin#LITERAL}（文字列）・{@link Origin#CONST}（列挙定数）・
     *                     {@link Origin#CLASS}（Class リテラル）のいずれか。
     *                     C-1 / C-2 なら {@code 0}
     * @param candidates   右辺の具象型の FQN
     * @param text         元の行（報告用）
     * @param row          契約表の何行目か（{@link ContractUsage} の添字）
     */
    record Contract(String declaredType, String methodName, String key, char keyKind,
                    String[] candidates, String text, int row) {
    }

    /** C-1 / C-2: 左辺の型ごとの契約。同じ型に C-1 と C-2 が並ぶことがある */
    private final Map<String, List<Contract>> byType = new LinkedHashMap<>();
    /** C-3: "型FQN#メソッド名(キー)" ごとの契約 */
    private final Map<String, Contract> byFactoryKey = new LinkedHashMap<>();
    private final ContractUsage usage;

    /**
     * @param usage     読み込んだ行
     * @param typeNames 単純名で書かれた型名を FQN に直す道具。null なら直さない
     */
    public TypeContracts(ContractUsage usage, TypeNames typeNames) {
        this.usage = usage;
        List<ContractUsage.Line> lines = usage.lines();
        for (int row = 0; row < lines.size(); row++) {
            Contract c = parse(lines.get(row).text(), row);
            if (c == null) {
                continue;
            }
            c = withFqn(c, typeNames);
            if (c.key().isEmpty()) {
                byType.computeIfAbsent(c.declaredType(), k -> new ArrayList<>()).add(c);
            } else {
                // 同じファクトリ・同じキーの行が 2 つあれば、先に書いたほうを使う
                byFactoryKey.putIfAbsent(leftSideOf(c), c);
            }
        }
    }

    /** 契約を 1 行も持たない表（同梱の行は無いので、設定を読まない経路はこれになる） */
    public static TypeContracts empty() {
        return new TypeContracts(new ContractUsage(List.of()), null);
    }

    /**
     * 単純名で書かれた型名を FQN に直した契約。
     *
     * <p>読み込みのときに 1 回だけ直す。引くたびに直すと、同じ判断を呼び出しの数だけ繰り返すうえ、
     * 「曖昧なので使わなかった」という知らせも呼び出しの数だけ出てしまう。
     */
    private static Contract withFqn(Contract c, TypeNames typeNames) {
        if (typeNames == null) {
            return c;
        }
        String type = resolve(typeNames, c.declaredType(), c.text());
        String[] candidates = new String[c.candidates().length];
        boolean changed = !type.equals(c.declaredType());
        for (int i = 0; i < candidates.length; i++) {
            candidates[i] = resolve(typeNames, c.candidates()[i], c.text());
            changed |= !candidates[i].equals(c.candidates()[i]);
        }
        return changed
                ? new Contract(type, c.methodName(), c.key(), c.keyKind(), candidates, c.text(), c.row())
                : c;
    }

    /** 単純名なら FQN に直す。曖昧なら直さず、どれのことか分からないと知らせる */
    private static String resolve(TypeNames typeNames, String name, String text) {
        List<String> conflicts = typeNames.ambiguousCandidates(name);
        if (!conflicts.isEmpty()) {
            Log.warn("契約の型名 " + name + " は " + conflicts.size() + " つの型に当たるので使えません: "
                    + String.join(" / ", conflicts) + "（完全修飾名で書いてください）: " + text);
            return name;
        }
        return typeNames.toFqn(name);
    }

    /** 行ごとの利用状況（どの契約が効いたか） */
    public ContractUsage usage() {
        return usage;
    }

    public boolean isEmpty() {
        return byType.isEmpty() && byFactoryKey.isEmpty();
    }

    /** ファクトリ＋キーの行（C-3）を持つか。データフローを切った実行で知らせるために使う */
    public boolean hasFactoryRows() {
        return !byFactoryKey.isEmpty();
    }

    /** 1行を読む。形が違えば null（黙って捨てず、呼び出し側がログに出せるよう null を返す） */
    static Contract parse(String line) {
        return parse(line, ContractUsage.NO_ROW);
    }

    /**
     * 左辺に実引数を書いた形（C-3）か。形が違って読めなかったときに、
     * 「キーは引用符で囲む」という助言を添えられるようにするために見分ける
     */
    static boolean hasArguments(String line) {
        String s = (line == null) ? "" : line.trim();
        int arrow = s.indexOf("=>");
        return arrow > 0 && s.substring(0, arrow).indexOf('(') >= 0;
    }

    /** @param row 契約表の何行目か（利用状況の記録用） */
    private static Contract parse(String line, int row) {
        String s = (line == null) ? "" : line.trim();
        if (s.isEmpty() || s.startsWith("#")) {
            return null;
        }
        int arrow = s.indexOf("=>");
        if (arrow <= 0) {
            return null;
        }
        String left = s.substring(0, arrow).trim();
        String[] candidates = candidatesOf(s.substring(arrow + 2));
        if (left.isEmpty() || candidates.length == 0) {
            return null;
        }
        String key = "";
        char keyKind = 0;
        int open = left.indexOf('(');
        if (open >= 0) {
            if (!left.endsWith(")")) {
                return null;
            }
            String inner = left.substring(open + 1, left.length() - 1).trim();
            if (inner.length() >= 2 && inner.charAt(0) == '"'
                    && inner.charAt(inner.length() - 1) == '"') {
                // "…" で囲んだキー。文字列リテラルとコンパイル時定数の値に当たる
                key = inner.substring(1, inner.length() - 1);
                keyKind = Origin.LITERAL;
            } else if (inner.endsWith(CLASS_SUFFIX)
                    && isQualifiedName(inner.substring(0, inner.length() - CLASS_SUFFIX.length()))) {
                // Class リテラル。Java のソースに書く形（UserDao.class の FQN）に合わせる。
                // 列挙定数より先に見る。「FQN + .class」も修飾名の形をしているため
                key = inner.substring(0, inner.length() - CLASS_SUFFIX.length());
                keyKind = Origin.CLASS;
            } else if (isQualifiedName(inner)) {
                // 修飾名は列挙定数。Java のソースに書く形（Kind.USER の FQN）に合わせる
                key = inner;
                keyKind = Origin.CONST;
            } else {
                return null;
            }
            if (key.isEmpty()) {
                return null;
            }
            left = left.substring(0, open).trim();
        }
        String type = left;
        String methodName = "";
        int hash = left.indexOf('#');
        if (hash >= 0) {
            type = left.substring(0, hash).trim();
            methodName = left.substring(hash + 1).trim();
            if (type.isEmpty() || methodName.isEmpty()) {
                return null;
            }
        }
        if (!key.isEmpty() && methodName.isEmpty()) {
            return null;   // C-3 はメソッド名が要る
        }
        return new Contract(type, methodName, key, keyKind, candidates, s, row);
    }

    /** 右辺（カンマ区切りの具象型） */
    private static String[] candidatesOf(String right) {
        List<String> out = new ArrayList<>(1);
        for (String one : right.split(",")) {
            String fqn = one.trim();
            if (!fqn.isEmpty()) {
                out.add(fqn);
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * C-3: レシーバがファクトリの戻り値なら、渡されたキーで契約を引く。無ければ null。
     *
     * @param recvOrigin レシーバの出所（{@link CallGraph#recvOrigin}）
     * @param dataflow   キーの値を引くのに使う
     * @param ctx        この経路で分かっていること。無ければ null
     */
    Contract matchFactory(String recvOrigin, DataflowResolver dataflow, DataflowContext ctx) {
        if (byFactoryKey.isEmpty() || recvOrigin == null
                || Origin.kindOf(recvOrigin) != Origin.RETURN) {
            return null;
        }
        for (String left : factoryLeftSidesOf(recvOrigin, dataflow, ctx)) {
            Contract hit = byFactoryKey.get(left);
            if (hit != null) {
                usage.markReached(hit.row());
                return hit;   // 実引数の先頭から見て、最初に表に載っているキーを使う
            }
        }
        return null;
    }

    /**
     * 呼び出し箇所のレシーバが「ファクトリの戻り値」なら、契約表に書ける左辺
     * （{@code 型#メソッド("キー")} / {@code 型#メソッド(列挙定数のFQN)}）の候補を、
     * 実引数の位置の順に返す。ファクトリの戻り値でなければ空。
     *
     * <p>読み取りそのものは {@link FactoryCalls} が持つ。ここはそれを契約表の綴りに直すだけ。
     * 絞れなかった呼び出しからひな形を作る側（{@code jche.report.ContractSuggestions}）も
     * これを使うので、ひな形が出す行と実際に引ける行が食い違わない。
     */
    public static List<String> factoryLeftSidesOf(String recvOrigin, DataflowResolver dataflow,
                                                  DataflowContext ctx) {
        List<FactoryCalls.Key> keys = FactoryCalls.keysOf(recvOrigin, dataflow, ctx);
        if (keys.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(keys.size());
        for (FactoryCalls.Key key : keys) {
            out.add(leftSideOf(key.typeAndName(), key.key(), key.kind()));
        }
        return out;
    }

    /**
     * C-2 / C-1: その呼び出しの宣言型（とメソッド名）で契約を引く。無ければ null。
     *
     * <p>当たった行には「左辺が一致した」印を付ける。実際に具象型を決められたかは
     * 呼び出し側（{@link CallResolver}）が {@link ContractUsage#markApplied} で付ける。
     * 分けてあるのは、「左辺が一度も一致しない（綴り違い）」と「一致したが右辺を採用できない」を
     * 報告で区別するため。
     *
     * @param declaredType 呼び出し先を宣言している型の FQN
     * @param signature    呼び出し先のシグネチャ（{@code find(java.lang.String)}）
     */
    Contract matchFor(String declaredType, String signature) {
        List<Contract> rows = byType.get(declaredType);
        if (rows == null) {
            return null;
        }
        String methodName = methodNameOf(signature);
        Contract byMethod = null;
        Contract byTypeOnly = null;
        for (Contract c : rows) {
            if (c.methodName().isEmpty()) {
                if (byTypeOnly == null) {
                    byTypeOnly = c;
                }
            } else if (byMethod == null && c.methodName().equals(methodName)) {
                byMethod = c;
            }
        }
        Contract hit = (byMethod != null) ? byMethod : byTypeOnly;
        if (hit != null) {
            usage.markReached(hit.row());
        }
        return hit;
    }

    /**
     * C-3 の索引の鍵＝契約表に書く左辺の正規形。
     * {@link #factoryLeftSidesOf} が呼び出し箇所から組み立てる形と一致させる
     */
    private static String leftSideOf(Contract c) {
        return leftSideOf(c.declaredType() + "#" + c.methodName(), c.key(), c.keyKind());
    }

    private static String leftSideOf(String typeAndName, String key, char keyKind) {
        String inner = switch (keyKind) {
            case Origin.LITERAL -> "\"" + key + "\"";
            case Origin.CLASS -> key + CLASS_SUFFIX;
            default -> key;
        };
        return typeAndName + "(" + inner + ")";
    }

    /** 修飾名（{@code jp.co.app.Kind.USER}）の形か。列挙定数のキーと、ただの書き間違いを分ける */
    private static boolean isQualifiedName(String s) {
        if (s.indexOf('.') < 0) {
            return false;
        }
        for (String part : s.split("\\.", -1)) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))) {
                return false;
            }
            for (int i = 1; i < part.length(); i++) {
                if (!Character.isJavaIdentifierPart(part.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** シグネチャ "find(java.lang.String)" からメソッド名だけを取り出す */
    private static String methodNameOf(String signature) {
        if (signature == null) {
            return "";
        }
        int paren = signature.indexOf('(');
        return (paren < 0) ? signature.trim() : signature.substring(0, paren).trim();
    }
}
