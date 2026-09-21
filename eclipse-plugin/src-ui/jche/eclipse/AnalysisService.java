// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;

/**
 * プロジェクトごとの解析結果（{@link ProjectAnalysis}）を持ち続ける入れ物。
 *
 * <p>ここが受け持つのは 2 つだけ。
 * <ol>
 *   <li>プロジェクト → 解析結果の対応を持つ</li>
 *   <li>ワークスペースの変更を購読し、変わった {@code *.java} を覚えておく</li>
 * </ol>
 *
 * <p>変更通知は UI スレッドで届く。ここでやるのは集合にパスを足すことだけで、解析はしない
 * （解析は {@link AnalysisJob}）。重い処理をここに書くと、保存やビルドのたびに Eclipse が固まる。
 *
 * <p><b>変更を見つけても、解析はしないし、結果も捨てない。</b>覚えたパスは木の行に ⚠ を出すため
 * だけに使う。解析をやり直す契機も、結果を捨てる契機も、利用者の指示だけである。
 * 使われていない解析プロセスを見回って終わらせる仕掛けは廃止した。結果が勝手に消えるのは、
 * メモリが空くことより困るからである（docs/eclipse-plugin-ui-simplify-qa.md の Q1・Q8）。
 */
public final class AnalysisService {

    /** 解析の状態が変わったことを知りたい側（ビュー）が実装する */
    public interface Listener {
        /** 呼び出しは UI スレッドとは限らない。画面を触るなら asyncExec すること */
        void analysisChanged(ProjectAnalysis analysis);
    }

    private final Map<IProject, ProjectAnalysis> byProject = new HashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private final IResourceChangeListener changeListener = this::resourceChanged;

    void start() {
        // 見るのは POST_CHANGE だけ。ビルド後（POST_BUILD）を見ていたのは自動再解析のためで、
        // その自動再解析をやめたので要らない
        ResourcesPlugin.getWorkspace().addResourceChangeListener(
                changeListener, IResourceChangeEvent.POST_CHANGE);
    }

    void stop() {
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(changeListener);
        List<ProjectAnalysis> all;
        synchronized (this) {
            all = new ArrayList<>(byProject.values());
            byProject.clear();
        }
        for (ProjectAnalysis analysis : all) {
            // 解析の子プロセスも終わらせる。残すとワークスペースを閉じても居座る
            analysis.dispose();
        }
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    void fireChanged(ProjectAnalysis analysis) {
        for (Listener listener : listeners) {
            try {
                listener.analysisChanged(analysis);
            } catch (RuntimeException e) {
                JchePlugin.log(IStatus.WARNING, Messages.get("service.notifyFailed"), e);
            }
        }
    }

    /** そのプロジェクトの解析結果。無ければ作る（作っただけでは解析は始まらない） */
    public synchronized ProjectAnalysis analysisFor(IProject project) {
        return byProject.computeIfAbsent(project, p -> new ProjectAnalysis(this, p));
    }

    /**
     * 動いている解析プロセスを全部終わらせ、持っている解析結果も捨てる。
     * 設定（解析に使う JDK・JDT・JVM 引数・キャッシュの置き場所）を変えたときに使う。
     *
     * <p>結果まで捨てるのは、結果が子プロセスのメモリにあるからである。プロセスだけ終わらせると
     * 画面は「解析済み」のままなのに、木を聞くと「まだ解析していません」と返る食い違いになる。
     */
    public void restartAll() {
        List<ProjectAnalysis> all;
        synchronized (this) {
            all = new ArrayList<>(byProject.values());
        }
        for (ProjectAnalysis analysis : all) {
            analysis.clearAnalysis();
        }
    }

    // ------------------------------------------------------------
    // ワークスペースの変更（UIスレッドで届く。ここでは数えるだけ）
    // ------------------------------------------------------------

    private void resourceChanged(IResourceChangeEvent event) {
        IResourceDelta delta = event.getDelta();
        if (delta == null) {
            return;
        }
        List<ProjectAnalysis> known;
        synchronized (this) {
            if (byProject.isEmpty()) {
                return;
            }
            known = new ArrayList<>(byProject.values());
        }
        Map<IProject, List<IResource>> changed = new HashMap<>();
        try {
            delta.accept(new IResourceDeltaVisitor() {
                @Override
                public boolean visit(IResourceDelta d) {
                    IResource resource = d.getResource();
                    if (resource.getType() == IResource.FILE && isInteresting((IFile) resource)) {
                        changed.computeIfAbsent(resource.getProject(), p -> new ArrayList<>()).add(resource);
                    }
                    return true;
                }
            });
        } catch (CoreException e) {
            JchePlugin.log(IStatus.WARNING, Messages.get("service.deltaFailed"), e);
            return;
        }
        for (ProjectAnalysis analysis : known) {
            List<IResource> files = changed.get(analysis.project());
            if (files != null && !files.isEmpty()) {
                analysis.markChanged(files);
            }
        }
    }

    /**
     * 解析結果に影響しうるファイルか。
     * ソース・依存jar・設定ファイルに加えて .classpath も見る。設定を自動生成している場合、
     * クラスパスの変更はそのまま解析の前提（依存jar・ソースフォルダ）の変更になるため。
     */
    private static boolean isInteresting(IFile file) {
        String name = file.getName();
        return name.endsWith(".java") || name.endsWith(".properties") || name.endsWith(".jar")
                || ".classpath".equals(name);
    }
}
