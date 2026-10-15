#!/usr/bin/env bash
# entry.packages / exclude.packages のパターン（jche.config.PackagePattern）の照合の検査。
#
#   bash test/pattern/run.sh
#   JCHE_CP="build/classes:依存jar..." bash test/pattern/run.sh   # コンパイル済みの classpath を使う
#
# パターンは文字列なので、照合を誤ってもコンパイルは通り、除外が効かない・効きすぎるだけで何も言わない。
# 途中の *（任意の 1 階層）・**（任意の 0 階層以上）と、従来の書き方（末尾の .* / .**・クラス・メソッド）について、
# 当たる例と当たらない例を並べて見る。
#
# ツール本体（src/）と検査プログラムを javac でコンパイルし、jbang が用意した JDK 25 で動かす
# （test/rules/run.sh と同じ経路）。
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
    -cp "$CP" -d check PackagePatternCheck.java \
    || { echo "  NG   検査プログラムのコンパイルに失敗しました"; echo "FAIL"; exit 1; }

# 日本語のメッセージを出すので文字コードを固定する
if "$JAVA_BIN" -Dstdout.encoding=UTF-8 -cp "check:$CP" jche.config.PackagePatternCheck; then
    echo "PASS"
else
    echo "FAIL"; exit 1
fi
