#!/usr/bin/env bash
# 拡張に同梱する解析本体（lib/jche-core.jar と lib/jdt/*.jar）を集める。
#
#   bash scripts/collect-lib.sh            # eclipse-plugin をビルドして lib/ を作り直す
#   bash scripts/collect-lib.sh --reuse    # eclipse-plugin/target/classes/lib が既にあればそれを使う
#
# 出どころは Eclipse プラグインのビルド（eclipse-plugin/pom.xml）と同じにする。
# JDT の版は //DEPS 行が唯一の正で、そこから pom.xml → lib/jdt/ と流れる
# （test/plugin/run.sh と test/vscode/package.sh が食い違いを検出する）。
# 同じものを2つのビルドで別々に集めると、版がずれたときに片方だけ気づかない。
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(cd .. && pwd)
SRC=$ROOT/eclipse-plugin/target/classes/lib

if [ "${1:-}" != "--reuse" ] || [ ! -f "$SRC/jche-core.jar" ]; then
    echo "== eclipse-plugin をビルドして lib/ を作る（mvn package）"
    mvn -B -q --no-transfer-progress -f "$ROOT/eclipse-plugin/pom.xml" package
fi
if [ ! -f "$SRC/jche-core.jar" ] || [ -z "$(ls "$SRC"/jdt/*.jar 2>/dev/null)" ]; then
    echo "NG   $SRC に jche-core.jar と jdt/*.jar が無い" >&2
    exit 1
fi

rm -rf lib
mkdir -p lib/jdt
cp "$SRC/jche-core.jar" lib/
cp "$SRC"/jdt/*.jar lib/jdt/
echo "== lib/ を用意した"
ls -1 lib lib/jdt | sed 's/^/   /'
