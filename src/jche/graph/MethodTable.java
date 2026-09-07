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
package jche.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import jche.cache.MethodRef;

/**
 * メソッドを int の ID に内部化する表。
 *
 * 保持するのはメソッドごとに文字列2本（キーとパッケージ名）と、
 * 宣言ファイル・宣言行だけ。型名・メソッド名はキーから切り出せるので持たない。
 * キー形式: typeFqn#methodName(paramSig)
 */
public final class MethodTable {

    private final HashMap<String, Integer> idByKey = new HashMap<>(1 << 16);
    private final ArrayList<String> keys = new ArrayList<>();
    private final ArrayList<String> pkgs = new ArrayList<>();

    /** 宣言情報（ソースがあるメソッドのみ設定される） */
    private final ArrayList<String> declFiles = new ArrayList<>();
    private final IntArray declLines = new IntArray(1 << 16);
    /**
     * 本体を持つか。既定はtrue（＝候補になりうる）。
     * D行が無いメソッド（jar内のメソッド等）はソースが無く展開もできないため、
     * 安全側に倒して候補から落とさない。
     */
    private final ArrayList<Boolean> hasBody = new ArrayList<>();

    /** 引数型略名が衝突しているラベル。初回の displayLabel() で一度だけ作る */
    private Set<String> ambiguous;

    public int intern(MethodRef ref) {
        return intern(ref.pkg(), ref.typeFqn(), ref.name(), ref.paramSig());
    }

    public int intern(String pkg, String typeFqn, String method, String params) {
        String key = typeFqn + "#" + method + "(" + params + ")";
        Integer id = idByKey.get(key);
        if (id != null) {
            return id;
        }
        int newId = keys.size();
        idByKey.put(key, newId);
        keys.add(key);
        pkgs.add(pkg == null ? "" : pkg);
        declFiles.add(null);
        declLines.add(-1);
        hasBody.add(Boolean.TRUE);
        return newId;
    }

    /** キーからIDを引く。未登録なら -1 */
    public int idOf(String key) {
        Integer id = idByKey.get(key);
        return (id == null) ? -1 : id;
    }

    public void setDeclaration(int id, String file, int line, boolean body) {
        declFiles.set(id, file);
        declLines.set(id, line);
        hasBody.set(id, body);
    }

    public boolean hasBody(int id) {
        return hasBody.get(id);
    }

    /** ソース上に宣言があるか（jar内のメソッドには無い） */
    public boolean hasSource(int id) {
        return declFiles.get(id) != null;
    }

    public int size() {
        return keys.size();
    }

    /** キー全体（typeFqn#methodName(paramSig)） */
    public String key(int id) {
        return keys.get(id);
    }

    /** キーのうち "#" 以降（methodName(paramSig)）。同名同引数の照合に使う */
    public String signature(int id) {
        String k = keys.get(id);
        return k.substring(k.indexOf('#') + 1);
    }

    /**
     * キーのうち括弧内（完全修飾の引数型をカンマ区切りにしたもの）。
     *
     * 開き括弧は "#" より後ろから探す。型名に括弧が混ざる可能性があるのは
     * typeNameOf() が最終手段でJDT内部キーを使った場合だけだが、そこで
     * 引数リストの切り出しがずれると別メソッドと同一視されてしまう。
     */
    private String rawParams(int id) {
        String k = keys.get(id);
        return k.substring(k.indexOf('(', k.indexOf('#')) + 1, k.lastIndexOf(')'));
    }

    public String pkg(int id) {
        return pkgs.get(id);
    }

    public String typeFqn(int id) {
        String k = keys.get(id);
        return k.substring(0, k.indexOf('#'));
    }

    public String methodName(int id) {
        String k = keys.get(id);
        return k.substring(k.indexOf('#') + 1, k.indexOf('('));
    }

    public boolean isConstructor(int id) {
        return MethodRef.CONSTRUCTOR.equals(methodName(id));
    }

    /** クラスの単純名（内部クラスは Outer.Inner の形を保つ） */
    public String simpleTypeName(int id) {
        String t = typeFqn(id);
        String p = pkgs.get(id);
        if (p.isEmpty()) {
            // パッケージが無いので、typeFqn全体がそのままクラスの入れ子構造を表す
            // （lastIndexOf('.')で末尾だけ切り出すと、デフォルトパッケージ上の
            //   内部クラスで外側のクラス名が失われてしまう）
            return t;
        }
        if (t.startsWith(p + ".")) {
            return t.substring(p.length() + 1);
        }
        int i = t.lastIndexOf('.');
        return (i >= 0) ? t.substring(i + 1) : t;
    }

