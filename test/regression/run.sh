#!/usr/bin/env bash
# 回帰テスト。samples/demo を解析し、出力 CSV を expected/ と比較する。
#
#   bash test/regression/run.sh            # jbang 経由で実行（初回は JDK と JDT を取得）
#   JCHE_CMD="java -cp bin:lib/* CallHierarchyExporter" bash test/regression/run.sh   # 既にコンパイル済みなら
#
# 各ケースを2回実行する。1回目はキャッシュ無し、2回目はキャッシュを再利用する経路。
# 期待出力を更新するときは、差分を確認したうえで output/ を expected/ にコピーする。
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
JCHE_CMD=${JCHE_CMD:-"bash $ROOT/jbangw/jbang run $ROOT/src/CallHierarchyExporter.java"}
CASES=${CASES:-"whole entry"}
fail=0

compare() {   # $1=case  $2=何回目
    local ok=1
    for f in call-hierarchy.csv methods.csv; do
        if diff --strip-trailing-cr -q "$1/expected/$f" "$1/output/$f" > /dev/null; then
            echo "  OK   $1/$f ($2)"
        else
            echo "  DIFF $1/$f ($2)"
            diff --strip-trailing-cr "$1/expected/$f" "$1/output/$f" | head -20
            ok=0
        fi
    done
    [ $ok = 1 ] || fail=1
}

for c in $CASES; do
    echo "== $c =="
    rm -rf "$c/.cache" "$c/output"
    if ! $JCHE_CMD "$c/config.properties" > "$c/run.log" 2>&1; then
        echo "  実行に失敗しました（1回目）。$c/run.log を確認してください"; tail -5 "$c/run.log"; fail=1; continue
    fi
    compare "$c" "1回目: キャッシュ無し"
    if ! $JCHE_CMD "$c/config.properties" >> "$c/run.log" 2>&1; then
        echo "  実行に失敗しました（2回目）。$c/run.log を確認してください"; tail -5 "$c/run.log"; fail=1; continue
    fi
    grep -E 'ソース解析:' "$c/run.log" | tail -1 | grep -q '再利用=0' && { echo "  DIFF 2回目でキャッシュが再利用されていません"; fail=1; }
    compare "$c" "2回目: キャッシュ再利用"
done

if [ $fail = 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
