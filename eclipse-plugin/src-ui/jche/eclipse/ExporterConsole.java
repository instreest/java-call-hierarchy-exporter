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

    /**
     * 作ったものを使い回す。行ごとに {@code newMessageStream()} を呼ぶと、
     * 1行につき1本のストリームが積み上がる
     */
    private static ExporterConsole cached;

    private final MessageConsole console;
    private final MessageConsoleStream stream;

    private ExporterConsole(MessageConsole console) {
        this.console = console;
        this.stream = console.newMessageStream();
    }

    /** すでにあるコンソールを返す。無ければ null（作らない） */
    static synchronized ExporterConsole find() {
        MessageConsole existing = lookup();
        return (existing == null) ? null : wrap(existing);
    }

    /**
     * コンソールを（無ければ作って）返す。前面には出さない。
     *
     * <p>解析の始めに呼ぶ。コンソールは「開いていなくても」内容を溜めるので、
     * 作っておけば、あとからビューを開いた利用者にも解析中のログが見える。
     * 以前は {@link #find()} しか通らず、<b>解析を始める前にビューを開いていなければ
     * 何も残らなかった</b>（docs/eclipse-plugin-progress-log-qa.md の Q2）
     */
    static synchronized ExporterConsole getOrCreate() {
        MessageConsole existing = lookup();
        if (existing != null) {
            return wrap(existing);
        }
        MessageConsole created = new MessageConsole(NAME, null);
        ConsolePlugin.getDefault().getConsoleManager().addConsoles(new IConsole[] { created });
        return wrap(created);
    }

    private static MessageConsole lookup() {
        IConsoleManager manager = ConsolePlugin.getDefault().getConsoleManager();
        for (IConsole existing : manager.getConsoles()) {
            if (existing instanceof MessageConsole && NAME.equals(((MessageConsole) existing).getName())) {
                return (MessageConsole) existing;
            }
        }
        return null;
    }

    /** 同じコンソールなら前に作ったものを返す。利用者が閉じて作り直したときだけ入れ替える */
    private static ExporterConsole wrap(MessageConsole console) {
        if (cached == null || cached.console != console) {
            cached = new ExporterConsole(console);
        }
        return cached;
    }

    void println(String line) {
        stream.println(line);
    }
}
