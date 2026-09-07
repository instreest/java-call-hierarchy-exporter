#!/usr/bin/env bash
# 回帰テスト。test/demo を解析し、出力 CSV を expected*/ と比較する。
#
#   bash test/regression/run.sh            # jbang 経由で実行（初回は JDK と JDT を取得）
#   JCHE_CMD="java -cp bin:lib/* CallHierarchyExporter" bash test/regression/run.sh   # 既にコンパイル済みなら
#
# ケースは2種類ある。
#   通常（whole / entry）… 同じ設定で2回実行する。1回目はキャッシュ無し、2回目はキャッシュを再利用する経路
#   jarchange            … 依存 jar 無し（config-before）→ 有り（config-after）→ 無し の順に実行し、
#                          キャッシュを保ったまま jar の追加・削除が出力に反映されることを確認する
# 実行ログは <case>/run-<回数>.log に残す。
# 期待出力を更新するときは、差分を確認したうえで output/ を expected*/ にコピーする。
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
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

run() {   # $1=case  $2=設定ファイル  $3=何回目  $4=ラベル   -> ログは $1/run-$3.log
    if ! $JCHE_CMD "$1/$2" > "$1/run-$3.log" 2>&1; then
        echo "  実行に失敗しました（$4）。$1/run-$3.log を確認してください"; tail -5 "$1/run-$3.log"; fail=1; return 1
    fi
}

# --- ログ検査 ---
# ツールは標準出力を端末の文字コードで書く（UTF-8 に固定すると Windows の画面で化けるため固定しない）。
# そのためログの日本語は環境によって化けうるので、検査は ASCII の部分だけで行う。
# フェーズ1の集計行「ソース解析: 再利用=N 新規解析=M（うち…=K） 失敗=F」は、
# 「=数字」が3つ以上あって数字で終わる最初の行として探す（同じ形の「型数=…」の行はフェーズ2なので後ろ）
summary_line() {   # $1=ログ
    LC_ALL=C grep -a -E -m1 '=[0-9]+.*=[0-9]+.*=[0-9]+[[:space:]]*$' "$1"
}
expect_reused() {   # $1=case  $2=何回目  $3=ラベル   … 集計行の最初の「=N」（再利用）が 0 でない
    if summary_line "$1/run-$2.log" | LC_ALL=C grep -q -E '^[^=]*=[1-9]'; then
        echo "  OK   $1 ログ ($3)"
    else
        echo "  DIFF $1 ログ: キャッシュが再利用されていません ($3): $(summary_line "$1/run-$2.log")"; fail=1
    fi
}
expect_library_reanalysis() {   # $1=case  $2=何回目  $3=ラベル   … 「依存jarの変更による再解析=N」の N が 0 でない
    if summary_line "$1/run-$2.log" | LC_ALL=C grep -q -E 'jar[^=]*=[1-9]'; then
        echo "  OK   $1 ログ ($3)"
    else
        echo "  DIFF $1 ログ: 依存jarの変更による再解析がありません ($3): $(summary_line "$1/run-$2.log")"; fail=1
    fi
}

for c in $CASES; do
    echo "== $c =="
    rm -rf "$c/.cache" "$c/output" "$c"/run-*.log
    if [ -f "$c/config-before.properties" ]; then
        run "$c" config-before.properties 1 "1回目: jar 無し" || continue
        compare "$c" expected-before "1回目: jar 無し"
        run "$c" config-after.properties 2 "2回目: jar 追加" || continue
        expect_library_reanalysis "$c" 2 "2回目: jar 追加で影響ファイルを再解析"
        expect_reused "$c" 2 "2回目: 他のファイルはキャッシュを再利用"
        compare "$c" expected-after "2回目: jar 追加"
        run "$c" config-before.properties 3 "3回目: jar 削除" || continue
        expect_library_reanalysis "$c" 3 "3回目: jar 削除で影響ファイルを再解析"
        compare "$c" expected-before "3回目: jar 削除"
    else
        run "$c" config.properties 1 "1回目" || continue
        compare "$c" expected "1回目: キャッシュ無し"
        run "$c" config.properties 2 "2回目" || continue
        expect_reused "$c" 2 "2回目: キャッシュを再利用"
        compare "$c" expected "2回目: キャッシュ再利用"
    fi
done

if [ $fail = 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
