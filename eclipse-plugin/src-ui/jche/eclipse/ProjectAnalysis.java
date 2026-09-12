// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.jobs.ISchedulingRule;

import org.eclipse.jdt.core.IJavaProject;

import jche.AnalysisSnapshot;

/**
 * プロジェクト1つぶんの解析結果と、その状態。
 *
 * <p>スナップショット（{@link AnalysisSnapshot}）は作り終えてから差し替えるだけで、
 * 中身を書き換えることはない。読む側（ビュー）は {@link #snapshot()} を1回読んで、
 * その参照を使い続ければ、途中で解析が終わっても一貫した木を描ける。ロックは要らない。
 *
 * <p>「解析後に変わったファイル」は {@link #changedFiles()} に溜める。解析が成功したら、
 * その解析が始まった時点で分かっていたぶんだけを消す（走っている最中の変更は残す）。
 */
public final class ProjectAnalysis {

    /** 画面に出す状態。組み合わせではなく1つに畳んである（バナーは1行だから） */
    public enum State {
        /** 解析できない（Java プロジェクトでなく、設定ファイルも無い） */
        NO_CONFIG,
        /** まだ一度も解析していない */
        NOT_ANALYZED,
        /** 初回の解析中（見せられる結果がまだ無い） */
        ANALYZING,
        /** 前の結果を見せたまま解析中 */
        UPDATING,
        /** 最新 */
        READY,
        /** 結果はあるが、その後ソースが変わっている */
        STALE,
        /** 解析に失敗した */
        FAILED
    }

    /**
     * 同じプロジェクトの解析だけを直列にするための規則。
     *
     * ワークスペースやプロジェクトそのものを規則にすると、解析中は保存もビルドも待たされる。
     * 自分自身としか衝突しない規則にして、解析が他の作業を止めないようにする。
     */
    private static final class AnalysisRule implements ISchedulingRule {
        @Override
        public boolean contains(ISchedulingRule rule) {
            return rule == this;
        }

        @Override
        public boolean isConflicting(ISchedulingRule rule) {
            return rule == this;
        }
    }

    /** プロジェクト直下にあれば自動的に使う設定ファイルの名前 */
    private static final String DEFAULT_CONFIG_NAME = "config.properties";

    private final AnalysisService service;
    private final IProject project;
    private final ISchedulingRule rule = new AnalysisRule();

    private volatile AnalysisSnapshot snapshot;
    private volatile AnalysisJob job;
    private volatile String errorMessage;
    private volatile IFile configFile;
    private volatile boolean autoAnalyze = true;

    /** 解析後に変わったソースの、プロジェクトからの相対パス（表示と ⚠ の判定に使う） */
    private final Set<String> changedFiles = new TreeSet<>();

    ProjectAnalysis(AnalysisService service, IProject project) {
        this.service = service;
        this.project = project;
    }

    public IProject project() {
        return project;
    }

