#!/usr/bin/env bash
# 回帰テスト。test/demo を解析し、出力 CSV を expected*/ と比較する。
#
#   bash test/regression/run.sh            # jbang 経由で実行（初回は JDK と JDT を取得）
#   JCHE_CMD="java -cp bin:lib/* jche.CallHierarchyExporter" bash test/regression/run.sh   # 既にコンパイル済みなら
#
# ケースは2種類ある。
#   通常（whole / entry）… 同じ設定で3回実行する。1回目はキャッシュ無し、2回目はキャッシュを再利用する経路、
#                          3回目は解析対象のソースと jar の更新時刻を全部変えてから（touch）実行し、中身が同じなら
#                          内容ハッシュでキャッシュが再利用されること（GitHub Actions のチェックアウト後と同じ状況）を確認する
#   novalues             … whole と同じ解析対象を dataflow.enabled=false（キャッシュの値の行を読まない）で。
#                          実行の形は通常ケースと同じ。値が無いので具象クラスの解決は CHA まで、条件分岐の
#                          打ち切りは起きない。キャッシュの形式を変えても、この指定の出力が変わらないことを見る
#   jarchange            … 依存 jar 無し（config-before）→ 有り（config-after）→ 無し の順に実行し、
#                          キャッシュを保ったまま jar の追加・削除が出力に反映されることを確認する
#   maven / mavenmulti / gradle
#                        … library.folders を空欄にして、test/maven-demo（pom.xml）、test/maven-multi
#                          （マルチモジュール）、test/gradle-demo（build.gradle）のビルドファイルから依存 jar を
#                          集める。jar は test/localrepo（library.repositories）から。ビルドツールは要らない。
#                          実行の形は通常ケースと同じ
#   plugin               … 拡張（インスタンス解析条件のプラグイン）と契約表（種類 C）。拡張なし
#                          （config-before）→ 同梱の拡張（config.properties。ファクトリのキーと対応表）→
#                          自前の拡張（config-custom。plugins/*.java を実行時にコンパイル）→
#                          種類 C の契約表（config-contracts。拡張を使わず表だけで絞る）→
#                          ファクトリ＋キーの契約表（config-contracts-factory。拡張と同じ結果になる）→
#                          算出規則の拡張（config-naming）→
#                          右辺を採用できない契約（config-contracts-miss）の順に実行し、
#                          拡張・契約表ありでのみ具象クラスに絞れること、拡張も契約表も
#                          キャッシュを捨てさせないことを確認する
#   cacheblocks          … キャッシュ（analysis-cache.tsv。1 ファイル）のブロックの整合。そのまま再利用できること、
#                          1 ブロックの中身を書き換える（検査値が合わなくなる）とそのファイルだけ解析し直して
#                          ほかは再利用すること、最終行（Z 行）のブロック数の書き換え・削除と、先頭 8 KB より
#                          後ろの文字化け（ヘッダの読み取りでは気づけない位置）では丸ごと作り直し、解析は
#                          失敗させないことを確認する。どの実行のあとも、キャッシュは 1 ファイルだけで、
#                          各ブロックの検査値とブロック数が合っていること（最終行の Z 行があること）。
#                          型解決できなかった呼び出しの件数（ログの警告）が、再利用・一部の解析し直しでも
#                          変わらないこと
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
# 文言の言語を固定する（既定は英語。固定しないと実行環境のロケールでログの文言が変わる）
export JCHE_LANG=en
ROOT=$(cd ../.. && pwd)
JCHE_CMD=${JCHE_CMD:-"bash $ROOT/jbangw/jbang run $ROOT/src/jche/CallHierarchyExporter.java"}
CASES=${CASES:-"whole entry novalues jarchange maven mavenmulti gradle plugin cacheblocks multi"}
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

# 出力の CSV に、その文字列を含む行があること（ASCII だけを見る）
expect_csv_contains() {   # $1=case  $2=ASCII の文字列  $3=ラベル
    local out
    out=$(latest_output "$1")
    if [ -n "$out" ] && LC_ALL=C grep -a -q -F -- "$2" "$out/call-hierarchy.csv"; then
        echo "  OK   $1/call-hierarchy.csv ($3)"
    else
        echo "  DIFF $1/call-hierarchy.csv に「$2」がありません ($3)"; fail=1
    fi
}