    /**
     * 表示用のメソッド名。
     *
     * コンストラクタは内部的には {@code <init>} だが、ソース上の名前はクラスの単純名。
     * 利用者が読むのはソースなので、表示は単純名に寄せる。
     * 暗黙のデフォルトコンストラクタも、補完されるとクラス名になるので同じ扱い。
     */
    public String displayMethodName(int id) {
        String m = methodName(id);
        if (!MethodRef.CONSTRUCTOR.equals(m)) {
            return m;
        }
        String simple = simpleTypeName(id);
        int dot = simple.lastIndexOf('.');
        return (dot >= 0) ? simple.substring(dot + 1) : simple;
    }

    /** 単純クラス名.表示用メソッド名。call-hierarchy 列と root 列の表記 */
    public String shortLabel(int id) {
        return simpleTypeName(id) + "." + displayMethodName(id);
    }

    /**
     * 単純クラス名 + 表示用メソッド名 + 引数型略名。オーバーロードを識別できる短い表記。
     * 略名が衝突している場合は callee 列と同じ理由で完全修飾の引数に戻す。
     */
    public String shortLabelWithParams(int id) {
        String params = ambiguousLabels().contains(plainDisplayLabel(id))
                ? rawParams(id) : shortParams(id);
        return simpleTypeName(id) + "." + displayMethodName(id) + "(" + params + ")";
    }

    /** 完全修飾クラス名 + 表示用メソッド名 + 完全修飾の引数リスト */
    public String fullSignature(int id) {
        return typeFqn(id) + "." + displayMethodName(id) + "(" + rawParams(id) + ")";
    }

    /**
     * call-hierarchy.csv の callee 列の表記。
     * 完全修飾クラス名 + 表示用メソッド名 + 引数型略名。
     *
     * 引数型を略名にするのは読みやすさのためだが、略した結果
     * java.util.List と other.List のように別物が同じ表記になることがある。
     * 「識別できる表記にする」のが目的の列でそれが起きては本末転倒なので、
     * 衝突した組だけ完全修飾の引数リストに戻す（下の ambiguousLabels()）。
     */
    public String displayLabel(int id) {
        String label = plainDisplayLabel(id);
        return ambiguousLabels().contains(label) ? fullSignature(id) : label;
    }

    private String plainDisplayLabel(int id) {
        return typeFqn(id) + "." + displayMethodName(id) + "(" + shortParams(id) + ")";
    }

    /**
     * 引数型略名が衝突しているラベルの集合。
     *
     * 一度だけ全メソッドを走査して作る。走査用のSetは作業後に捨て、
     * 残すのは衝突したラベルだけ（通常は0件）なので、常時のメモリは増えない。
     * キーは重複しないので、同じラベルが2回出た時点で必ず引数が違う。
     */
    private Set<String> ambiguousLabels() {
        if (ambiguous != null) {
            return ambiguous;
        }
        Set<String> seen = new HashSet<>(keys.size() * 2);
        Set<String> dup = new LinkedHashSet<>();
        for (int id = 0; id < keys.size(); id++) {
            String label = plainDisplayLabel(id);
            if (!seen.add(label)) {
                dup.add(label);
            }
        }
        ambiguous = dup;
        return ambiguous;
    }

    /** 完全修飾の引数リストを、型ごとに略名へ置き換えたもの */
    private String shortParams(int id) {
        String raw = rawParams(id);
        if (raw.isEmpty()) {
            return "";
        }
        // 引数型は toRef() で消去済み（getErasure）なので、ジェネリクスの
        // 山括弧が入ることはない。よってカンマで素直に分割できる
        StringBuilder sb = new StringBuilder(raw.length());
        int start = 0;
        while (start <= raw.length()) {
            int comma = raw.indexOf(',', start);
            int end = (comma < 0) ? raw.length() : comma;
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(simpleParamName(raw.substring(start, end)));
            if (comma < 0) {
                break;
            }
            start = comma + 1;
        }
        return sb.toString();
    }

    /**
     * 引数型1つぶんの略名。java.lang.String → String、java.lang.String[] → String[]。
     *
     * 内部クラス（fn.Outer.Inner）は末尾だけを取って Inner になる。名前だけでは
     * どこまでがパッケージでどこからが外側クラスか決められないため（大文字小文字の
     * 慣習に頼ると、その慣習に従っていないコードで誤る）。
     * これで別物が同じ表記になった場合は displayLabel() が完全修飾に戻す。
     */
    private static String simpleParamName(String fq) {
        int arr = fq.indexOf('[');
        String base = (arr < 0) ? fq : fq.substring(0, arr);
        String suffix = (arr < 0) ? "" : fq.substring(arr);
        int dot = base.lastIndexOf('.');
        return ((dot >= 0) ? base.substring(dot + 1) : base) + suffix;
    }

    /** 宣言ファイル（プロジェクトルートからの相対パス）。ソースが無ければ null */
    public String declFile(int id) {
        return declFiles.get(id);
    }

    public int declLine(int id) {
        return declLines.get(id);
    }
}
