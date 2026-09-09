// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;

/**
 * 「Call Hierarchy Exporter」コンソール。名前で1つだけ作り、以降は使い回す。
 *
 * {@link jche.util.Log} は標準出力に書くが、Eclipse を GUI から起動したときの標準出力は
 * 利用者から見えない。そこで {@code Log.attachSink} でこのコンソールにも同じ行を流す
 * （docs/eclipse-plugin-qa.md の Q5）。
 */
final class ExporterConsole {

    private static final String NAME = "Call Hierarchy Exporter";

    private final MessageConsole console;
    private final MessageConsoleStream stream;

    private ExporterConsole(MessageConsole console) {
        this.console = console;
        this.stream = console.newMessageStream();
    }

    /**
     * すでにあるコンソールを返す。無ければ null（作らない）。
     * 裏で走る解析は、コンソールを勝手に開いてまで記録しない
     */
    static ExporterConsole find() {
        IConsoleManager manager = ConsolePlugin.getDefault().getConsoleManager();
        for (IConsole existing : manager.getConsoles()) {
            if (existing instanceof MessageConsole message && NAME.equals(message.getName())) {
                return new ExporterConsole(message);
            }
        }
        return null;
    }

    /** コンソールを（無ければ作って）前面に出す */
    static ExporterConsole show() {
        IConsoleManager manager = ConsolePlugin.getDefault().getConsoleManager();
        for (IConsole existing : manager.getConsoles()) {
            if (existing instanceof MessageConsole message && NAME.equals(message.getName())) {
                message.activate();
                return new ExporterConsole(message);
            }
        }
        MessageConsole created = new MessageConsole(NAME, null);
        manager.addConsoles(new IConsole[] { created });
        created.activate();
        return new ExporterConsole(created);
    }

    void println(String line) {
        stream.println(line);
    }

    /** 実行ごとに前回の内容を消す。どこまでが今回のログか分からなくなるのを避ける */
    void clear() {
        console.clearConsole();
    }
}
