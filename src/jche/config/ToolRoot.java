// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;

/**
 * このツール自身のプロジェクトフォルダ（リポジトリの直下）の特定。
 *
 * 解析キャッシュは解析対象のプロジェクトごとのサイドカーとして、出力フォルダではなくこのツールの
 * プロジェクトフォルダ内（{@code .cache/}）に置く。そのために「自分がどこに置かれているか」が要る。
 *
 * 目印は {@code src/CallHierarchyExporter.java}。次の順に探し、最初に見つかった場所を採る。
 * <ol>
 *   <li>作業ディレクトリと、その上位。README の手順（jbang でも java 直接でも）はプロジェクト直下を
 *       作業ディレクトリにして実行するので、ほとんどはここで決まる</li>
 *   <li>実行中のクラスの置き場所と、その上位。{@code java -cp bin} で動かしたときの {@code bin/} の親。
 *       jbang 経由では {@code ~/.jbang/cache/jars/} の下なので、ここでは見つからない</li>
 * </ol>
 * どちらにも無ければ作業ディレクトリを使う（{@link #found} が false になるので、呼び出し側は
 * 警告を出してキャッシュがどこにできたかを見えるようにする）。
 */
public final class ToolRoot {

    /** 目印のファイル（プロジェクト直下からの相対パス） */
    private static final String MARKER = "src/CallHierarchyExporter.java";

    public final Path dir;
    /** 目印で特定できたか。false なら {@link #dir} は作業ディレクトリ */
    public final boolean found;

    private ToolRoot(Path dir, boolean found) {
        this.dir = dir;
        this.found = found;
    }

    /**
     * 場所が分かっているときに使う（起動コマンド jche / jche.cmd は自分のあるフォルダを環境変数で渡してくる）。
     * 目印が無いフォルダを渡されたら {@link #found} は false になる。
     */
    public static ToolRoot at(Path dir) {
        Path abs = dir.toAbsolutePath().normalize();
        return new ToolRoot(abs, Files.isRegularFile(abs.resolve(MARKER)));
    }

    /**
     * @param mainClass エントリポイントのクラス（その置き場所を第2候補の起点にする）
     */
    public static ToolRoot locate(Class<?> mainClass) {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path hit = findUpward(cwd);
        if (hit == null) {
            Path codeLocation = codeLocationOf(mainClass);
            if (codeLocation != null) {
                hit = findUpward(codeLocation);
            }
        }
        return (hit != null) ? new ToolRoot(hit, true) : new ToolRoot(cwd, false);
    }

    private static Path findUpward(Path start) {
        for (Path d = start; d != null; d = d.getParent()) {
            if (Files.isRegularFile(d.resolve(MARKER))) {
                return d;
            }
        }
        return null;
    }

    /** クラスの置き場所（クラスフォルダか jar）。分からなければ null */
    private static Path codeLocationOf(Class<?> c) {
        try {
            CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            return Paths.get(cs.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }
}
