// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;

import jche.AnalysisSnapshot;
import jche.Exporter;
import jche.config.Config;

/**
 * 検査用: ValueStoreCheck と TraceCheck が解析するプロジェクトの一覧と、その解析のしかた。
 *
 * <p>どのプロジェクトも既存の設定ファイルをそのまま読み、キャッシュと出力の置き場所だけを
 * {@code test/dataflow/work/<名前>/} に差し替える（設定ファイルの中の相対パスの起点は元のフォルダのまま。
 * {@link Config#Config(Properties, Path, Path, LocalDateTime)}）。元の回帰テストのキャッシュ・出力は触らない。
 */
final class CheckProjects {

    /**
     * 解析するプロジェクト 1 つ。
     *
     * @param name   名前（作業フォルダと、TraceCheck の記録のファイル名）
     * @param config 設定ファイル（リポジトリのルートからの相対パス）
     * @param strict 値に出所の文法の文字（{@code | ; { }}）を含まないプロジェクトか。含むもの（values）は
     *               文字列の読み方があいまいになる参照があって当然で、その参照が文法の文字を含むことを確かめる
     */
    record Project(String name, String config, boolean strict) {
    }

    /**
     * 値に文法の文字を含まないプロジェクト（ValueStoreCheck の厳しい側と TraceCheck）。
     * plugin-mapping / plugin-naming は、ファクトリに渡したキーを証拠として読む拡張（組み込みの
     * TypeMappingProvider と、plugins/ の NamingProvider）。paths は読み手の分かれ道を踏むための題材
     * （test/dataflow/projects/paths。config.properties の説明）で、paths-mapping はそれに TypeMappingProvider を
     * 付けたもの（列挙定数のキーの証拠の種別）
     */
    static final List<Project> STRICT = List.of(
            new Project("demo", "test/dataflow/config.properties", true),
            new Project("jls", "test/jls/project/config.properties", true),
            new Project("entry", "test/regression/entry/config.properties", true),
            new Project("plugin-contracts", "test/regression/plugin/config-contracts.properties", true),
            new Project("plugin-contracts-factory", "test/regression/plugin/config-contracts-factory.properties",
                    true),
            new Project("plugin-custom", "test/regression/plugin/config-custom.properties", true),
            new Project("plugin-mapping", "test/regression/plugin/config.properties", true),
            new Project("plugin-naming", "test/regression/plugin/config-naming.properties", true),
            new Project("paths", "test/dataflow/projects/paths/config.properties", true),
            new Project("paths-mapping", "test/dataflow/projects/paths/config-mapping.properties", true));

    /** 値に文法の文字をわざと含むプロジェクト（test/regression/values） */
    static final Project VALUES = new Project("values", "test/regression/values/config.properties", false);

    private CheckProjects() {
    }

    /** リポジトリのルート（test/dataflow から 2 つ上） */
    static Path root() {
        return Path.of("").toAbsolutePath().normalize().getParent().getParent();
    }

    /** キャッシュと出力を {@code test/dataflow/work/<名前>/} に置いた設定 */
    static Config configOf(Project p) throws IOException {
        Path root = root();
        Path file = root.resolve(p.config());
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(r);
        }
        Path work = root.resolve("test/dataflow/work").resolve(p.name());
        props.setProperty("cache.folder", work.resolve(".cache").toString());
        props.setProperty("output.folder", work.resolve("output").toString());
        return new Config(props, file.getParent(), root, LocalDateTime.now());
    }

    /** フェーズ 1・2 まで（CLI と同じ経路。{@link Exporter#analyze(Config)}） */
    static AnalysisSnapshot analyze(Config config) throws Exception {
        return Exporter.analyze(config);
    }
}
