// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayList;
import java.util.List;

import jche.cache.Origin;
import jche.util.Names;

/**
 * 呼び出し箇所のレシーバが「ファクトリメソッドの戻り値」だったときに、そこから読み取れること。
 *
 * <pre>
 *   Dao dao = DaoFactory.get("USER_DAO");   // dao の出所
 *   dao.find();                             // ← ここのレシーバの出所がこれ
 *
 *   M:jp.co.xxx.DaoFactory#get(java.lang.String)|n=1;0=L:USER_DAO
 *   └ 種別 RETURN      └ ファクトリのメソッドキー             └ 実引数 0 のリテラル
 * </pre>
 *
 * <h2>なぜ 1 か所に寄せるか</h2>
 * 同じ読み取りを 3 か所が使う。契約表の種類 C（{@link TypeContracts}）、拡張に渡す証拠
 * （{@link CallResolver}）、絞れなかった呼び出しのひな形（{@code jche.report.ContractSuggestions}）。
 * 読める形が増えたときに 3 か所が食い違うと、「ひな形が出した行が効かない」「表では引けるのに
 * 拡張には届かない」といった食い違いになるので、読み口はここだけにする。
 *
 * <h2>ソースに書かれた型と、宣言元の型</h2>
 * {@code DaoFactory.get(...)} の {@code get} が親の {@code BaseFactory} で宣言されていると、
 * 出所に載るメソッドキーは<b>宣言元</b>（{@code BaseFactory#get}）になる。利用者がソースを見て
 * 書くのは {@code DaoFactory} のほうなので、書かれた型（{@code s=} で持つ）を先に、
 * 宣言元を次に返す。契約表も拡張もどちらの型でも指定でき、
 * <b>書かれた型で指定すれば、その型で呼んでいる箇所だけに効く</b>（他の子クラス経由は含まれない）。
 *
 * <h2>フェーズAの証拠採取は要らない</h2>
 * ここが読むのは値グラフから組み直した出所（{@link OriginRenderer}）で、キャッシュを再利用した
 * 実行でも同じものが手に入る。AST を走査し直す必要が無いので、キャッシュの指紋にも影響しない。
 */
public final class FactoryCalls {

    /**
     * ファクトリに渡された 1 つのキー。
     *
     * @param typeAndName ファクトリのメソッド（{@code 型FQN#メソッド名}）
     * @param key         渡された値。{@link #readsOf} の表を参照
     * @param kind        キーの種別。{@link #readsOf} の表を参照
     */
    public record Key(String typeAndName, String key, char kind) {
    }

    /** 実引数 1 つから読み取ったキー（{@link Key} から「どのファクトリか」を落とした形） */
    private record Read(String value, char kind) {
    }

    private FactoryCalls() {
    }

    /**
     * レシーバがファクトリの戻り値なら、渡されたキーを<b>実引数の位置の順</b>に返す。
     * ファクトリの戻り値でなければ空。
     *
     * @param recvOrigin レシーバの出所（{@link CallGraph#recvOrigin}）
     * @param dataflow   キーの値を引くのに使う
     * @param ctx        この経路で分かっていること。無ければ null
     */
    public static List<Key> keysOf(String recvOrigin, DataflowResolver dataflow,
                                   DataflowContext ctx) {
        if (recvOrigin == null || Origin.kindOf(recvOrigin) != Origin.RETURN) {
            return List.of();
        }
        List<String> owners = ownersOf(recvOrigin);
        if (owners.isEmpty()) {
            return List.of();
        }
        List<Key> out = new ArrayList<>(owners.size());
        for (String arg : argOriginsOf(Origin.argsOf(recvOrigin))) {
            for (Read read : readsOf(arg, dataflow, ctx)) {
                for (String owner : owners) {
                    out.add(new Key(owner, read.value(), read.kind()));
                }
            }
        }
        return out;
    }

