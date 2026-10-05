// Kotlin DSL（build.gradle.kts）のサブプロジェクト。ツールが .kts も Groovy と同じ読み方で読めることを
// 回帰テスト（test/regression/gradle）で踏むためのもの。app が project(':kts') で参照し、util は
// このファイルだけが宣言するので、.kts を読めなければ app の Strings.upper が型解決に失敗して期待出力と食い違う
dependencies {
    // 版カタログ（gradle/libs.versions.toml の sample-util）。Kotlin DSL では構成名を関数として呼ぶ形になる
    api(libs.sample.util)
}
