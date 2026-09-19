// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Properties;

/**
 * 画面に出す文言。既定は<b>英語</b>で、Eclipse が日本語で動いているときだけ日本語になる。
 *
 * <p>なぜ要るか: これまで文言はソースに日本語で直接書いてあった。日本語の Eclipse
 * （Pleiades など）を使っている人には読めるが、それ以外の人には読めない。
 * Pleiades は Eclipse 本体の文言を辞書で置き換える仕組みなので、
 * <b>このプラグインの文言は Pleiades を入れても訳されない</b>。
 * 言語ごとのファイルを持つしかない（docs/eclipse-plugin-nls-qa.md）。
 *
 * <ul>
 *   <li>{@code jche/eclipse/messages.properties} … 英語（既定。どの言語でも必ず読む）</li>
 *   <li>{@code jche/eclipse/messages_ja.properties} … 日本語（上に重ねる）</li>
 * </ul>
 *
 * <p>plugin.xml とバンドルの名前（ビュー名・メニューの見出し）は OSGi の仕組みで訳すので、
 * そちらは {@code plugin.properties} / {@code plugin_ja.properties} にある（MANIFEST.MF の
 * {@code Bundle-Localization}）。訳の置き場所が2つに分かれるのは、読む側が
 * 「このクラス」と「OSGi のフレームワーク」で別だからである。
 *
 * <h2>なぜ {@code ResourceBundle} でも {@code NLS} でもないのか</h2>
 * <ol>
 *   <li>{@code java.util.ResourceBundle} と {@code org.eclipse.osgi.util.NLS} は、
 *       Java 8 では properties を <b>ISO-8859-1</b> として読む（UTF-8 になるのは Java 9 から）。
 *       このプラグインは Java 8 の Eclipse でも動かすので、日本語が文字化けする。
 *       ここでは読む文字コードを UTF-8 に固定している</li>
 *   <li>{@code NLS} は {@code org.eclipse.osgi} に入っている。この文言は
 *       {@code jche.eclipse.server}（<b>Eclipse に触らない層</b>。Eclipse 無しでコンパイル・
 *       実行できることを test/plugin-client が検査している）からも使うので、
 *       Eclipse の API に依存させられない</li>
 * </ol>
 * そのため、このクラスは <b>JDK の標準 API だけ</b>で書いてある。
 *
 * <h2>置き換え</h2>
 * 値の差し込みは {@code {0}} {@code {1}} … で、{@link #format} が単純に置き換える
 * （{@code MessageFormat} は使わない。あれは {@code '} を引用符として解釈するので、
 * 英語の {@code don't} のような文が黙って壊れる）。
 */
public final class Messages {

    /** 文言ファイルの置き場所（クラスパス上） */
    private static final String BASE = "jche/eclipse/messages";

    private static final Properties TEXTS = load();

    private Messages() {
    }

    /** 文言を引く。キーが無ければキー自身を {@code !} で囲んで返す（画面で気づけるように） */
    public static String get(String key) {
        String text = TEXTS.getProperty(key);
        return (text == null) ? "!" + key + "!" : text;
    }

    /** 文言を引いて {@code {0}} {@code {1}} … を差し替える */
    public static String format(String key, Object... args) {
        return substitute(get(key), args);
    }

    /**
     * {@code {n}} を args[n] で置き換える。対応する引数が無ければそのまま残す
     * （訳を直すときに「差し込みが足りない」と気づけるように）。
     */
    private static String substitute(String text, Object[] args) {
        if (args == null || args.length == 0 || text.indexOf('{') < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length() + 32);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int end = (c == '{') ? placeholderEnd(text, i) : -1;
            if (end < 0) {
                sb.append(c);
                continue;
            }
            int index = Integer.parseInt(text.substring(i + 1, end));
            if (index >= args.length) {
                sb.append(text, i, end + 1);
            } else {
                sb.append(args[index]);
            }
            i = end;
        }
        return sb.toString();
    }

    /** {@code text[start]} が {@code {数字}} の始まりなら、その {@code }} の位置。違えば -1 */
    private static int placeholderEnd(String text, int start) {
        int i = start + 1;
        while (i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
            i++;
        }
        return (i > start + 1 && i < text.length() && text.charAt(i) == '}') ? i : -1;
    }

    /**
     * 英語を土台にして、その言語のファイルを重ねる。
     * 重ねる形にしておけば、訳が足りない項目は英語のまま出る（空白にはならない）。
     *
     * <p>文言のファイルが1つも読めなくても <b>null は返さない</b>。読めないのは
     * バンドルの組み立てを間違えたときで（検査は test/plugin-nls/run.sh）、そのときは
     * 画面にキー名が出るだけにする。文言のために機能ごと落とさない。
     */
    private static Properties load() {
        Properties english = read(BASE + ".properties", null);
        String language = language();
        if (language.isEmpty() || "en".equals(language)) {
            return (english == null) ? new Properties() : english;
        }
        Properties localized = read(BASE + "_" + language + ".properties", english);
        if (localized != null) {
            return localized;
        }
        return (english == null) ? new Properties() : english;
    }

    /** 読めなければ null（英語すら読めないときは、キー名が画面に出る） */
    private static Properties read(String resource, Properties defaults) {
        InputStream in = Messages.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            return defaults;
        }
        Properties properties = (defaults == null) ? new Properties() : new Properties(defaults);
        try {
            Reader reader = new InputStreamReader(in, Charset.forName("UTF-8"));
            try {
                properties.load(reader);
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            return defaults;
        }
        return properties;
    }

    /**
     * 表示に使う言語。
     *
     * <p>{@code osgi.nl} を先に見るのは、Eclipse の {@code -nl ja}（や eclipse.ini の指定）が
     * ここに入るためである。OS の言語と Eclipse の言語は別に決められるので、
     * <b>Eclipse に合わせる</b>ほうが利用者の期待に合う。Eclipse の外（検査プログラム）では
     * この値が無いので、そのときは JVM の既定にする。
     */
    private static String language() {
        String nl = System.getProperty("osgi.nl", "");
        if (!nl.isEmpty()) {
            int underscore = nl.indexOf('_');
            return ((underscore < 0) ? nl : nl.substring(0, underscore)).toLowerCase(Locale.ROOT);
        }
        String language = Locale.getDefault().getLanguage();
        return (language == null) ? "" : language.toLowerCase(Locale.ROOT);
    }
}
