// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import jche.eclipse.server.ServerConnection;
import jche.eclipse.server.ServerResponse;
import jche.eclipse.server.ServerTimeoutException;

/**
 * 解析1回ぶんのジョブ。やるのは「設定をファイルにして、子プロセスに ANALYZE を投げ、待つ」だけ。
 *
 * <p>解析そのものは Eclipse の外で走るので、ここが重い処理をすることはない。
 * それでも Job にしてあるのは、進捗と中止を Eclipse の作法どおりに扱うためである。
 * 中止は子プロセスへ {@code CANCEL} として伝わる。
 */
final class AnalysisJob extends Job {

    /** 解析の応答を待つ上限。大きなプロジェクトでも足りるように長めに取る */
    private static final long ANALYZE_TIMEOUT_MS = 60L * 60L * 1000L;

    /** 進捗をログへ残す間隔。これより短い間隔の同じ名前の進捗は書かない */
    private static final long PROGRESS_LOG_INTERVAL_MS = 10_000L;

    private final ProjectAnalysis analysis;
    private final ConfigSource configSource;
    private final Path scratchDir;
    private final Set<String> handledChanges;
    private volatile IProgressMonitor monitor;

    /** ログに残した最後の進捗の名前と時刻。同じ名前の進捗を毎回書くと、ログが進捗で埋まる */
    private String loggedLabel;
    private long loggedAt;

    AnalysisJob(ProjectAnalysis analysis, ConfigSource configSource, Path scratchDir,
                Set<String> handledChanges) {
        super(Messages.format("job.analyze", analysis.project().getName()));
        this.analysis = analysis;
        this.configSource = configSource;
        this.scratchDir = scratchDir;
        this.handledChanges = handledChanges;
    }

    /** 子プロセスから届いた進捗を、Eclipse の進捗表示へ移し、間引いてログにも残す */
    void reportProgress(String label, long done, long total) {
        String text = (total > 0) ? (label + " " + done + "/" + total) : (label + " " + done);
        IProgressMonitor current = monitor;
        if (current != null) {
            current.subTask(text);
        }
        logProgress(label, text);
    }

    /**
     * 進捗をログにも残す。名前が変わったときと、同じ名前が
     * {@link #PROGRESS_LOG_INTERVAL_MS} 続いたときだけ書く。
     * 進捗はファイル1つごとに来るので、そのまま書くとログが進捗で埋まる
     */
    private synchronized void logProgress(String label, String text) {
        long now = System.currentTimeMillis();
        if (label.equals(loggedLabel) && now - loggedAt < PROGRESS_LOG_INTERVAL_MS) {
            return;
        }
        loggedLabel = label;
        loggedAt = now;
        AnalysisLog.get().println(analysis.project().getName(),
                Messages.format("job.progressLog", text));
    }

    @Override
    protected IStatus run(IProgressMonitor progressMonitor) {
        this.monitor = progressMonitor;
        progressMonitor.beginTask(Messages.get("job.analyzing"), IProgressMonitor.UNKNOWN);
        // 解析を始めたらコンソールを前面に出す。子プロセスの標準エラー（JVM の警告や
        // OutOfMemoryError の痕跡）はここにしか流れてこないので、見えるところに置く
        ExporterConsole console = ExporterConsole.show();
        long started = System.currentTimeMillis();
        ServerResponse result = null;
        String error = null;
        try {
            ServerConnection connection = analysis.connection();
            Path config = configSource.materialize(scratchDir);
            String start = Messages.format("job.started", configSource.label());
            console.println(start);
            AnalysisLog.get().println(analysis.project().getName(), start);
            ServerResponse response = connection.request(ANALYZE_TIMEOUT_MS,
                    "ANALYZE", config.toAbsolutePath().toString());
            if (response.isOk()) {
                result = response;
            } else if ("cancelled".equals(response.reason())) {
                return Status.CANCEL_STATUS;
            } else {
                error = response.reason();
            }
        } catch (ServerTimeoutException e) {
            // 返ってこない子プロセスは、そのままにすると次の解析もそれを待つことになる。
            // 捨てて、次の解析で起動し直す（docs/eclipse-plugin-progress-log-qa.md の Q5）
            analysis.discardServer();
            error = e.getMessage() + "\n" + Messages.get("job.serverStopped");
        } catch (IOException e) {
            error = String.valueOf(e.getMessage());
        } finally {
            this.monitor = null;
            progressMonitor.done();
            finish(started, result, error, progressMonitor.isCanceled());
            analysis.jobFinished(this, result, error, handledChanges);
        }
        if (progressMonitor.isCanceled()) {
            return Status.CANCEL_STATUS;
        }
        if (error != null) {
            return new Status(IStatus.ERROR, JchePlugin.PLUGIN_ID,
                    Messages.format("analysis.failed", error));
        }
        return Status.OK_STATUS;
    }

    /** 終わり方（成功・中止・失敗）と所要時間を、コンソールとログファイルの両方へ残す */
    private void finish(long started, ServerResponse result, String error, boolean cancelled) {
        long seconds = (System.currentTimeMillis() - started) / 1000L;
        String line;
        if (error != null) {
            line = Messages.format("job.finishedFailed", Long.valueOf(seconds), error);
        } else if (result == null || cancelled) {
            line = Messages.format("job.finishedCancelled", Long.valueOf(seconds));
        } else {
            line = Messages.format("job.finishedOk", Long.valueOf(seconds));
        }
        ExporterConsole console = ExporterConsole.find();
        if (console != null) {
            // 失敗は標準エラーと同じ赤で書く。溜まったログの中で終わり方が目に入るように
            if (error != null) {
                console.printlnError(line);
            } else {
                console.println(line);
            }
        }
        AnalysisLog.get().println(analysis.project().getName(), line);
        File file = AnalysisLog.get().file();
        if (error != null && file != null && console != null) {
            console.println(Messages.format("job.logAt", file.getAbsolutePath()));
        }
    }

    @Override
    protected void canceling() {
        // Eclipse 側で中止されたら、子プロセスにも伝える
        analysis.cancel();
    }
}
