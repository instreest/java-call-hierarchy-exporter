#!/usr/bin/env bash
# 呼び出しに効いている条件の一覧（設定ファイルの conditions.target）の検査。
#
#   bash test/conditions/run.sh
#
# conditions.target は通常の解析を置き換えない。キャッシュの更新も CSV の出力も
# これまでどおり行ったうえで、追加で call-conditions.csv を書く（docs/call-conditions.md）。
# ここでは test/demo を対象に、型・メソッド・ファイル・行の各指定で条件が出ること、
# 判定できる条件と判定できない条件が見分けられること、通常の出力が揃っていることを見る。
#
# 対象は config.properties の conditions.target で指定するので、ケースごとに
# base.properties へその1行を足した設定ファイルを作って渡す。
#
# 出力は日本語なので、文字コードを UTF-8 に固定して実行する（-Dstdout.encoding=UTF-8）。
# ツール本体は javac でコンパイルし、jbang が用意した JDK 25 と JDT の jar で動かす
# （test/dataflow/run.sh と同じ経路。jbang 自身が作ったスクリプトの jar は除く）。
set -uo pipefail
cd "$(dirname "$0")"
# 文言の言語を固定する（既定は英語。固定しないと実行環境のロケールで照合が変わる）
export JCHE_LANG=en
ROOT=$(cd ../.. && pwd)
JBANG="bash $ROOT/jbangw/jbang"
fail=0

CP=$($JBANG info classpath "$ROOT/src/jche/CallHierarchyExporter.java" | tr ':' '\n' | grep -v '/cache/jars/' | paste -sd:)
JAVA_HOME_25=$($JBANG jdk home 25)
if [ -z "$CP" ] || [ -z "$JAVA_HOME_25" ]; then
    echo "  NG   jbang から JDT の classpath または JDK 25 を取得できませんでした"; echo "FAIL"; exit 1
fi

rm -rf build .cache out case.properties
"$JAVA_HOME_25/bin/javac" --release 17 -Xlint:all -Werror -Xdoclint:all,-missing -encoding UTF-8 \
    -cp "$CP" -d build $(find "$ROOT/src" -name '*.java') \
    || { echo "  NG   コンパイルに失敗しました"; echo "FAIL"; exit 1; }

latest_output() { ls -d out/*/ 2>/dev/null | sort | tail -1 | sed 's#/$##'; }

run() {   # $1=conditions.target の値（空なら足さない） -> 出力は out.log、終了コードは $code
    cp base.properties case.properties
    [ -n "$1" ] && printf 'conditions.target=%s\n' "$1" >> case.properties
    "$JAVA_HOME_25/bin/java" -Dstdout.encoding=UTF-8 -cp "build:$CP" jche.CallHierarchyExporter \
        case.properties > out.log 2>&1
    code=$?
    OUT=$(latest_output)
}

expect() {   # $1=説明  $2=期待する文字列
    if grep -q -F -- "$2" out.log; then
        echo "  OK   $1"
    else
        echo "  NG   $1（「$2」が出力にありません）"; sed -n 1,20p out.log; fail=1
    fi
}

expect_missing() {   # $1=説明  $2=出てはいけない文字列
    if grep -q -F -- "$2" out.log; then
        echo "  NG   $1（「$2」が出力にあります）"; fail=1
    else
        echo "  OK   $1"
    fi
}

expect_code() {   # $1=説明  $2=期待する終了コード
    if [ "$code" = "$2" ]; then echo "  OK   $1"; else echo "  NG   $1（終了コード $code、期待 $2）"; fail=1; fi
}

expect_csv() {   # $1=説明  $2=call-conditions.csv に期待する文字列
    if [ -f "$OUT/call-conditions.csv" ] && grep -q -F -- "$2" "$OUT/call-conditions.csv"; then
        echo "  OK   $1"
    else
        echo "  NG   $1（call-conditions.csv に「$2」がありません）"; fail=1
    fi
}

echo "== 型で指定（fx.branch.Feature）。通常の出力に追加される =="
run fx.branch.Feature
expect_code "終了コード 0" 0
expect "通常どおり呼び出し階層を出す" "call-hierarchy.csv"
expect "追加の出力であることが分かる" "=== Extra: conditions that gate the calls (conditions.target=fx.branch.Feature) ==="
expect "条件の一覧の場所を出す" "Call conditions: "
expect "判定できる条件が画面にも出る" "[decidable]"
if [ -f "$OUT/call-hierarchy.csv" ] && [ -f "$OUT/methods.csv" ] && [ -f "$OUT/call-conditions.csv" ] \
   && [ -f "$OUT/run.log" ] && [ -d .cache ]; then
    echo "  OK   通常の出力（call-hierarchy.csv / methods.csv / run.log）とキャッシュはそのまま作る"
else
    echo "  NG   通常の出力またはキャッシュがありません"; ls -a "$OUT" 2>/dev/null; fail=1
fi
expect_csv "CSV の見出し" "file,line,caller,callee,condIndex,decidable,condition,subjectKind,expectation"
expect_csv "条件が無い呼び出しも1行出る" "Mode.<clinit>()"

echo "== メソッドで指定（fx.branch.Feature#mode） =="
run 'fx.branch.Feature#mode'
expect_code "終了コード 0" 0
expect "equals の条件が出る" '"full".equals(name)'
expect "外側の条件から順に出る" "1. [decidable]"
expect_missing "指定していないメソッドの呼び出しは出ない" "Feature.pick"
expect_csv "判定できる条件は decidable=1 と期待値つき" '1,"""full"".equals(name)",param 1,= full'

echo "== 判定できない条件（fx.excluded.Ping#a の n > 0） =="
run 'fx.excluded.Ping#a'
expect_code "終了コード 0" 0
expect "範囲比較は判定できない条件として出る" "[undecidable] n > 0"
expect_csv "判定できない条件は decidable=0" "0,n > 0,,"

echo "== null 判定も判定できない条件（fx.service.Notifier#execute） =="
run 'fx.service.Notifier#execute'
expect "null 判定は判定できない条件" "[undecidable] dao != null"

echo "== 行で指定（src/fx/branch/Feature.java:19） =="
run 'src/fx/branch/Feature.java:19'
expect_code "終了コード 0" 0
expect "その行の呼び出しだけが出る" "Matching call sites: 1 "

echo "== 対象が無いときは警告にとどめ、解析は成功させる =="
run fx.branch.NoSuchType
expect_code "終了コード 0（解析そのものは成功）" 0
expect "見つからないことを警告する" "No target matches conditions.target"
expect "通常の出力は行う" "call-hierarchy.csv"
if [ -f "$OUT/call-conditions.csv" ]; then
    echo "  NG   該当が無いのに call-conditions.csv ができています"; fail=1
else
    echo "  OK   該当が無ければ call-conditions.csv は作らない"
fi

run 'src/fx/branch/Feature.java:1'
expect_code "該当する呼び出しが無いときも成功" 0
expect "指定の外し方を案内する" "No call site matches conditions.target"

echo "== conditions.target が空欄なら、これまでどおり =="
run ""
expect_code "終了コード 0" 0
expect_missing "条件の一覧は出さない" "追加: 呼び出しに効いている条件"
if [ -f "$OUT/call-hierarchy.csv" ] && [ ! -f "$OUT/call-conditions.csv" ]; then
    echo "  OK   call-conditions.csv は作らない"
else
    echo "  NG   conditions.target が空欄なのに call-conditions.csv があります"; fail=1
fi

rm -rf build out.log case.properties .cache out
if [ $fail = 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
