// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

import jche.util.Messages;

/**
 * 文言の引き方の検査。{@code jche.util.Messages} だけを読み、実際に何語で出るかを 1 行で書き出す。
 *
 * <p>JDT に触らないので、JDT の jar 無しで（javac だけで）動かせる。
 * 言語は環境変数・システムプロパティ・設定ファイルの順で決まるが、
 * ここでは前の 2 つと、設定ファイル相当の {@link Messages#applyConfigured} を見る。
 *
 * <pre>
 *   java NlsProbe                    … いまの言語と、いくつかのキーの値を出す
 *   java NlsProbe configured=ja      … 設定ファイルで ja を指定した場合
 * </pre>
 */
public final class NlsProbe {

    private NlsProbe() {
    }

    /**
     * @param args {@code configured=<言語>} を渡すと {@link Messages#applyConfigured} を呼ぶ
     */
    public static void main(String[] args) {
        for (String a : args) {
            if (a.startsWith("configured=")) {
                Messages.applyConfigured(a.substring("configured=".length()));
            }
        }
        System.out.println("language=" + Messages.language());
        System.out.println("menu=" + Messages.get("cli.menu.quit"));
        System.out.println("format=" + Messages.format("cli.select.noFile", "X.properties"));
        System.out.println("missing=" + Messages.get("no.such.key"));
    }
}
