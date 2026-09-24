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
#   2) キャッシュファイル（analysis-cache.tsv）の中身が一致すること
#      （ブロックの並びは差分更新で変わるので、ブロックをパスの順に並べ替えて比較。ブロックの中の行の
#      並びは保つ。記号・ノードの番号はブロックの中だけで通じるので、行を全部並べ替えると比較にならない）。
#      CSV に出ない事実（注釈の値など）の取りこぼしはここで捕まる
#   3) 書き換えで事実が実際に変わっていること（何も変わらない編集だと 1) 2) が素通りしてしまう）
#   5) 定数の値が変わっていない書き換えでは、連鎖して余計に解析し直さないこと
#   6) 事実の作り方が変わったとき（文字コード）と、キャッシュが壊れているときは、
#      中途半端に再利用せず全件解析し直すこと。1 ブロックだけが壊れていれば、そのファイルだけを解析し直すこと
#   7) 中断した前回の実行が残した一時ファイルから、解析済みのぶんを引き継ぐこと
#      （検査値の合わないブロックは引き継がず、そのファイルだけを解析し直すこと）
#   8) 以前の形式が残した dataflow-cache.tsv を消すこと
#   4) キャッシュの行が壊れていないこと（行頭が既知の種別で、F 行の次は必ず I 行。ブロックの中の行が
#      決まった順に並ぶこと。記号（S 行）とノード（N 行）の番号がブロックの中で 0 から詰まっていて、
#      参照がブロックに収まること）
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
}

