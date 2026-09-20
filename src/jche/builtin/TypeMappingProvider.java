// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.builtin;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import jche.extension.Hint;
import jche.extension.TypeCandidateProvider;
import jche.extension.UsageReporter;
import jche.util.Log;
import jche.util.Messages;

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
public final class TypeMappingProvider implements TypeCandidateProvider, UsageReporter {

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
    /** 実際に引かれた左辺。設定したのに効いていない行を最後に知らせるために数える */
    private final Set<String> used = new HashSet<>();
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
            Log.warn(Messages.format("extension.typeMapping.noFiles", getClass().getSimpleName(), KEY_FILES));
            return;
        }
        for (String one : raw.split(",")) {
            String name = one.trim();
            if (!name.isEmpty()) {
                Path p = Paths.get(name);
                load(p.isAbsolute() ? p : configDir.resolve(p));
            }
        }
        Log.info(Messages.format("extension.typeMapping.loaded", getClass().getSimpleName(), mapping.size()));
    }

    private void load(Path file) {
        if (!Files.isRegularFile(file)) {
            Log.warn(Messages.format("extension.typeMapping.missingFile", KEY_FILES, file));
            return;
        }
        Properties p = new Properties();
        // properties の既定は ISO-8859-1 なので、日本語のコメントが化けないよう UTF-8 で読む
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            Log.warn(Messages.format("extension.typeMapping.unreadable", file, e));
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
        Log.info(Messages.format("extension.typeMapping.file", file));
    }

    @Override
    public boolean appliesToStaticBound() {
        return appliesToStaticBound;
    }

    @Override
    public String[] candidates(String declaredType, String signature, List<Hint> hints) {
        for (Hint hint : hints) {
            String key = hint.kind() + HINT_SEPARATOR + hint.value();
            String[] byHint = mapping.get(key);
            if (byHint != null) {
                used.add(key);
                return byHint;
            }
        }
        String methodName = methodNameOf(signature);
        if (!methodName.isEmpty()) {
            String key = declaredType + "#" + methodName;
            String[] byMethod = mapping.get(key);
            if (byMethod != null) {
                used.add(key);
                return byMethod;
            }
        }
        String[] byType = mapping.get(declaredType);
        if (byType != null) {
            used.add(declaredType);
        }
        return byType;
    }

    /**
     * 対応表のどの行が引かれたかを知らせる。
     *
     * <p>引かれなかった行には 2 つの原因がある。左辺の綴り違い（効いていない）と、その呼び出しが
     * 先の段で既に絞れていて拡張まで来なかった場合（効かせる必要が無い）。どちらかは機械的には
     * 決められないので、両方を挙げて利用者に確かめてもらう。
     */
    @Override
    public void reportUsage() {
        if (mapping.isEmpty()) {
            return;   // init で既に警告済み
        }
        Log.info(Messages.format("extension.typeMapping.usage", getClass().getSimpleName(),
                used.size(), mapping.size()));
        List<String> unused = new ArrayList<>();
        for (String key : mapping.keySet()) {
            if (!used.contains(key)) {
                unused.add(key);
            }
        }
        if (unused.isEmpty()) {
            return;
        }
        Log.warn(Messages.format("extension.typeMapping.unused", unused.size()));
        for (String key : unused) {
            Log.info("    " + key);
        }
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
