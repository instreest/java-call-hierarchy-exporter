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

bash "$GITHUB_ACTION_PATH/jbangw/jbang" run "$GITHUB_ACTION_PATH/src/CallHierarchyExporter.java" "${configs[@]}"
