// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

// ---------------------------------------------------------------------------
// JBang 用の指示行（jbang で実行するときだけ意味を持つ。javac / java には単なるコメント）
//
// DEPS: 依存はこの1行だけ。推移的な依存（org.eclipse.platform.* 等）は
//       Maven Central の POM から自動で解決される。JDTの版を変えるときはここを書き換える。
//         3.46.0 … JDK 17以上で動作。ソースは Java 26 まで解析可
//         3.33.0 … JDK 11以上で動作。ソースは Java 19 まで解析可
//       解析対象ソースのJavaバージョンは、この版とは別に config.properties の
//       source.level で指定する（未指定なら、この版が対応する最大値）。
// JAVA: このツール自身を動かすJDK。25 に固定するのは、JDTが「自分が動いている
//       JVMのブートクラスパス」を解析対象のクラスパスに含めるため、実行JDKが
//       変わると解析結果が変わるから。手元に25が無ければ jbang が取得する。
// SOURCES: 本体は src/jche 配下のパッケージに分かれている。jbang はこの指定で
//       それらも一緒にコンパイルする。
//
// 標準出力の文字コードは指定しない（//JAVA_OPTIONS を置かない）。JDK 19以降、
// System.out はコンソール自身の文字コードで書き出すため、指定しないのが最も
// 確実に読める。UTF-8に固定すると、MS932のままのWindowsコンソールで
// ログだけが文字化けする。端末側を chcp でUTF-8に切り替える方法も採らない。
// 日本語Windowsではコードページの切り替え自体が画面を消去してしまう。
// CSV等のファイル入出力は常に明示的な文字コードを使うので、いずれの影響も受けない。
// ---------------------------------------------------------------------------
//DEPS org.eclipse.jdt:org.eclipse.jdt.core:3.46.0
//JAVA 25
//SOURCES jche/**/*.java

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import jche.Exporter;

/**
 * java-call-hierarchy-exporter のエントリポイント。
 *
 * Javaプロジェクトを対象に、メソッド呼び出し階層を一括抽出してCSV出力する。
 * Eclipse IDE の起動は不要で、通常のJavaアプリとして動作する。
 * Eclipse のプラグインとして使うこともできる（{@code eclipse-plugin/}）。
 *
 * ここは引数の解釈だけを行い、解析の本体は {@link Exporter} にある
 * （プラグインからも同じ入口を使えるようにするため。docs/eclipse-plugin-qa.md の Q3）。
 *
 * 使い方（設定ファイルのパスを引数で渡す。複数渡せば順に処理する。省略時は config.properties）:
 * <pre>
 *   jbang src/CallHierarchyExporter.java config.properties
 *   jbang src/CallHierarchyExporter.java projA.properties projB.properties
 *   java -cp "bin;lib/*" CallHierarchyExporter config.properties
 * </pre>
 * 1つの設定が失敗しても残りは処理し、最後にまとめて結果を出す。1つでも失敗すれば終了コードは 1。
 */
public class CallHierarchyExporter {

    /** 引数を省略したときの設定ファイル（作業ディレクトリからの相対） */
    private static final String DEFAULT_CONFIG = "config.properties";

    public static void main(String[] args) {
        // 設定ファイルのパスは引数で受け取る（複数可）。jbang はスクリプト名より後ろの
        // 引数をそのまま渡してくるので、jbang 経由でも java 直接実行でも同じ形
        List<Path> configPaths = new ArrayList<>();
        for (String a : args) {
            configPaths.add(Paths.get(a));
        }
        if (configPaths.isEmpty()) {
            System.err.println("config.propertiesのパスが指定されていません。");
            System.err.println("既定値の「" + DEFAULT_CONFIG + "」で実行します。");
            configPaths.add(Paths.get(DEFAULT_CONFIG));
        }
        if (Exporter.run(configPaths) > 0) {
            System.exit(1);
        }
    }
}
