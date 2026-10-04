// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaModelException;

/**
 * 対象プロジェクトを参照している（参照元）・対象が参照している（依存先）ワークスペースのプロジェクトを集める。
 *
 * <p>参照は Java のビルド・パスの「プロジェクト」（解決済みクラスパスの {@code CPE_PROJECT}）で見る。
 * どちらの向きも推移的に辿る（A ← B ← C の C も参照元。{@code workspace.scope=callers} では C の起点から B を経て
 * A に届く経路が答えの一部になるため）。集めた一覧は設定ファイルの {@code workspace.projects} に書き足す候補で、
 * 書くかどうかは利用者が選ぶ（{@link WorkspaceProjectsDialog}）。
 */
final class WorkspaceReferences {

    /** 候補 1 件。参照元と依存先の両方であることもある（相互参照は Eclipse が拒むので、推移的な場合だけ） */
    static final class Candidate {
        final IProject project;
        final boolean referencing;
        final boolean referenced;

        Candidate(IProject project, boolean referencing, boolean referenced) {
            this.project = project;
            this.referencing = referencing;
            this.referenced = referenced;
        }
    }

    private WorkspaceReferences() {
    }

    /** 対象の参照元と依存先（名前順）。対象自身は含めない */
    static List<Candidate> collect(IProject target) {
        Map<String, Set<String>> referenced = new HashMap<>();    // プロジェクト名 → それが参照しているプロジェクト名
        Map<String, Set<String>> referencing = new HashMap<>();   // プロジェクト名 → それを参照しているプロジェクト名
        Map<String, IProject> byName = new HashMap<>();
        for (IProject candidate : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            IJavaProject javaProject = EclipseProjectConfig.javaProjectOf(candidate);
            if (javaProject == null) {
                continue;
            }
            byName.put(candidate.getName(), candidate);
            try {
                for (IClasspathEntry entry : javaProject.getResolvedClasspath(true)) {
                    if (entry.getEntryKind() != IClasspathEntry.CPE_PROJECT) {
                        continue;
                    }
                    String other = entry.getPath().lastSegment();
                    if (other == null || other.equals(candidate.getName())) {
                        continue;
                    }
                    referenced.computeIfAbsent(candidate.getName(), k -> new HashSet<>()).add(other);
                    referencing.computeIfAbsent(other, k -> new HashSet<>()).add(candidate.getName());
                }
            } catch (JavaModelException e) {
                JchePlugin.log(IStatus.WARNING,
                        Messages.format("project.resolvedClasspathFailed", e.getMessage()), e);
            }
        }
        Set<String> callers = closure(target.getName(), referencing);
        Set<String> callees = closure(target.getName(), referenced);
        Set<String> names = new HashSet<>(callers);
        names.addAll(callees);
        names.remove(target.getName());
        List<Candidate> out = new ArrayList<>();
        for (String name : names) {
            IProject project = byName.get(name);
            if (project != null) {
                out.add(new Candidate(project, callers.contains(name), callees.contains(name)));
            }
        }
        out.sort(Comparator.comparing(c -> c.project.getName()));
        return out;
    }

    /** 起点から辺を辿って届く名前の集合（起点を含む） */
    private static Set<String> closure(String start, Map<String, Set<String>> edges) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : edges.getOrDefault(current, new HashSet<>())) {
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return seen;
    }
}
