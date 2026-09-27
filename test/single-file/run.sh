#!/usr/bin/env bash
# 1 ファイル版（single-file/CallHierarchyExporterSingle.java）の検査。
#
#   bash test/single-file/run.sh
#   JCHE_CP="依存jar..." bash test/single-file/run.sh   # jbang を使わず、PATH の javac / java（JDK 25）と与えた JDT の classpath で
#
# 見るのは 4 つ。
#   1. 生成し直し忘れが無いこと … single-file/generate.sh で作り直した結果が、コミットされているファイルと一致する。
#      本体（src/jche）を直したのに 1 ファイル版が古いまま、を検出する
#   2. ビルドできること … 本体と同じ引数（--release 17 -Xlint:all -Werror -Xdoclint:all,-missing）で警告ゼロでコンパイルできる
#   3. 起動できること … --help が終了コード 0 で使い方を出す。知らないオプションは 2
#   4. 本体と同じ出力になること … 回帰テスト（test/regression/run.sh）の whole・entry を 1 ファイル版で回して期待値と一致する。
#      あわせて、同梱の拡張を本体と同じ名前（jche.builtin.TypeMappingProvider）で読み込めること
#      （1 ファイル版では入れ子のクラス名に読み替える。plugin ケースの 1・2 回目に当たる実行で expected と比べる）
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

# --- 1. 生成し直し忘れ ---
if ! "$JAVA_BIN" "$ROOT/single-file/generator/MergeSources.java" "$ROOT/src/jche" build/regenerated.java > build/generate.log 2>&1; then
    echo "  NG   1 ファイル版を生成できませんでした（single-file/generator/MergeSources.java）"; cat build/generate.log; echo "FAIL"; exit 1
fi
if diff -q build/regenerated.java "$SINGLE" > /dev/null; then
    echo "  OK   single-file/CallHierarchyExporterSingle.java は src/jche から生成し直した結果と一致する"
else
    echo "  NG   single-file/CallHierarchyExporterSingle.java が古い。bash single-file/generate.sh で生成し直してコミットすること"
    diff build/regenerated.java "$SINGLE" | head -20
    fail=1
fi

# --- 2. ビルド（本体の lint と同じ引数） ---
if "$JAVAC_BIN" --release 17 -Xlint:all -Werror -Xdoclint:all,-missing -encoding UTF-8 \
        -cp "$CP" -d build/classes "$SINGLE" > build/javac.log 2>&1; then
    echo "  OK   1 ファイル版を警告ゼロでコンパイルできる"
else
    echo "  NG   1 ファイル版のコンパイルに失敗した"; head -30 build/javac.log; echo "FAIL"; exit 1
fi
CMD="$JAVA_BIN -cp $PWD/build/classes:$CP jche.CallHierarchyExporterSingle"

# --- 3. 起動 ---
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

# --- 4. 本体と同じ出力 ---
if CASES="whole entry" JCHE_CMD="$CMD" bash "$ROOT/test/regression/run.sh" > build/regression.log 2>&1; then
    echo "  OK   回帰テスト（whole・entry）が 1 ファイル版でも通る"
else
    echo "  NG   回帰テスト（whole・entry）が 1 ファイル版で通らない（build/regression.log）"
    grep -E 'DIFF|NG|失敗' build/regression.log | head -10; fail=1
fi

# 同梱の拡張を本体と同じ名前で読み込めること（plugin ケースの 1・2 回目に当たる実行）
PLUGIN="$ROOT/test/regression/plugin"
rm -rf "$PLUGIN/.cache" "$PLUGIN/output"
if $CMD "$PLUGIN/config-before.properties" > build/plugin-1.log 2>&1 \
        && $CMD "$PLUGIN/config.properties" > build/plugin-2.log 2>&1; then
    out=$(ls -d "$PLUGIN"/output/*/ 2>/dev/null | sort | tail -1 | sed 's#/$##')
    if LC_ALL=C grep -a -q 'TypeMappingProvider' build/plugin-2.log; then
        echo "  OK   同梱の拡張 jche.builtin.TypeMappingProvider を読み込んだ"
    else
        echo "  NG   同梱の拡張 jche.builtin.TypeMappingProvider を読み込めていない（build/plugin-2.log）"; fail=1
    fi
    for f in call-hierarchy.csv methods.csv; do
        if diff --strip-trailing-cr -q "$PLUGIN/expected/$f" "$out/$f" > /dev/null; then
            echo "  OK   plugin/$f（同梱の拡張で具象クラス 1 件に絞れる）"
        else
            echo "  DIFF plugin/$f（同梱の拡張）"; diff --strip-trailing-cr "$PLUGIN/expected/$f" "$out/$f" | head -10; fail=1
        fi
    done
else
    echo "  NG   plugin ケースの実行に失敗した（build/plugin-*.log）"; tail -5 build/plugin-2.log; fail=1
fi
rm -rf "$PLUGIN/.cache" "$PLUGIN/output"

if [ $fail = 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
