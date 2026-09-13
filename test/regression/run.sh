#!/usr/bin/env bash
# 回帰テスト。test/demo を解析し、出力 CSV を expected*/ と比較する。
#
#   bash test/regression/run.sh            # jbang 経由で実行（初回は JDK と JDT を取得）
#   JCHE_CMD="java -cp bin:lib/* CallHierarchyExporter" bash test/regression/run.sh   # 既にコンパイル済みなら
#
# ケースは2種類ある。
#   通常（whole / entry）… 同じ設定で3回実行する。1回目はキャッシュ無し、2回目はキャッシュを再利用する経路、
#                          3回目は解析対象のソースと jar の更新時刻を全部変えてから（touch）実行し、中身が同じなら
#                          内容ハッシュでキャッシュが再利用されること（GitHub Actions のチェックアウト後と同じ状況）を確認する
#   jarchange            … 依存 jar 無し（config-before）→ 有り（config-after）→ 無し の順に実行し、
#                          キャッシュを保ったまま jar の追加・削除が出力に反映されることを確認する
#   maven / mavenmulti / gradle
#                        … library.folders を空欄にして、test/maven-demo（pom.xml）、test/maven-multi
#                          （マルチモジュール）、test/gradle-demo（build.gradle）のビルドファイルから依存 jar を
#                          集める。jar は test/localrepo（library.repositories）から。ビルドツールは要らない。
#                          実行の形は通常ケースと同じ
#   plugin               … 拡張（インスタンス解析条件のプラグイン）。拡張なし（config-before）→ 同梱の拡張
#                          （config.properties。ファクトリのキーと対応表）→ 自前の拡張（config-custom。
#                          plugins/*.java を実行時にコンパイル）の順に実行し、拡張ありでのみ具象クラスに
#                          絞れること、フェーズAの拡張を変えるとキャッシュが捨てられることを確認する
#   multi                … 最後に whole と entry の設定ファイルを 1 回の起動にまとめて渡し（存在しない設定も
#                          1 つ混ぜる）、設定ごとに出力フォルダができること、1 つが失敗しても残りが処理されて
#                          終了コードが 1 になることを確認する。あわせて環境変数 JCHE_OUTPUT_DIR_FILE
#                          （成功した設定の出力フォルダを 1 行ずつ書き出す。GitHub Actions 用）も確認する
# 出力はツールが <case>/output/<解析開始日時>_<プロジェクト名>/ に書く（実行のたびに新しいフォルダ）。
# 比較は最新のフォルダに対して行う。キャッシュは各ケースの cache.folder=./.cache の下（whole だけは
# 既定どおり、ツールのプロジェクトフォルダの .cache/demo_<ハッシュ>/ にできることを検査する）。
# 実行ログは <case>/run-<回数>.log に残す（ツール自身が出力フォルダに書く run.log とは別）。
# 期待出力を更新するときは、差分を確認したうえで最新の output/*/ の CSV を expected*/ にコピーする。
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
JCHE_CMD=${JCHE_CMD:-"bash $ROOT/jbangw/jbang run $ROOT/src/CallHierarchyExporter.java"}
CASES=${CASES:-"whole entry jarchange maven mavenmulti gradle plugin multi"}
fail=0

