#!/usr/bin/env bash
# Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
#
# action.yml の "Export call hierarchy" ステップ。用意した設定ファイルでツールを動かす。
# 作業ディレクトリは action.yml で ${{ github.action_path }}（ツールのプロジェクトフォルダ）にしてある。
set -euo pipefail

if [ ! -s "${JCHE_CONFIG_LIST:?}" ]; then
    echo "::error::設定ファイルの一覧が空です"
    exit 1
fi

# パスに空白が入っていても壊れないよう、1 行 1 ファイルで配列に読む
configs=()
while IFS= read -r line; do
    [ -n "$line" ] || continue
    configs+=("$line")
done < "$JCHE_CONFIG_LIST"

status=0
bash "$GITHUB_ACTION_PATH/jbangw/jbang" run "$GITHUB_ACTION_PATH/src/jche/CallHierarchyExporter.java" "${configs[@]}" || status=$?

# 依存 jar の警告をジョブに見せる。ツールはローカルリポジトリに無い jar を警告して解析を続ける
# （結果は欠けるがジョブは緑のまま）ので、run.log の「[WARN] 依存jar:」行を GitHub の warning
# アノテーションとジョブサマリに出し、アーティファクトを開かなくても気づけるようにする。
# 明細（座標と要求元）は run.log にある。
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"
dir_file="${JCHE_OUTPUT_DIR_FILE:-}"
if [ -n "$dir_file" ] && [ -f "$dir_file" ]; then
    while IFS= read -r d; do
        [ -n "$d" ] && [ -f "$d/run.log" ] || continue
        warnings=$(grep -F '[WARN] 依存jar:' "$d/run.log" | sed 's/^.*\[WARN\] //' || true)
        [ -n "$warnings" ] || continue
        {
            echo "### 依存 jar の警告（\`$(basename "$d")\`）"
            echo ""
            printf '%s\n' "$warnings" | sed 's/^/- /'
            echo ""
            echo "解析は続けましたが、無い jar の型を使う呼び出しは出力から欠けます。"
            echo "このアクションより前に依存を取得しておいてください（例: \`mvn -B dependency:go-offline\`）。"
            echo "明細は \`run.log\` にあります。"
            echo ""
        } >> "$summary"
        printf '%s\n' "$warnings" | while IFS= read -r w; do
            echo "::warning title=依存jar::$(basename "$d"): $w"
        done
    done < "$dir_file"
fi

exit "$status"
