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
 *   <li>ワークスペースの変更を購読し、変わった {@code *.java} を「未反映」として数える</li>
 * </ol>
 *
 * <p>変更通知は UI スレッドで届く。ここでやるのは集合にパスを足すことだけで、解析はしない
 * （解析は {@link AnalysisJob}）。重い処理をここに書くと、保存やビルドのたびに Eclipse が固まる。
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
        ResourcesPlugin.getWorkspace().addResourceChangeListener(
                changeListener, IResourceChangeEvent.POST_CHANGE | IResourceChangeEvent.POST_BUILD);
    }

    void stop() {
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(changeListener);
        List<ProjectAnalysis> all;
        synchronized (this) {
            all = new ArrayList<>(byProject.values());
            byProject.clear();
        }
        for (ProjectAnalysis analysis : all) {
            analysis.cancel();
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
                JchePlugin.log(IStatus.WARNING, "解析状態の通知でエラーが起きました", e);
            }
        }
    }

    /** そのプロジェクトの解析結果。無ければ作る（作っただけでは解析は始まらない） */
    public synchronized ProjectAnalysis analysisFor(IProject project) {
        return byProject.computeIfAbsent(project, p -> new ProjectAnalysis(this, p));
    }

    /** 解析済みのプロジェクトだけを返す（ビューの初期表示用） */
    public synchronized List<ProjectAnalysis> analyzed() {
        List<ProjectAnalysis> result = new ArrayList<>();
        for (ProjectAnalysis analysis : byProject.values()) {
            if (analysis.snapshot() != null) {
                result.add(analysis);
            }
        }
        return result;
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
            JchePlugin.log(IStatus.WARNING, "ワークスペースの変更を読み取れませんでした", e);
            return;
        }
        boolean afterBuild = event.getType() == IResourceChangeEvent.POST_BUILD;
        for (ProjectAnalysis analysis : known) {
            List<IResource> files = changed.get(analysis.project());
            if (files != null && !files.isEmpty()) {
                analysis.markChanged(files);
            }
            if (afterBuild) {
                // 自動再解析は「ビルドが終わって静かになってから」。タイピング中には走らせない
                analysis.scheduleAutoAnalysisIfNeeded();
            }
        }
    }

    /** 解析結果に影響しうるファイルか。ソースと設定ファイルだけを見る */
    private static boolean isInteresting(IFile file) {
        String name = file.getName();
        return name.endsWith(".java") || name.endsWith(".properties") || name.endsWith(".jar");
    }
}
