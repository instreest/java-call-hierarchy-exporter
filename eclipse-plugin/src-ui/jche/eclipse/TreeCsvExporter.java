// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import jche.graph.MethodTable;
import jche.report.Csv;

/**
 * いま画面に出ている（＝フィルタ後の）木を、そのまま CSV に書き出す。
 *
 * <p>設定ファイルからの一括出力（{@link ExportCallHierarchyHandler}）とは別物で、
 * こちらは「1つのメソッドについて、画面で絞り込んだ結果」を持ち出すためのもの。
 * 列は画面の見たままに合わせてある（深さ・メソッド・ファイル・行・解決の理由）。
 *
 * <p>木の展開は UI スレッドでは行わない。深さやフィルタしだいでは行数が多くなるため。
 */
final class TreeCsvExporter {

    /** 書き出す行数の上限。これを超えたら打ち切って、その旨を最後の行に書く */
    private static final long MAX_ROWS = 200_000L;

    private TreeCsvExporter() {
    }

    static void export(CallersModel model, CallNode root, String path) {
        Job job = new Job("呼び出し階層をCSVに出力") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                monitor.beginTask("書き出し中", IProgressMonitor.UNKNOWN);
                try {
                    long rows = write(model, root, Paths.get(path), monitor);
                    if (monitor.isCanceled()) {
                        return Status.CANCEL_STATUS;
                    }
                    jche.util.Log.info("画面の呼び出し階層を書き出しました: " + path + "（" + rows + " 行）");
                    return Status.OK_STATUS;
                } catch (IOException e) {
                    return new Status(IStatus.ERROR, JchePlugin.PLUGIN_ID,
                            "CSV の書き出しに失敗しました: " + e, e);
                } finally {
                    monitor.done();
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private static long write(CallersModel model, CallNode root, Path file, IProgressMonitor monitor)
            throws IOException {
        MethodTable methods = model.methods();
        long rows = 0;
        try (BufferedWriter out = Csv.writer(file, Charset.forName("UTF-8"), true)) {
            out.write("depth,method,file,line,reason,note");
            out.newLine();
            Deque<CallNode> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty() && !monitor.isCanceled()) {
                CallNode node = stack.pop();
                out.write(String.valueOf(node.depth()));
                out.write(Csv.DELIM);
                out.write(Csv.esc(methods.key(node.methodId())));
                out.write(Csv.DELIM);
                out.write(Csv.esc(nullToEmpty(methods.declFile(node.methodId()))));
                out.write(Csv.DELIM);
                out.write(String.valueOf(model.callLine(node.edgeIndex())));
                out.write(Csv.DELIM);
                out.write(Csv.esc(nullToEmpty(model.resolutionLabel(node.edgeIndex()))));
                out.write(Csv.DELIM);
                out.write(node.recursive() ? "再帰" : (node.truncated() ? "深さ上限" : ""));
                out.newLine();
                if (++rows >= MAX_ROWS) {
                    out.write("# 行数の上限（" + MAX_ROWS + "）で打ち切りました");
                    out.newLine();
                    break;
                }
                List<CallNode> children = model.childrenOf(node);
                for (int i = children.size() - 1; i >= 0; i--) {
                    stack.push(children.get(i));
                }
            }
        }
        return rows;
    }

    private static String nullToEmpty(String s) {
        return (s == null) ? "" : s;
    }
}
