#!/usr/bin/env bash
# CallResolver.resolve の決定性の検査（Issue #80）。test/demo を解析し、全エッジを
# 「昇順 → 同じ resolver でもう一度昇順 → 新しい resolver で降順」の 3 通りで解決して、結果が一致することを見る。
#
#   bash test/dataflow/run.sh
#
# ツール本体（src/）と検査プログラム（ResolveOrderCheck.java）を javac でコンパイルし、jbang が用意した
# JDK 25 と JDT の jar で動かす（CI の lint と同じ経路。jbang 自身が作ったスクリプトの jar は除く）。
# キャッシュは test/dataflow/.cache に、コンパイル結果は test/dataflow/build にできる（どちらもコミットしない）。
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
JBANG="bash $ROOT/jbangw/jbang"

CP=$($JBANG info classpath "$ROOT/src/CallHierarchyExporter.java" | tr ':' '\n' | grep -v '/cache/jars/' | paste -sd:)
JAVA_HOME_25=$($JBANG jdk home 25)
if [ -z "$CP" ] || [ -z "$JAVA_HOME_25" ]; then
    echo "  NG   jbang から JDT の classpath または JDK 25 を取得できませんでした"; echo "FAIL"; exit 1
fi

rm -rf build .cache output
"$JAVA_HOME_25/bin/javac" --release 17 -Xlint:all -Werror -Xdoclint:all,-missing -encoding UTF-8 \
    -cp "$CP" -d build $(find "$ROOT/src" -name '*.java') ResolveOrderCheck.java ResolverFactory.java \
    || { echo "  NG   コンパイルに失敗しました"; echo "FAIL"; exit 1; }

if "$JAVA_HOME_25/bin/java" -cp "build:$CP" ResolveOrderCheck config.properties; then
    echo "PASS"
else
    echo "FAIL"; exit 1
fi
