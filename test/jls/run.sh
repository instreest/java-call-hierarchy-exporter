#!/usr/bin/env bash
# Java 言語仕様（JLS SE 26）への適合と、javac（JDK 26）との整合の検査。
#
#   bash test/jls/run.sh
#
# test/jls/project/src の各ファイルは JLS SE 26 の 1 つの節に対応する（パッケージ名が節番号。
# jls.s14_14_02 = §14.14.2。無名パッケージの CompactMain.java は §7.3 / §12.1.4）。
# 各ファイルの先頭のコメントに、その節の何を検査するかを書いてある。
#
# 1. ツールで解析する（source.level=26。JDT が対応する最新の版）
# 2. 同じソースを JDK 26 の javac で --release 26 -parameters でコンパイルする
# 3. JlsCheck.java（JDK 26 で直接実行）が次の 2 つを検査する
#    - expect.tsv: 節ごとに人が書いた期待値（何が何を呼ぶはずか・何が宣言されるはずか）を、
#      出力 CSV とキャッシュに当てる。1 行が 1 テストで、節番号と説明を持つ。キャッシュは
#      jche.cache.CacheDump で記号を 4 列に戻した形（build/cache-dump.tsv）を渡す
#    - javac のクラスファイル: 型・宣言・呼び出し（呼び出し先がこのソースのもの）・ラムダ・
#      ブリッジメソッドを、ツールのキャッシュの事実と突き合わせる
#
# ツール本体は javac でコンパイルし、jbang が用意した JDK 25 と JDT の jar で動かす
# （test/ctorbody/run.sh と同じ経路）。javac と検査プログラムは jbang が用意した JDK 26 で動かす。
# JCHE_JAVAC26 / JCHE_JAVA26 で差し替えられる。
set -uo pipefail
cd "$(dirname "$0")"
# 文言の言語を固定する（既定は英語。固定しないと実行環境のロケールで照合が変わる）
export JCHE_LANG=en
ROOT=$(cd ../.. && pwd)
JBANG="bash $ROOT/jbangw/jbang"

CP=${JCHE_CP:-$($JBANG info classpath "$ROOT/src/jche/CallHierarchyExporter.java" \
    | tr ':' '\n' | grep -v '/cache/jars/' | paste -sd:)}
JAVA_BIN=${JCHE_JAVA:-"$($JBANG jdk home 25)/bin/java"}
JAVAC_BIN=${JCHE_JAVAC:-"$($JBANG jdk home 25)/bin/javac"}
JAVAC26=${JCHE_JAVAC26:-"$($JBANG jdk home 26)/bin/javac"}
JAVA26=${JCHE_JAVA26:-"$($JBANG jdk home 26)/bin/java"}
if [ -z "$CP" ]; then
    echo "  NG   jbang から JDT の classpath を取得できませんでした"; echo "FAIL"; exit 1
fi

rm -rf build project/out project/.cache
if [ -z "${JCHE_CLASSES:-}" ]; then
    "$JAVAC_BIN" --release 17 -Xlint:all -Werror -Xdoclint:all,-missing -encoding UTF-8 \
        -cp "$CP" -d build/tool $(find "$ROOT/src" -name '*.java') \
        || { echo "  NG   ツールのコンパイルに失敗しました"; echo "FAIL"; exit 1; }
    CLASSES=$PWD/build/tool
else
    CLASSES=$JCHE_CLASSES
fi

# javac 26 でコンパイルする。-parameters はコンストラクタの合成引数（外側のインスタンス・enum の
# name/ordinal・取り込んだ変数）を MethodParameters の印で見分けるため。-g は行番号表のため
"$JAVAC26" --release 26 -parameters -g -encoding UTF-8 -d build/javac \
    $(find project/src -name '*.java') \
    || { echo "  NG   javac 26 でのコンパイルに失敗しました（fixture が Java 26 として正しくない）"; echo "FAIL"; exit 1; }

( cd project && "$JAVA_BIN" -cp "$CLASSES:$CP" jche.CallHierarchyExporter config.properties ) \
    > build/run.log 2>&1
OUT=$(ls -d project/out/*/ 2>/dev/null | sort | tail -1 | sed 's#/$##')
CACHE=$(ls project/.cache/*/analysis-cache.tsv 2>/dev/null | head -1)
if [ -z "$OUT" ] || [ ! -f "$OUT/call-hierarchy.csv" ] || [ -z "$CACHE" ]; then
    echo "  NG   解析できませんでした（test/jls/build/run.log）"; tail -20 build/run.log; echo "FAIL"; exit 1
fi

# キャッシュはメソッドを記号（S 行の番号）で指すので、名前で照合できるよう 4 列に戻した形を渡す
if ! "$JAVA_BIN" -cp "$CLASSES:$CP" jche.cache.CacheDump "$CACHE" > build/cache-dump.tsv 2> build/cache-dump.log; then
    echo "  NG   キャッシュを読める形にできませんでした（test/jls/build/cache-dump.log）"
    grep -v JAVA_TOOL_OPTIONS build/cache-dump.log | tail -5; echo "FAIL"; exit 1
fi

"$JAVA26" -Dstdout.encoding=UTF-8 JlsCheck.java expect.tsv "$OUT" build/cache-dump.tsv build/javac