# 絞れなかった呼び出しから作るひな形（contracts-suggested.txt）に、その行があること
expect_suggested() {   # $1=case  $2=ASCII の文字列  $3=ラベル
    local out
    out=$(latest_output "$1")
    if [ -n "$out" ] && LC_ALL=C grep -a -q -F -- "$2" "$out/contracts-suggested.txt" 2> /dev/null; then
        echo "  OK   $1 ひな形 ($3)"
    else
        echo "  DIFF $1 ひな形に「$2」がありません ($3)"; fail=1
    fi
}

# 拡張（TypeMappingProvider）と種類 C の契約表が、由来ラベル以外は
# まったく同じ出力になること。指定の仕方を変えても結果は変わらない、がこの比較の眼目。
# 期待出力をもう 1 組持つ代わりに、resolved-by 列のラベルを同じ綴りに読み替えて expected と突き合わせる
expect_same_as_mapping() {   # $1=ラベル
    local out ok=1 f
    out=$(latest_output plugin)
    if [ -z "$out" ]; then
        echo "  DIFF plugin: 出力フォルダがありません ($1)"; fail=1; return
    fi
    for f in call-hierarchy.csv methods.csv; do
        if diff --strip-trailing-cr -q \
                <(sed 's/RESOLVED:MAPPING/RESOLVED:=/' "plugin/expected/$f") \
                <(sed 's/RESOLVED:CONTRACT/RESOLVED:=/' "$out/$f") > /dev/null; then
            echo "  OK   plugin/$f ($1)"
        else
            echo "  DIFF plugin/$f ($1)"
            diff --strip-trailing-cr \
                <(sed 's/RESOLVED:MAPPING/RESOLVED:=/' "plugin/expected/$f") \
                <(sed 's/RESOLVED:CONTRACT/RESOLVED:=/' "$out/$f") | head -10
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
expect_parsed() {   # $1=case  $2=何回目  $3=件数  $4=ラベル   … 集計行の 2 つ目の「=N」（新規解析）が $3
    if summary_line "$1/run-$2.log" | LC_ALL=C grep -q -E "^[^=]*=[0-9]+[^=]*=$3([^0-9]|\$)"; then
        echo "  OK   $1 ログ ($4)"
    else
        echo "  DIFF $1 ログ: 新規解析が $3 件ではありません ($4): $(summary_line "$1/run-$2.log")"; fail=1
    fi
}
expect_not_reused() {   # $1=case  $2=何回目  $3=ラベル   … 集計行の最初の「=N」（再利用）が 0
    if summary_line "$1/run-$2.log" | LC_ALL=C grep -q -E '^[^=]*=0([^0-9]|$)'; then
        echo "  OK   $1 ログ ($3)"
    else
        echo "  DIFF $1 ログ: キャッシュが捨てられていません ($3): $(summary_line "$1/run-$2.log")"; fail=1
    fi
}
expect_log_missing() {   # $1=case  $2=何回目  $3=ASCII の文字列  $4=ラベル
    if LC_ALL=C grep -a -q -F -- "$3" "$1/run-$2.log"; then
        echo "  DIFF $1 ログに出てはいけない行があります ($4): $3"; fail=1
    else
        echo "  OK   $1 ログ ($4)"
    fi
}

# 「N call(s) could not have their types resolved」（型解決できなかった呼び出しの件数）の N。無ければ空
unresolved_count() {   # $1=case  $2=何回目
    LC_ALL=C grep -a -o -E '[0-9]+ call\(s\) could not have their types resolved' "$1/run-$2.log" \
        | head -1 | cut -d' ' -f1
}
expect_unresolved_count() {   # $1=case  $2=何回目  $3=件数  $4=ラベル
    local n
    n=$(unresolved_count "$1" "$2")
    if [ "$n" = "$3" ]; then
        echo "  OK   $1 ログ ($4)"
    else
        echo "  DIFF $1 ログ: 型解決できなかった呼び出しが ${n:-（行なし）} 件です。期待は $3 件 ($4)"; fail=1
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

# キャッシュ（1 ファイル）のブロックの整合のケース。
# 壊れたブロックだけを解析し直すこと（検査値）と、切れた・読めないキャッシュを丸ごと作り直すことを、
# 壊し方を変えて確認する
cacheblocks_case() {
    echo "== cacheblocks =="
    rm -rf cacheblocks/.cache cacheblocks/output cacheblocks/run-*.log
    local dir=cacheblocks/.cache/demo_*
    # キャッシュが 1 ファイルだけで（以前の dataflow-cache.tsv が無い）、各ブロックの検査値（F 行の最後の列）と
    # 最終行のブロック数が合っていること
    expect_cache_intact() {   # $1=ラベル
        local cache bad
        cache=$(ls $dir/analysis-cache.tsv 2>/dev/null)
        if [ -z "$cache" ] || [ "$(ls $dir | wc -l)" != 1 ]; then
            echo "  DIFF cacheblocks キャッシュが analysis-cache.tsv の 1 ファイルではありません ($1): $(ls $dir 2>/dev/null)"
            fail=1; return
        fi
        bad=$(python3 - "$cache" <<'PY'
import sys, zlib
lines = open(sys.argv[1], 'rb').read().split(b'\n')
blocks, crc, want, errors, trailer = 0, 0, None, [], False
def close():
    if want is not None and '%08x' % crc != want:
        errors.append('crc: ' + want)
for line in lines[1:]:
    if not line:
        continue
    if line[:1] in (b'F', b'Z'):
        close()
        want = None
        if line[:1] == b'Z':
            trailer = True
            if int(line.split(b'\t')[1]) != blocks:
                errors.append('Z: ' + line.decode())
            continue
        blocks += 1
        crc, want = 0, line.split(b'\t')[7].decode()
    elif want is not None:
        crc = zlib.crc32(line + b'\n', crc)
close()
if not trailer:
    errors.append('Z 行（最終行）がありません')
print('\n'.join(errors))
PY
)
        if [ -z "$bad" ]; then
            echo "  OK   cacheblocks キャッシュは 1 ファイルで、検査値とブロック数が合う（$1）"
        else
            echo "  DIFF cacheblocks キャッシュの検査値かブロック数が合いません ($1)"; echo "$bad" | head -5; fail=1
        fi
    }

    run cacheblocks config.properties 1 "1回目: キャッシュ無し" || return
    compare cacheblocks expected "1回目: キャッシュ無し"
    expect_cache_intact "1回目"
    # 型解決できなかった呼び出しの件数（警告と warnings.txt に出る）。再利用・書き写したブロックの件数は
    # U 行を読まずに F 行の未解決数の列から取るので、その列を読み違えると件数だけが黙って変わる
    local unresolved
    unresolved=$(unresolved_count cacheblocks 1)
    if [ -n "$unresolved" ] && [ "$unresolved" -gt 0 ]; then
        echo "  OK   cacheblocks ログ (1回目: 型解決できなかった呼び出しが $unresolved 件)"
    else
        echo "  DIFF cacheblocks ログ: 型解決できなかった呼び出しの件数の行がありません (1回目)"; fail=1
    fi

    run cacheblocks config.properties 2 "2回目: そのまま" || return
    expect_reused cacheblocks 2 "2回目: 再利用"
    expect_parsed cacheblocks 2 0 "2回目: 解析し直したファイルは無い"
    expect_unresolved_count cacheblocks 2 "$unresolved" "2回目: 再利用しても型解決できなかった呼び出しの件数は同じ"
    compare cacheblocks expected "2回目: そのまま"
    expect_cache_intact "2回目"

    # 1 ブロック（Deep.java。ほかのファイルから参照されない型だけを宣言する）の C 行の 1 文字だけを変える。
    # 行の形は保つので、検査値が無ければ気づけない（古い行番号がそのまま出力に出る）
    python3 - "$(ls $dir/analysis-cache.tsv)" <<'PY'
import sys
p = sys.argv[1]
lines = open(p, encoding='utf-8').read().split('\n')
block = None
for i, line in enumerate(lines):
    if line.startswith('F\t'):
        block = line.split('\t')[1]
    elif block is not None and block.endswith('/Deep.java') and line.startswith('C\t'):
        cols = line.split('\t')
        cols[3] = str(int(cols[3]) + 1)   # 呼び出し箇所の行番号
        lines[i] = '\t'.join(cols)
        break
else:
    sys.exit('Deep.java の C 行が見つかりません')
open(p, 'w', encoding='utf-8').write('\n'.join(lines))
PY
    run cacheblocks config.properties 3 "3回目: 1 ブロックの中身が壊れた" || return
    expect_reused cacheblocks 3 "3回目: 壊れたブロック以外は再利用"
    expect_parsed cacheblocks 3 1 "3回目: 壊れたブロックのファイルだけを解析し直す"
    expect_log_contains cacheblocks 3 "failed the integrity check" "3回目: 検査値が合わないことをログに出す"
    expect_unresolved_count cacheblocks 3 "$unresolved" "3回目: 型解決できなかった呼び出しの件数は同じ"
    compare cacheblocks expected "3回目: 1 ブロックの中身が壊れた"
    expect_cache_intact "3回目: 解析し直したブロックで置き換わる"

    run cacheblocks config.properties 4 "4回目: 直ったあと" || return
    expect_parsed cacheblocks 4 0 "4回目: 全件再利用に戻る"
    compare cacheblocks expected "4回目: 直ったあと"
    expect_cache_intact "4回目"

    # 最終行（Z 行）のブロック数だけを書き換える。途中のブロックが抜けた状況と同じ
    python3 - "$(ls $dir/analysis-cache.tsv)" <<'PY'
import sys
p = sys.argv[1]
lines = open(p, encoding='utf-8').read().split('\n')
for i in range(len(lines) - 1, -1, -1):
    if lines[i].startswith('Z\t'):
        lines[i] = 'Z\t99999'
        break
open(p, 'w', encoding='utf-8').write('\n'.join(lines))
PY
    run cacheblocks config.properties 5 "5回目: 最終行のブロック数が合わない" || return
    expect_not_reused cacheblocks 5 "5回目: ブロック数が合わなければ作り直す"
    compare cacheblocks expected "5回目: 最終行のブロック数が合わない"
    expect_cache_intact "5回目"

    # 最終行そのものを消す（書き終える前に落ちた形）
    python3 - "$(ls $dir/analysis-cache.tsv)" <<'PY'
import sys
p = sys.argv[1]
lines = [l for l in open(p, encoding='utf-8') if not l.startswith('Z\t')]
open(p, 'w', encoding='utf-8').write(''.join(lines))
PY
    run cacheblocks config.properties 6 "6回目: 最終行が無い" || return
    expect_not_reused cacheblocks 6 "6回目: 最終行が無ければ作り直す"
    compare cacheblocks expected "6回目: 最終行が無い"
    expect_cache_intact "6回目"

    # 最終行の直前に不正なバイトを差し込む。先頭 8 KB より後ろなので、ヘッダの読み取り（パス0）では
    # 気づけず、丸ごと読むパス1 で初めて気づく。そこで例外を投げると、キャッシュのせいで解析ごと失敗する
    # （run が失敗を拾う）
    python3 - "$(ls $dir/analysis-cache.tsv)" <<'PY'
import sys
p = sys.argv[1]
b = bytearray(open(p, 'rb').read())
z = b.rindex(b'\nZ\t')
assert z > 8192, 'キャッシュが 8 KB より小さい'
b[z:z] = b'\xff\xfe bad'
open(p, 'wb').write(bytes(b))
PY
    run cacheblocks config.properties 7 "7回目: 途中の文字が壊れた" || return
    expect_not_reused cacheblocks 7 "7回目: 読めなければ解析を失敗させず作り直す"
    compare cacheblocks expected "7回目: 途中の文字が壊れた"
    expect_cache_intact "7回目"
}

# 拡張のケース。同じソースを 3 通りの設定で解析し、拡張の効き目とキャッシュの扱いを見る
plugin_case() {
    echo "== plugin =="
    rm -rf plugin/.cache plugin/output plugin/run-*.log
    run plugin config-before.properties 1 "1回目: 拡張なし" || return
    compare plugin expected-before "1回目: 拡張なし（CHA で実装2件に広がる）"
    # 絞れなかった呼び出しから、そのまま貼れる契約表のひな形が出ること。
    # 「候補N件」と言われても何を書けばよいか分からない、への導線
    expect_suggested plugin 'fxp.DaoFactory#get("USER_DAO") => ??' "1回目: ファクトリとキーのひな形"
    expect_suggested plugin "fxp.DaoFactory#get(fxp.DaoKind.ORDER) => ??" "1回目: 列挙定数のキーのひな形"
    expect_suggested plugin "fxp.DaoFactory#get(fxp.ReportDao.class) => ??" \
        "1回目: Class リテラルのキーのひな形"
    expect_suggested plugin "fxp.Service#run => ??" "1回目: 宣言型とメソッド名のひな形"
    # キーが呼び出し元から引数で渡ってくる形も、経路が分かればひな形に出る
    expect_suggested plugin 'fxp.DaoFactory#get("ORDER_DAO") => ??' \
        "1回目: 経路で決まるキーのひな形"
    # キーが決まらず型単位の広い行になったものは、そうと分かる注記を添える
    expect_suggested plugin "fixes every call of that method on the type to one implementation" \
        "1回目: 広い行だと分かる注記が付く"

    run plugin config.properties 2 "2回目: 同梱の拡張" || return
    expect_log_contains plugin 2 "TypeMappingProvider" "2回目: 拡張を読み込んだ"
    # 対応表が「効いたか」の知らせ。わざと引かれない行だけが挙がり、効いている行は挙がらない
    expect_log_contains plugin 2 "FACTORY_KEY@NO_SUCH_KEY" "2回目: 引かれなかった対応表の行を挙げる"
    expect_log_missing plugin 2 "FACTORY_KEY@REPORT_DAO" "2回目: 効いている対応表の行は挙げない"
    # Class リテラルのキー。対応表の左辺は FACTORY_CLASS@<型の FQN>。
    # 右辺の ReportDaoImpl は find() を親から継承しているので、出るのは AbstractDao.find
    expect_csv_contains plugin "App.classKey,AbstractDao.find" \
        "2回目: Class リテラルのキーで絞れる"
    # 拡張はキャッシュに何も書かないので、拡張なしで作ったキャッシュをそのまま再利用できる
    expect_reused plugin 2 "2回目: 拡張を足してもキャッシュは捨てない"
    compare plugin expected "2回目: 同梱の拡張（具象クラス1件に絞れる）"

    run plugin config.properties 3 "3回目: 同じ拡張" || return
    expect_reused plugin 3 "3回目: 拡張が同じならキャッシュを再利用"
    compare plugin expected "3回目: 同じ拡張"

    run plugin config-custom.properties 4 "4回目: 自前の拡張" || return
    expect_log_contains plugin 4 "DiXmlProvider" "4回目: plugins/*.java をコンパイルして読み込んだ"
    compare plugin expected-custom "4回目: 自前の拡張（DI 設定ファイルから絞れる）"

    # 種類 C の契約表。拡張をいっさい使わず、契約表の行だけで同じように絞れる
    run plugin config-contracts.properties 5 "5回目: 種類Cの契約表" || return
    # 契約表はキャッシュの指紋に入らないので、表を足しても作り直さない
    expect_reused plugin 5 "5回目: 契約表を足してもキャッシュを作り直さない"
    expect_log_contains plugin 5 "fxp.DaoFactory#get(ORDER_DAO)" \
        "5回目: キーを引用符で囲んでいない行は助言つきの警告で知らせる"
    expect_log_contains plugin 5 "fxp.NoSuchType => fxp.UserDaoImpl" \
        "5回目: 一度も当たらなかった契約を挙げる"
    # 列挙定数のキー（引用符なしの FQN）。C-2 より先に当たるので enumKey だけ OrderDaoImpl になる
    expect_csv_contains plugin "App.enumKey,OrderDaoImpl.find" \
        "5回目: 列挙定数のキーで絞れる"
    # ファクトリの実装が親クラスにあっても、ソースに書いた子クラスの名前で指定できること。
    # 同じ親を持つ別の子クラス経由（otherFactory）は巻き込まず、C-2 に落ちること
    expect_csv_contains plugin "App.inheritedFactory,OrderDaoImpl.find" \
        "5回目: 親で実装されたファクトリを子クラス名で指定できる"
    expect_csv_contains plugin "App.otherFactory,UserDaoImpl.find" \
        "5回目: その指定は別の子クラス経由には効かない"
    # 型単位の広い行（fxp.Dao#find）が先に絞るので、経路ごとのやり直しは起きない。
    # 既に 1 件に決まったものを経路ごとに覆さない、という規則の検査
    expect_csv_contains plugin "App.viaParam,App.byKey,UserDaoImpl.find" \
        "5回目: 先に絞れていれば経路でやり直さない"
    compare plugin expected-contracts "5回目: 種類Cの契約表（C-1 と C-2 で絞れる）"

    # ファクトリ＋キー（C-3）。データフローの値グラフに載っている実引数からキーを引く。
    # 2 回目（同梱の拡張）と由来ラベル以外は同じ出力になる
    run plugin config-contracts-factory.properties 6 "6回目: ファクトリ＋キーの契約表" || return
    expect_reused plugin 6 "6回目: 契約表はキャッシュを作り直さない"
    # この表は型名を単純名で書いてある。FQN で書いた場合と同じ結果になることを下の比較が見る
    expect_log_missing plugin 6 "Cannot use the contract type name" "6回目: 単純名が曖昧になっていない"
    # キーが呼び出し元から引数で渡ってくる形。経路が分かってから絞れる
    # （byKey を単独の起点として辿る経路では、キーが分からないので絞れないまま）
    expect_csv_contains plugin "App.viaParam,App.byKey,OrderDaoImpl.find" \
        "6回目: 経路で決まるキーでも絞れる"
    # Class リテラルのキー。契約表では FQN に .class を付けて書く
    expect_csv_contains plugin "App.classKey,AbstractDao.find" \
        "6回目: Class リテラルのキーで絞れる"
    expect_same_as_mapping "6回目: 拡張と同じ結果（由来ラベルだけが違う）"

    # 算出規則を書いた自前の拡張。採取の設定を書かなくても、ファクトリのキーが Hint として届く
    run plugin config-naming.properties 7 "7回目: 算出規則の拡張" || return
    expect_reused plugin 7 "7回目: 拡張を差し替えてもキャッシュを作り直さない"
    expect_csv_contains plugin "App.factoryCall,UserDaoImpl.find" "7回目: 変数に受けた呼び出しを算出規則で絞る"
    expect_csv_contains plugin "App.chainedCall,OrderDaoImpl.find" "7回目: 変数に受けない呼び出しも絞る"
    expect_csv_contains plugin "RESOLVED:NAMING" "7回目: 拡張のラベルが出る"

    # 右辺を採用できない契約は、候補を落として CHA に戻す（呼び出しを落とさない）
    run plugin config-contracts-miss.properties 8 "8回目: 右辺を採用できない契約" || return
    expect_log_contains plugin 8 "fxp.Dao#find => fxp.Service" \
        "8回目: 当たったが採用できなかった契約を挙げる"
    compare plugin expected-before "8回目: 採用できない契約は CHA に戻す（拡張なしと同じ出力）"
}

for c in $CASES; do
    if [ "$c" = multi ]; then
        multi_case; continue
    fi
    if [ "$c" = plugin ]; then
        plugin_case; continue
    fi
    if [ "$c" = cacheblocks ]; then
        cacheblocks_case; continue
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
            # 契約表が「効いたか」の知らせ。whole の 2 行はどちらも当たるので挙がってはならず、
            # entry の contracts.txt はわざと当たらない行だけなので、そのまま挙がる
            whole)
                expect_log_missing whole 1 "fx.entry.Dispatcher#submit(java.lang.Runnable) -> a0 : run()" \
                    "1回目: 効いている契約は当たらなかった行として挙げない" ;;
            entry)
                expect_log_contains entry 1 "fx.entry.NoSuchDispatcher#submit" \
                    "1回目: 当たらなかった契約を挙げる（呼び戻し）"
                expect_log_contains entry 1 "fx.entry.NoSuchEndpoint" \
                    "1回目: 当たらなかった契約を挙げる（入口）"
                expect_log_contains entry 1 "fx.entry.Dispatcher#submit(java.lang.Runnable) -> r : run()" \
                    "1回目: 呼び出し先には一致したが繋げなかった契約を挙げる" ;;
        esac
        compare "$c" expected "1回目: キャッシュ無し"
        expect_run_files "$c" config.properties "1回目"
        if [ "$c" = whole ]; then
            expect_sidecar_cache "$c" "1回目"
        fi
        run "$c" config.properties 2 "2回目" || continue
        expect_reused "$c" 2 "2回目: キャッシュを再利用"
        compare "$c" expected "2回目: キャッシュ再利用"
        # 更新時刻だけが変わったソース・jar は再利用される（同一性に更新時刻を入れていない）
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