latest_output() {   # $1=case  -> 最新の出力フォルダ（フォルダ名の先頭が日時なので、名前順の末尾）
    ls -d "$1"/output/*/ 2>/dev/null | sort | tail -1 | sed 's#/$##'
}

compare() {   # $1=case  $2=期待出力のフォルダ  $3=ラベル
    local ok=1 out
    out=$(latest_output "$1")
    if [ -z "$out" ]; then
        echo "  DIFF $1: 出力フォルダがありません ($3)"; fail=1; return
    fi
    for f in call-hierarchy.csv methods.csv; do
        if diff --strip-trailing-cr -q "$1/$2/$f" "$out/$f" > /dev/null; then
            echo "  OK   $1/$f ($3)"
        else
            echo "  DIFF $1/$f ($3)"
            diff --strip-trailing-cr "$1/$2/$f" "$out/$f" | head -20
            ok=0
        fi
    done
    [ $ok = 1 ] || fail=1
}

# 出力フォルダに設定ファイルの複製と実行ログ（run.log）があること。run.log は UTF-8 で書かれるので
# 日本語も検査できるが、ここでも ASCII だけを見る（最後に出る「呼び出し階層: …/call-hierarchy.csv（N 行）」の行）
expect_run_files() {   # $1=case  $2=設定ファイル名  $3=ラベル
    local out
    out=$(latest_output "$1")
    if [ -f "$out/$2" ] && cmp -s "$1/$2" "$out/$2"; then
        echo "  OK   $1 設定ファイルの複製 ($3)"
    else
        echo "  DIFF $1 設定ファイルの複製がありません: $out/$2 ($3)"; fail=1
    fi
    if [ -f "$out/run.log" ] && LC_ALL=C grep -a -q -F "call-hierarchy.csv" "$out/run.log"; then
        echo "  OK   $1 run.log ($3)"
    else
        echo "  DIFF $1 run.log がありません、または完了の行がありません: $out/run.log ($3)"; fail=1
    fi
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
expect_not_reused() {   # $1=case  $2=何回目  $3=ラベル   … 集計行の最初の「=N」（再利用）が 0
    if summary_line "$1/run-$2.log" | LC_ALL=C grep -q -E '^[^=]*=0([^0-9]|$)'; then
        echo "  OK   $1 ログ ($3)"
    else
        echo "  DIFF $1 ログ: キャッシュが捨てられていません ($3): $(summary_line "$1/run-$2.log")"; fail=1
    fi
}
expect_log_contains() {   # $1=case  $2=何回目  $3=ASCII の文字列  $4=ラベル
    if LC_ALL=C grep -a -q -F -- "$3" "$1/run-$2.log"; then
        echo "  OK   $1 ログ ($4)"
    else
        echo "  DIFF $1 ログ: 「$3」がありません ($4)"; fail=1
    fi
}
expect_library_reanalysis() {   # $1=case  $2=何回目  $3=ラベル   … 「依存jarの変更による再解析=N」の N が 0 でない
    if summary_line "$1/run-$2.log" | LC_ALL=C grep -q -E 'jar[^=]*=[1-9]'; then
        echo "  OK   $1 ログ ($3)"
    else
        echo "  DIFF $1 ログ: 依存jarの変更による再解析がありません ($3): $(summary_line "$1/run-$2.log")"; fail=1
    fi
}
expect_nothing_parsed() {   # $1=case  $2=何回目  $3=ラベル   … 集計行の 2 つ目の「=N」（新規解析）が 0
    # 新規解析が 0 なら、依存 jar の変更による再解析も 0（内訳は新規解析が 0 のときは出ない）
    if summary_line "$1/run-$2.log" | LC_ALL=C grep -q -E '^[^=]*=[0-9]+[^=]*=0([^0-9]|$)'; then
        echo "  OK   $1 ログ ($3)"
    else
        echo "  DIFF $1 ログ: 解析し直したファイルがあります ($3): $(summary_line "$1/run-$2.log")"; fail=1
    fi
}
# 解析対象のソースと jar の更新時刻を全部「今」にする（中身は変えない）。GitHub Actions の actions/checkout や
# コピーで更新時刻だけが変わった状況の再現。対象は test/ 配下の解析対象と test/localrepo の jar
touch_sources_and_jars() {
    find "$ROOT/test/demo" "$ROOT/test/maven-demo" "$ROOT/test/maven-multi" "$ROOT/test/gradle-demo" \
         "$ROOT/test/localrepo" -type f -exec touch {} +
}

# whole は cache.folder を空欄にしてあるので、キャッシュはツールのプロジェクトフォルダの .cache/demo_<ハッシュ>/ にできる
expect_sidecar_cache() {   # $1=case  $2=ラベル
    if ls "$ROOT"/.cache/demo_*/analysis-cache.tsv > /dev/null 2>&1 && [ ! -e "$1/.cache" ]; then
        echo "  OK   $1 キャッシュの場所 $ROOT/.cache/demo_* ($2)"
    else
        echo "  DIFF $1 キャッシュがツールのプロジェクトフォルダの .cache/demo_* にありません ($2)"; fail=1
    fi
}

# 複数の設定ファイルを 1 回の起動で処理するケース。whole と entry はここまでのケースで作ったキャッシュを再利用する。
# 3 つ目に存在しない設定ファイルを渡し、それが失敗しても前後の設定が処理されること、終了コードが 1 になることを見る
multi_case() {
    echo "== multi =="
    rm -rf whole/output entry/output run-multi.log output-dirs.txt
    # 出力フォルダの場所を機械的に受け取る経路（環境変数 JCHE_OUTPUT_DIR_FILE。GitHub Actions の
    # action.yml がこれで結果の場所を知る）も、ここで一緒に検査する。成功した設定の数だけ、
    # その出力フォルダの絶対パスが 1 行ずつ入る（失敗した設定の行は入らない）
    JCHE_OUTPUT_DIR_FILE="$PWD/output-dirs.txt" \
        $JCHE_CMD whole/config.properties no-such-config.properties entry/config.properties > run-multi.log 2>&1
    local code=$?
    if [ $code = 1 ]; then
        echo "  OK   multi 終了コード=1（存在しない設定ファイルが失敗）"
    else
        echo "  DIFF multi 終了コードが 1 ではありません: $code"; tail -5 run-multi.log; fail=1
    fi
    if LC_ALL=C grep -a -q -E '^.*\] *OK .*whole/config.properties' run-multi.log \
        && LC_ALL=C grep -a -q -E '^.*\] *FAIL .*no-such-config.properties' run-multi.log \
        && LC_ALL=C grep -a -q -E '^.*\] *OK .*entry/config.properties' run-multi.log; then
        echo "  OK   multi 実行結果の一覧（OK / FAIL / OK）"
    else
        echo "  DIFF multi 実行結果の一覧が期待どおりではありません"; LC_ALL=C grep -a -E '\] *(OK|FAIL) ' run-multi.log; fail=1
    fi
    local dirs
    dirs=$(grep -c . output-dirs.txt 2> /dev/null || echo 0)
    if [ "$dirs" = 2 ] && [ -f "$(head -1 output-dirs.txt)/call-hierarchy.csv" ]; then
        echo "  OK   multi JCHE_OUTPUT_DIR_FILE（成功した 2 件の出力フォルダ）"
    else
        echo "  DIFF multi JCHE_OUTPUT_DIR_FILE の内容が期待どおりではありません（$dirs 行）"
        cat output-dirs.txt 2> /dev/null
        fail=1
    fi
    compare whole expected "multi: whole"
    expect_run_files whole config.properties "multi: whole"
    compare entry expected "multi: entry"
    expect_run_files entry config.properties "multi: entry"
}

