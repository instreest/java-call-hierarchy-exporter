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
import jche.cli.Terminal;
import jche.config.ToolRoot;

/**
 * 対話モードのエントリポイント。プロジェクト直下の {@code jche.sh} / {@code jche.cmd} から起動する。
 *
 * <pre>
 *   jche.sh                           対話モード（メニューで設定ファイルを選んで解析する）
 *   jche.sh a.properties [b.properties…] 対話なしで解析する（{@link CallHierarchyExporter} を直接動かすのと同じ）
 *   jche.sh --help
 * </pre>
 *
 * 起動コマンドは自分のあるフォルダを環境変数 {@code JCHE_ROOT} で渡してくる。どこから実行しても
 * ツールのプロジェクトフォルダ（キャッシュ・設定ファイルの置き場所）が同じになるようにするため。
 * 無ければ（jbang で直接動かしたとき）{@link ToolRoot#locate} で探す。
 *
 * 起動コマンドの役目（JDK / JBang の置き場所、JVM のオプション、再起動）は {@code jche.sh} の冒頭のコメントと
 * {@link jche.cli.LauncherSettings} を参照。
 */
public class Jche {

    public static void main(String[] args) throws Exception {
        List<Path> configPaths = new ArrayList<>();
        for (String a : args) {
            if (a.equals("--help") || a.equals("-h")) {
                usage();
                return;
            }
            configPaths.add(Paths.get(a));
        }

        String rootEnv = System.getenv("JCHE_ROOT");
        ToolRoot toolRoot = (rootEnv != null && !rootEnv.isEmpty())
                ? ToolRoot.at(Paths.get(rootEnv))
                : ToolRoot.locate(Jche.class);

        if (!configPaths.isEmpty()) {
            // 対話なし。引数の設定ファイルを順に処理して終わる（jbang で CallHierarchyExporter.java を動かすのと同じ）
            int failed = CallHierarchyExporter.runAll(configPaths, toolRoot);
            System.exit(failed > 0 ? 1 : 0);
        }

        int code = new App(new Terminal(), toolRoot, CallHierarchyExporter::runAll).run();
        System.exit(code);
    }

    private static void usage() {
        System.out.println("使い方:");
        System.out.println("  jche.sh / jche.cmd                 対話モード（メニューで設定ファイルを選んで解析する）");
        System.out.println("  jche.sh a.properties [b.properties…] 対話なしで解析する。設定ファイルごとに出力フォルダができる");
        System.out.println("  jche.sh --help                     この説明");
        System.out.println();
        System.out.println("JDK / JBang の置き場所や JVM のオプションは launcher.properties（プロジェクト直下）で決まる。");
        System.out.println("対話モードの「環境設定」から書き換えられる。");
    }
}
