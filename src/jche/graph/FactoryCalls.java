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
 * <h2>フェーズAの証拠採取は要らない</h2>
 * ここが読むのは値グラフから組み直した出所（{@link OriginRenderer}）で、キャッシュを再利用した
 * 実行でも同じものが手に入る。AST を走査し直す必要が無いので、キャッシュの指紋にも影響しない。
 */
public final class FactoryCalls {

    /**
     * ファクトリに渡された 1 つのキー。
     *
     * @param typeAndName ファクトリのメソッド（{@code 型FQN#メソッド名}）
     * @param key         渡された値。文字列そのもの、または列挙定数の {@code 型FQN.定数名}
     * @param kind        {@link Origin#LITERAL}（文字列）か {@link Origin#CONST}（列挙定数）
     */
    public record Key(String typeAndName, String key, char kind) {
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
        String typeAndName = typeAndNameOf(Origin.valueOf(recvOrigin));
        if (typeAndName.isEmpty()) {
            return List.of();
        }
        List<Key> out = new ArrayList<>(1);
        for (String arg : argOriginsOf(Origin.argsOf(recvOrigin))) {
            // 文字列のキー。リテラルのほか、コンパイル時定数は値まで評価されたものが返る
            String literal = dataflow.literalValueOf(arg, ctx);
            if (literal != null) {
                out.add(new Key(typeAndName, literal, Origin.LITERAL));
            }
            // 列挙定数のキー。値グラフには「型FQN.定数名」で載っている
            if (Origin.kindOf(arg) == Origin.CONST) {
                String name = Origin.valueOf(arg);
                if (!name.isEmpty()) {
                    out.add(new Key(typeAndName, name, Origin.CONST));
                }
            }
        }
        return out;
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
