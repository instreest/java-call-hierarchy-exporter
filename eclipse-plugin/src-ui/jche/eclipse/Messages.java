// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

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
 *   <li>{@code jche/eclipse/messages.properties} … 英語（既定。どの言語でも土台になる）</li>
 *   <li>{@code jche/eclipse/messages_ja.properties} … 日本語（上に重ねる）</li>
 * </ul>
 *
 * <p>plugin.xml とバンドルの名前（ビュー名・メニューの見出し）は OSGi の仕組みで訳すので、
 * そちらは {@code plugin.properties} / {@code plugin_ja.properties} にある（MANIFEST.MF の
 * {@code Bundle-Localization}）。訳の置き場所が2つに分かれるのは、読む側が
 * 「このクラス」と「OSGi のフレームワーク」で別だからである。
 *
 * <h2>なぜ {@code NLS} ではなく {@code ResourceBundle} なのか</h2>
 * {@code org.eclipse.osgi.util.NLS} は {@code org.eclipse.osgi} に入っている。この文言は
 * {@code jche.eclipse.server}（<b>Eclipse に触らない層</b>。Eclipse 無しでコンパイル・実行
 * できることを test/plugin-client が検査している）からも使うので、Eclipse の API に
 * 依存させられない。{@code ResourceBundle} は JDK の標準 API なので、その縛りに掛からない。
 *
 * <p>properties は UTF-8 として読まれる（Java 9 以降。それ以前は ISO-8859-1 だった）。
 * プラグインの下限が Java 11 なので、訳文はそのまま書けて {@code \\uXXXX} への変換は要らない
 * （下限を Java 8 にしていた頃は、この 1 点のために読み込みを自前で書いていた。
 * docs/eclipse-plugin-java-floor-qa.md）。
 *
 * <h2>置き換え</h2>
 * 値の差し込みは {@code {0}} {@code {1}} … で、{@link #format} が単純に置き換える
 * （{@code MessageFormat} は使わない。あれは {@code '} を引用符として解釈するので、
 * 英語の {@code don't} のような文が黙って壊れる）。
 */
public final class Messages {

    /** 文言ファイルの置き場所（クラスパス上） */
    private static final String BASE = "jche.eclipse.messages";

    private static final ResourceBundle TEXTS = load();

    private Messages() {
    }

    /** 文言を引く。キーが無ければキー自身を {@code !} で囲んで返す（画面で気づけるように） */
    public static String get(String key) {
        if (TEXTS != null) {
            try {
                return TEXTS.getString(key);
            } catch (MissingResourceException e) {
                // 下の既定へ落とす
            }
        }
        return "!" + key + "!";
    }

    /** 文言を引いて {@code {0}} {@code {1}} … を差し替える */
    public static String format(String key, Object... args) {
        return substitute(get(key), args);
    }

    /**
     * いま画面に出している言語（{@code en} / {@code ja} など）。
     *
     * <p>解析は別プロセス（{@code --server}）で動き、そのログはこの画面のコンソールに流れる。
     * 子プロセスに {@code -Djche.lang=<この値>} を渡すことで、画面と解析ログの言語がそろう
     * （{@code jche.util.Messages} が同じキーでこの値を読む。docs/nls-qa.md の Q8）。
     *
     * @return 言語コード
     */
    public static String language() {
        return locale().getLanguage();
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
     * その言語の文言を読む。英語のファイルが親になるので、訳が足りない項目は英語で出る。
     *
     * <p>{@code getNoFallbackControl} を使うのは、<b>OS の言語へ勝手に落ちないようにする</b>ためである。
     * {@code ResourceBundle} の既定は「求めた言語が無ければ OS の言語を試し、それも無ければ土台」で、
     * 日本語の Windows で英語の Eclipse を使っている人に日本語が出てしまう。
     * ここで見たいのは Eclipse の言語だけなので、求めた言語 → 土台（英語）の 2 段に限る。
     *
     * <p>文言のファイルが1つも読めなくても <b>例外にしない</b>（null を返して、画面にはキー名が出る）。
     * 読めないのはバンドルの組み立てを間違えたときで（検査は test/plugin-nls/run.sh）、
     * そのために機能ごと落とさない。
     */
    private static ResourceBundle load() {
        try {
            return ResourceBundle.getBundle(BASE, locale(), Messages.class.getClassLoader(),
                    ResourceBundle.Control.getNoFallbackControl(
                            ResourceBundle.Control.FORMAT_PROPERTIES));
        } catch (RuntimeException e) {   // MissingResourceException もここに入る
            return null;
        }
    }

    /**
     * 表示に使う言語。
     *
     * <p>{@code osgi.nl} を先に見るのは、Eclipse の {@code -nl ja}（や eclipse.ini の指定）が
     * ここに入るためである。OS の言語と Eclipse の言語は別に決められるので、
     * <b>Eclipse に合わせる</b>ほうが利用者の期待に合う。Eclipse の外（検査プログラム）では
     * この値が無いので、そのときは JVM の既定にする。
     */
    private static Locale locale() {
        String nl = System.getProperty("osgi.nl", "");
        if (nl.isEmpty()) {
            return Locale.getDefault();
        }
        int underscore = nl.indexOf('_');
        String language = (underscore < 0) ? nl : nl.substring(0, underscore);
        return new Locale(language.toLowerCase(Locale.ROOT));
    }
}
