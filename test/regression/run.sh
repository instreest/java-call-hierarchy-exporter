#!/usr/bin/env bash
# 回帰テスト。samples/demo を解析し、出力 CSV を expected*/ と比較する。
#
#   bash test/regression/run.sh            # jbang 経由で実行（初回は JDK と JDT を取得）
#   JCHE_CMD="java -cp bin:lib/* CallHierarchyExporter" bash test/regression/run.sh   # 既にコンパイル済みなら
#
# ケースは2種類ある。
#   通常（whole / entry）… 同じ設定で2回実行する。1回目はキャッシュ無し、2回目はキャッシュを再利用する経路
#   jarchange            … 依存 jar 無し（config-before）→ 有り（config-after）→ 無し の順に実行し、
#                          キャッシュを保ったまま jar の追加・削除が出力に反映されることを確認する
# 期待出力を更新するときは、差分を確認したうえで output/ を expected*/ にコピーする。
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
# 実行ログの日本語を確認するので、JVM の標準出力を UTF-8 に固定する（ロケールが C/POSIX の環境でも読めるように）。
# ツール本体は端末の文字コードに従う設計だが、ここではファイルに書いてから読むので固定してよい
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
JCHE_CMD=${JCHE_CMD:-"bash $ROOT/jbangw/jbang run $ROOT/src/CallHierarchyExporter.java"}
CASES=${CASES:-"whole entry jarchange"}
fail=0

compare() {   # $1=case  $2=期待出力のフォルダ  $3=ラベル
    local ok=1
    for f in call-hierarchy.csv methods.csv; do
        if diff --strip-trailing-cr -q "$1/$2/$f" "$1/output/$f" > /dev/null; then
            echo "  OK   $1/$f ($3)"
        else
            echo "  DIFF $1/$f ($3)"
            diff --strip-trailing-cr "$1/$2/$f" "$1/output/$f" | head -20
            ok=0
        fi
    done
    [ $ok = 1 ] || fail=1
}

run() {   # $1=case  $2=設定ファイル  $3=ラベル   -> 実行ログは $1/run.log に追記
    if ! $JCHE_CMD "$1/$2" >> "$1/run.log" 2>&1; then
        echo "  実行に失敗しました（$3）。$1/run.log を確認してください"; tail -5 "$1/run.log"; fail=1; return 1
    fi
}

# 直近の実行ログの「ソース解析:」行に $2 が含まれることを確認する
expect_log() {   # $1=case  $2=期待する文字列  $3=ラベル
    if grep -E 'ソース解析:' "$1/run.log" | tail -1 | grep -q -- "$2"; then
        echo "  OK   $1 ログ ($3)"
    else
        echo "  DIFF $1 ログに「$2」がありません ($3): $(grep -E 'ソース解析:' "$1/run.log" | tail -1)"; fail=1
    fi
}

for c in $CASES; do
    echo "== $c =="
    rm -rf "$c/.cache" "$c/output" "$c/run.log"
    if [ -f "$c/config-before.properties" ]; then
        run "$c" config-before.properties "1回目: jar 無し" || continue
        compare "$c" expected-before "1回目: jar 無し"
        run "$c" config-after.properties "2回目: jar 追加" || continue
        expect_log "$c" '依存jarの変更による再解析=' "2回目: jar 追加で影響ファイルを再解析"
        expect_log "$c" '再利用=[1-9]' "2回目: 他のファイルはキャッシュを再利用"
        compare "$c" expected-after "2回目: jar 追加"
        run "$c" config-before.properties "3回目: jar 削除" || continue
        expect_log "$c" '依存jarの変更による再解析=' "3回目: jar 削除で影響ファイルを再解析"
        compare "$c" expected-before "3回目: jar 削除"
    else
        run "$c" config.properties "1回目" || continue
        compare "$c" expected "1回目: キャッシュ無し"
        run "$c" config.properties "2回目" || continue
        expect_log "$c" '再利用=[1-9]' "2回目: キャッシュを再利用"
        compare "$c" expected "2回目: キャッシュ再利用"
    fi
done

if [ $fail = 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
