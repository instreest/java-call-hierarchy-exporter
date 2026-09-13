// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.builtin;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import jche.extension.Hint;
import jche.extension.TypeCandidateProvider;
import jche.util.Log;

/**
 * 同梱のフェーズB拡張: 対応表ファイルを引いて具象型を返す。
 *
 * DI 設定ファイルによるインスタンス特定も、ファクトリメソッドの生成条件も、突き詰めると
 * 「この宣言型（またはこの証拠）のときは、この具象クラス」という対応表になる。
 * その対応表さえ書けば済むよう、Java を書かずに使えるものを同梱している。
 * 対応表を機械的に作れない場合（設定ファイルの形式が独特、条件が複雑）だけ、
 * {@link TypeCandidateProvider} を自分で実装すればよい。
 *
 * <pre>
 *   # config.properties
 *   resolver.candidate.providers=jche.builtin.TypeMappingProvider
 *   plugin.mapping.files=di-mapping.properties
 * </pre>
 *
 * 対応表（UTF-8 の properties 形式。値はカンマ区切りで複数書ける）:
 * <pre>
 *   # 1) 宣言型 -> 具象型（DI 設定から機械的に書き出せる形）
 *   jp.co.xxx.dao.UserDao = jp.co.xxx.dao.UserDaoImpl
 *
 *   # 2) 宣言型#メソッド -> 具象型（同じ型でもメソッドによって実装が違う場合）
 *   jp.co.xxx.dao.UserDao#find = jp.co.xxx.dao.CachedUserDao
 *
 *   # 3) 証拠 -> 具象型（フェーズAが拾ったキーで引く。ファクトリメソッド用）
 *   #    区切りは @（properties では : と = が区切り文字なので使えない）
 *   FACTORY_KEY@USER_DAO = jp.co.xxx.dao.UserDaoImpl
 * </pre>
 *
 * 引く順番は 3（証拠）→ 2（型＋メソッド）→ 1（型）。呼び出し箇所ごとの情報である証拠を
 * 最優先にし、そこに無ければ型全体の既定に落とす。
 */
public final class TypeMappingProvider implements TypeCandidateProvider {

    /** 対応表ファイル（設定ファイルのフォルダからの相対、カンマ区切り） */
    public static final String KEY_FILES = "plugin.mapping.files";
    /** CSV の由来ラベルを変えたいとき */
    public static final String KEY_LABEL = "plugin.mapping.label";
    /** 段0（静的束縛）の呼び出しにもこの拡張を適用するか */
    public static final String KEY_STATIC_BOUND = "plugin.mapping.applies.to.static.bound";

    private static final String DEFAULT_LABEL = "MAPPING";
    /**
     * 対応表で「証拠の種別」と「値」を区切る文字。
     *
     * properties は {@code :} と {@code =} をキーと値の区切りとして読むため、
     * {@code FACTORY_KEY:USER_DAO} と書くとキーが {@code FACTORY_KEY} になってしまう。
     * 書き手が気づきにくい落とし穴なので、区切りにはどちらでもない文字を使う。
     */
    public static final String HINT_SEPARATOR = "@";

    private final Map<String, String[]> mapping = new LinkedHashMap<>();
    private String label = DEFAULT_LABEL;
    private boolean appliesToStaticBound;

    @Override
    public void init(Properties config, Path configDir) {
        this.label = config.getProperty(KEY_LABEL, DEFAULT_LABEL).trim();
        if (this.label.isEmpty()) {
            this.label = DEFAULT_LABEL;
        }
        this.appliesToStaticBound =
                Boolean.parseBoolean(config.getProperty(KEY_STATIC_BOUND, "false").trim());
        String raw = config.getProperty(KEY_FILES, "").trim();
        if (raw.isEmpty()) {
            Log.warn(getClass().getSimpleName() + ": " + KEY_FILES + " が空欄です（対応表が無いので何も解決しません）");
            return;
        }
        for (String one : raw.split(",")) {
            String name = one.trim();
            if (!name.isEmpty()) {
                Path p = Paths.get(name);
                load(p.isAbsolute() ? p : configDir.resolve(p));
            }
        }
        Log.info("[plugin] " + getClass().getSimpleName() + ": 対応表 " + mapping.size() + " 件");
    }

    private void load(Path file) {
        if (!Files.isRegularFile(file)) {
            Log.warn(KEY_FILES + " のファイルがありません: " + file);
            return;
        }
        Properties p = new Properties();
        // properties の既定は ISO-8859-1 なので、日本語のコメントが化けないよう UTF-8 で読む
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            Log.warn("対応表を読めません: " + file + " (" + e + ")");
            return;
        }
        for (String key : p.stringPropertyNames()) {
            List<String> values = new ArrayList<>();
            for (String v : p.getProperty(key, "").split(",")) {
                String t = v.trim();
                if (!t.isEmpty()) {
                    values.add(t);
                }
            }
            if (!values.isEmpty()) {
                mapping.put(key.trim(), values.toArray(new String[0]));
            }
        }
        Log.info("[plugin] 対応表: " + file);
    }

    @Override
    public boolean appliesToStaticBound() {
        return appliesToStaticBound;
    }

    @Override
    public String[] candidates(String declaredType, String signature, List<Hint> hints) {
        for (Hint hint : hints) {
            String[] byHint = mapping.get(hint.kind() + HINT_SEPARATOR + hint.value());
            if (byHint != null) {
                return byHint;
            }
        }
        String methodName = methodNameOf(signature);
        if (!methodName.isEmpty()) {
            String[] byMethod = mapping.get(declaredType + "#" + methodName);
            if (byMethod != null) {
                return byMethod;
            }
        }
        return mapping.get(declaredType);
    }

    /** シグネチャ "find(java.lang.String)" からメソッド名だけを取り出す */
    private static String methodNameOf(String signature) {
        if (signature == null) {
            return "";
        }
        int paren = signature.indexOf('(');
        return (paren < 0) ? signature.trim() : signature.substring(0, paren).trim();
    }

    @Override
    public String label() {
        return label;
    }
}
