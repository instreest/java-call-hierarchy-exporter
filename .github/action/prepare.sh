#!/usr/bin/env bash
# Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
#
# action.yml の "Prepare config" ステップ。設定ファイルを用意し、後続ステップへ渡す値を決める。
#
#   - config 入力があれば、それを絶対パスに直して一覧にする（内容には触らない）
#   - 無ければ、解析対象の入力から設定ファイルを 1 つ生成する
#   - 出力フォルダの一覧を受け取るファイル（JCHE_OUTPUT_DIR_FILE）と、2 つのキャッシュキーを決める
#     （JBang / JDT 用と、AST 解析結果用）
#
# 生成先を RUNNER_TEMP にするのは、解析対象リポジトリのチェックアウトに書き込まないため
# （利用者のワークフローが git diff --exit-code で作業ツリーの汚れを検査していることがある）。
# 生成した設定ファイルの相対パスの起点はそのフォルダになるので、パスは全て絶対パスで書く。
#
# ランナーは ubuntu / macOS / windows のいずれもありうる。macOS の bash は 3.2 なので、
# 連想配列や mapfile は使わない。
set -euo pipefail

work="${RUNNER_TEMP:?RUNNER_TEMP が未設定です}/java-call-hierarchy-exporter"
rm -rf "$work"
mkdir -p "$work"
config_list="$work/configs.txt"
output_dir_file="$work/output-dirs.txt"
: > "$config_list"

# properties ファイルに書く値。Windows のパス区切り \ は properties のエスケープ文字で、
# そのまま書くと java.util.Properties に食われる（D:\a\repo は D:arepo になる）ので / に直す。
# Java は Windows でも / を区切りとして扱うので、これで通る
prop() {
    printf '%s' "$1" | tr '\\' '/'
}

# ワークスペースからの相対パスを絶対パスにする（既に絶対パスならそのまま）
abspath() {
    case "$1" in
        /*|\\*|[A-Za-z]:[/\\]*) printf '%s' "$1" ;;
        *) printf '%s/%s' "${GITHUB_WORKSPACE:-$PWD}" "$1" ;;
    esac
}

configs_in=$(printf '%s' "${JCHE_CONFIG:-}" | tr -d '[:space:]' || true)
if [ -n "$configs_in" ]; then
    # 渡された設定ファイルをそのまま使う。改行区切りとカンマ区切りの両方を受ける
    printf '%s\n' "${JCHE_CONFIG}" | tr ',' '\n' | while IFS= read -r raw; do
        c=$(printf '%s' "$raw" | tr -d '\r' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
        [ -n "$c" ] || continue
        p=$(abspath "$c")
        if [ ! -f "$p" ]; then
            echo "::error::config に指定された設定ファイルがありません: $c （解決後: $p）"
            exit 1
        fi
        printf '%s\n' "$p" >> "$config_list"
    done
    echo "設定ファイル（config 入力）:"
    sed 's/^/  /' "$config_list"
else
    # 入力から設定ファイルを生成する
    project_root=$(abspath "${JCHE_PROJECT_ROOT:-.}")
    output_folder=$(abspath "${JCHE_OUTPUT_FOLDER:-call-hierarchy-output}")
    if [ ! -d "$project_root" ]; then
        echo "::error::project-root がありません: ${JCHE_PROJECT_ROOT:-.} （解決後: $project_root）"
        exit 1
    fi
    mkdir -p "$output_folder"
    cfg="$work/config.properties"
    cat > "$cfg" <<EOF
# java-call-hierarchy-exporter の GitHub Action が入力から生成した設定ファイル。
# 各項目の意味は、ツールに同梱の config/config.properties のコメントを参照。
# 相対パスの起点になるのはこのファイルのフォルダなので、パスは絶対パスで書いてある。
project.root=$(prop "$project_root")
source.folders=$(prop "${JCHE_SOURCE_FOLDERS:-}")
source.encoding=${JCHE_SOURCE_ENCODING:-UTF-8}
source.level=${JCHE_SOURCE_LEVEL:-}
library.folders=$(prop "${JCHE_LIBRARY_FOLDERS:-}")
library.build.tool=${JCHE_LIBRARY_BUILD_TOOL:-auto}
library.repositories=$(prop "${JCHE_LIBRARY_REPOSITORIES:-}")
external.library.folders=$(prop "${JCHE_EXTERNAL_LIBRARY_FOLDERS:-}")
entry.packages=${JCHE_ENTRY_PACKAGES:-}
exclude.packages=${JCHE_EXCLUDE_PACKAGES:-}
output.folder=$(prop "$output_folder")
EOF
    if [ -n "$(printf '%s' "${JCHE_EXTRA_CONFIG:-}" | tr -d '[:space:]' || true)" ]; then
        printf '\n# extra-config 入力\n%s\n' "${JCHE_EXTRA_CONFIG}" >> "$cfg"
    fi
    printf '%s\n' "$cfg" >> "$config_list"
    echo "生成した設定ファイル: $cfg"
    sed 's/^/  /' "$cfg"
fi

if [ ! -s "$config_list" ]; then
    echo "::error::使う設定ファイルがありません"
    exit 1
fi

# キャッシュキー。ツール本体（//DEPS の版を含む）と JBang ラッパーが変わったら作り直す
hash_files() {
    if command -v sha256sum > /dev/null 2>&1; then
        sha256sum "$@"
    else
        shasum -a 256 "$@"   # macOS には sha256sum が無い
    fi
}
cache_key=$(hash_files "$GITHUB_ACTION_PATH/jbangw/jbang" "$GITHUB_ACTION_PATH/src/CallHierarchyExporter.java" \
    | hash_files | cut -c1-16)

# AST 解析キャッシュのキー。使う設定ファイルの内容（生成した場合は絶対パス込みで毎回同じになる）から作る。
# 同じリポジトリの別ジョブが別の設定でこのアクションを呼んでも、互いのキャッシュを上書きしないようにするため。
# ツール本体の版は含めない。キャッシュの形式が変わったときはツール自身が捨てるので、キーで分ける必要が無い
analysis_cache_key=$(tr '\n' '\0' < "$config_list" | xargs -0 cat | hash_files | cut -c1-16)

{
    echo "config-list=$config_list"
    echo "output-dir-file=$output_dir_file"
    echo "cache-key=$cache_key"
    echo "analysis-cache-key=$analysis_cache_key"
} >> "${GITHUB_OUTPUT:-/dev/stdout}"