    public AnalysisSnapshot snapshot() {
        return snapshot;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public boolean isAnalyzing() {
        return job != null;
    }

    public boolean isAutoAnalyze() {
        return autoAnalyze;
    }

    public void setAutoAnalyze(boolean value) {
        autoAnalyze = value;
        service.fireChanged(this);
    }

    /**
     * 解析に使う設定の出どころ。設定ファイルが無ければプロジェクトの構成から自動生成する
     * （{@link ConfigSource}）。解析できないときだけ null。
     */
    public ConfigSource configSource() {
        IFile selected = configFile;
        if (selected != null && selected.exists()) {
            return ConfigSource.ofFile(selected);
        }
        IFile atRoot = project.getFile(DEFAULT_CONFIG_NAME);
        if (atRoot.exists() && atRoot.getLocation() != null) {
            return ConfigSource.ofFile(atRoot);
        }
        IJavaProject javaProject = EclipseProjectConfig.javaProjectOf(project);
        IPath location = project.getLocation();
        if (javaProject != null && location != null) {
            return ConfigSource.generated(javaProject, location.toFile().toPath());
        }
        return null;
    }

    /** 利用者が明示的に選んだ設定ファイル。null に戻すと自動判定に戻る */
    public void setConfigFile(IFile file) {
        this.configFile = file;
        service.fireChanged(this);
    }

    /** プロジェクト内の設定ファイル候補（直下の *.properties。深く探すと大量に出るため1階層だけ） */
    public List<IFile> findConfigFiles() {
        List<IFile> result = new ArrayList<>();
        try {
            for (IResource member : project.members()) {
                if (member.getType() == IResource.FILE && member.getName().endsWith(".properties")) {
                    result.add((IFile) member);
                }
            }
        } catch (CoreException e) {
            JchePlugin.log(IStatus.WARNING, "設定ファイルを探せませんでした: " + project.getName(), e);
        }
        // config.properties があればそれを先頭に
        result.sort((a, b) -> Boolean.compare(!"config.properties".equals(a.getName()),
                !"config.properties".equals(b.getName())));
        return result;
    }

    public State state() {
        if (configSource() == null) {
            return State.NO_CONFIG;
        }
        boolean analyzing = isAnalyzing();
        if (snapshot == null) {
            if (analyzing) {
                return State.ANALYZING;
            }
            return (errorMessage != null) ? State.FAILED : State.NOT_ANALYZED;
        }
        if (analyzing) {
            return State.UPDATING;
        }
        if (errorMessage != null) {
            return State.FAILED;
        }
        return changedCount() > 0 ? State.STALE : State.READY;
    }

    /** 解析後に変わったファイルの、プロジェクトからの相対パス */
    public synchronized Set<String> changedFiles() {
        return new LinkedHashSet<>(changedFiles);
    }

    public synchronized int changedCount() {
        return changedFiles.size();
    }

    /** そのファイルが解析後に変わっているか（木の ⚠ 判定。パスは project.root からの相対） */
    public synchronized boolean isChangedSinceAnalysis(String relativePath) {
        if (relativePath == null || changedFiles.isEmpty()) {
            return false;
        }
        String normalized = relativePath.replace('\\', '/');
        for (String changed : changedFiles) {
            if (changed.endsWith(normalized) || normalized.endsWith(changed)) {
                return true;
            }
        }
        return false;
    }

    void markChanged(List<IResource> resources) {
        boolean added = false;
        synchronized (this) {
            for (IResource resource : resources) {
                added |= changedFiles.add(resource.getProjectRelativePath().toString().replace('\\', '/'));
            }
        }
        if (added) {
            service.fireChanged(this);
        }
    }

    /** 解析が成功したときに、その解析が拾ったぶんの変更を消す */
    synchronized void clearChanged(Set<String> handled) {
        changedFiles.removeAll(handled);
    }

    // ------------------------------------------------------------
    // 解析の起動と中止
    // ------------------------------------------------------------

    /** 自動再解析。変更が無ければ何もしない。すでに走っていても何もしない */
    void scheduleAutoAnalysisIfNeeded() {
        if (!autoAnalyze || isAnalyzing() || changedCount() == 0 || snapshot == null) {
            return;
        }
        schedule(false);
    }

    /** 利用者が明示的に指示した解析。走っていれば中止してから作り直す */
    public void reanalyze() {
        cancel();
        schedule(true);
    }

    private synchronized void schedule(boolean user) {
        if (job != null) {
            return;
        }
        ConfigSource source = configSource();
        if (source == null) {
            return;
        }
        AnalysisJob newJob = new AnalysisJob(this, source, changedFiles());
        newJob.setRule(rule);
        newJob.setUser(user);
        // 自動再解析は右下で静かに進める。モーダルにはしない
        newJob.setPriority(user ? org.eclipse.core.runtime.jobs.Job.INTERACTIVE
                : org.eclipse.core.runtime.jobs.Job.LONG);
        job = newJob;
        service.fireChanged(this);
        newJob.schedule(user ? 0L : AnalysisJob.AUTO_DELAY_MS);
    }

    /** 走っている解析を中止する（戻るのを待たない） */
    public void cancel() {
        AnalysisJob current = job;
        if (current != null) {
            current.cancel();
        }
    }

    void jobFinished(AnalysisJob finished, AnalysisSnapshot result, String error, Set<String> handled) {
        synchronized (this) {
            if (job != finished) {
                return;
            }
            job = null;
        }
        if (result != null) {
            snapshot = result;
            errorMessage = null;
            clearChanged(handled);
        } else if (error != null) {
            errorMessage = error;
        }
        service.fireChanged(this);
    }
}
