// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.util.List;

/**
 * 依存の 1 件。無い要素は空文字列（version はここでは空のこともある。dependencyManagement で決まる）。
 * scope の既定は compile、type の既定は jar だが、管理側の値で埋めるため生の値のまま持つ。
 */
record Dependency(String groupId, String artifactId, String version, String type, String classifier,
                  String scope, boolean optional, List<Exclusion> exclusions, String systemPath) {

    Dependency {
        exclusions = List.copyOf(exclusions);
    }

    /** dependencyManagement との突き合わせに使う鍵（Maven と同じく type と classifier も含む） */
    String managementKey() {
        return groupId + ":" + artifactId + ":" + (type.isEmpty() ? "jar" : type) + ":" + classifier;
    }

    String ga() {
        return groupId + ":" + artifactId;
    }

    Dependency withVersion(String v) {
        return new Dependency(groupId, artifactId, v, type, classifier, scope, optional, exclusions, systemPath);
    }
}
