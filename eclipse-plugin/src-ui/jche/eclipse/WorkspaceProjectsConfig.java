// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;

/**
 * 設定ファイルの {@code workspace.projects}（一緒に解析するワークスペースの他のプロジェクト）の読み書き。
 *
 * <p>解析が読むのは設定ファイルの {@code workspace.projects} だけで、画面の状態で解析の範囲を変えることはしない
 * （正本は設定ファイル 1 つ。docs/workspace-callers-design.md の Q11）。プラグインがするのは、参照元・依存先の
 * プロジェクトを集めて（{@link WorkspaceReferences}）この項目に書き足すことと、書いてある項目を読んで画面
 * （ツールチップ・⚠ の振り分け・カーソルの扱い）に使うことだけである。
 *
 * <p>読み方は解析側（{@code jche.config.ConfigFile}）に合わせる: 1 行に「項目=値」、バックスラッシュはそのまま、
 * 値の続きは行末の {@code \}（次の行が「項目=」なら捨てる）か字下げ、続きの途中の {@code #} の行は読み飛ばす。
 * 書くときは、既にある {@code workspace.projects} の論理行（続きの行も）を 1 行に差し替え、無ければ末尾に足す。
 * ほかの行はそのまま残す。
 */
final class WorkspaceProjectsConfig {

    static final String KEY = "workspace.projects";

    /** 設定ファイルに書いてあった 1 件と、それがワークスペースのどのプロジェクトか（無ければ null） */
    static final class Entry {
        final String raw;
        final IProject project;

        Entry(String raw, IProject project) {
            this.raw = raw;
            this.project = project;
        }
    }

    private WorkspaceProjectsConfig() {
    }

    /** 設定ファイルの {@code workspace.projects} の値（カンマで分けた生の文字列）。無ければ空 */
    static List<String> read(IFile configFile) throws IOException, CoreException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(configFile.getContents(true), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        String value = valueOf(lines, KEY);
        return (value == null) ? new ArrayList<>() : splitList(value);
    }

    /** 行の列から、その項目の値（続きの行をつないだもの）。無ければ null。検査（test/plugin-config）が解析側の読み手と比べる */
    static String valueOf(List<String> lines, String key) {
        int[] range = logicalLineOf(lines, key);
        return (range == null) ? null : valueOf(lines, range[0], range[1]);
    }

    /** 行の列の、その項目の論理行を「項目=値」の 1 行に差し替えたもの（無ければ末尾に足す）。ほかの行はそのまま */
    static List<String> replaced(List<String> lines, String key, String value) {
        List<String> out = new ArrayList<>(lines);
        String newLine = key + "=" + value;
        int[] range = logicalLineOf(out, key);
        if (range == null) {
            if (!out.isEmpty() && !out.get(out.size() - 1).trim().isEmpty()) {
                out.add("");
            }
            out.add(newLine);
        } else {
            for (int i = range[1] - 1; i >= range[0]; i--) {
                out.remove(i);
            }
            out.add(range[0], newLine);
        }
        return out;
    }

    /**
     * 設定ファイルの各件を、ワークスペースのプロジェクトに結び付ける。件は設定ファイルのフォルダからの相対パスか
     * 絶対パスで、プロジェクトのフォルダか、そのプロジェクトの設定ファイル（{@code project.root} をそこから読む）
     */
    static List<Entry> resolve(IFile configFile) throws IOException, CoreException {
        List<Entry> out = new ArrayList<>();
        IPath location = configFile.getLocation();
        Path configDir = (location == null) ? null : Paths.get(location.toOSString()).getParent();
        for (String raw : read(configFile)) {
            out.add(new Entry(raw, projectAt(rootOf(configDir, raw))));
        }
        return out;
    }

