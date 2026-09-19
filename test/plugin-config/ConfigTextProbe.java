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
        System.out.println(failures == 0 ? "DONE" : "NG   " + failures + " 件");
        if (failures != 0) {
            System.exit(1);
        }
    }

    /** library.jars に value を入れて書き出し、読み戻して一致するか */
    private static void check(String label, String value) {
        Properties p = new Properties();
        p.setProperty("project.root", ".");
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
        System.out.println("OK   " + label);
    }
}
