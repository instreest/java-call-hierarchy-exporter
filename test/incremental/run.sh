#!/usr/bin/env bash
# キャッシュの健全性の検査。「ソースを書き換えたあとの差分更新の結果」と「キャッシュを消してからの
# 全件解析の結果」が一致すること、および壊れた・古いキャッシュを再利用しないことを見る。
#
#   bash test/incremental/run.sh
#   JCHE_CP="build/classes:依存jar..." bash test/incremental/run.sh   # コンパイル済みの classpath を使う
#
# 期待出力（expected*/）は持たない。同じソースに対する 2 通りの解析が一致するかだけを見るので、
# 期待値の更新が要らず、「差分更新だけ結果が違う」という取りこぼしをそのまま検出できる。
#
# 見るもの:
#   1) call-hierarchy.csv / methods.csv が一致すること
#   2) キャッシュファイル 2 つ（analysis-cache.tsv / dataflow-cache.tsv）の中身が一致すること
#      （ブロックの並びは差分更新で変わるので、行を並べ替えて比較）。
#      CSV に出ない事実（注釈の値など）の取りこぼしはここで捕まる。
#      2 つのブロック（F 行）が常に対になっていることも見る
#   3) 書き換えで事実が実際に変わっていること（何も変わらない編集だと 1) 2) が素通りしてしまう）
#   5) 定数の値が変わっていない書き換えでは、連鎖して余計に解析し直さないこと
#   6) 事実の作り方が変わったとき（文字コード）と、キャッシュが壊れているときは、
#      中途半端に再利用せず全件解析し直すこと
#   7) 中断した前回の実行が残した一時ファイルから、解析済みのぶんを引き継ぐこと
#   4) キャッシュの行が壊れていないこと（行頭が既知の種別で、F 行の次は必ず I 行）
#
# 解析対象は src/ を work/ に複製したもので、書き換えるのは複製だけ（作業ツリーは汚さない）。
# ツール本体は javac でコンパイルし、jbang が用意した JDK 25 と JDT の jar で動かす
# （test/dataflow/run.sh・test/conditions/run.sh と同じ経路）。
set -uo pipefail
cd "$(dirname "$0")"
# 文言の言語を固定する（既定は英語。固定しないと実行環境のロケールでログの文言が変わる）
export JCHE_LANG=en
ROOT=$(cd ../.. && pwd)
fail=0

if [ -n "${JCHE_CP:-}" ]; then
    CP="$JCHE_CP"
    JAVA_BIN=java
    JAVAC_BIN=javac
    JAR_BIN=jar
else
    JBANG="bash $ROOT/jbangw/jbang"
    CP=$($JBANG info classpath "$ROOT/src/jche/CallHierarchyExporter.java" | tr ':' '\n' | grep -v '/cache/jars/' | paste -sd:)
    JAVA_HOME_25=$($JBANG jdk home 25)
    if [ -z "$CP" ] || [ -z "$JAVA_HOME_25" ]; then
        echo "  NG   jbang から JDT の classpath または JDK 25 を取得できませんでした"; echo "FAIL"; exit 1
    fi
    rm -rf build
    "$JAVA_HOME_25/bin/javac" --release 17 -Xlint:all -Werror -Xdoclint:all,-missing -encoding UTF-8 \
        -cp "$CP" -d build $(find "$ROOT/src" -name '*.java') \
        || { echo "  NG   コンパイルに失敗しました"; echo "FAIL"; exit 1; }
    CP="build:$CP"
    JAVA_BIN="$JAVA_HOME_25/bin/java"
    JAVAC_BIN="$JAVA_HOME_25/bin/javac"
    JAR_BIN="$JAVA_HOME_25/bin/jar"
fi

CACHE=.cache/*/analysis-cache.tsv
FLOW_CACHE=.cache/*/dataflow-cache.tsv

CONFIG=config.properties

