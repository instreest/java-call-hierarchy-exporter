#!/usr/bin/env bash
# データフローの解決の検査。
#
#   bash test/dataflow/run.sh              # 検査する
#   bash test/dataflow/run.sh --record     # TraceCheck の記録（trace/*.txt）を作り直す（下の注意）
#
# 1. ResolveOrderCheck … CallResolver.resolve の決定性（Issue #80）。test/demo を解析し、全エッジを
#    「昇順 → 同じ resolver でもう一度昇順 → 新しい resolver で降順」の 3 通りで解決して、結果が一致することを見る
# 2. StoreUnitCheck   … 値の表を組む側を、書き手が作らない行（手で書き換えたキャッシュ）で直接たたく
#    （条件の値の数・実引数の位置の形・種別・メソッドキーの共有。jche/graph/StoreUnitCheck.java の説明）
#    ValueStoreCheck  … 値の表（jche.graph.ValueStore / GuardTable）が、今の読み手が受け取っている出所の文字列と
#    同じ中身を持つこと（値の読み手を文字列から表へ移す stage B の間の証明。ValueStoreCheck.java の説明）
# 3. TraceCheck       … 経路を歩いた中身（解決・経路の値・打ち切り・呼び戻しなど）が、記録（trace/*.txt）と
#    1 文字も違わないこと（stage B の間だけ。記録は文字列の側が変わっていないときに --record で作る。
#    読み手を移すコミットで作り直してはいけない）
#
# ValueStoreCheck と TraceCheck は CheckProjects.java に並べたプロジェクト（test/demo・test/jls/project・test/regression の entry と
# plugin の設定、値に | ; { } を含む test/regression/values、読み手の分かれ道を踏むための projects/paths）を
# 解析する。キャッシュと出力は work/<名前>/ に置く。
#
# ツール本体（src/）と検査プログラム（このフォルダの *.java。jche/ の下は src と同じパッケージに入れて
# パッケージの中だけで見えるものを読む）を javac でコンパイルし、jbang が用意した JDK 25 と JDT の jar で動かす
# （CI の lint と同じ経路。jbang 自身が作ったスクリプトの jar は除く）。projects/ の下は解析される側の
# ソースなので、検査プログラムとしてはコンパイルしない。
# キャッシュは .cache と work/ に、コンパイル結果は build にできる（どれもコミットしない）。
set -uo pipefail
cd "$(dirname "$0")"
# 文言の言語を固定する（既定は英語。固定しないと実行環境のロケールでログの文言が変わる）
export JCHE_LANG=en
ROOT=$(cd ../.. && pwd)
JBANG="bash $ROOT/jbangw/jbang"

CP=${JCHE_CP:-$($JBANG info classpath "$ROOT/src/jche/CallHierarchyExporter.java" | tr ':' '\n' | grep -v '/cache/jars/' | paste -sd:)}
JAVA_HOME_25=${JCHE_JAVA_HOME:-$($JBANG jdk home 25)}
if [ -z "$CP" ] || [ -z "$JAVA_HOME_25" ]; then
    echo "  NG   jbang から JDT の classpath または JDK 25 を取得できませんでした"; echo "FAIL"; exit 1
fi

rm -rf build .cache output work
mkdir -p work
"$JAVA_HOME_25/bin/javac" --release 17 -Xlint:all -Werror -Xdoclint:all,-missing -encoding UTF-8 \
    -cp "$CP" -d build $(find "$ROOT/src" -name '*.java') \
    $(find . -name '*.java' -not -path './build/*' -not -path './work/*' -not -path './projects/*') \
    || { echo "  NG   コンパイルに失敗しました"; echo "FAIL"; exit 1; }

fail=0
# 検査プログラムのログは work/<検査>.log に置き、結果の行（OK / NG / REC とその続き）だけを出す
run_check() {   # $1=クラス名  $2..=引数
    local name=$1; shift
    "$JAVA_HOME_25/bin/java" -cp "build:$CP" "$name" "$@" > "work/$name.log" 2>&1
    local code=$?
    grep -a -E '^(OK|NG|REC|  NG|     |       )' "work/$name.log"
    if [ $code != 0 ]; then
        echo "  NG   $name が失敗しました（終了コード $code。test/dataflow/work/$name.log）"
        grep -a -E 'Exception|Error' "work/$name.log" | head -5
        fail=1
    fi
}

if [ "${1:-}" = --record ]; then
    run_check TraceCheck --record
    if [ $fail = 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
    exit 0
fi

run_check ResolveOrderCheck config.properties
run_check jche.graph.StoreUnitCheck
run_check ValueStoreCheck
run_check TraceCheck

if [ $fail = 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
