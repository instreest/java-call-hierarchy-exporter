// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

/**
 * 依存から除外する座標（Maven の exclusions、Gradle の exclude）。Maven / Gradle のどちらの
 * ビルドファイルから来た依存にも付くので、{@link MavenPom} の内側ではなく独立した型にしてある。
 */
record Exclusion(String groupId, String artifactId) {

    /** {@code *} はワイルドカード */
    boolean matches(String g, String a) {
        return (groupId.equals("*") || groupId.equals(g)) && (artifactId.equals("*") || artifactId.equals(a));
    }
}
