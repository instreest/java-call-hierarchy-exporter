#!/usr/bin/env bash
# 呼び出しに効いている条件のオンデマンド調査（--conditions）の検査。
#
#   bash test/conditions/run.sh
#
# test/demo を対象に、型・メソッド・ファイル・行の各指定で条件が出ること、判定できる条件と
# 判定できない条件が見分けられること、対象が無いときの終了コードを見る。
# あわせて「キャッシュも出力フォルダも作らない」ことを確認する（この機能の前提。docs/call-conditions.md）。
#
# 出力は日本語なので、文字コードを UTF-8 に固定して実行する（-Dstdout.encoding=UTF-8）。
# ツール本体は javac でコンパイルし、jbang が用意した JDK 25 と JDT の jar で動かす
# （test/dataflow/run.sh と同じ経路。jbang 自身が作ったスクリプトの jar は除く）。
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
JBANG="bash $ROOT/jbangw/jbang"
fail=0

CP=$($JBANG info classpath "$ROOT/src/jche/CallHierarchyExporter.java" | tr ':' '\n' | grep -v '/cache/jars/' | paste -sd:)
JAVA_HOME_25=$($JBANG jdk home 25)
if [ -z "$CP" ] || [ -z "$JAVA_HOME_25" ]; then
    echo "  NG   jbang から JDT の classpath または JDK 25 を取得できませんでした"; echo "FAIL"; exit 1
fi

rm -rf build .cache out
"$JAVA_HOME_25/bin/javac" --release 17 -Xlint:all -Werror -Xdoclint:all,-missing -encoding UTF-8 \
    -cp "$CP" -d build $(find "$ROOT/src" -name '*.java') \
    || { echo "  NG   コンパイルに失敗しました"; echo "FAIL"; exit 1; }

run() {   # $1=対象  -> 出力は out.log、終了コードは $code
    "$JAVA_HOME_25/bin/java" -Dstdout.encoding=UTF-8 -cp "build:$CP" jche.CallHierarchyExporter \
        --conditions "$1" config.properties > out.log 2>&1
    code=$?
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

echo "== 型で指定（fx.branch.Feature） =="
run fx.branch.Feature
expect_code "終了コード 0" 0
expect "ファイル名に読み替えて解析する" "src/fx/branch/Feature.java"
expect "判定できる条件が出る" "[判定可]"
expect "条件が無い呼び出しはそう書く" "（条件なし。このメソッドに入れば必ず実行される）"

echo "== メソッドで指定（fx.branch.Feature#mode） =="
run 'fx.branch.Feature#mode'
expect_code "終了コード 0" 0
expect "equals の条件が出る" '"full".equals(name)'
expect "外側の条件から順に出る" "1. [判定可]"
expect_missing "指定していないメソッドの呼び出しは出ない" "Feature.pick"

echo "== 判定できない条件（fx.excluded.Ping#a の n > 0） =="
run 'fx.excluded.Ping#a'
expect_code "終了コード 0" 0
expect "範囲比較は判定できない条件として出る" "[判定不可] n > 0"

echo "== null 判定も判定できない条件（fx.service.Notifier#execute） =="
run 'fx.service.Notifier#execute'
expect "null 判定は判定できない条件" "[判定不可] dao != null"

echo "== 行で指定（src/fx/branch/Feature.java:19） =="
run 'src/fx/branch/Feature.java:19'
expect_code "終了コード 0" 0
expect "その行の呼び出しだけが出る" "該当した呼び出し: 1 件"

echo "== 対象が無いとき =="
run fx.branch.NoSuchType
expect_code "終了コード 4" 4
expect "見つからないことを伝える" "対象のソースファイルが見つかりません"

run 'src/fx/branch/Feature.java:1'
expect_code "該当する呼び出しが無いときも終了コード 4" 4
expect "指定の外し方を案内する" "指定に合う呼び出しがありません"

echo "== 副作用が無いこと =="
if [ ! -e .cache ] && [ ! -e out ]; then
    echo "  OK   キャッシュも出力フォルダも作らない"
else
    echo "  NG   キャッシュまたは出力フォルダができています"; ls -a; fail=1
fi

rm -rf build out.log
if [ $fail = 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