run() {   # 解析を1回走らせ、出力フォルダを OUT に、解析し直した件数を PARSED に、キャッシュの複製を $1 に置く
    "$JAVA_BIN" -Dstdout.encoding=UTF-8 -cp "$CP" jche.CallHierarchyExporter "$CONFIG" \
        > out.log 2>&1
    if [ $? != 0 ]; then
        echo "  NG   解析に失敗しました"; tail -5 out.log; fail=1; return 1
    fi
    OUT=$(ls -d out/*/ 2>/dev/null | sort | tail -1 | sed 's#/$##')
    # 「ソース解析: 再利用=N 新規解析=M（…） 失敗=F」の N と M。日本語に依存しないよう、
    # 「=数字」が3つ以上あって数字で終わる最初の行から取る（test/regression/run.sh と同じ探し方）
    local summary
    summary=$(LC_ALL=C grep -a -E -m1 '=[0-9]+.*=[0-9]+.*=[0-9]+[[:space:]]*$' out.log)
    REUSED=$(LC_ALL=C sed -E 's/^[^=]*=([0-9]+).*$/\1/' <<< "$summary")
    PARSED=$(LC_ALL=C sed -E 's/^[^=]*=[0-9]+[^=]*=([0-9]+).*$/\1/' <<< "$summary")
    cp $CACHE "$1"
    cp $FLOW_CACHE "${1%.tsv}-flow.tsv"
}

# 2 つのキャッシュのブロック（F 行）が完全に一致すること。これが崩れると
# 「呼び出し階層は再利用、データフローは欠けている」ブロックが生まれる
check_paired() {   # $1=キャッシュの複製（analysis 側）  $2=ラベル
    local flow="${1%.tsv}-flow.tsv"
    if diff -q <(grep '^F	' "$1") <(grep '^F	' "$flow") > /dev/null; then
        echo "  OK   $2 2 つのキャッシュのブロックが対"
    else
        echo "  NG   $2 2 つのキャッシュの F 行が一致しません"
        diff <(grep '^F	' "$1") <(grep '^F	' "$flow") | head -5; fail=1
    fi
}

# dataflow 側の行が壊れていないこと（種別は F / A / J / K / N / P / R / X / Z だけ）と、
# 値グラフ（N 行）の不変条件。番号がブロックごとに 0 から詰まっていて、
# レシーバ・実引数の参照が同じブロックの範囲に収まっていること。
# 番号がブロック内ローカルなので、ここが崩れると差分更新でブロックを書き写した瞬間に参照がずれる
check_flow_rows() {   # $1=キャッシュの複製（analysis 側）  $2=ラベル
    local bad
    bad=$(awk -F'\t' '
        NR == 1 { next }
        { kind = substr($0, 1, 1) }
        index("FAJKNPRXZ", kind) == 0 { print NR": 未知の行種別: "$0; next }
        kind == "F" { nodes = 0; next }
        kind == "N" {
            if ($2 != nodes) { print NR": N 行の番号が連番ではありません（期待 "nodes"）: "$0 }
            if ($5 != -1 && ($5 < 0 || $5 >= nodes)) { print NR": recv がブロックの範囲外です: "$0 }
            if ($6 != "") {
                n = split($6, as, ",")
                for (i = 1; i <= n; i++) {
                    split(as[i], kv, "=")
                    if (kv[2] < 0 || kv[2] >= nodes) { print NR": 実引数がブロックの範囲外です: "$0 }
                }
            }
            nodes++
            next
        }
        kind == "P" {
            if ($9 != -1 && ($9 < 0 || $9 >= nodes)) { print NR": P 行の recv がブロックの範囲外です: "$0 }
            if ($10 != "") {
                n = split($10, as, ",")
                for (i = 1; i <= n; i++) {
                    split(as[i], kv, "=")
                    if (kv[2] < 0 || kv[2] >= nodes) { print NR": P 行の実引数がブロックの範囲外です: "$0 }
                }
            }
        }' "${1%.tsv}-flow.tsv")
    if [ -z "$bad" ]; then
        echo "  OK   $2 データフローのキャッシュの行と値グラフの参照が壊れていない"
    else
        echo "  NG   $2 データフローのキャッシュが壊れています"; echo "$bad" | head -5; fail=1
    fi
}

# analysis 側が上限で捨てている値を、dataflow 側が上限なしで持っていること。
# ここが空になると「分けたのに上限が外れていない」＝2b の意味が無い状態を見逃す
check_unbounded_values() {   # $1=キャッシュの複製（analysis 側）  $2=ラベル
    local flow="${1%.tsv}-flow.tsv" long
    # 64 文字を超える値を持つ N 行（Awkward.longLiteral の SQL）。符号化されているのでタブ・改行は \t \n
    long=$(awk -F'\t' 'substr($0,1,1)=="N" && $3=="L" && length($4) > 64' "$flow" | wc -l)
    if [ "$long" -ge 1 ] && grep -q 'ORDER BY id DESC' "$flow"; then
        echo "  OK   $2 64 文字超の文字列を値グラフが保持（$long 件）"
    else
        echo "  NG   $2 64 文字超の文字列が値グラフにありません（$long 件）"; fail=1
    fi
    # 符号化されたタブ・改行が入っていること（生のタブ・改行なら行が割れている）
    if grep -q 'SELECT id, name, kind, created_at\\n\\tFROM orders' "$flow"; then
        echo "  OK   $2 タブ・改行が符号化されて 1 行に収まっている"
    else
        echo "  NG   $2 タブ・改行の符号化が期待と違います"
        grep -o 'SELECT id[^\t]*' "$flow" | head -2; fail=1
    fi
}

# 差分更新でブロックの並びが変わるので、行を並べ替えてから比べる。
# ヘッダの世代の印（gen=）は「2 つのキャッシュが同じ実行で書かれたか」を表すだけで、
# 実行ごとに変わる（事実ではない）ので比較から外す
strip_generation() { LC_ALL=C sed -E 's/\tgen=[0-9a-f]+$//' "$1"; }
normalized() { strip_generation "$1" | LC_ALL=C sort; }

# F 行（サイズ・エラー数・内容ハッシュ）と T 行（ソース一覧の指紋）を除いた「事実」だけ。
# どちらもファイルを書き換えれば中身に関わらず必ず変わるので、
# 「事実が変わったか」を見るときはこちらで比べる。
# 事実は 2 つのキャッシュに分かれている（値の出所・フィールドへの代入は dataflow 側）ので、
# 片方だけを見ると「値だけが変わった書き換え」を取りこぼす
normalized_facts() {
    { strip_generation "$1"; strip_generation "${1%.tsv}-flow.tsv"; } \
        | LC_ALL=C grep -v -E "^[FT]	" | LC_ALL=C sort
}

# 行頭が既知の種別で、F 行の直後が必ず I 行であること。
# 値に紛れ込んだタブ・改行で行が割れると、ここで引っかかる
check_rows() {   # $1=キャッシュ  $2=ラベル
    local bad
    bad=$(awk 'NR == 1 { next }
               { if (index("TLFIHDVKACRMXUZ", substr($0, 1, 1)) == 0) { print NR": 未知の行種別: "$0; next } }
               prev == "F" && substr($0, 1, 1) != "I" { print NR": F 行の次が I 行ではありません: "$0 }
               { prev = substr($0, 1, 1) }' "$1")
    if [ -z "$bad" ]; then
        echo "  OK   $2 キャッシュの行が壊れていない"
    else
        echo "  NG   $2 キャッシュの行が壊れています"; echo "$bad" | head -5; fail=1
    fi
}

# 1 ケース: 書き換え -> 差分更新 -> キャッシュを消して全件解析 -> 一致を見る
case_of() {   # $1=ラベル  $2=書き換えるコマンド  $3=事実（F行以外）が変わるべきか(yes/no)
    echo "== $1 =="
    rm -rf work .cache out out.log base.tsv inc.tsv full.tsv base-flow.tsv inc-flow.tsv full-flow.tsv
    mkdir -p work && cp -r src work/src
    run base.tsv || return
    local base_csv=$OUT

    eval "$2"
    run inc.tsv || return
    local inc_csv=$OUT
    INC_PARSED=$PARSED
    check_rows inc.tsv "$1 差分更新"

    rm -rf .cache
    run full.tsv || return
    local full_csv=$OUT
    check_rows full.tsv "$1 全件解析"

    for f in call-hierarchy.csv methods.csv; do
        if diff --strip-trailing-cr -q "$inc_csv/$f" "$full_csv/$f" > /dev/null; then
            echo "  OK   $1 $f（差分更新 == 全件解析）"
        else
            echo "  NG   $1 $f が差分更新と全件解析で違います"
            diff --strip-trailing-cr "$inc_csv/$f" "$full_csv/$f" | head -10; fail=1
        fi
    done

    if diff -q <(normalized inc.tsv) <(normalized full.tsv) > /dev/null; then
        echo "  OK   $1 キャッシュ（差分更新 == 全件解析）"
    else
        echo "  NG   $1 キャッシュが差分更新と全件解析で違います"
        diff <(normalized inc.tsv) <(normalized full.tsv) | head -10; fail=1
    fi
    if diff -q <(normalized inc-flow.tsv) <(normalized full-flow.tsv) > /dev/null; then
        echo "  OK   $1 データフローのキャッシュ（差分更新 == 全件解析）"
    else
        echo "  NG   $1 データフローのキャッシュが差分更新と全件解析で違います"
        diff <(normalized inc-flow.tsv) <(normalized full-flow.tsv) | head -10; fail=1
    fi
    check_paired inc.tsv "$1 差分更新"
    check_flow_rows inc.tsv "$1 差分更新"
    check_unbounded_values inc.tsv "$1 差分更新"

    # 書き換えが効いていることの確認。何も変わらない編集だと上の比較が素通りしてしまう
    if diff -q <(normalized_facts base.tsv) <(normalized_facts full.tsv) > /dev/null; then
        if [ "$3" = no ]; then
            echo "  OK   $1 事実は変わらない"
        else
            echo "  NG   $1 書き換えが事実に効いていません（検査が素通りします）"; fail=1
        fi
    else
        if [ "$3" = yes ]; then
            echo "  OK   $1 書き換えが事実に効いている"
        else
            echo "  NG   $1 変わらないはずの書き換えで事実が変わりました"
            diff <(normalized_facts base.tsv) <(normalized_facts full.tsv) | head -5; fail=1
        fi
    fi
    # 差分更新の側が、書き換え前の事実のままになっていないこと（古い値を引きずっていない）
    if [ "$3" = yes ] && diff -q <(normalized_facts base.tsv) <(normalized_facts inc.tsv) > /dev/null; then
        echo "  NG   $1 差分更新が書き換え前のキャッシュのままです"; fail=1
    fi
}

# 2 段の定数。Base だけを書き換える。Client / Branch は Base を参照する型として持っていないが、
# Names / Switches 経由で値が焼き込まれているので、連鎖して解析し直されなければ古い結果が残る
case_of "2段の定数（具象クラスと条件分岐）" \
    "sed -i -e 's/inc.AlphaDao/inc.BetaDao/' -e 's/MODE = \"ALPHA\"/MODE = \"BETA\"/' work/src/inc/Base.java" yes
changed_parsed=$INC_PARSED

# 注釈のメンバの既定値。@Tag とだけ書いた側の H 行に焼き込まれる。CSV には出ないのでキャッシュで見る
case_of "注釈の既定値" \
    "sed -i 's/default \"alpha\"/default \"beta\"/' work/src/inc/Tag.java" yes

# 列挙定数の改名。値ではなく型として依存に載るので、もともと追随できているはずの経路
case_of "列挙定数の改名" \
    "sed -i 's/    FULL,/    HEAVY,/' work/src/inc/Mode.java && sed -i -e 's/Mode.FULL/Mode.HEAVY/' -e 's/case FULL ->/case HEAVY ->/' work/src/inc/Dispatch.java" yes

# 更新時刻もサイズも変えずに中身だけ変える（ALPHA -> OMEGA は同じ長さ）。
# 同一性を「更新時刻とサイズ」で見ていると、変更に気づかず古い結果を再利用してしまう。
# バージョン管理が更新時刻を復元する設定や、同じ長さの書き換えの再現
case_of "更新時刻もサイズも同じで中身だけ変更" \
    "cp -p work/src/inc/Base.java keep.tmp \
     && sed -i 's/MODE = \"ALPHA\"/MODE = \"OMEGA\"/' work/src/inc/Base.java \
     && touch -r keep.tmp work/src/inc/Base.java && rm -f keep.tmp" yes

# 定数の値は変えずコメントだけ足す。Names / Switches は解析し直されるが、値が変わっていないので
# その先（Client / Branch）へは連鎖しない。連鎖が空振りしても結果が変わらないことを固定する
case_of "定数を宣言するファイルのコメントだけ変更" \
    "printf '\n// comment only\n' >> work/src/inc/Base.java" no
unchanged_parsed=$INC_PARSED

# 値が変わったときだけ連鎖することの確認（値を見ずに連鎖させると、ここで件数が並ぶ）
if [ -n "$changed_parsed" ] && [ -n "$unchanged_parsed" ] \
        && [ "$unchanged_parsed" -lt "$changed_parsed" ]; then
    echo "  OK   定数の値が変わらない書き換えでは解析し直す件数が少ない（$unchanged_parsed < $changed_parsed）"
else
    echo "  NG   定数の値が変わらないのに、変わったときと同じだけ解析し直しています（$unchanged_parsed / $changed_parsed）"
    fail=1
fi

# --- キャッシュを捨てる条件 ---------------------------------------------
# 事実の作り方が変わった（文字コード）／キャッシュが壊れている場合は、中途半端に再利用すると
# 呼び出しが静かに欠ける。丸ごと捨てて全件解析し直すこと
discard_case() {   # $1=ラベル  $2=壊す・変えるコマンド  $3=ログに出るはずの文字列  $4=出力が基準と一致すべきか(yes/no)
    echo "== $1 =="
    rm -rf work .cache out out.log base.tsv inc.tsv full.tsv base-flow.tsv inc-flow.tsv full-flow.tsv case.properties
    mkdir -p work && cp -r src work/src
    cp config.properties case.properties
    CONFIG=case.properties
    run base.tsv || { CONFIG=config.properties; return; }
    local base_csv=$OUT

    eval "$2"
    run inc.tsv || { CONFIG=config.properties; return; }
    CONFIG=config.properties

    if grep -q -F -- "$3" out.log; then
        echo "  OK   $1 破棄したことをログに出す"
    else
        echo "  NG   $1 破棄したことがログに出ていません（「$3」）"; sed -n 1,10p out.log; fail=1
    fi
    if [ "$REUSED" = 0 ]; then
        echo "  OK   $1 再利用せず全件解析した"
    else
        echo "  NG   $1 壊れた・古いキャッシュを再利用しています（再利用=$REUSED）"; fail=1
    fi
    if [ "$4" = yes ]; then
        if diff --strip-trailing-cr -q "$base_csv/call-hierarchy.csv" "$OUT/call-hierarchy.csv" > /dev/null; then
            echo "  OK   $1 出力は壊れる前と同じ"
        else
            echo "  NG   $1 出力が壊れる前と違います"
            diff --strip-trailing-cr "$base_csv/call-hierarchy.csv" "$OUT/call-hierarchy.csv" | head -10; fail=1
        fi
    fi
}

# ソースの文字コードはキャッシュの鍵に入っている。source.encoding が空欄なら pom.xml から
# 決まるので、.java を1行も触らずに解釈が変わることがある
discard_case "文字コードの変更" \
    "printf 'source.encoding=MS932\n' >> case.properties" \
    "[cache]" no

# 途中で切れたキャッシュ。切れた場所より前のブロックは「サイズも内容ハッシュも一致する」ように
# 見えるので、印が無いことで気づけなければ、そのファイルの呼び出しが静かに欠ける
discard_case "途中で切れたキャッシュ" \
    "head -n -3 \$(ls .cache/*/analysis-cache.tsv) > cut.tmp && mv cut.tmp \$(ls .cache/*/analysis-cache.tsv)" \
    "[cache]" yes

# 文字が壊れたキャッシュ。解析ごと失敗させず、破棄して全件解析に倒すこと
discard_case "文字が壊れたキャッシュ" \
    "printf '\\xff\\xfe bad\\n' >> \$(ls .cache/*/analysis-cache.tsv)" \
    "[cache]" yes

# 形式の版が古いキャッシュ。列の並びが変わっているので、再利用すると「エラー」ではなく
# 「静かに違う結果」になる（実際に、C 行の列を変えたのに版を上げ忘れたことがある。
# そのときは recvKey を recvKind として読んでいた）。版だけを書き換えて、捨てることを見る
discard_case "形式の版が古い analysis キャッシュ" \
    "sed -i '1s/^jche-cache-v[0-9]*/jche-cache-v1/' \$(ls .cache/*/analysis-cache.tsv)" \
    "[cache]" yes

# dataflow 側の版も同じ。こちらだけ古い場合、対の判定で両方が捨てられる
discard_case "形式の版が古い dataflow キャッシュ" \
    "sed -i '1s/^jche-dataflow-v[0-9]*/jche-dataflow-v1/' \$(ls .cache/*/dataflow-cache.tsv)" \
    "[cache]" yes

# --- 中断した実行からの引き継ぎ -----------------------------------------
# フェーズ1の途中で実行が終わると一時ファイル（.tmp）だけが残る。次の実行は、これから解析する
# ファイルのぶんをパースし直さずに書き写す。正しさの理屈は変えないので、結果は引き継ぎ無しと一致する
TOTAL_FILES=$(ls src/inc/*.java | wc -l)

# 中断した実行が残す一時ファイルを作る。完成したキャッシュを .tmp へ移し、末尾を削って
# 「最後のブロックが書き終わっていない」状態にする（実際の中断と同じ形）。
# キャッシュは 2 つあり、本物の中断では両方の .tmp が残るので、ここでも両方作る。
# 引き継ぐのは「両方の一時ファイルにそろっているブロック」だけなので、片方だけでは引き継がない
make_partial() {
    local cache flow
    cache=$(ls .cache/*/analysis-cache.tsv)
    flow=$(ls .cache/*/dataflow-cache.tsv)
    head -n -3 "$cache" > "$cache.tmp"
    head -n -3 "$flow" > "$flow.tmp"
    rm -f "$cache" "$flow"
}

salvage_case() {   # $1=ラベル  $2=引き継ぐ前に行う書き換え（空なら何もしない）  $3=引き継げるはずか(yes/no)
    echo "== $1 =="
    rm -rf work .cache out out.log base.tsv inc.tsv full.tsv base-flow.tsv inc-flow.tsv full-flow.tsv case.properties
    mkdir -p work && cp -r src work/src
    run base.tsv || return          # まず完成したキャッシュを作る
    make_partial
    [ -n "$2" ] && eval "$2"

    run inc.tsv || return           # 引き継ぎありの実行
    local inc_csv=$OUT
    SALVAGE_PARSED=$PARSED
    check_rows inc.tsv "$1 引き継ぎ"

    if [ "$3" = yes ]; then
        if [ "$PARSED" -lt "$TOTAL_FILES" ]; then
            echo "  OK   $1 解析し直したのは $PARSED / $TOTAL_FILES 件（引き継ぎが効いている）"
        else
            echo "  NG   $1 引き継げていません（$PARSED / $TOTAL_FILES 件を解析し直した）"; fail=1
        fi
    else
        if [ "$PARSED" = "$TOTAL_FILES" ]; then
            echo "  OK   $1 引き継がず $PARSED / $TOTAL_FILES 件を解析し直した"
        else
            echo "  NG   $1 引き継いではいけない状態で引き継いでいます（$PARSED / $TOTAL_FILES 件）"; fail=1
        fi
    fi
    if ls .cache/*/*.partial > /dev/null 2>&1 || ls .cache/*/*.tmp > /dev/null 2>&1; then
        echo "  NG   $1 一時ファイルが残っています: $(ls .cache/*/)"; fail=1
    else
        echo "  OK   $1 一時ファイルが残らない"
    fi

    rm -rf .cache
    run full.tsv || return          # 同じソースを引き継ぎ無しで
    for f in call-hierarchy.csv methods.csv; do
        if diff --strip-trailing-cr -q "$inc_csv/$f" "$OUT/$f" > /dev/null; then
            echo "  OK   $1 $f（引き継ぎ == 引き継ぎ無し）"
        else
            echo "  NG   $1 $f が引き継ぎの有無で違います"
            diff --strip-trailing-cr "$inc_csv/$f" "$OUT/$f" | head -10; fail=1
        fi
    done
    if diff -q <(normalized inc.tsv) <(normalized full.tsv) > /dev/null; then
        echo "  OK   $1 キャッシュ（引き継ぎ == 引き継ぎ無し）"
    else
        echo "  NG   $1 キャッシュが引き継ぎの有無で違います"
        diff <(normalized inc.tsv) <(normalized full.tsv) | head -10; fail=1
    fi
    if diff -q <(normalized inc-flow.tsv) <(normalized full-flow.tsv) > /dev/null; then
        echo "  OK   $1 データフローのキャッシュ（引き継ぎ == 引き継ぎ無し）"
    else
        echo "  NG   $1 データフローのキャッシュが引き継ぎの有無で違います"
        diff <(normalized inc-flow.tsv) <(normalized full-flow.tsv) | head -10; fail=1
    fi
    check_paired inc.tsv "$1 引き継ぎ"
}