    /** 件が指すプロジェクトのフォルダ。設定ファイルを指していればその {@code project.root}。分からなければ null */
    private static Path rootOf(Path configDir, String raw) {
        Path p = Paths.get(raw.trim());
        if (!p.isAbsolute() && configDir != null) {
            p = configDir.resolve(p);
        }
        p = p.normalize();
        if (Files.isRegularFile(p)) {
            try {
                String value = valueOf(Files.readAllLines(p, StandardCharsets.UTF_8), "project.root");
                if (value == null || value.trim().isEmpty()) {
                    return null;
                }
                Path root = Paths.get(value.trim());
                return (root.isAbsolute() ? root : p.getParent().resolve(root)).normalize();
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
        return p;
    }

    /** そのフォルダを場所に持つ、開いているプロジェクト。無ければ null */
    static IProject projectAt(Path root) {
        if (root == null) {
            return null;
        }
        for (IProject candidate : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            IPath location = candidate.getLocation();
            if (location != null && candidate.isAccessible()
                    && Paths.get(location.toOSString()).normalize().equals(root)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 設定ファイルに書く 1 件。設定ファイルのフォルダからの相対パス（区切りは {@code /}。{@code ../app-batch}）。
     * 相対にできない（別のドライブ）ときは絶対パス
     */
    static String entryFor(IFile configFile, IProject project) {
        IPath location = project.getLocation();
        if (location == null) {
            return project.getName();
        }
        Path target = Paths.get(location.toOSString()).normalize();
        IPath configLocation = configFile.getLocation();
        if (configLocation != null) {
            Path configDir = Paths.get(configLocation.toOSString()).getParent();
            try {
                String relative = configDir.relativize(target).toString().replace('\\', '/');
                return relative.isEmpty() ? "." : relative;
            } catch (IllegalArgumentException e) {
                // 別のドライブ。絶対パスで書く
            }
        }
        return target.toString();
    }

    /**
     * {@code workspace.projects} を書き換える（無ければ末尾に足す）。ほかの行はそのまま。
     * 空の一覧なら項目を空にする（{@code workspace.projects=}）
     */
    static void write(IFile configFile, List<String> entries) throws IOException, CoreException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(configFile.getContents(true), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String line : replaced(lines, KEY, String.join(",", entries))) {
            sb.append(line).append('\n');
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        configFile.setContents(new ByteArrayInputStream(bytes), IFile.KEEP_HISTORY, null);
    }

    /**
     * その項目の論理行（先頭の行の位置と、終わりの次の行の位置）。無ければ null。
     * 論理行は「項目=」の行から始まり、行末の {@code \}（次の行が「項目=」でなければ）か字下げで次の行に続く。
     * 続きの途中の {@code #} の行は読み飛ばす
     */
    private static int[] logicalLineOf(List<String> lines, String key) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!key.equals(keyOf(line))) {
                continue;
            }
            int end = i + 1;
            boolean continued = endsWithBackslash(line);
            while (end < lines.size()) {
                String next = lines.get(end);
                if (next.trim().startsWith("#")) {
                    end++;
                    continue;
                }
                boolean indented = !next.isEmpty() && Character.isWhitespace(next.charAt(0))
                        && !next.trim().isEmpty();
                if ((continued && keyOf(next) == null) || indented) {
                    continued = endsWithBackslash(next);
                    end++;
                    continue;
                }
                break;
            }
            return new int[] {i, end};
        }
        return null;
    }

    /** 論理行の値（続きの行をつないだもの。行末の {@code \} と字下げは外す） */
    private static String valueOf(List<String> lines, int start, int end) {
        StringBuilder value = new StringBuilder();
        for (int i = start; i < end; i++) {
            String line = lines.get(i);
            if (i > start && line.trim().startsWith("#")) {
                continue;
            }
            String part = (i == start) ? line.substring(line.indexOf('=') + 1) : line.trim();
            if (endsWithBackslash(part)) {
                part = part.substring(0, part.length() - 1);
            }
            value.append(part.trim());
        }
        return value.toString();
    }

    /** 「項目=値」の行の項目。それ以外（注釈・空行・字下げの続き）なら null */
    private static String keyOf(String line) {
        if (line.isEmpty() || Character.isWhitespace(line.charAt(0)) || line.trim().startsWith("#")) {
            return null;
        }
        int eq = line.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        String key = line.substring(0, eq).trim();
        if (key.startsWith("﻿")) {
            key = key.substring(1);
        }
        return key.isEmpty() ? null : key;
    }

    private static boolean endsWithBackslash(String line) {
        String t = line.trim();
        return t.endsWith("\\");
    }

    static List<String> splitList(String raw) {
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
