#!/usr/bin/env bash
# src/jche 配下の全ソースから、1 ファイル版 single-file/CallHierarchyExporterSingle.java を生成し直す。
#
#   bash single-file/generate.sh                 # 生成して上書き（拡張 API は隣の jche/extension/ にそのまま写す）
#   bash single-file/generate.sh <出力先.java>   # 別の場所に書く（test/single-file/run.sh が古くなっていないかの比較に使う）
#
# 本体（src/jche）を直したら必ず走らせる。生成し直し忘れは test/single-file/run.sh が検出する。
# 生成器は single-file/generator/MergeSources.java（JDK 17 以上の java で直接実行する。JDT は要らない）。
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=${1:-single-file/CallHierarchyExporterSingle.java}
java single-file/generator/MergeSources.java src/jche "$OUT"