salvage_case "中断した実行からの引き継ぎ" "" yes

# 更新時刻だけが変わっても引き継ぐ（git のチェックアウトや CI のワークスペース作り直しの再現）。
# 同一性に更新時刻を入れていると、ここで引き継げなくなる
salvage_case "更新時刻だけ変わっても引き継ぐ" \
    "find work/src -type f -exec touch {} +" yes

# 中断してからソースが1つでも変われば引き継がない。
# 引き継ぐブロックは他のファイルの内容にも依存する（呼び出し先・親型・定数の値はバインディング解決の
# 結果）ので、そのファイル自身が変わっていなくてもブロックは古くなる。
# ここでは Base.java の定数を変える（2段先の Client.java に値が焼き込まれている）
salvage_case "中断後にソースが変わったら引き継がない" \
    "sed -i 's/inc.AlphaDao/inc.BetaDao/' work/src/inc/Base.java" no

# --- 依存 jar の並び順 -------------------------------------------------
# jar の集合が同じでも、クラスパス上の並びが変われば同名クラスの解決先が変わる（先勝ち）。
# 並び順を見ていないと、追加も変更も削除も 0 件になって全ファイルが再利用され、
# 古い解決結果がそのまま残る（#104）。
#
# jarorder/libsrc/a と b は同じ dup.Shared を別のシグネチャで持つ。解析対象は run(1) を呼ぶので、
# run(int) を持つ b が勝てば run(int)、a が勝てば run(long) に解決される。
jar_order_case() {
    echo "== 依存 jar の並び順 =="
    rm -rf jarwork .cache out out.log inc.tsv full.tsv jarorder.properties
    mkdir -p jarwork
    cp -r jarorder/src jarwork/src
    # 標準エラーはいったんファイルへ（JAVA_TOOL_OPTIONS の通知が混ざるため）。
    # 失敗したときだけ、その通知を除いて中身を出す
    for v in a b; do
        if ! "$JAVAC_BIN" -d "jarwork/classes-$v" $(find "jarorder/libsrc/$v" -name '*.java') 2> jarwork/tool.log; then
            echo "  NG   検査用 jar のコンパイルに失敗しました"
            grep -v JAVA_TOOL_OPTIONS jarwork/tool.log | head -5; fail=1; return
        fi
        mkdir -p "jarwork/lib$v"
        if ! ( cd "jarwork/classes-$v" && "$JAR_BIN" cf "../lib$v/dup-$v.jar" dup ) 2> jarwork/tool.log; then
            echo "  NG   検査用 jar を作れませんでした"
            grep -v JAVA_TOOL_OPTIONS jarwork/tool.log | head -5; fail=1; return
        fi
    done

    CONFIG=jarorder.properties
    order_config() {   # $1=library.folders の並び
        cat > jarorder.properties <<EOF
project.root=./jarwork
source.folders=src
library.folders=$1
library.build.tool=none
source.encoding=UTF-8
entry.packages=app.Main
exclude.packages=java.**,javax.**
cache.enabled=true
cache.folder=./.cache
dataflow.enabled=true
branch.pruning.enabled=true
output.encoding=UTF-8
output.folder=./out
EOF
    }

    order_config "liba,libb"
    run base.tsv || { CONFIG=config.properties; return; }

    order_config "libb,liba"            # 並びだけを入れ替える（jar の中身は同じ）
    run inc.tsv || { CONFIG=config.properties; return; }
    local inc_csv=$OUT
    check_rows inc.tsv "依存 jar の並び順 差分更新"
    if [ "$PARSED" -ge 1 ]; then
        echo "  OK   依存 jar の並び順 入れ替えを検知して解析し直した"
    else
        echo "  NG   依存 jar の並び順 入れ替えを検知していません（新規解析=$PARSED）"; fail=1
    fi

    rm -rf .cache
    run full.tsv || { CONFIG=config.properties; return; }
    for f in call-hierarchy.csv methods.csv; do
        if diff --strip-trailing-cr -q "$inc_csv/$f" "$OUT/$f" > /dev/null; then
            echo "  OK   依存 jar の並び順 $f（差分更新 == 全件解析）"
        else
            echo "  NG   依存 jar の並び順 $f が差分更新と全件解析で違います"
            diff --strip-trailing-cr "$inc_csv/$f" "$OUT/$f" | head -10; fail=1
        fi
    done
    # 入れ替えで解決先が実際に変わっていること（変わらなければ検査が素通りする）
    if diff -q <(normalized_facts base.tsv) <(normalized_facts full.tsv) > /dev/null; then
        echo "  NG   依存 jar の並び順 入れ替えで解決先が変わっていません（検査が素通りします）"; fail=1
    else
        echo "  OK   依存 jar の並び順 入れ替えで解決先が変わっている"
    fi

    # 並びを変えずにもう一度。並び順の判定が効きすぎて毎回解析し直していないこと
    run again.tsv || { CONFIG=config.properties; return; }
    CONFIG=config.properties
    if [ "$PARSED" = 0 ]; then
        echo "  OK   依存 jar の並び順 変えなければ解析し直さない"
    else
        echo "  NG   依存 jar の並び順 変えていないのに解析し直しています（新規解析=$PARSED）"; fail=1
    fi
    rm -f again.tsv
}

jar_order_case

if [ $fail = 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
