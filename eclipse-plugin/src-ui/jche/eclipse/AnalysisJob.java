// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.nio.file.Path;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import jche.AnalysisSnapshot;
import jche.Exporter;
import jche.config.Config;
import jche.util.CancelledException;
import jche.util.Log;
import jche.util.RunControl;

/**
 * 解析1回ぶんのジョブ。UI スレッドでは絶対に動かない。
 *
 * <p>やることは 3 つ。設定を読み、{@link Exporter#analyze(Config)} で
 * フェーズ1・2 を回し、呼び出し元の転置索引まで作ってから
 * {@link ProjectAnalysis} に結果を渡す。索引をここで作るのは、
 * 最初にビューを開いた瞬間に UI スレッドで作られるのを避けるため。
 *
 * <p>進捗と中止は {@link RunControl} 経由で解析本体へ渡す。中止されたときは
 * {@link CancelledException} が飛んでくるので、失敗ではなく取り消しとして終わる。
 */
final class AnalysisJob extends Job {

    /** 自動再解析を始めるまでの待ち時間。連続した変更をまとめる（タイピング中に走らせない） */
    static final long AUTO_DELAY_MS = 3000L;

    private final ProjectAnalysis analysis;
    private final ConfigSource configSource;
    private final Set<String> handledChanges;

    AnalysisJob(ProjectAnalysis analysis, ConfigSource configSource, Set<String> handledChanges) {
        super("呼び出し階層の解析: " + analysis.project().getName());
        this.analysis = analysis;
        this.configSource = configSource;
        this.handledChanges = handledChanges;
    }

    /**
     * 何の上で解析したかをログに残す。JDT は動いている JVM の標準クラスを解析対象の
     * クラスパスに含めるので、Eclipse の JVM が変われば結果も変わりうる。
     * 後から結果を見比べたときに、その差を説明できるようにしておく。
     */
    private static void logEnvironment() {
        Log.info("実行 JVM: " + System.getProperty("java.version")
                + "（" + System.getProperty("java.vendor", "?") + "）"
                + " / JDT Core: " + EnvironmentInfo.jdtVersion()
                + " / このJDTの解析上限: Java " + EnvironmentInfo.latestSupportedJavaVersion());
    }

    @Override
    protected IStatus run(IProgressMonitor monitor) {
        monitor.beginTask("解析中", IProgressMonitor.UNKNOWN);
        ExporterConsole console = ExporterConsole.find();
        AnalysisSnapshot result = null;
        String error = null;
        if (console != null) {
            Log.attachSink(console::println);
        }
        RunControl.attach(new RunControl.Listener() {
            @Override
            public void progress(String label, long done, long total) {
                monitor.subTask(total > 0 ? label + " " + done + "/" + total : label + " " + done);
            }

            @Override
            public boolean isCancelled() {
                return monitor.isCanceled();
            }
        });
        try {
            Path cacheRoot = Platform.getStateLocation(
                    Platform.getBundle(JchePlugin.PLUGIN_ID)).toFile().toPath();
            Log.resetClock();
            Log.info("設定: " + configSource.label());
            logEnvironment();
            Config config = configSource.toConfig(cacheRoot);
            AnalysisSnapshot snapshot = Exporter.analyze(config);
            monitor.subTask("呼び出し元の索引を作成中");
            // 索引もここで作る。UI スレッドで作らせない
            snapshot.inbound();
            result = snapshot;
        } catch (CancelledException e) {
            return Status.CANCEL_STATUS;
        } catch (Exception | Error e) {
            error = String.valueOf(e);
            Log.error("解析に失敗しました", e);
        } finally {
            RunControl.detach();
            Log.detachSink();
            Log.detachFile();
            monitor.done();
            analysis.jobFinished(this, result, error, handledChanges);
        }
        if (error != null) {
            return new Status(IStatus.ERROR, JchePlugin.PLUGIN_ID, "解析に失敗しました: " + error);
        }
        return Status.OK_STATUS;
    }
}