# 拡張のケース。同じソースを 3 通りの設定で解析し、拡張の効き目とキャッシュの扱いを見る
plugin_case() {
    echo "== plugin =="
    rm -rf plugin/.cache plugin/output plugin/run-*.log
    run plugin config-before.properties 1 "1回目: 拡張なし" || return
    compare plugin expected-before "1回目: 拡張なし（CHA で実装2件に広がる）"

    run plugin config.properties 2 "2回目: 同梱の拡張" || return
    expect_log_contains plugin 2 "FactoryKeyCollector" "2回目: フェーズAの拡張を読み込んだ"
    expect_log_contains plugin 2 "TypeMappingProvider" "2回目: フェーズBの拡張を読み込んだ"
    # フェーズAの拡張が増えたので、拡張なしで作ったキャッシュは捨てられて全件解析し直しになる
    expect_not_reused plugin 2 "2回目: フェーズAの拡張が変わったのでキャッシュを捨てた"
    compare plugin expected "2回目: 同梱の拡張（具象クラス1件に絞れる）"

    run plugin config.properties 3 "3回目: 同じ拡張" || return
    expect_reused plugin 3 "3回目: 拡張が同じならキャッシュを再利用"
    compare plugin expected "3回目: 同じ拡張"

    run plugin config-custom.properties 4 "4回目: 自前の拡張" || return
    expect_log_contains plugin 4 "DiXmlProvider" "4回目: plugins/*.java をコンパイルして読み込んだ"
    compare plugin expected-custom "4回目: 自前の拡張（DI 設定ファイルから絞れる）"
}

for c in $CASES; do
    if [ "$c" = multi ]; then
        multi_case; continue
    fi
    if [ "$c" = plugin ]; then
        plugin_case; continue
    fi
    echo "== $c =="
    rm -rf "$c/.cache" "$c/output" "$c"/run-*.log
    if [ "$c" = whole ]; then
        rm -rf "$ROOT"/.cache/demo_*
    fi
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
        # ビルドファイルのケースは、直接の依存 greeter と、その POM から辿った推移的な依存 core の jar が
        # 依存 jar の一覧（ログの ASCII 部分）に出ることを確かめる。jar が JDT に渡ったこと自体は
        # 期待出力（jar の型への呼び出し）との比較が保証する
        case "$c" in
            maven|mavenmulti|gradle)
                expect_log_contains "$c" 1 "greeter-1.0.jar" "1回目: 直接の依存の jar を集めた"
                expect_log_contains "$c" 1 "core-1.0.jar" "1回目: 推移的な依存の jar を集めた" ;;
        esac
        compare "$c" expected "1回目: キャッシュ無し"
        expect_run_files "$c" config.properties "1回目"
        if [ "$c" = whole ]; then
            expect_sidecar_cache "$c" "1回目"
        fi
        run "$c" config.properties 2 "2回目" || continue
        expect_reused "$c" 2 "2回目: キャッシュを再利用"
        compare "$c" expected "2回目: キャッシュ再利用"
        # 更新時刻だけが変わったソース・jar は、サイズと内容ハッシュが同じなら再利用される
        # （依存 jar の「変更」としても検知されない）
        touch_sources_and_jars
        run "$c" config.properties 3 "3回目: 更新時刻だけ変更" || continue
        expect_reused "$c" 3 "3回目: 更新時刻だけ変わったソースはキャッシュを再利用"
        expect_nothing_parsed "$c" 3 "3回目: 更新時刻だけ変わった jar も変更とみなさず、新規解析は 0"
        compare "$c" expected "3回目: 更新時刻だけ変更"
        # 2 回目は別の出力フォルダにできる（1 回目のフォルダはそのまま残る）
        if [ "$(ls -d "$c"/output/*/ 2>/dev/null | wc -l)" -ge 2 ]; then
            echo "  OK   $c 実行ごとに出力フォルダが分かれる"
        else
            echo "  DIFF $c 出力フォルダが実行ごとに分かれていません: $(ls "$c"/output)"; fail=1
        fi
    fi
done

if [ $fail = 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
