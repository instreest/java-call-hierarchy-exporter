#!/usr/bin/env bash
# 1 ファイル版（single-file/CallHierarchyExporterSingle.java）の検査。
#
#   bash test/single-file/run.sh
#   JCHE_CP="依存jar..." bash test/single-file/run.sh   # jbang を使わず、PATH の javac / java（JDK 25）と与えた JDT の classpath で
#
# 見るのは 2 つ。1 ファイル版は本体（src/jche）と同期を取らない場合があるので、本体との一致
# （生成し直した結果との一致・回帰テストの期待値との一致）は見ない（docs/single-file-qa.md の Q8）。
#   1. ビルドできること … 本体と同じ引数（--release 17 -Xlint:all -Werror -Xdoclint:all,-missing）で警告ゼロでコンパイルできる
#   2. 起動できること … --help が終了コード 0 で使い方を出す。知らないオプションは 2
set -uo pipefail
cd "$(dirname "$0")"
# 文言の言語を固定する（既定は英語。固定しないと実行環境のロケールでログの文言が変わる）
export JCHE_LANG=en
ROOT=$(cd ../.. && pwd)
SINGLE="$ROOT/single-file/CallHierarchyExporterSingle.java"
fail=0

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
    JAVA_BIN="$JAVA_HOME_25/bin/java"
    JAVAC_BIN="$JAVA_HOME_25/bin/javac"
fi

rm -rf build
mkdir -p build

# --- 1. ビルド（本体の lint と同じ引数） ---
if "$JAVAC_BIN" --release 17 -Xlint:all -Werror -Xdoclint:all,-missing -encoding UTF-8 \
        -cp "$CP" -d build/classes "$SINGLE" "$ROOT"/single-file/jche/extension/*.java > build/javac.log 2>&1; then
    echo "  OK   1 ファイル版を警告ゼロでコンパイルできる"
else
    echo "  NG   1 ファイル版のコンパイルに失敗した"; head -30 build/javac.log; echo "FAIL"; exit 1
fi
CMD="$JAVA_BIN -cp $PWD/build/classes:$CP jche.CallHierarchyExporterSingle"

# --- 2. 起動 ---
if $CMD --help > build/help.log 2>&1 && grep -q 'java-call-hierarchy-exporter' build/help.log; then
    echo "  OK   --help が使い方を出して 0 で終わる"
else
    echo "  NG   --help の終了コードか出力が想定と違う"; head -5 build/help.log; fail=1
fi
$CMD --no-such-option > build/badopt.log 2>&1
if [ $? = 2 ]; then
    echo "  OK   知らないオプションは 2 で終わる"
else
    echo "  NG   知らないオプションの終了コードが 2 でない"; head -5 build/badopt.log; fail=1
fi

if [ $fail = 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
