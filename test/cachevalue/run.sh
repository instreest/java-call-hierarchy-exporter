#!/usr/bin/env bash
# キャッシュの列の符号化（CacheFormat.escape / unescape。joinRow が書き、CacheReader が戻す）の検査。
#
#   bash test/cachevalue/run.sh
#   JCHE_CP="build/classes:依存jar..." bash test/cachevalue/run.sh   # コンパイル済みの classpath を使う
#
# 見る性質は「どんな文字列でも往復する」「符号化した結果に行を壊す文字が残らない」「符号化した結果を UTF-8 に
# 書ける（対になっていないサロゲートが残らない）」の 3 つ。
# SQL やログ文言のような長さも中身も選べない文字列をキャッシュにそのまま持つための土台なので、
# 実データでは踏まない形（全制御文字、バックスラッシュの連なり、途中で切れた符号）まで機械的にかける。
# あわせて、文字列のハッシュ（FileHash.ofText。長い定数の K 行・自分の宣言の指紋）が、対になっていない
# サロゲートだけが違う文字列を区別することも見る。
#
# ツール本体（src/）と検査プログラムを javac でコンパイルし、jbang が用意した JDK 25 と
# JDT の jar で動かす（test/dataflow/run.sh・test/conditions/run.sh と同じ経路）。
set -uo pipefail
cd "$(dirname "$0")"
# 文言の言語を固定する（既定は英語。固定しないと実行環境のロケールでログの文言が変わる）
export JCHE_LANG=en
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
    -cp "$CP" -d check ValueEncodingCheck.java \
    || { echo "  NG   検査プログラムのコンパイルに失敗しました"; echo "FAIL"; exit 1; }

# 出力に制御文字の表示が混ざるので、文字コードを固定して読めるようにする
if "$JAVA_BIN" -Dstdout.encoding=UTF-8 -cp "check:$CP" ValueEncodingCheck; then
    echo "PASS"
else
    echo "FAIL"; exit 1
fi
