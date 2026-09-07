// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

import jche.Exporter;
import jche.util.Log;

/**
 * 「呼び出し階層をCSVに出力」コマンドのハンドラ。
 *
 * 選択された設定ファイル（1つ以上の {@code *.properties}）を、コマンドラインと同じ
 * {@link Exporter#run(List, Path)} に渡すだけ。解析そのものはプラグイン側に一切持たない
 * （持つと CLI と Eclipse で挙動が割れるため。docs/eclipse-plugin-qa.md の Q4）。
 *
 * 解析は数分かかりうるので {@link Job} に載せて画面を止めない。ログは
 * 「Call Hierarchy Exporter」コンソールに出す。Eclipse では標準出力が利用者から見えないため
 * （同 Q5）。
 */
public class ExportCallHierarchyHandler extends AbstractHandler {

    /** MANIFEST.MF の Bundle-SymbolicName（singleton 指定は含まない） */
    private static final String BUNDLE_ID = "io.github.instreest.jche.eclipse";

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        List<IFile> configFiles = configFilesOf(selection);
        if (configFiles.isEmpty()) {
            // visibleWhen で絞ってあるので通常は起きない。キーバインドから呼ばれたときの保険
            ExporterConsole.show().println("設定ファイル（*.properties）を選んでから実行してください。");
            return null;
        }
        schedule(configFiles);
        return null;
    }

    /** 選択のうち、ワークスペース上の実ファイルとして場所が分かる *.properties だけを取り出す */
    private static List<IFile> configFilesOf(ISelection selection) {
        List<IFile> files = new ArrayList<>();
        if (selection instanceof IStructuredSelection structured) {
            for (Iterator<?> it = structured.iterator(); it.hasNext();) {
                if (it.next() instanceof IFile file && file.getLocation() != null) {
                    files.add(file);
                }
            }
        }
        return files;
    }

    private static void schedule(List<IFile> configFiles) {
        ExporterConsole console = ExporterConsole.show();
        Job job = new Job("呼び出し階層をCSVに出力") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                monitor.beginTask("解析中", IProgressMonitor.UNKNOWN);
                List<Path> paths = new ArrayList<>();
                for (IFile file : configFiles) {
                    paths.add(file.getLocation().toFile().toPath());
                }
                console.clear();
                Log.attachSink(console::println);
                int failed;
                try {
                    // キャッシュはプラグインの状態フォルダ（ワークスペースの .metadata 配下）へ。
                    // jar の中で動くので、CLI のようにツールのフォルダを目印から探せない
                    Path cacheRoot = Platform.getStateLocation(
                            Platform.getBundle(BUNDLE_ID)).toFile().toPath();
                    failed = Exporter.run(paths, cacheRoot);
                } catch (RuntimeException | Error e) {
                    // run() は設定ごとに握りつぶすので、ここに来るのは想定外の失敗だけ。
                    // それでも Job の外へ投げず、コンソールとステータスの両方に残す
                    Log.error("呼び出し階層の出力に失敗しました", e);
                    return error("呼び出し階層の出力に失敗しました: " + e);
                } finally {
                    Log.detachSink();
                    Log.detachFile();
                    refreshOutput(configFiles);
                    monitor.done();
                }
                if (failed > 0) {
                    return error(failed + " 件の設定ファイルで失敗しました。コンソールを確認してください。");
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private static IStatus error(String message) {
        return new Status(IStatus.ERROR, BUNDLE_ID, message);
    }

    /**
     * 出力フォルダはワークスペースの外から書かれるので、Eclipse はできたファイルを知らない。
     * 設定ファイルのあるフォルダを更新して、CSV がパッケージ・エクスプローラーに出るようにする。
     */
    private static void refreshOutput(List<IFile> configFiles) {
        for (IFile file : configFiles) {
            try {
                file.getParent().refreshLocal(IResource.DEPTH_INFINITE, null);
            } catch (CoreException e) {
                ResourcesPlugin.getPlugin().getLog().log(
                        new Status(IStatus.WARNING, BUNDLE_ID,
                                "出力フォルダの更新に失敗しました: " + file.getFullPath(), e));
            }
        }
    }
}