# ブロックの中の番号の不変条件。記号（S 行）とノード（N 行）の番号がブロックごとに 0 から詰まっていて、
# 記号の参照（D・O・R・C 行は 0 以上、U・M・A 行の呼び出し元は -1 も可）とノードの参照（N 行のレシーバ・
# 実引数は自分より前のノード、C・U 行のレシーバ・実引数はブロックのノード）がブロックの範囲に収まること。
# 番号がブロック内ローカルなので、ここが崩れると差分更新でブロックを書き写した瞬間に参照がずれる
check_refs() {   # $1=キャッシュの複製  $2=ラベル
    local bad
    bad=$(awk -F'\t' '
        function nodeok(v, n) { return v ~ /^[0-9]+$/ && v + 0 < n }
        function symok(v, none) { return (none && v == "-1") || (v ~ /^[0-9]+$/ && v + 0 < syms) }
        function argsok(a, n,    k, i, as, kv) {
            if (a == "") return 1
            k = split(a, as, ",")
            for (i = 1; i <= k; i++) {
                if (split(as[i], kv, "=") != 2 || !nodeok(kv[2], n)) return 0
            }
            return 1
        }
        NR == 1 { next }
        { kind = substr($0, 1, 1) }
        kind == "F" { syms = 0; nodes = 0; next }
        kind == "S" {
            if ($2 != syms) { print NR": S 行の番号が連番ではありません（期待 "syms"）: "$0 }
            syms++
            next
        }
        kind == "N" {
            if ($2 != nodes) { print NR": N 行の番号が連番ではありません（期待 "nodes"）: "$0 }
            if ($5 != -1 && !nodeok($5, nodes)) { print NR": N 行の recv がブロックの範囲外です: "$0 }
            if (!argsok($6, nodes)) { print NR": N 行の実引数がブロックの範囲外です: "$0 }
            nodes++
            next
        }
        kind == "D" || kind == "O" || kind == "R" {
            if (!symok($2, 0)) { print NR": "kind" 行の記号が範囲外です: "$0 }
        }
        kind == "C" {
            if (!symok($2, 0) || !symok($3, 0)) { print NR": C 行の記号が範囲外です: "$0 }
        }
        kind == "U" || kind == "M" || kind == "A" {
            if (!symok($3, 1)) { print NR": "kind" 行の呼び出し元の記号が範囲外です: "$0 }
        }
        kind == "C" || kind == "U" {
            if ($9 != -1 && !nodeok($9, nodes)) { print NR": "kind" 行の recv がブロックの範囲外です: "$0 }
            if (!argsok($10, nodes)) { print NR": "kind" 行の実引数がブロックの範囲外です: "$0 }
        }' "$1")
    if [ -z "$bad" ]; then
        echo "  OK   $2 記号と値グラフの番号・参照が壊れていない"
    else
        echo "  NG   $2 記号か値グラフの番号・参照が壊れています"; echo "$bad" | head -5; fail=1
    fi
}

# 値グラフが文字列を上限なしで持っていること（長い SQL がそのまま、タブ・改行は符号化されて入っている）
check_unbounded_values() {   # $1=キャッシュの複製  $2=ラベル
    local long
    # 64 文字を超える値を持つ N 行（Awkward.longLiteral の SQL）。符号化されているのでタブ・改行は \t \n
    long=$(awk -F'\t' 'substr($0,1,1)=="N" && $3=="L" && length($4) > 64' "$1" | wc -l)
    if [ "$long" -ge 1 ] && grep -q 'ORDER BY id DESC' "$1"; then
        echo "  OK   $2 64 文字超の文字列を値グラフが保持（$long 件）"
    else
        echo "  NG   $2 64 文字超の文字列が値グラフにありません（$long 件）"; fail=1
    fi
    # 符号化されたタブ・改行が入っていること（生のタブ・改行なら行が割れている）
    if grep -q 'SELECT id, name, kind, created_at\\n\\tFROM orders' "$1"; then
        echo "  OK   $2 タブ・改行が符号化されて 1 行に収まっている"
    else
        echo "  NG   $2 タブ・改行の符号化が期待と違います"
        grep -o 'SELECT id[^\t]*' "$1" | head -2; fail=1
    fi
}

# 差分更新でブロックの並びが変わるので、ブロックを F 行のパスの順に並べ替えてから比べる。
# ブロックの中の行の並びは保つ（記号・ノードの番号はブロックの中だけで通じるので、
# 行を全部並べ替えると、同じ行が別のブロックでは別のものを指していても一致してしまう）。
# ヘッダ・L 行・T 行はブロックより前、最終行は最後に置く
blocks_sorted() {   # $1=キャッシュ  $2=外す行種別（grep -E の文字クラス。空なら外さない）
    awk -F'\t' -v OFS='\t' -v drop="$2" '
        NR == 1 { printf "0\t\t%08d\t%s\n", NR, $0; next }
        substr($0, 1, 1) == "F" { path = $2; n = 0 }
        substr($0, 1, 1) == "Z" { printf "2\t\t%08d\t%s\n", 0, $0; next }
        drop != "" && substr($0, 1, 1) ~ ("^[" drop "]$") { next }
        path == "" { printf "0\t\t%08d\t%s\n", NR, $0; next }
        { printf "1\t%s\t%08d\t%s\n", path, n++, $0 }
    ' "$1" | LC_ALL=C sort -t "$(printf '\t')" -k1,1 -k2,2 -k3,3 | cut -f4-
}
normalized() { blocks_sorted "$1" ""; }

# F 行（サイズ・エラー数・内容ハッシュ・検査値）と T 行（ソース一覧の指紋）を除いた「事実」だけ。
# どちらもファイルを書き換えれば中身に関わらず必ず変わるので、
# 「事実が変わったか」を見るときはこちらで比べる（ブロックの区切りと並べ替えには F 行のパスを使う）
normalized_facts() { blocks_sorted "$1" "FT"; }

# 行頭が既知の種別で、F 行の直後が必ず I 行であること。
# 値に紛れ込んだタブ・改行で行が割れると、ここで引っかかる。
# ブロックの中の行の並び（CacheFormat の「行の種別と列」の順。読み手がこの並びに依存している）:
#   F, I, S*, N*, R*, H*, D*, O*, V*, C と U（ソース上の順で混ざる）, M*, A*, K*, J*, X*
# ある種別の行が、それより後ろに並ぶべき種別の行より後に出てこないこと。L・T 行はブロックより前、Z 行は最後
check_rows() {   # $1=キャッシュ  $2=ラベル
    local bad
    bad=$(awk 'BEGIN { split("F I S N R H D O V C M A K J X", order, " ")
                       for (i in order) rank[order[i]] = i + 0   # 添字は文字列なので数に直す
                       rank["U"] = rank["C"] }
               NR == 1 { next }
               { kind = substr($0, 1, 1) }
               { if (index("TLFISNRHDOVCUMAKJXZ", kind) == 0) { print NR": 未知の行種別: "$0; next } }
               prev == "F" && kind != "I" { print NR": F 行の次が I 行ではありません: "$0 }
               ended { print NR": Z 行（最終行）の後に行があります: "$0 }
               kind == "Z" { ended = 1 }
               kind == "L" || kind == "T" { if (inBlock) print NR": "kind" 行がブロックの中にあります: "$0 }
               kind == "F" { inBlock = 1; last = rank["F"] }
               inBlock && kind in rank && kind != "F" {
                   if (rank[kind] < last) print NR": "kind" 行がブロックの中で並ぶべき位置より後にあります: "$0
                   else last = rank[kind]
               }
               { prev = kind }' "$1")
    if [ -z "$bad" ]; then
        echo "  OK   $2 キャッシュの行が壊れていない"
    else
        echo "  NG   $2 キャッシュの行が壊れています"; echo "$bad" | head -5; fail=1
    fi
}

# 1 ケース: 書き換え -> 差分更新 -> キャッシュを消して全件解析 -> 一致を見る
case_of() {   # $1=ラベル  $2=書き換えるコマンド  $3=事実（F行以外）が変わるべきか(yes/no)
    echo "== $1 =="
    rm -rf work .cache out out.log base.tsv inc.tsv full.tsv
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
    check_refs inc.tsv "$1 差分更新"
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
    rm -rf work .cache out out.log base.tsv inc.tsv full.tsv case.properties
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
discard_case "形式の版が古いキャッシュ" \
    "sed -i '1s/^jche-cache-v[0-9]*/jche-cache-v1/' \$(ls .cache/*/analysis-cache.tsv)" \
    "[cache]" yes

# 解析に使った JDT の版が違うキャッシュ。バインディングの解決・JLS の解釈・条件式のテキストは JDT の版で
# 変わりうるので、鍵（ヘッダ行の jdt=）が違えば捨てる。以前は鍵に入っておらず、JDT を上げても再利用していた
discard_case "JDT の版が違うキャッシュ" \
    "sed -i -E '1s/\tjdt=[^\t]*/\tjdt=0.0.0/' \$(ls .cache/*/analysis-cache.tsv)" \
    "[cache]" yes

# --- 1 ブロックだけが壊れたキャッシュ ------------------------------------
# 各ブロックは F 行に検査値（crc）を持つ。行の形を保ったまま中身だけが変わった（書き換え・化け）ブロックは、
# 検査値が合わないのでそのファイルだけを解析し直す（ほかのブロックは再利用する）。検査値が無ければ
# 壊れた中身がそのまま出力に出る
TOTAL_FILES=$(ls src/inc/*.java | wc -l)

# C 行 1 行の、呼び出し箇所の行番号の最後の 1 文字だけを別の数字にする（行の形は保つ）。壊したブロックの
# パスを出力する。$2=first なら最初に C 行を持つブロック、middle なら最初でも最後でもないブロックのうち
# C 行を持つものの真ん中（中断した実行の一時ファイルは最後のブロックが書きかけなので、それ以外を壊す）
damage_call_row() {   # $1=キャッシュ（か一時ファイル）  $2=first|middle
    python3 - "$1" "$2" <<'PY'
import sys
p, where = sys.argv[1], sys.argv[2]
lines = open(p, encoding='utf-8').read().split('\n')
blocks = []                     # [パス, 最初の C 行の位置]
for i, line in enumerate(lines):
    if line.startswith('F\t'):
        blocks.append([line.split('\t')[1], None])
    elif blocks and blocks[-1][1] is None and line.startswith('C\t'):
        blocks[-1][1] = i
if where == 'middle':
    candidates = [b for b in blocks[1:-1] if b[1] is not None]
    target = candidates[len(candidates) // 2] if candidates else None
else:
    target = next((b for b in blocks if b[1] is not None), None)
if target is None:
    sys.exit(0)
i = target[1]
cols = lines[i].split('\t')
cols[3] = cols[3][:-1] + str((int(cols[3][-1]) + 1) % 10)
lines[i] = '\t'.join(cols)
open(p, 'w', encoding='utf-8').write('\n'.join(lines))
print(target[0])
PY
}

damaged_block_case() {
    echo "== ブロックの中身が壊れていたら、そのファイルだけ解析し直す =="
    rm -rf work .cache out out.log base.tsv inc.tsv full.tsv
    mkdir -p work && cp -r src work/src
    run base.tsv || return
    local damaged
    damaged=$(damage_call_row "$(ls $CACHE)" first)
    if [ -z "$damaged" ]; then
        echo "  NG   C 行が見つからず、壊せませんでした"; fail=1; return
    fi
    run inc.tsv || return
    local inc_csv=$OUT
    check_rows inc.tsv "壊れたブロック 差分更新"
    check_refs inc.tsv "壊れたブロック 差分更新"
    if [ "$PARSED" -ge 1 ] && [ "$PARSED" -lt "$TOTAL_FILES" ] && [ "$REUSED" -ge 1 ]; then
        echo "  OK   壊れたブロック（$damaged）を含む $PARSED / $TOTAL_FILES 件だけを解析し直した"
    else
        echo "  NG   壊れたブロックの扱いが期待と違います（新規解析=$PARSED 再利用=$REUSED / $TOTAL_FILES 件）"; fail=1
    fi
    if grep -q -F -- "failed the integrity check" out.log; then
        echo "  OK   壊れたブロック 検査値が合わないことをログに出す"
    else
        echo "  NG   壊れたブロック 検査値が合わないことがログに出ていません"; fail=1
    fi
    # 壊れたブロックは解析し直せば済み、利用者が対処することは無い（Log.info）。案内は作らない
    if [ -f "$inc_csv/warnings.txt" ]; then
        echo "  NG   壊れたブロック 解析し直しただけで warnings.txt ができています"; head -5 "$inc_csv/warnings.txt"; fail=1
    else
        echo "  OK   壊れたブロック warnings.txt は作らない（利用者が対処することではない）"
    fi
    rm -rf .cache
    run full.tsv || return
    for f in call-hierarchy.csv methods.csv; do
        if diff --strip-trailing-cr -q "$inc_csv/$f" "$OUT/$f" > /dev/null; then
            echo "  OK   壊れたブロック $f（解析し直し == 全件解析）"
        else
            echo "  NG   壊れたブロック $f が全件解析と違います"
            diff --strip-trailing-cr "$inc_csv/$f" "$OUT/$f" | head -10; fail=1
        fi
    done
    if diff -q <(normalized inc.tsv) <(normalized full.tsv) > /dev/null; then
        echo "  OK   壊れたブロック キャッシュ（解析し直し == 全件解析）"
    else
        echo "  NG   壊れたブロック キャッシュが全件解析と違います"
        diff <(normalized inc.tsv) <(normalized full.tsv) | head -10; fail=1
    fi
}
damaged_block_case

# --- 以前の形式が残したファイル ------------------------------------------
# キャッシュが 2 ファイルだった版の dataflow-cache.tsv（と一時ファイル）は、もう読まないので消す。
# 利用者が対処することではないので、確認してほしいことの案内（warnings.txt）は作らない。
# 残っていてもキャッシュの再利用には影響しない
legacy_files_case() {
    echo "== 以前の形式が残した dataflow-cache.tsv を消す =="
    rm -rf work .cache out out.log base.tsv inc.tsv
    mkdir -p work && cp -r src work/src
    run base.tsv || return
    local dir f
    dir=$(dirname "$(ls $CACHE)")
    for f in dataflow-cache.tsv dataflow-cache.tsv.tmp dataflow-cache.tsv.partial; do
        printf 'jche-dataflow-v7\tsource=17\n' > "$dir/$f"
    done
    run inc.tsv || return
    if ls "$dir"/dataflow-cache.tsv* > /dev/null 2>&1; then
        echo "  NG   以前の形式のファイルが残っています: $(ls "$dir")"; fail=1
    else
        echo "  OK   以前の形式のファイル（dataflow-cache.tsv と一時ファイル）を消した"
    fi
    if grep -q -F -- "[cache] Deleted dataflow-cache.tsv" out.log; then
        echo "  OK   消したことをログに出す"
    else
        echo "  NG   消したことがログに出ていません"; fail=1
    fi
    if [ -f "$OUT/warnings.txt" ]; then
        echo "  NG   消しただけで warnings.txt ができています"; head -5 "$OUT/warnings.txt"; fail=1
    else
        echo "  OK   warnings.txt は作らない（利用者が対処することではない）"
    fi
    if [ "$PARSED" = 0 ] && [ "$REUSED" = "$TOTAL_FILES" ]; then
        echo "  OK   キャッシュはそのまま再利用した（$REUSED 件）"
    else
        echo "  NG   以前の形式のファイルがあるだけで解析し直しています（新規解析=$PARSED 再利用=$REUSED）"; fail=1
    fi
}
legacy_files_case

# --- 中断した実行からの引き継ぎ -----------------------------------------
# フェーズ1の途中で実行が終わると一時ファイル（.tmp）だけが残る。次の実行は、これから解析する
# ファイルのぶんをパースし直さずに書き写す。正しさの理屈は変えないので、結果は引き継ぎ無しと一致する
# 中断した実行が残す一時ファイルを作る。完成したキャッシュを .tmp へ移し、末尾を削って
# 「最後のブロックが書き終わっていない」状態にする（実際の中断と同じ形）
make_partial() {
    local cache
    cache=$(ls .cache/*/analysis-cache.tsv)
    head -n -3 "$cache" > "$cache.tmp"
    rm -f "$cache"
}

salvage_case() {   # $1=ラベル  $2=引き継ぐ前に行う書き換え（空なら何もしない）  $3=引き継げるはずか(yes/no)
                   # $4=引き継ぎありの実行のログに出るはずの文字列（省略可）
    echo "== $1 =="
    rm -rf work .cache out out.log base.tsv inc.tsv full.tsv case.properties
    mkdir -p work && cp -r src work/src
    run base.tsv || return          # まず完成したキャッシュを作る
    make_partial
    [ -n "$2" ] && eval "$2"

    run inc.tsv || return           # 引き継ぎありの実行
    local inc_csv=$OUT
    SALVAGE_PARSED=$PARSED
    check_rows inc.tsv "$1 引き継ぎ"
    check_refs inc.tsv "$1 引き継ぎ"
    # ログは次の実行（引き継ぎ無し）で上書きされるので、ここで見る
    if [ -n "${4:-}" ]; then
        if grep -q -F -- "$4" out.log; then
            echo "  OK   $1 ログに「$4」が出る"
        else
            echo "  NG   $1 ログに「$4」が出ていません"; fail=1
        fi
    fi

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
}

salvage_case "中断した実行からの引き継ぎ" "" yes
plain_salvage_parsed=$SALVAGE_PARSED

# 一時ファイルの途中のブロックが壊れていたら（行の形を保ったまま中身が変わった）、そのブロックだけを
# 引き継がずに解析し直す。旧キャッシュのパス1 と同じく検査値で見る。検査値を見ていないと、
# 壊れた行がそのまま新しいキャッシュと出力に書き写される（下の「引き継ぎ無し」との比較で捕まる）
salvage_case "一時ファイルの途中のブロックが壊れていたら、そのファイルだけ解析し直す" \
    "DAMAGED=\$(damage_call_row \$(ls .cache/*/analysis-cache.tsv.tmp) middle)" yes "failed the integrity check"
if [ -z "${DAMAGED:-}" ]; then
    echo "  NG   一時ファイルの途中に C 行を持つブロックが無く、壊せませんでした"; fail=1
elif [ -n "$plain_salvage_parsed" ] && [ "$SALVAGE_PARSED" = $((plain_salvage_parsed + 1)) ]; then
    echo "  OK   壊れたブロック（$DAMAGED）のファイルだけが増えて $SALVAGE_PARSED 件を解析し直した"
else
    echo "  NG   解析し直した件数が期待と違います（$SALVAGE_PARSED 件。壊さなければ $plain_salvage_parsed 件）"; fail=1
fi

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

# 中断のあとで形式の版が上がった（ツールを更新した）。一時ファイルのヘッダを見ていないと、
# 旧形式の行を新しいキャッシュへそのまま書き写してしまう
salvage_case "一時ファイルの版が古ければ引き継がない" \
    "sed -i '1s/^jche-cache-v[0-9]*/jche-cache-v1/' \$(ls .cache/*/analysis-cache.tsv.tmp)" no

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
