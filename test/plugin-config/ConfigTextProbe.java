// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.StringReader;
import java.util.Properties;

/**
 * 自動生成した設定（{@link EclipseProjectConfig#toFileText}）が、解析側と同じ読み方
 * （{@link Properties#load}）でそのまま読み戻せることを確かめる。
 *
 * <p>Eclipse のプロジェクト構成から作る値には Windows のパスがそのまま入る。properties では
 * バックスラッシュがエスケープなので、逃がさずに書くと「バックスラッシュ + u」が Unicode
 * エスケープと解釈されて解析ごと失敗し（Malformed uxxxx encoding）、そうならない場合も
 * パスが静かに壊れる。書く側と読む側で往復することが、この検査の見ている点である。
 *
 * <p>見ているのは {@code library.jars} だけではない。自動生成した設定は
 * <b>project.root も絶対パス</b>で書く（プラグインの作業フォルダに置くので、相対だと
 * 作業フォルダ自身を解析対象だと解釈されてしまう）。つまり Windows では起点そのものに
 * バックスラッシュが入るため、ここが壊れると解析が1行も動かない。
 * {@code output.folder} も同じ理由で絶対パスになる。
 */
public final class ConfigTextProbe {

    private static int failures;

    public static void main(String[] args) {
        // jar の名前が u で始まるとき（spring-petclinic は Thymeleaf 経由で unbescape に依存する）
        check("Windows のパス（u で始まるフォルダを含む）",
                "C:\\Users\\taro\\.m2\\repository\\org\\unbescape\\unbescape\\1.1.6\\unbescape-1.1.6.jar");
        // 例外にはならないが、逃がさないと C:temp になる
        check("Windows のパス（t で始まるフォルダ）", "C:\\temp\\lib\\a.jar");
        check("カンマ区切りで複数", "C:\\a\\unbescape.jar,C:\\b\\util.jar");
        check("空白を含むパス", "C:\\Program Files\\java\\lib\\rt.jar");
        check("先頭が空白", " C:\\a.jar");
        check("日本語を含むパス", "C:\\ユーザー\\taro\\src");
        check("POSIX のパス", "/home/taro/.m2/repository/org/unbescape/unbescape.jar");
        checkOptionalKeys();
        System.out.println(failures == 0 ? "DONE" : "NG   " + failures + " 件");
        if (failures != 0) {
            System.exit(1);
        }
    }

    /**
     * 指定があるときだけ書く項目（output.folder / cache.folder）の往復。
     * 空欄のときは行ごと出さない（解析側の既定に任せる）ことも確かめる。
     */
    private static void checkOptionalKeys() {
        Properties p = base();
        p.setProperty("output.folder", "C:\\workspace\\.metadata\\.plugins\\jche\\output");
        Properties read = reload("output.folder あり", p);
        if (read == null) {
            return;
        }
        if (!"C:\\workspace\\.metadata\\.plugins\\jche\\output".equals(read.getProperty("output.folder"))) {
            System.out.println("NG   output.folder が壊れている: " + read.getProperty("output.folder"));
            failures++;
            return;
        }
        System.out.println("OK   output.folder（Windows のパス）");

        Properties without = reload("output.folder なし", base());
        if (without == null) {
            return;
        }
        if (without.getProperty("output.folder") != null) {
            System.out.println("NG   空欄の output.folder が書き出されている");
            failures++;
            return;
        }
        System.out.println("OK   空欄の output.folder は書き出さない");
    }

    /** Windows のパスを起点にした、ふつうの自動生成の設定 */
    private static Properties base() {
        Properties p = new Properties();
        p.setProperty("project.root", "C:\\workspace\\spring-petclinic");
        p.setProperty("source.folders", "src\\main\\java");
        p.setProperty("library.jars", "C:\\a\\unbescape.jar");
        p.setProperty("source.encoding", "UTF-8");
        p.setProperty("source.level", "17");
        return p;
    }

    /** 書き出して読み戻す。読めなければ NG を出して null */
    private static Properties reload(String label, Properties p) {
        Properties read = new Properties();
        try {
            read.load(new StringReader(EclipseProjectConfig.toFileText(p)));
        } catch (Exception e) {
            System.out.println("NG   " + label + ": 読み戻せない: " + e);
            failures++;
            return null;
        }
        return read;
    }

    /** project.root と library.jars に value を入れて書き出し、読み戻して一致するか */
    private static void check(String label, String value) {
        Properties p = new Properties();
        // 自動生成の設定では project.root も絶対パス。Windows ではここにも区切りが入る
        p.setProperty("project.root", "C:\\workspace\\unbescape-sample");
        p.setProperty("source.folders", "src\\main\\java");
        p.setProperty("library.jars", value);
        p.setProperty("source.encoding", "UTF-8");
        p.setProperty("source.level", "17");

        String text = EclipseProjectConfig.toFileText(p);
        Properties read = new Properties();
        try {
            read.load(new StringReader(text));
        } catch (Exception e) {
            System.out.println("NG   " + label + ": 読み戻せない: " + e);
            failures++;
            return;
        }
        String actual = read.getProperty("library.jars");
        // load は値の前後の空白を落とすので、先頭の空白は逃がしたうえで残ること
        if (!value.equals(actual)) {
            System.out.println("NG   " + label + ": 期待 [" + value + "] / 実際 [" + actual + "]");
            failures++;
            return;
        }
        if (!"src\\main\\java".equals(read.getProperty("source.folders"))) {
            System.out.println("NG   " + label + ": source.folders が壊れている: "
                    + read.getProperty("source.folders"));
            failures++;
            return;
        }
        if (!"C:\\workspace\\unbescape-sample".equals(read.getProperty("project.root"))) {
            System.out.println("NG   " + label + ": project.root が壊れている: "
                    + read.getProperty("project.root"));
            failures++;
            return;
        }
        System.out.println("OK   " + label);
    }
}
