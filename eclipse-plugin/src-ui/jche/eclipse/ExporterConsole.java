// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;

/**
 * 「Call Hierarchy Exporter」コンソール。名前で1つだけ作り、以降は使い回す。
 *
 * <p>ここへ流れるのは、解析の子プロセスが返すログ（プロトコルの {@code #L} 行）と、
 * その<b>標準エラー</b>の 2 つである。標準エラーには JVM の警告・GC のログ・
 * {@code OutOfMemoryError} のスタックトレースが出る。解析が返ってこないときに見たいのは
 * まさにそれなので、
 * <ul>
 *   <li>解析を始めたら<b>コンソールを前面に出す</b>（{@link #show()}）</li>
 *   <li>標準エラーの行は<b>赤で書く</b>（{@link #printlnError(String)}）。
 *       解析本体のログに紛れて見落とさないようにするため</li>
 * </ul>
 * （docs/eclipse-plugin-progress-log-qa.md の Q6）
 *
 * <p>同じ内容はファイルにも残る（{@link AnalysisLog}）。コンソールは「いま見る」ため、
 * ファイルは「後から見る」ための出口である。
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
    /** 標準エラー用。色を変えて、解析本体のログと見分けられるようにする */
    private final MessageConsoleStream errorStream;

    private ExporterConsole(MessageConsole console) {
        this.console = console;
        this.stream = console.newMessageStream();
        this.errorStream = console.newMessageStream();
        paintErrorStream();
    }

    /** すでにあるコンソールを返す。無ければ null（作らない） */
    static synchronized ExporterConsole find() {
        MessageConsole existing = lookup();
        return (existing == null) ? null : wrap(existing);
    }

    /**
     * コンソールを（無ければ作って）返す。前面には出さない。
     *
     * <p>コンソールは「開いていなくても」内容を溜めるので、作っておけば、
     * あとからビューを開いた利用者にも解析中のログが見える。
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

    /**
     * コンソールを（無ければ作って）返し、<b>コンソールビューを前面に出す</b>。解析の始めに呼ぶ。
     *
     * <p>以前は「前面に出すのは操作の邪魔になる」として開かなかったが、それでは
     * 標準エラー（解析が止まったときの唯一の手がかり）に気付けない。解析はもともと
     * 利用者が押して始めるものなので、その結果としてコンソールが出るのは邪魔にならない
     * （docs/eclipse-plugin-progress-log-qa.md の Q6）。
     */
    static synchronized ExporterConsole show() {
        final ExporterConsole result = getOrCreate();
        // 解析ジョブのスレッドから呼ばれる。ビューを触るので UI スレッドへ回す
        runOnUi(new Runnable() {
            @Override
            public void run() {
                IConsoleManager manager = ConsolePlugin.getDefault().getConsoleManager();
                manager.showConsoleView(result.console);
            }
        });
        return result;
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

    /** 解析本体のログ1行 */
    void println(String line) {
        stream.println(line);
    }

    /** 子プロセスの標準エラー1行。色が付く以外は {@link #println(String)} と同じ */
    void printlnError(String line) {
        errorStream.println(line);
    }

    /**
     * 標準エラーの行を赤にする。
     *
     * <p>色は SWT が持っている既定の色なので、こちらで捨てなくてよい（捨てると Eclipse が壊れる）。
     * {@code Display} に触るには UI スレッドが要るので回す。回せない（ワークベンチが動いていない）
     * ときは何もしない。<b>色が付かないだけで、行は出る</b>。
     */
    private void paintErrorStream() {
        runOnUi(new Runnable() {
            @Override
            public void run() {
                errorStream.setColor(Display.getCurrent().getSystemColor(SWT.COLOR_RED));
            }
        });
    }

    /** UI スレッドで動かす。ワークベンチが動いていなければ何もしない */
    private static void runOnUi(Runnable action) {
        if (!PlatformUI.isWorkbenchRunning()) {
            return;
        }
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(action);
    }
}
