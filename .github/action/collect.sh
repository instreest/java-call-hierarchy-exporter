#!/usr/bin/env bash
# Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
#
# action.yml の "Collect outputs" ステップ。ツールが JCHE_OUTPUT_DIR_FILE に書いた
# 出力フォルダの一覧を、アクションの出力（outputs）とジョブサマリにする。
#
# 解析が失敗したときも動く（if: always()）ので、1 つも出力フォルダが無い場合を通常の経路として扱う。
set -euo pipefail

dirs=""
file="${JCHE_OUTPUT_DIR_FILE:-}"
if [ -n "$file" ] && [ -f "$file" ]; then
    dirs=$(grep -v '^[[:space:]]*$' "$file" || true)
fi

first=""
if [ -n "$dirs" ]; then
    first=$(printf '%s\n' "$dirs" | head -1)
fi

out="${GITHUB_OUTPUT:-/dev/stdout}"
{
    echo "output-dir=$first"
    echo "output-dirs<<JCHE_EOF"
    if [ -n "$dirs" ]; then
        printf '%s\n' "$dirs"
    fi
    echo "JCHE_EOF"
    echo "artifact-path<<JCHE_EOF"
    if [ -n "$dirs" ]; then
        printf '%s\n' "$dirs"
    fi
    echo "JCHE_EOF"
    if [ -n "$first" ]; then
        echo "csv=$first/call-hierarchy.csv"
        echo "methods-csv=$first/methods.csv"
        echo "run-log=$first/run.log"
    else
        echo "csv="
        echo "methods-csv="
        echo "run-log="
    fi
} >> "$out"

if [ -z "$dirs" ]; then
    echo "出力フォルダはできていません（解析が始まる前に失敗した可能性があります）"
    exit 0
fi

# ジョブサマリ。CSV の行数（ヘッダを除く）まで出して、実行結果の見当が付くようにする
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"
{
    echo "### java-call-hierarchy-exporter"
    echo ""
    echo "| 出力フォルダ | call-hierarchy.csv | methods.csv |"
    echo "| --- | ---: | ---: |"
} >> "$summary"
printf '%s\n' "$dirs" | while IFS= read -r d; do
    rows() {   # ヘッダ 1 行を除いた行数。ファイルが無ければ -
        if [ -f "$1" ]; then
            n=$(wc -l < "$1" | tr -d '[:space:]')
            echo $((n - 1))
        else
            echo "-"
        fi
    }
    echo "| \`$(basename "$d")\` | $(rows "$d/call-hierarchy.csv") | $(rows "$d/methods.csv") |" >> "$summary"
    echo "出力フォルダ: $d"
done
