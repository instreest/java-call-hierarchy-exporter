// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 利用者に見せる文言。既定は<b>英語</b>で、日本語を選んだときだけ日本語を重ねる。
 *
 * <p>対象は「人が読むもの」＝対話モードの画面・解析ログ・エラーの説明である。
 * CSV のセルの中身は<b>対象外</b>で、言語に関わらず英語で固定する（あれは人向けの文章ではなく
 * 機械可読な出力のデータで、期待値との比較や Excel のフィルタに使われるため。
 * {@code docs/nls-qa.md} の Q6）。コメントは今までどおり日本語。
 *
 * <h2>なぜ properties ファイルではないのか</h2>
 * Eclipse プラグイン（{@code jche.eclipse.Messages}）は {@code messages.properties} を
 * {@code ResourceBundle} で読んでいる。あちらは Maven の単一バンドルで、{@code <resources>} が
 * 既に通っているので置くだけで配られる。
 *
 * <p>{@code src/} は事情が違う。ここのクラスは jbang（{@code //SOURCES}）・
 * ルートの {@code pom.xml}（{@code <sourceDirectory>src</sourceDirectory>} だけでリソースの指定が無い）・
 * {@code eclipse-plugin/pom.xml}（{@code jche/**} を取り込んでプラグインの jar に入れる）・
 * VSCode プラグインの配布物・GitHub Actions、そして
 * <b>{@code javac -d <出力先>} で直接コンパイルしている検査スクリプト</b>
 * （{@code test/cachevalue} / {@code conditions} / {@code contracts} /
 * {@code dataflow} / {@code incremental} / {@code server}）から使われる。
 * properties にすると、このすべてに「リソースを一緒に配る」処理が要る。
 * そして 1 か所でも漏れると<b>例外も警告も出ずに、画面にキー名が出るだけ</b>になる。
 * 動かしてみるまで気づけない壊れ方なので採らなかった。
 *
 * <p>文言の表は {@link MessagesEn} / {@link MessagesJa} という<b>ただの Java のクラス</b>に置く。
 * 両エントリポイントの {@code //SOURCES} が既に拾うのでビルド側の変更が要らず、
 * 表が配られていない状態がコンパイル時に起きえない。キーの過不足と差し込みの数は
 * {@code test/nls/run.sh} が突き合わせる。
 *
 * <h2>どの言語で出すか</h2>
 * 先に決めたものが勝つ。
 * <ol>
 *   <li>環境変数 {@code JCHE_LANG}（{@code en} / {@code ja}）</li>
 *   <li>システムプロパティ {@code jche.lang}</li>
 *   <li>設定ファイルの {@code message.language}（{@link #applyConfigured} で後から渡ってくる）</li>
 *   <li>JVM の既定ロケール（＝OS の言語）</li>
 * </ol>
 * 環境変数を一番強くしてあるのは、<b>検査スクリプトと CI が言語を固定できる</b>ようにするためである。
 * 設定ファイルより先に決まるので、設定ファイルを読む前に出るエラー（ファイルが無い等）にも効く。
 *
 * <h2>差し込み</h2>
 * {@code {0}} {@code {1}} … を引数で置き換えるだけで、{@code MessageFormat} は使わない。
 * あれは {@code '} を引用符として解釈するので、英語の {@code don't} のような文が黙って壊れる
 * （壊れるのは英語の側なので、日本語で確かめている限り見つからない）。
 */
public final class Messages {

    /** 土台の言語。訳が足りない項目はここへ落ちる */
    private static final String BASE_LANGUAGE = "en";

    /** 環境変数・システムプロパティで決まった言語。どちらも無ければ null */
    private static final String FORCED = forcedLanguage();

    /**
     * いま引いている言語。
     *
     * <p>{@code volatile} なのは、言語を決めるのと文言を引くのが別のスレッドになりうるため
     * （サーバーモードでは要求を読むスレッドと解析するスレッドが分かれている）。
     * 書き込みは起動時と設定を読んだ直後だけだが、書いた結果が見えないと
     * 片方のスレッドだけ古い言語のままになる
     */
    private static volatile String language = (FORCED != null) ? FORCED : defaultLanguage();

    /** 現在の言語の文言。英語の表に訳を重ねたもの（差し替えるので {@code volatile}） */
    private static volatile Map<String, String> texts = build(language);

    private Messages() {
    }

    /**
     * 設定ファイルの {@code message.language} を反映する。
     *
     * <p>環境変数・システムプロパティで既に決まっているときは<b>何もしない</b>（そちらが優先）。
     * 空欄や知らない言語も無視して、それまでの言語のままにする。
     *
     * @param configured 設定ファイルに書かれた値。未指定なら空文字か null
     */
    public static synchronized void applyConfigured(String configured) {
        if (FORCED != null) {
            return;
        }
        String lang = normalize(configured);
        if (lang == null) {
            return;
        }
        switchTo(lang);
    }

    /**
     * 言語を直接決める。プラグインが自分の画面の言語に解析側を合わせるときに使う
     * （Eclipse は {@code osgi.nl}、VSCode は表示言語）。
     *
     * @param requested {@code en} / {@code ja} など。知らない言語なら何もしない
     */
    public static synchronized void setLanguage(String requested) {
        String lang = normalize(requested);
        if (lang != null) {
            switchTo(lang);
        }
    }

    /** いま文言を出している言語（{@code en} / {@code ja}） */
    public static String language() {
        return language;
    }

    /**
     * 文言を引く。
     *
     * @param key 文言のキー
     * @return その言語の文言。キーが無ければキー自身を {@code !} で囲んだもの（画面で気づけるように）
     */
    public static String get(String key) {
        String text = texts.get(key);
        return (text != null) ? text : "!" + key + "!";
    }

    /**
     * 文言を引いて {@code {0}} {@code {1}} … を差し替える。
     *
     * @param key  文言のキー
     * @param args 差し込む値
     * @return 差し込み済みの文言
     */
    public static String format(String key, Object... args) {
        return substitute(get(key), args);
    }

    // ------------------------------------------------------------------ 組み立て

    private static synchronized void switchTo(String lang) {
        if (!lang.equals(language)) {
            language = lang;
            texts = build(lang);
        }
    }

    /** 英語の表を土台にして、その言語の訳を重ねる。訳が足りない項目は英語のまま出る */
    private static Map<String, String> build(String lang) {
        Map<String, String> map = new HashMap<>(2048);
        put(map, MessagesEn.table());
        if ("ja".equals(lang)) {
            put(map, MessagesJa.table());
        }
        return map;
    }

    /**
     * 分野ごとに分かれた表を取り込む。1 つの表はキーと値が交互に並んだ配列。
     *
     * <p>分野ごとにメソッドを分けてあるのは、1 つのメソッド（やクラス初期化）の大きさに
     * 64KB の上限があるためである。文言が増えても、分野を足せば上限に当たらない。
     */
    private static void put(Map<String, String> map, String[][] tables) {
        for (String[] table : tables) {
            for (int i = 0; i + 1 < table.length; i += 2) {
                map.put(table[i], table[i + 1]);
            }
        }
    }

    /** 環境変数とシステムプロパティを見る。どちらも無ければ null（設定ファイルと OS に任せる） */
    private static String forcedLanguage() {
        String env = normalize(System.getenv("JCHE_LANG"));
        return (env != null) ? env : normalize(System.getProperty("jche.lang"));
    }

    /** JVM の既定ロケール。訳を持たない言語なら土台（英語）にする */
    private static String defaultLanguage() {
        String lang = normalize(Locale.getDefault().getLanguage());
        return (lang != null) ? lang : BASE_LANGUAGE;
    }

    /**
     * 言語の指定を {@code en} / {@code ja} に揃える。
     *
     * <p>国と地域（{@code ja_JP} / {@code ja-JP}）は言語だけ見る。地域まで分ける訳を持つ予定が無く、
     * 持つときになってから足せばよい。訳を持たない言語（{@code fr} など）は null を返し、
     * 呼び出し側でそれまでの言語（初期化時は英語）のままにする。
     */
    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        int sep = indexOfAny(value, '_', '-', '.');
        if (sep >= 0) {
            value = value.substring(0, sep);
        }
        value = value.toLowerCase(Locale.ROOT);
        return (BASE_LANGUAGE.equals(value) || "ja".equals(value)) ? value : null;
    }

    private static int indexOfAny(String value, char a, char b, char c) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == a || ch == b || ch == c) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ 差し込み

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
}
