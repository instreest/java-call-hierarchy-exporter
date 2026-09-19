// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse.server;

import java.io.IOException;

/**
 * 決めた時間内に子プロセスが応答しなかった。
 *
 * <p>ほかの入出力の失敗と区別できるようにしてあるのは、呼び出し側が
 * 「子プロセスを捨てて起動し直す」を選べるようにするためである（解析の
 * {@code ANALYZE} が返らないときは、たいてい子プロセスが詰まっている）。
 * メッセージには最後に届いた進捗と直近のログが入っている
 * （docs/eclipse-plugin-progress-log-qa.md の Q5）。
 */
public class ServerTimeoutException extends IOException {

    private static final long serialVersionUID = 1L;

    ServerTimeoutException(String message) {
        super(message);
    }
}
