// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.jdt.core.IJavaProject;

import jche.eclipse.server.JavaLocator;
import jche.eclipse.server.ServerConnection;
import jche.eclipse.server.ServerLauncher;
import jche.eclipse.server.ServerResponse;

/**
 * プロジェクト1つぶんの解析。<b>解析そのものは子プロセス</b>で、ここはその窓口である。
 *
 * <p>Eclipse の中に解析結果（グラフ）は持たない。持つのは
 * 「どの設定で解析したか」「いつの結果か」「解析後に変わったファイル」だけで、
 * 木が要るときは {@link #requestTree} で子プロセスに聞く
 * （docs/out-of-process-analysis-design.md）。
 *
 * <p>子プロセスはプロジェクトごとに1つ常駐し、解析結果をメモリに持ち続ける。
 * だから木の問い合わせは速く、解析のやり直しも差分で済む。
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

    /** 木の問い合わせの結果を受け取る側（ビュー） */
    public interface TreeCallback {
        /** UI スレッドとは限らない。画面を触るなら asyncExec すること */
        void done(ServerResponse response, String error);
    }

    /**
     * 同じプロジェクトの解析だけを直列にするための規則。
     * ワークスペースやプロジェクトを規則にすると、解析中は保存もビルドも待たされる。
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

    private volatile ServerConnection connection;
    private volatile AnalysisJob job;
    private volatile String errorMessage;
    private volatile IFile configFile;
    private volatile boolean autoAnalyze = true;

    /** 最後に子プロセスを使った時刻。アイドル判定に使う */
    private volatile long lastUsed = System.currentTimeMillis();

    /** 直近の解析の結果（サーバーが返した値）。未解析なら null */
    private volatile ServerResponse lastAnalysis;
    /** サーバーの素性（JDT の版・JVM の版・解析できる Java の上限） */
    private volatile ServerResponse serverInfo;

    /** 解析後に変わったソースの、プロジェクトからの相対パス */
    private final Set<String> changedFiles = new TreeSet<>();

    ProjectAnalysis(AnalysisService service, IProject project) {
        this.service = service;
        this.project = project;
    }

    public IProject project() {
        return project;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public boolean isAnalyzing() {
        return job != null;
    }

    public boolean isAnalyzed() {
        return lastAnalysis != null;
    }

    /** 解析結果の要約（methods / edges / at など）。未解析なら null */
    public ServerResponse lastAnalysis() {
        return lastAnalysis;
    }

    /** サーバーの素性（jdt / jvm / maxJava）。未起動なら null */
    public ServerResponse serverInfo() {
        return serverInfo;
    }

    public boolean isAutoAnalyze() {
        return autoAnalyze;
    }

    public void setAutoAnalyze(boolean value) {
        autoAnalyze = value;
        service.fireChanged(this);
    }

    /** 解析に使う設定の出どころ。解析できないときだけ null */
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
        return (javaProject != null) ? ConfigSource.generated(javaProject) : null;
    }

    /** 利用者が明示的に選んだ設定ファイル。null に戻すと自動判定に戻る */
    public void setConfigFile(IFile file) {
        this.configFile = file;
        service.fireChanged(this);
    }

    /** プロジェクト内の設定ファイル候補（直下の *.properties だけ） */
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
        result.sort((a, b) -> Boolean.compare(!DEFAULT_CONFIG_NAME.equals(a.getName()),
                !DEFAULT_CONFIG_NAME.equals(b.getName())));
        return result;
    }

    public State state() {
        if (configSource() == null) {
            return State.NO_CONFIG;
        }
        boolean analyzing = isAnalyzing();
        if (lastAnalysis == null) {
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

    // ------------------------------------------------------------
    // 変更の記録
    // ------------------------------------------------------------

    public synchronized Set<String> changedFiles() {
        return new LinkedHashSet<>(changedFiles);
    }

    public synchronized int changedCount() {
        return changedFiles.size();
    }

    /** そのファイルが解析後に変わっているか（木の ⚠ 判定） */
    public synchronized boolean isChangedSinceAnalysis(String relativePath) {
        if (relativePath == null || relativePath.isEmpty() || changedFiles.isEmpty()) {
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

    synchronized void clearChanged(Set<String> handled) {
        changedFiles.removeAll(handled);
    }

    // ------------------------------------------------------------
    // 子プロセス
    // ------------------------------------------------------------

    /**
     * 子プロセスへの接続。無ければ起動する。
     *
     * <p>起動は解析やツリーの問い合わせが要求されたときだけ行う（Eclipse の起動を遅くしない）。
     * 解析用の JDK が見つからないときは、その旨を例外で返して画面に出す。
     */
    synchronized ServerConnection connection() throws IOException {
        lastUsed = System.currentTimeMillis();
        ServerConnection current = connection;
        if (current != null && current.isAlive()) {
            return current;
        }
        JavaLocator.Found java = PluginRuntime.findJava(null);
        if (java == null) {
            throw new IOException("解析に使う JDK（" + JavaLocator.MINIMUM
                    + " 以上、推奨 " + JavaLocator.PREFERRED + "）が見つかりません。"
                    + "［ウィンドウ > 設定 > 呼び出し階層 (Exporter)］で場所を指定するか、取得してください");
        }
        List<File> classpath = PluginRuntime.analysisClasspath();
        File cacheRoot = new File(PluginRuntime.stateLocation(), "cache");
        ExporterConsole console = ExporterConsole.find();
        if (console != null) {
            console.println("解析プロセスを起動します: " + java);
        }
        ServerConnection started = ServerLauncher.start(java.executable(), classpath, cacheRoot,
                PluginRuntime.vmArguments(), null);
        started.setListener(new ServerConnection.Listener() {
            @Override
            public void progress(String label, long done, long total) {
                AnalysisJob running = job;
                if (running != null) {
                    running.reportProgress(label, done, total);
                }
            }

            @Override
            public void log(String line) {
                ExporterConsole console = ExporterConsole.find();
                if (console != null) {
                    console.println(line);
                }
            }
        });
        connection = started;
        try {
            serverInfo = started.request(ServerConnection.DEFAULT_TIMEOUT_MS, "HELLO",
                    String.valueOf(1));
        } catch (IOException e) {
            // 素性が取れなくても解析はできる。画面の表示が少し寂しくなるだけ
            PluginRuntime.logWarning("解析サーバーの素性を取得できませんでした", e);
        }
        return started;
    }

    /** 木や検索の問い合わせを子プロセスへ投げる。UI スレッドを塞がないよう Job で走らせる */
    public void request(String what, TreeCallback callback, long timeoutMs, String... words) {
        org.eclipse.core.runtime.jobs.Job query =
                new org.eclipse.core.runtime.jobs.Job(what) {
                    @Override
                    protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                        try {
                            ServerResponse response = connection().request(timeoutMs, words);
                            callback.done(response, null);
                        } catch (IOException e) {
                            callback.done(null, String.valueOf(e.getMessage()));
                        }
                        return org.eclipse.core.runtime.Status.OK_STATUS;
                    }
                };
        query.setSystem(true);
        query.schedule();
    }

    /** 木を1つ取り寄せる */
    public void requestTree(String methodKey, boolean callers, String[] filters, TreeCallback callback) {
        List<String> words = new ArrayList<>();
        words.add("TREE");
        words.add(methodKey);
        words.add(callers ? "callers" : "callees");
        words.addAll(Arrays.asList(filters));
        request("呼び出し階層の取得", callback, 120_000L, words.toArray(new String[0]));
    }

    // ------------------------------------------------------------
    // 解析の起動と中止
    // ------------------------------------------------------------

    void scheduleAutoAnalysisIfNeeded() {
        if (!autoAnalyze || isAnalyzing() || changedCount() == 0 || lastAnalysis == null) {
            return;
        }
        schedule(false);
    }

    /** 利用者が明示的に指示した解析 */
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
        Path scratch = new File(PluginRuntime.stateLocation(), "config/" + project.getName()).toPath();
        AnalysisJob newJob = new AnalysisJob(this, source, scratch, changedFiles());
        newJob.setRule(rule);
        newJob.setUser(user);
        newJob.setPriority(user ? org.eclipse.core.runtime.jobs.Job.INTERACTIVE
                : org.eclipse.core.runtime.jobs.Job.LONG);
        job = newJob;
        service.fireChanged(this);
        newJob.schedule(user ? 0L : AnalysisJob.AUTO_DELAY_MS);
    }

    /** 走っている解析を中止する（子プロセスは生かしたまま） */
    public void cancel() {
        AnalysisJob current = job;
        if (current != null) {
            current.cancel();
        }
        ServerConnection current2 = connection;
        if (current2 != null) {
            current2.cancel();
        }
    }

    void jobFinished(AnalysisJob finished, ServerResponse result, String error, Set<String> handled) {
        synchronized (this) {
            if (job != finished) {
                return;
            }
            job = null;
        }
        if (result != null) {
            lastAnalysis = result;
            errorMessage = null;
            clearChanged(handled);
        } else if (error != null) {
            errorMessage = error;
        }
        service.fireChanged(this);
    }

    /**
     * しばらく使われていない解析プロセスを終わらせる。
     *
     * <p>解析結果をメモリに持ち続けるのが常駐の値打ちなので、短く切りすぎると毎回作り直しになる。
     * 既定は 10 分で、設定で変えられる（0 なら終わらせない）。解析中は対象にしない。
     *
     * @return 終わらせたら true
     */
    boolean closeIfIdle(long idleMillis) {
        if (idleMillis <= 0 || isAnalyzing() || connection == null) {
            return false;
        }
        if (System.currentTimeMillis() - lastUsed < idleMillis) {
            return false;
        }
        dispose();
        // 次に開いたときは「まだ解析していない」状態から始まる（グラフは子プロセスにあったため）
        lastAnalysis = null;
        service.fireChanged(this);
        return true;
    }

    /** 子プロセスを終わらせる（プラグインの停止時・プロジェクトを見なくなったとき） */
    void dispose() {
        cancel();
        ServerConnection current = connection;
        connection = null;
        if (current != null) {
            current.close();
        }
    }
}
