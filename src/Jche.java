// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

// ---------------------------------------------------------------------------
// JBang 用の指示行。DEPS / JAVA は src/CallHierarchyExporter.java と同じにしておく
// （JDT の版を変えるときは両方を書き換える。test/pom/run.sh が食い違いを検出する）。
// SOURCES に CallHierarchyExporter.java を含めるのは、解析の処理をそのまま使うため。
// ---------------------------------------------------------------------------
//DEPS org.eclipse.jdt:org.eclipse.jdt.core:3.46.0
//JAVA 25
//SOURCES CallHierarchyExporter.java jche/**/*.java

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import jche.cli.App;
import jche.cli.LauncherSettings;
import jche.cli.Terminal;
import jche.config.ToolRoot;

/**
 * 起動コマンドのエントリポイント。プロジェクト直下の {@code java-call-hierarchy-exporter.sh} / {@code java-call-hierarchy-exporter.cmd} から起動する。
 *
 * <pre>
 *   java-call-hierarchy-exporter.sh                           引数なし … 対話モード（メニューで設定ファイルを選んで解析する）
 *   java-call-hierarchy-exporter.sh a.properties [b.properties…] 引数あり … 対話なしで解析する（{@link CallHierarchyExporter} を直接動かすのと同じ）
 *   java-call-hierarchy-exporter.sh --help
 * </pre>
 *
 * 設定ファイルを 1 つでも渡したときは、画面も標準入力も使わずに解析だけを行って終わる（Issue #83）。
 * バッチやタスクスケジューラ、CI から呼べるようにするため。終了コードは、すべて成功なら 0、
 * 1 つでも失敗すれば 1、引数が誤っていれば 2。
 * 起動コマンド側も、引数があるときは JDK / JBang の置き場所を尋ねない（既定のまま進む）。
 * 例外はネットワークからの取得（JBang 本体・JDK・依存 jar）で、必要なときは引数の有無によらず起動コマンドが
 * 操作者に確認する（Issue #86）。取りやめたときの終了コードは 3。この確認は Java が動く前の話なので
 * 起動コマンド側にあり、ここでは「アプリが始まった」目印（{@link LauncherSettings#markStarted()}）を置くだけ。
 *
 * 起動コマンドは自分のあるフォルダを環境変数 {@code JCHE_ROOT} で渡してくる。どこから実行しても
 * ツールのプロジェクトフォルダ（キャッシュ・設定ファイルの置き場所）が同じになるようにするため。
 * 無ければ（jbang で直接動かしたとき）{@link ToolRoot#locate} で探す。
 *
 * 起動コマンドの役目（JDK / JBang の置き場所、JVM のオプション、再起動）は {@code java-call-hierarchy-exporter.sh} の冒頭のコメントと
 * {@link jche.cli.LauncherSettings} を参照。
 */
public class Jche {

    public static void main(String[] args) throws Exception {
        // 起動コマンドが「jbang がアプリを始められなかった（JDK / 依存 jar の取得が要る）」と見分けるための目印。
        // 引数の検査より前に置く（知らないオプションで 2 を返すのもアプリの判断なので、取得の確認にしない）
        LauncherSettings.markStarted();
        List<Path> configPaths = new ArrayList<>();
        for (String a : args) {
            if (a.equals("--help") || a.equals("-h")) {
                usage();
                return;
            }
            if (a.startsWith("-") && !a.equals("-")) {
                // 設定ファイルのパスとして扱うと「ファイルがありません」になって分かりにくいので、ここで弾く
                System.err.println("知らないオプションです: " + a);
                System.err.println();
                usage();
                System.exit(2);
                return;
            }
            configPaths.add(Paths.get(a));
        }

        String rootEnv = System.getenv("JCHE_ROOT");
        ToolRoot toolRoot = (rootEnv != null && !rootEnv.isEmpty())
                ? ToolRoot.at(Paths.get(rootEnv))
                : ToolRoot.locate(Jche.class);

        if (!configPaths.isEmpty()) {
            // 引数あり … 対話なし。設定ファイルを順に処理して終わる（jbang で CallHierarchyExporter.java を動かすのと同じ）。
            // メニューは出さないので、標準入力が無い環境（バッチ・CI・cron）でもそのまま動く
            int failed = CallHierarchyExporter.runAll(configPaths, toolRoot);
            System.exit(failed > 0 ? 1 : 0);
        }

        int code = new App(new Terminal(), toolRoot, CallHierarchyExporter::runAll).run();
        System.exit(code);
    }

    private static void usage() {
        System.out.println("使い方（Windows は java-call-hierarchy-exporter.cmd。以下は .sh で書く）:");
        System.out.println("  java-call-hierarchy-exporter.sh                              対話モード（メニューで設定ファイルを選んで解析する）");
        System.out.println("  java-call-hierarchy-exporter.sh a.properties [b.properties…] 対話なしで解析する。設定ファイルごとに出力フォルダができる");
        System.out.println("  java-call-hierarchy-exporter.sh --help                       この説明");
        System.out.println();
        System.out.println("設定ファイルを渡したときは、何も尋ねずに解析だけを行って終わる（バッチやタスクスケジューラ、CI 向け）。");
        System.out.println("ただし JBang 本体・JDK・依存 jar をネットワークから取得する必要があるときだけは、取得してよいかを確認する。");
        System.out.println("終了コードは、すべて成功なら 0、1 つでも失敗すれば 1、引数が誤っていれば 2、取得を取りやめたら 3。");
        System.out.println();
        System.out.println("JDK / JBang の置き場所や JVM のオプション、取得の確認の要否（JCHE_ALLOW_DOWNLOAD=yes / no）は");
        System.out.println("launcher.properties（プロジェクト直下）で決まる。対話モードの「環境設定」から書き換えられる。");
    }
}
