#!/usr/bin/env bash
# キャッシュの最終行を末尾から読む（CacheReader.lastLineOf）の検査。
#
#   bash test/cachetail/run.sh
#   JCHE_CP="build/classes:依存jar..." bash test/cachetail/run.sh   # コンパイル済みの classpath を使う
#
# 2 つのキャッシュが対になっているか（Z 行のブロック数が一致するか）の判定は毎回の実行で走るので、
# 先頭から読まずに末尾だけを読む。バイト単位で境界を触るため、改行の有無・CRLF・空ファイル・
# 読む量の境目・多バイト文字が窓の先頭で切れる形まで機械的にかける。
# 「先頭から読んで得た最後の非空行と一致する」ことも見る（置き換え前と同じ答えになること）。
#
# ツール本体（src/）と検査プログラムを javac でコンパイルし、jbang が用意した JDK 25 と
# JDT の jar で動かす（test/cachevalue/run.sh と同じ経路）。
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)

if [ -n "${JCHE_CP:-}" ]; then
    CP="$JCHE_CP"
    JAVA_BIN=java
    JAVAC_BIN=javac
else
    JBANG="bash $ROOT/jbangw/jbang"
    CP=$($JBANG info classpath "$ROOT/src/jche/CallHierarchyExporter.java" | tr ':' '\n' | grep -v '/cache/jars/' | paste -sd:)
    JAVA_HOME_25=$($JBANG jdk home 25)
    if [ -z "$CP" ] || [ -z "$JAVA_HOME_25" ]; then
        echo "  NG   jbang から JDT の classpath または JDK 25 を取得できませんでした"; echo "FAIL"; exit 1
    fi
    rm -rf build
    "$JAVA_HOME_25/bin/javac" --release 17 -Xlint:all -Werror -Xdoclint:all,-missing -encoding UTF-8 \
        -cp "$CP" -d build $(find "$ROOT/src" -name '*.java') \
        || { echo "  NG   ツール本体のコンパイルに失敗しました"; echo "FAIL"; exit 1; }
    CP="build:$CP"
    JAVA_BIN="$JAVA_HOME_25/bin/java"
    JAVAC_BIN="$JAVA_HOME_25/bin/javac"
fi

rm -rf check
"$JAVAC_BIN" --release 17 -Xlint:all -Werror -Xdoclint:all,-missing -encoding UTF-8 \
    -cp "$CP" -d check TrailerReadCheck.java \
    || { echo "  NG   検査プログラムのコンパイルに失敗しました"; echo "FAIL"; exit 1; }

if "$JAVA_BIN" -Dstdout.encoding=UTF-8 -cp "check:$CP" TrailerReadCheck; then
    echo "PASS"
else
    echo "FAIL"; exit 1
fi
