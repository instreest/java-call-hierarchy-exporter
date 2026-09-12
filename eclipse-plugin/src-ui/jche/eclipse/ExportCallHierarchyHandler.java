// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

import jche.eclipse.server.ServerLauncher;

/**
 * 「呼び出し階層をCSVに出力」コマンドのハンドラ（設定ファイルからの一括出力）。
 *
 * <p>解析は Eclipse の中ではなく<b>子プロセス</b>で行う。ここがやるのは、選ばれた設定ファイルを
 * コマンドライン版の引数として渡して起動し、その出力をコンソールへ流すことだけである。
 * ビューの「呼び出し元階層」とは別の経路だが、走るコードも JDT も同じ（同梱の lib/）。
 */
public class ExportCallHierarchyHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        List<IFile> configFiles = configFilesOf(selection);
        if (configFiles.isEmpty()) {
            ExporterConsole.show().println("設定ファイル（*.properties）を選んでから実行してください。");
            return null;
        }
        schedule(configFiles);
        return null;
    }

    private static List<IFile> configFilesOf(ISelection selection) {
        List<IFile> files = new ArrayList<>();
        if (selection instanceof IStructuredSelection) {
            for (Iterator<?> it = ((IStructuredSelection) selection).iterator(); it.hasNext();) {
                Object element = it.next();
                if (element instanceof IFile && ((IFile) element).getLocation() != null) {
                    files.add((IFile) element);
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
                monitor.beginTask("解析中（別プロセス）", IProgressMonitor.UNKNOWN);
                Process process = null;
                try {
                    List<String> command = commandFor(configFiles);
                    console.clear();
                    console.println("実行: " + String.join(" ", command));
                    process = new ProcessBuilder(command).redirectErrorStream(true).start();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            process.getInputStream(), ServerLauncher.CHARSET))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            console.println(line);
                            if (monitor.isCanceled()) {
                                process.destroy();
                                return Status.CANCEL_STATUS;
                            }
                        }
                    }
                    int exit = process.waitFor();
                    refreshOutput(configFiles);
                    if (exit != 0) {
                        return new Status(IStatus.ERROR, JchePlugin.PLUGIN_ID,
                                "CSV の出力に失敗しました（終了コード " + exit + "）。コンソールを確認してください。");
                    }
                    return Status.OK_STATUS;
                } catch (IOException e) {
                    return new Status(IStatus.ERROR, JchePlugin.PLUGIN_ID,
                            "解析プロセスを起動できませんでした: " + e.getMessage(), e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (process != null) {
                        process.destroy();
                    }
                    return Status.CANCEL_STATUS;
                } finally {
                    monitor.done();
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    /** コマンドライン版と同じ起動（設定ファイルを並べて渡す） */
    private static List<String> commandFor(List<IFile> configFiles) throws IOException {
        File java = PluginRuntime.findJava();
        if (java == null) {
            throw new IOException("解析に使う JDK（" + PluginRuntime.MINIMUM_JAVA + " 以上）が見つかりません");
        }
        List<File> classpath = PluginRuntime.analysisClasspath();
        List<String> command = new ArrayList<>();
        command.add(java.getAbsolutePath());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-cp");
        StringBuilder cp = new StringBuilder();
        for (File entry : classpath) {
            if (cp.length() > 0) {
                cp.append(File.pathSeparatorChar);
            }
            cp.append(entry.getAbsolutePath());
        }
        command.add(cp.toString());
        command.add("CallHierarchyExporter");
        for (IFile file : configFiles) {
            command.add(file.getLocation().toFile().getAbsolutePath());
        }
        return command;
    }

    /** 出力フォルダはワークスペースの外から書かれるので、Eclipse に知らせる */
    private static void refreshOutput(List<IFile> configFiles) {
        for (IFile file : configFiles) {
            try {
                file.getParent().refreshLocal(IResource.DEPTH_INFINITE, null);
            } catch (CoreException e) {
                JchePlugin.log(IStatus.WARNING,
                        "出力フォルダの更新に失敗しました: " + file.getFullPath(), e);
            }
        }
    }
}
