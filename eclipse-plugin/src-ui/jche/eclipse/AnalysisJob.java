// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import jche.eclipse.server.ServerConnection;
import jche.eclipse.server.ServerResponse;

/**
 * 解析1回ぶんのジョブ。やるのは「設定をファイルにして、子プロセスに ANALYZE を投げ、待つ」だけ。
 *
 * <p>解析そのものは Eclipse の外で走るので、ここが重い処理をすることはない。
 * それでも Job にしてあるのは、進捗と中止を Eclipse の作法どおりに扱うためである。
 * 中止は子プロセスへ {@code CANCEL} として伝わる。
 */
final class AnalysisJob extends Job {

    /** 自動再解析を始めるまでの待ち時間。連続した変更をまとめる（タイピング中に走らせない） */
    static final long AUTO_DELAY_MS = 3000L;

    /** 解析の応答を待つ上限。大きなプロジェクトでも足りるように長めに取る */
    private static final long ANALYZE_TIMEOUT_MS = 60L * 60L * 1000L;

    private final ProjectAnalysis analysis;
    private final ConfigSource configSource;
    private final Path scratchDir;
    private final Set<String> handledChanges;
    private volatile IProgressMonitor monitor;

    AnalysisJob(ProjectAnalysis analysis, ConfigSource configSource, Path scratchDir,
                Set<String> handledChanges) {
        super("呼び出し階層の解析: " + analysis.project().getName());
        this.analysis = analysis;
        this.configSource = configSource;
        this.scratchDir = scratchDir;
        this.handledChanges = handledChanges;
    }

    /** 子プロセスから届いた進捗を、Eclipse の進捗表示へ移す */
    void reportProgress(String label, long done, long total) {
        IProgressMonitor current = monitor;
        if (current != null) {
            current.subTask(total > 0 ? label + " " + done + "/" + total : label + " " + done);
        }
    }

    @Override
    protected IStatus run(IProgressMonitor progressMonitor) {
        this.monitor = progressMonitor;
        progressMonitor.beginTask("解析中（別プロセス）", IProgressMonitor.UNKNOWN);
        ServerResponse result = null;
        String error = null;
        try {
            ServerConnection connection = analysis.connection();
            Path config = configSource.materialize(scratchDir);
            ExporterConsole console = ExporterConsole.find();
            if (console != null) {
                console.println("解析を開始します（設定: " + configSource.label() + "）");
            }
            ServerResponse response = connection.request(ANALYZE_TIMEOUT_MS,
                    "ANALYZE", config.toAbsolutePath().toString());
            if (response.isOk()) {
                result = response;
            } else if ("cancelled".equals(response.reason())) {
                return Status.CANCEL_STATUS;
            } else {
                error = response.reason();
            }
        } catch (IOException e) {
            error = String.valueOf(e.getMessage());
        } finally {
            this.monitor = null;
            progressMonitor.done();
            analysis.jobFinished(this, result, error, handledChanges);
        }
        if (progressMonitor.isCanceled()) {
            return Status.CANCEL_STATUS;
        }
        if (error != null) {
            return new Status(IStatus.ERROR, JchePlugin.PLUGIN_ID, "解析に失敗しました: " + error);
        }
        return Status.OK_STATUS;
    }

    @Override
    protected void canceling() {
        // Eclipse 側で中止されたら、子プロセスにも伝える
        analysis.cancel();
    }
}
