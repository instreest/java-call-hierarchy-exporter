// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse.server;

/**
 * プロトコルの文字列の逃がし方。サーバー側の {@code jche.server.Protocol} と対になる。
 *
 * <p>同じ規則を2か所に書いているのは、プラグイン側が解析本体（{@code jche-core.jar}）を
 * <b>クラスパスに載せない</b>ためである（載せると Eclipse の JDK で読まれてしまい、
 * Java 8 で動かすという目的が崩れる）。規則は短いので、写す方が依存を作るより安い。
 * 変えるときは両方を直すこと（{@code test/plugin-client/run.sh} が往復で検査する）。
 */
final class Wire {

    static final String SEP = "\t";

    private Wire() {
    }

    static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unescape(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                sb.append(c);
                continue;
            }
            char next = value.charAt(++i);
            if (next == 't') {
                sb.append('\t');
            } else if (next == 'n') {
                sb.append('\n');
            } else if (next == 'r') {
                sb.append('\r');
            } else if (next == '\\') {
                sb.append('\\');
            } else {
                sb.append(next);
            }
        }
        return sb.toString();
    }
}