    /**
     * 実引数 1 つから読み取れるキー。読めなければ空。
     *
     * <h4>ここが唯一の追加口</h4>
     * 「ファクトリの何をキーとして扱うか」を決めているのはこのメソッドだけで、
     * 契約表・拡張へ渡す証拠・ひな形の 3 つはすべてここを通る（{@link #keysOf} の説明）。
     * 解析対象の書き方に合わせて種類を足すときは、ここに 1 行足したうえで、
     * 対になる 3 か所（契約表の読み書き {@code TypeContracts}、証拠の種別
     * {@code jche.extension.Hint}、ひな形の見出し）も揃える。
     *
     * <pre>
     *   get("USER")            L  Origin.LITERAL  文字列。コンパイル時定数は値まで評価される
     *   get(Kind.USER)         V  Origin.CONST    列挙定数。値は「型FQN.定数名」
     *   get(UserDao.class)     K  Origin.CLASS    Class リテラル。値は型の FQN
     * </pre>
     *
     * 読めない形（変数・メソッドの戻り値・{@code null}）は<b>何も返さない</b>。
     * 当てずっぽうの値を返すと、違う具象クラスへ静かに解決してしまうため
     */
    private static List<Read> readsOf(String arg, DataflowResolver dataflow, DataflowContext ctx) {
        List<Read> out = new ArrayList<>(2);
        // 文字列のキー。リテラルのほか、コンパイル時定数は値まで評価されたものが返る
        String literal = dataflow.literalValueOf(arg, ctx);
        if (literal != null) {
            out.add(new Read(literal, Origin.LITERAL));
        }
        char kind = Origin.kindOf(arg);
        // 列挙定数は「型FQN.定数名」、Class リテラルは型の FQN が値グラフに載っている
        if (kind == Origin.CONST || kind == Origin.CLASS) {
            String value = Origin.valueOf(arg);
            if (value != null && !value.isEmpty()) {
                out.add(new Read(value, kind));
            }
        }
        return out;
    }

    /**
     * そのファクトリ呼び出しを指すのに使える「型FQN#メソッド名」。
     *
     * <p>先頭は<b>ソースに書かれた型</b>、次が<b>宣言元の型</b>。同じなら 1 つだけ。
     * {@code DaoFactory.get(...)} の {@code get} が親の {@code BaseFactory} で宣言されていると、
     * 利用者がソースを見て書くのは {@code DaoFactory} のほうなので、そちらを先に試す。
     * 宣言元でも書けるようにしてあるのは、親の型で「どの子クラス経由でも」と広く指定したい場合と、
     * 以前から宣言元で書いてある表のため。
     */
    private static List<String> ownersOf(String recvOrigin) {
        String declaring = typeAndNameOf(Origin.valueOf(recvOrigin));
        if (declaring.isEmpty()) {
            return List.of();
        }
        String written = Origin.staticReceiverOf(recvOrigin);
        if (written == null || written.isEmpty()) {
            return List.of(declaring);
        }
        String methodName = declaring.substring(declaring.indexOf('#') + 1);
        return List.of(written + "#" + methodName, declaring);
    }

    /** メソッドキー "jp.co.X#get(java.lang.String)" から "jp.co.X#get" を取り出す */
    private static String typeAndNameOf(String methodKey) {
        if (methodKey == null) {
            return "";
        }
        int paren = methodKey.indexOf('(');
        String head = (paren < 0) ? methodKey.trim() : methodKey.substring(0, paren).trim();
        return (head.indexOf('#') > 0) ? head : "";
    }

    /**
     * 実引数リストから、実引数の出所を<b>位置の順</b>に取り出す（{@code n=} と {@code r=} は除く）。
     *
     * 位置で並べ直すのは、「先頭から最初に当たったキーを使う」という規則を、
     * キャッシュの書き出しの並びに依存させないため
     */
    private static List<String> argOriginsOf(String args) {
        if (args == null || args.isEmpty()) {
            return List.of();
        }
        List<int[]> order = new ArrayList<>(2);        // {位置, origins の添字}
        List<String> origins = new ArrayList<>(2);
        for (String entry : Origin.entriesOf(args)) {
            int eq = entry.indexOf('=');
            if (eq <= 0 || !Character.isDigit(entry.charAt(0))) {
                continue;
            }
            int index = Names.parseIntOr(entry.substring(0, eq), -1);
            if (index < 0) {
                continue;
            }
            order.add(new int[] {index, origins.size()});
            origins.add(Origin.unnest(entry.substring(eq + 1)));
        }
        order.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<String> out = new ArrayList<>(origins.size());
        for (int[] pair : order) {
            out.add(origins.get(pair[1]));
        }
        return out;
    }
}
