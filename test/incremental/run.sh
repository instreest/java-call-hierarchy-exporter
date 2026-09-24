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
#   9) 同じ行に並ぶ宣言（1 行に書いたメソッド、同じ行のラムダ）の前後が、ID の振られ方（キャッシュ上の
#      ブロックの並び）によらず、ファイルの中の宣言の順番になること
#  10) 何も変わっていなければキャッシュを書き直さないこと（ファイルそのもの＝inode と更新時刻が変わらない）。
#      書き手の書くとおりの形でない（CRLF に変換された）キャッシュは、再利用しつつ書き直して形を戻すこと
#  11) どの実行のあとも、キャッシュのフォルダに一時ファイル（*.tmp・*.partial。依存の索引・エッジの記録・
#      型解決に失敗した呼び出しの行を含む）が残らないこと。前の実行が残したもの（SIGKILL・停電）は、次の実行の
#      最初に消すこと。強制終了（SIGTERM）のときは JVM の終了フックが消すこと。どちらでも、中断からの引き継ぎに
#      使うキャッシュ本体の一時ファイル（analysis-cache.tsv.tmp）は消さずに引き継ぐこと
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
    # 実行の中で使う一時ファイル（キャッシュ本体の .tmp・引き継ぎの .partial・依存の索引 *.deps-*.tmp・
    # エッジの記録 *.edges-*.tmp・型解決に失敗した呼び出しの行 *.unresolved-*.tmp）は、終われば残らない
    local left
    left=$(find .cache -name '*.tmp' -o -name '*.partial' 2>/dev/null)
    if [ -n "$left" ]; then
        echo "  NG   実行のあとに一時ファイルが残っています: $left"; fail=1
    fi
}

# ブロックの中の番号の不変条件。記号（S 行）とノード（N 行）とガード（G 行）の番号がブロックごとに 0 から
# 詰まっていて、記号の参照（D・O・R・C 行は 0 以上、U・M・A 行の呼び出し元は -1 も可）とノードの参照
# （N 行のレシーバ・実引数は自分より前のノード、R 行と J 行の値・C 行と U 行のレシーバと実引数は
# ブロックのノード。値は -1 も可）とガードの参照（C・U 行の guard はブロックのガードか -1）が
# ブロックの範囲に収まること。番号がブロック内ローカルなので、ここが崩れると差分更新でブロックを
# 書き写した瞬間に参照がずれる。
# G 行はさらに、書き手の決まりどおりであること:
#   - 番号は今のガードの続き（同じ番号）か次のガード（1 つ大きい番号）だけ
#   - subject はブロックのノードで、種別が引数（A）か定数（V）（-1 は条件の調査のメモリ上だけで、書かない）
#   - op は EQ / NE / IN / NI だけで、値の数は EQ・NE が 1 つ、IN・NI が 1 つ以上（列は 5 + 値の数）
#   - ガード番号は C・U 行が初めて使う順に振られていて（まだ使っていない番号を飛ばして指さない）、
#     ブロックのガードはどれも使われている
check_refs() {   # $1=キャッシュの複製  $2=ラベル
    local bad
    bad=$(awk -F'\t' '
        function nodeok(v, n) { return v ~ /^[0-9]+$/ && v + 0 < n }
        function endblock() {
            if (inb && used != guards) print NR": 使われていない G 行のガードがあります（使用 "used" / "guards"）"
            inb = 0
        }
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
        kind == "F" || kind == "Z" { endblock(); inb = (kind == "F"); syms = 0; nodes = 0; guards = 0; used = 0; delete nk; next }
        kind == "S" {
            if ($2 != syms) { print NR": S 行の番号が連番ではありません（期待 "syms"）: "$0 }
            syms++
            next
        }
        kind == "N" {
            if ($2 != nodes) { print NR": N 行の番号が連番ではありません（期待 "nodes"）: "$0 }
            if ($5 != -1 && !nodeok($5, nodes)) { print NR": N 行の recv がブロックの範囲外です: "$0 }
            if (!argsok($6, nodes)) { print NR": N 行の実引数がブロックの範囲外です: "$0 }
            nk[$2] = $3
            nodes++
            next
        }
        kind == "G" {
            if ($2 == guards) { guards++ }
            else if ($2 != guards - 1 || guards == 0) { print NR": G 行の番号が詰まっていません（期待 "guards - 1" か "guards"）: "$0 }
            if (!nodeok($4, nodes)) { print NR": G 行の subject がブロックのノードではありません: "$0 }
            else if (nk[$4] != "A" && nk[$4] != "V") { print NR": G 行の subject が A か V のノードではありません（"nk[$4]"）: "$0 }
            if ($3 !~ /^(EQ|NE|IN|NI)$/) { print NR": G 行の op が EQ / NE / IN / NI ではありません: "$0 }
            else if ((($3 == "EQ" || $3 == "NE") && NF != 6) || NF < 6) { print NR": G 行の op と値の数が合いません: "$0 }
            next
        }
        kind == "D" || kind == "O" || kind == "R" {
            if (!symok($2, 0)) { print NR": "kind" 行の記号が範囲外です: "$0 }
        }
        kind == "R" {
            if ($3 != -1 && !nodeok($3, nodes)) { print NR": R 行の値がブロックの範囲外です: "$0 }
        }
        kind == "J" {
            if ($5 != -1 && !nodeok($5, nodes)) { print NR": J 行の値がブロックの範囲外です: "$0 }
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
            if ($11 != -1 && !nodeok($11, guards)) { print NR": "kind" 行の guard がブロックの範囲外です: "$0 }
            else if ($11 != -1 && $11 + 0 > used) { print NR": "kind" 行の guard が初めて使う順になっていません（次は "used"）: "$0 }
            else if ($11 != -1 && $11 + 0 == used) { used++ }
            if (NF != 12) { print NR": "kind" 行の列の数が 12 ではありません: "$0 }
        }
        END { endblock() }' "$1")
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

# new の証拠（C 行の hints 列）が載っていて、呼び出しの絞り込みに効いていること。題材の Client.local
# （Dao local = new AlphaDao(); local.select();）の 1 件。書き手がファイルの中で呼び出し元と変数を結びつけて
# 書くので、差分更新で解析し直したブロックでも書き写したブロックでも同じに載っていなければならない。
# これが無いと、上の「差分更新 == 全件解析」の比較が hints 列について素通りする
check_hints() {   # $1=キャッシュの複製  $2=出力フォルダ  $3=ラベル
    local n
    n=$(awk -F'\t' 'substr($0, 1, 1) == "C" && $12 == "inc.AlphaDao"' "$1" | wc -l)
    if [ "$n" -ge 1 ] && awk -F, 'index($1, "at inc.Client.local(") == 1 && $2 == "AlphaDao.select" \
            && $3 == "RESOLVED:LOCAL_NEW" { found = 1 } END { exit !found }' "$2/call-hierarchy.csv"; then
        echo "  OK   $3 new の証拠（hints）が C 行に載り、LOCAL_NEW に絞れている"
    else
        echo "  NG   $3 new の証拠（hints）が載っていないか、絞り込みに効いていません（C 行 $n 件）"; fail=1
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
#   F, I, S*, N*, G*, R*, H*, D*, O*, V*, C と U（ソース上の順で混ざる）, M*, A*, K*, J*
# ある種別の行が、それより後ろに並ぶべき種別の行より後に出てこないこと。L・T 行はブロックより前、Z 行は最後
check_rows() {   # $1=キャッシュ  $2=ラベル
    local bad
    bad=$(awk 'BEGIN { split("F I S N G R H D O V C M A K J", order, " ")
                       for (i in order) rank[order[i]] = i + 0   # 添字は文字列なので数に直す
                       rank["U"] = rank["C"] }
               NR == 1 { next }
               { kind = substr($0, 1, 1) }
               { if (index("TLFISNGRHDOVCUMAKJZ", kind) == 0) { print NR": 未知の行種別: "$0; next } }
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
# $4（省略可）は最初の解析の前に行う用意（題材に無いファイルを work/ に足す）
case_of() {   # $1=ラベル  $2=書き換えるコマンド  $3=事実（F行以外）が変わるべきか(yes/no)  $4=用意するコマンド
    echo "== $1 =="
    rm -rf work .cache out out.log base.tsv inc.tsv full.tsv
    mkdir -p work && cp -r src work/src
    if [ -n "${4:-}" ]; then eval "$4"; fi
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
    check_hints inc.tsv "$inc_csv" "$1 差分更新"

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

# 同じ行に並ぶ宣言の前後。1 行に書いた 2 つのメソッド（OneLine）と、1 行に書いたメソッドとその中の 2 つの
# ラムダ（OneLineLambdas。entry.packages で起点にしている）は、どちらも戻り値の出所（R 行）を持つ側が
# 宣言（D 行）より先に ID 化される。呼び出し側（OneLineUser）だけを書き換えると、そのブロックがキャッシュの
# 先頭へ移り、R 行を持たない側（OneLine.a・OneLineLambdas.pair）が先に ID 化される。同着を ID で決めていると、
# methods.csv と call-hierarchy.csv（起点の並び）が全件解析と差分更新とで食い違う
# （docs/deterministic-row-order-qa.md の Q14）
case_of "同じ行に並ぶ宣言（呼び出し側だけを解析し直す）" \
    "printf '\n// comment only\n' >> work/src/inc/OneLineUser.java" no

# 並びそのものも見る（上の比較は「どちらの実行でも同じ」までしか見ない）。同じ行の宣言はファイルの中の
# 宣言の順番で並ぶ。メソッドどうしはソースの並び、メソッドとその中のラムダは外側が先
if [ -n "${OUT:-}" ] && [ -f "$OUT/methods.csv" ]; then
    same_line_methods=$(grep -o '^OneLine\.[ab]()' "$OUT/methods.csv" | paste -sd' ')
    if [ "$same_line_methods" = "OneLine.a() OneLine.b()" ]; then
        echo "  OK   同じ行のメソッドは methods.csv でソースの並び（$same_line_methods）"
    else
        echo "  NG   同じ行のメソッドの並びが期待と違います（$same_line_methods）"; fail=1
    fi
    same_line_roots=$(sed -nE 's/^[^,]*,[^,]*,[^,]*,[0-9]+,(OneLineLambdas\.(pair|lambda\$pair\$[0-9]+)),.*/\1/p' \
        "$OUT/call-hierarchy.csv" | uniq | paste -sd' ')
    if [ "$same_line_roots" = 'OneLineLambdas.pair OneLineLambdas.lambda$pair$0 OneLineLambdas.lambda$pair$1' ]; then
        echo "  OK   同じ行のメソッドとラムダは起点の並びで外側が先・ソースの並び（$same_line_roots）"
    else
        echo "  NG   同じ行のメソッドとラムダの起点の並びが期待と違います（$same_line_roots）"; fail=1
    fi
fi

# --- 別のファイルの変化が I 行に載らない依存 ---------------------------------
# 次のケースは、書き換えたファイルを I 行に持たないファイルの事実が、全件解析では変わるもの。
# 差分更新がそのファイルを解析し直さないと、古い事実（未解決・欠けた上書き・欠けた M 行・別のクラスへの
# 解決・コンパイルエラーの数）のまま残る（docs/cache-unification-qa.md の Q42〜Q46・Q50）。
# 題材は work/ にだけ足す（test/incremental/src は他のケースと共有しているので触らない）

jfile() {   # $1=work/src からの相対パス。本文は標準入力
    mkdir -p "work/src/$(dirname "$1")"
    cat > "work/src/$1"
}

# 無かった型のソースを後から足す。JDT は無い型の名前にバインディングを返さないので、参照していた側の
# I 行には載らない（コンパイルエラーか BINDING_FAILED として残る）。git stash / checkout で消えて戻る場合も同じ
setup_missing_type() {
    jfile miss/Caller.java <<'EOF'
package miss;
public class Caller {
    // 型の名前だけの static 呼び出し。new Foo() や Foo 型の変数なら、JDT が型を miss.Foo として
    // 復元するので I 行に載る（その形は今でも追随できる）。名前だけの形は何も残らない
    public void call() { Foo.run(); }
}
EOF
}
edit_missing_type() {
    jfile miss/Foo.java <<'EOF'
package miss;
public class Foo {
    public static void run() { new Foo().go(); }
    public void go() { }
}
EOF
}
case_of "無かった型のソースを後から足す" edit_missing_type yes setup_missing_type

# 既存のファイルを書き換えて、無かった型を宣言する（新しいファイルが増えなくても型は増える）
setup_missing_type_in_file() {
    jfile miss2/Caller.java <<'EOF'
package miss2;
public class Caller {
    public void call() { Extra.work(); }
}
EOF
    jfile miss2/Holder.java <<'EOF'
package miss2;
public class Holder { }
EOF
}
edit_missing_type_in_file() {
    printf '\nclass Extra { static void work() { } }\n' >> work/src/miss2/Holder.java
}
case_of "既存のファイルに無かった型を足す" edit_missing_type_in_file yes setup_missing_type_in_file

# 祖父母の型に抽象メソッドを足す。D の I 行は親の E だけで、F は載らない。E は F に依存するので解析し直すが、
# 定数が変わらなければ E の型は連鎖しないので、D の上書き（O 行 D#m(String) -> F#m(Object)）が欠けたまま残る
setup_super_chain() {
    jfile sup/F.java <<'EOF'
package sup;
public abstract class F<T> { }
EOF
    jfile sup/E.java <<'EOF'
package sup;
public abstract class E extends F<String> { }
EOF
    jfile sup/D.java <<'EOF'
package sup;
public class D extends E {
    public void m(String s) { System.out.println(s); }
}
EOF
    jfile sup/U.java <<'EOF'
package sup;
public class U {
    void go(F<String> f) { f.toString(); }
}
EOF
}
edit_super_chain() {
    jfile sup/F.java <<'EOF'
package sup;
public abstract class F<T> {
    public abstract void m(T t);
}
EOF
    sed -i 's/f.toString();/f.m("x");/' work/src/sup/U.java
}
case_of "祖父母の型に抽象メソッドを足す（上書きの事実）" edit_super_chain yes setup_super_chain

# 祖父母の型にオーバーロードを足す。A の new B().m(1) は B#m(long) から C#m(int) に変わる
setup_super_overload() {
    jfile ovl/C.java <<'EOF'
package ovl;
public class C { }
EOF
    jfile ovl/B.java <<'EOF'
package ovl;
public class B extends C {
    public void m(long x) { }
}
EOF
    jfile ovl/A.java <<'EOF'
package ovl;
public class A {
    void a() { new B().m(1); }
}
EOF
}
edit_super_overload() {
    jfile ovl/C.java <<'EOF'
package ovl;
public class C {
    public void m(int x) { }
}
EOF
}
case_of "祖父母の型にオーバーロードを足す" edit_super_overload yes setup_super_overload

# ラムダの目標の関数型インターフェースの親を変える。T の I 行には SAM を宣言した Opener は載るが、
# 目標の Door は載らない。Door が Closer も継承すると、ラムダは Closer#act() の実装にもなる（M 行が増える）
setup_lambda_target() {
    jfile lam/Opener.java <<'EOF'
package lam;
interface Opener { void act(); }
EOF
    jfile lam/Closer.java <<'EOF'
package lam;
interface Closer { void act(); }
EOF
    jfile lam/Door.java <<'EOF'
package lam;
interface Door extends Opener { }
EOF
    jfile lam/T.java <<'EOF'
package lam;
public class T {
    public static void main(String[] args) {
        Door d = () -> System.out.println("x");
        close((Closer) (Object) d);
    }
    static void close(Closer c) { c.act(); }
}
EOF
}
edit_lambda_target() {
    sed -i 's/extends Opener { }/extends Opener, Closer { }/' work/src/lam/Door.java
}
case_of "ラムダの目標の型の親を変える" edit_lambda_target yes setup_lambda_target

# 同じパッケージに型を足して、オンデマンド import（shq.*）と java.lang の型を隠す（JLS 6.4.1）。
# Main の I 行は shq.Helper と java.lang.Math で、足した shp.Helper・shp.Math にはどちらも触れない
setup_shadow() {
    jfile shq/Helper.java <<'EOF'
package shq;
public class Helper {
    public static void work() { }
}
EOF
    jfile shp/Main.java <<'EOF'
package shp;
import shq.*;
public class Main {
    void go() {
        Helper.work();
        Math.abs(1);
    }
}
EOF
}
edit_shadow() {
    jfile shp/Helper.java <<'EOF'
package shp;
public class Helper {
    public static void work() { }
}
EOF
    jfile shp/Math.java <<'EOF'
package shp;
class Math {
    static int abs(int x) { return x; }
}
EOF
}
case_of "同じパッケージの型が import と java.lang を隠す" edit_shadow yes setup_shadow

# 宣言にだけ書いた型（戻り値・throws・ローカル変数の型）を消す。呼び出しも値も無いので I 行に載らず、
# 全件解析ではコンパイルエラーになるのに、差分更新ではエラー 0 のまま残る（warnings.txt の件数が食い違う）
setup_decl_only() {
    jfile dec/Dao.java <<'EOF'
package dec;
public interface Dao { }
EOF
    jfile dec/DecEx.java <<'EOF'
package dec;
public class DecEx extends Exception { }
EOF
    jfile dec/Chain.java <<'EOF'
package dec;
public class Chain {
    public static Dao c1() { return null; }
    static void t() throws DecEx { }
    static void local() { Dao d = null; }
}
EOF
}
edit_decl_only() {
    rm -f work/src/dec/Dao.java work/src/dec/DecEx.java
}
case_of "宣言にだけ書いた型を消す" edit_decl_only yes setup_decl_only

# 親型の連鎖は処理の順に依らない。部分型（D・A）のパスが親（E・I2）より前に並ぶので、同じ周回で親より先に
# 解析し直される。そのときに「親が変わった型か」を見ると、まだ親が変わった型に入っていないので連鎖せず、
# 部分型の利用者（U）が古いまま残る。連鎖は、解析し直した型の形（継承したものを含むメンバーの指紋。I 行）が
# 前回と違うかで決める（docs/cache-unification-qa.md の Q44）
setup_order_class() {
    jfile ordc/F.java <<'EOF'
package ordc;
public abstract class F<T> { }
EOF
    jfile ordc/E.java <<'EOF'
package ordc;
public class E extends F<String> { }
EOF
    jfile ordc/D.java <<'EOF'
package ordc;
public class D extends E {
    public void m(long x) { System.out.println(x); }
    public F<String> self() { return this; }
}
EOF
    jfile ordc/U.java <<'EOF'
package ordc;
public class U {
    void go(D d) { d.m(1); }
}
EOF
}
edit_order_class() {
    jfile ordc/F.java <<'EOF'
package ordc;
public abstract class F<T> {
    public void m(int x) { System.out.println(x); }
}
EOF
}
case_of "部分型のパスが親より前に並ぶ（クラス）" edit_order_class yes setup_order_class

setup_order_iface() {
    jfile ordi/I1.java <<'EOF'
package ordi;
public interface I1 { }
EOF
    jfile ordi/I2.java <<'EOF'
package ordi;
public interface I2 extends I1 { }
EOF
    jfile ordi/A.java <<'EOF'
package ordi;
public class A implements I2 {
    public I1 self() { return this; }
}
EOF
    jfile ordi/U.java <<'EOF'
package ordi;
public class U {
    void go(A a) { a.m2(); }
}
EOF
}
edit_order_iface() {
    jfile ordi/I1.java <<'EOF'
package ordi;
public interface I1 {
    default void m2() { System.out.println(1); }
}
EOF
}
case_of "部分型のパスが親より前に並ぶ（インターフェース）" edit_order_iface yes setup_order_iface

# 式の型にだけ現れる型にメンバーを足す。U の a.getB().x() は、x が無いうちは解決できない（BINDING_FAILED と
# コンパイルエラー）。ソースに B の名前は無いので、解決できなかった呼び出しの受け手の型（B）を I 行に載せて
# おかないと、B に x を足しても U を解析し直さない（docs/cache-unification-qa.md の Q50）
setup_expr_member() {
    jfile exm/B.java <<'EOF'
package exm;
public class B { }
EOF
    jfile exm/A.java <<'EOF'
package exm;
public class A {
    public B b;
    public B getB() { return b; }
}
EOF
    jfile exm/U.java <<'EOF'
package exm;
public class U {
    void go(A a) { a.getB().x(); }
    int count(A a) { return a.getB().count + a.b.count; }
}
EOF
}
edit_expr_member() {
    jfile exm/B.java <<'EOF'
package exm;
public class B {
    public int count;
    public void x() { System.out.println(1); }
}
EOF
}
case_of "式の型にだけ現れる型にメソッドとフィールドを足す" edit_expr_member yes setup_expr_member

# 親の型引数にだけ現れる型（D extends E<Foo> の Foo）にメソッドを足す。d.get() の型は Foo だが、U のソースにも
# E の宣言にも Foo の名前は無い
setup_type_arg_member() {
    jfile tam/E.java <<'EOF'
package tam;
public class E<T> {
    T v;
    public T get() { return v; }
}
EOF
    jfile tam/D.java <<'EOF'
package tam;
public class D extends E<Foo> { }
EOF
    jfile tam/Foo.java <<'EOF'
package tam;
public class Foo { }
EOF
    jfile tam/U.java <<'EOF'
package tam;
public class U {
    void go(D d) { d.get().x(); }
}
EOF
}
edit_type_arg_member() {
    jfile tam/Foo.java <<'EOF'
package tam;
public class Foo {
    public void x() { System.out.println(1); }
}
EOF
}
case_of "親の型引数にだけ現れる型にメソッドを足す" edit_type_arg_member yes setup_type_arg_member

# 型変数の上限の親にメソッドを足す。Box<?> の b.get() の型は捕捉された型変数で、その上限（Mid）で探す。
# Box は Mid を参照しているので解析し直すが、Mid の部分型ではないので連鎖しない。解決できなかった呼び出しの
# 受け手の型を、型変数なら上限の消去で I 行に載せる
setup_bound_member() {
    jfile bnd/Base.java <<'EOF'
package bnd;
public class Base { }
EOF
    jfile bnd/Mid.java <<'EOF'
package bnd;
public class Mid extends Base { }
EOF
    jfile bnd/Box.java <<'EOF'
package bnd;
public class Box<T extends Mid> {
    T v;
    public T get() { return v; }
}
EOF
    jfile bnd/User.java <<'EOF'
package bnd;
public class User {
    void go(Box<?> b) { b.get().n(); }
}
EOF
}
edit_bound_member() {
    jfile bnd/Base.java <<'EOF'
package bnd;
public class Base {
    public void n() { System.out.println(1); }
}
EOF
}
case_of "型変数の上限の親にメソッドを足す" edit_bound_member yes setup_bound_member

# 実引数の式の型の親を変える。a.m(b.getC()) の C は U のソースに無い。C が I を実装すると、選ばれるメソッドが
# A#m(Object) から A#m(I) に変わる（JLS 15.12.2）。解決できた呼び出しでも、受け手と実引数の式の型を I 行に載せる
setup_arg_type() {
    jfile arg/C.java <<'EOF'
package arg;
public class C { }
EOF
    jfile arg/I.java <<'EOF'
package arg;
public interface I { }
EOF
    jfile arg/A.java <<'EOF'
package arg;
public class A {
    public void m(Object o) { System.out.println(1); }
    public void m(I i) { System.out.println(2); }
}
EOF
    jfile arg/B.java <<'EOF'
package arg;
public class B {
    public C getC() { return null; }
}
EOF
    jfile arg/U.java <<'EOF'
package arg;
public class U {
    void go(A a, B b) { a.m(b.getC()); }
}
EOF
}
edit_arg_type() {
    sed -i 's/public class C { }/public class C implements I { }/' work/src/arg/C.java
}
case_of "実引数の式の型の親を変える（オーバーロードの選択が変わる）" edit_arg_type yes setup_arg_type

# 式の型に、親のフィールドを隠すフィールドを足す。a.getB().count は P#count から B#count に変わる（A 行）
setup_field_hide() {
    jfile fhd/P.java <<'EOF'
package fhd;
public class P {
    public int count;
}
EOF
    jfile fhd/B.java <<'EOF'
package fhd;
public class B extends P { }
EOF
    jfile fhd/A.java <<'EOF'
package fhd;
public class A {
    public B getB() { return null; }
}
EOF
    jfile fhd/U.java <<'EOF'
package fhd;
public class U {
    int go(A a) { return a.getB().count; }
}
EOF
}
edit_field_hide() {
    sed -i 's/public class B extends P { }/public class B extends P { public int count; }/' work/src/fhd/B.java
}
case_of "式の型に親のフィールドを隠すフィールドを足す" edit_field_hide yes setup_field_hide

# 新しい型で解析し直すのは、解決できなかった名前（コンパイルエラーの引数。I 行）が新しい型の名前に当たる
# ブロックだけ。無名クラス・入れ子の型を足しても、名前の違う型で失敗しているブロック（Fail）は解析し直さない
# （docs/cache-unification-qa.md の Q42）。名前の当たるブロック（Caller2）は解析し直す
setup_new_type_names() {
    jfile ntn/Fail.java <<'EOF'
package ntn;
import org.missing.Lib;
public class Fail {
    void f(Lib l) { l.go(); }
}
EOF
    jfile ntn/Caller2.java <<'EOF'
package ntn;
public class Caller2 {
    void call() { Foo2.run(); }
}
EOF
    jfile ntn/Main.java <<'EOF'
package ntn;
public class Main {
    void run() { System.out.println(1); }
}
EOF
}
edit_new_type_anon() {
    jfile ntn/Main.java <<'EOF'
package ntn;
public class Main {
    static class Inner { }
    void run() {
        Runnable r = new Runnable() { public void run() { System.out.println(2); } };
        r.run();
    }
}
EOF
}
case_of "無名クラス・入れ子の型を足す（名前の違う型で失敗しているブロックは解析し直さない）" \
    edit_new_type_anon yes setup_new_type_names
if [ "$INC_PARSED" = 1 ]; then
    echo "  OK   解析し直したのは書き換えた 1 件だけ（新規解析=$INC_PARSED）"
else
    echo "  NG   無名クラス・入れ子の型を足しただけで、ほかのブロックも解析し直しています（新規解析=$INC_PARSED）"; fail=1
fi
edit_new_type_named() {
    jfile ntn/Foo2.java <<'EOF'
package ntn;
public class Foo2 {
    public static void run() { }
}
EOF
}
case_of "無かった型を足す（名前の当たるブロックだけを解析し直す）" edit_new_type_named yes setup_new_type_names
if [ "$INC_PARSED" = 2 ]; then
    echo "  OK   解析し直したのは足した Foo2 と名前の当たる Caller2 の 2 件（新規解析=$INC_PARSED）"
else
    echo "  NG   新しい型の名前に当たらないブロックも解析し直しています（新規解析=$INC_PARSED。期待は 2）"; fail=1
fi

# 親型の本体だけを変えても、部分型の利用者へは連鎖しない。Sub は Base を参照しているので解析し直すが、
# Sub の形（継承したものを含むメンバー）は変わらないので、Sub だけを参照する User（s.n() の n は Sub の宣言）は
# 再利用する（docs/cache-unification-qa.md の Q44）
setup_body_only() {
    jfile bod/Base.java <<'EOF'
package bod;
public class Base {
    public void m() { System.out.println(1); }
}
EOF
    jfile bod/Sub.java <<'EOF'
package bod;
public class Sub extends Base {
    public void n() { m(); }
}
EOF
    jfile bod/User.java <<'EOF'
package bod;
public class User {
    void go(Sub s) { s.n(); }
}
EOF
}
edit_body_only() {
    sed -i 's/println(1)/println(2)/' work/src/bod/Base.java
}
case_of "親型のメソッドの本体だけを変える（部分型の利用者へは連鎖しない）" edit_body_only yes setup_body_only
if [ "$INC_PARSED" = 2 ]; then
    echo "  OK   解析し直したのは Base と Sub の 2 件（新規解析=$INC_PARSED）"
else
    echo "  NG   親型の本体だけの変更で、部分型の利用者まで解析し直しています（新規解析=$INC_PARSED。期待は 2）"; fail=1
fi

# --- 何も変わっていなければ書き直さない -----------------------------------
# 解析するファイルが無く、依存 jar・ソース一覧も同じで、どのブロックも有効なら、書き直しても同じバイト列に
# なるので旧キャッシュをそのまま残す（ファイルを作り直さない＝inode も更新時刻も変わらない）。
# 書き手の書くとおりの形でない（CRLF に変換された）キャッシュは、ブロックを再利用しつつ書き直して形を戻す
# （ブロックをバイトのまま写さず、行に戻して '\n' で書き直す経路）
unchanged_case() {
    echo "== 何も変わっていなければキャッシュを書き直さない =="
    rm -rf work .cache out out.log base.tsv inc.tsv full.tsv
    mkdir -p work && cp -r src work/src
    run base.tsv || return
    local before after
    before=$(stat -c %i:%Y "$(ls $CACHE)")
    sleep 1.1   # 書き直していれば更新時刻が変わるように、秒をまたぐ
    run inc.tsv || return
    after=$(stat -c %i:%Y "$(ls $CACHE)")
    if [ "$before" = "$after" ] && cmp -s base.tsv inc.tsv; then
        echo "  OK   キャッシュのファイルはそのまま（inode:更新時刻 $after、中身も同じ）"
    else
        echo "  NG   何も変わっていないのにキャッシュを書き直しています（$before -> $after）"; fail=1
    fi
    if [ "$PARSED" = 0 ] && [ "$REUSED" = "$TOTAL_FILES" ]; then
        echo "  OK   全件を再利用として数える（再利用=$REUSED）"
    else
        echo "  NG   再利用・新規解析の件数が書き直したときと違います（新規解析=$PARSED 再利用=$REUSED / $TOTAL_FILES 件）"; fail=1
    fi
    if grep -q -F -- "[cache] Nothing changed" out.log; then
        echo "  OK   そのまま使うことをログに出す"
    else
        echo "  NG   そのまま使うことがログに出ていません"; fail=1
    fi

    # CRLF に変換されたキャッシュ（git の改行変換を通ったなど）。そのままは残さず、書き直して '\n' に戻す
    sed -i 's/$/\r/' "$(ls $CACHE)"
    run crlf.tsv || return
    if cmp -s base.tsv crlf.tsv && [ "$PARSED" = 0 ] && [ "$REUSED" = "$TOTAL_FILES" ]; then
        echo "  OK   CRLF のキャッシュは全件再利用し、'\n' で書き直す（元のキャッシュと同じバイト列）"
    else
        echo "  NG   CRLF のキャッシュの扱いが期待と違います（新規解析=$PARSED 再利用=$REUSED）"
        cmp base.tsv crlf.tsv | head -2; fail=1
    fi
    if grep -q -F -- "[cache] Nothing changed" out.log; then
        echo "  NG   CRLF のキャッシュを書き直さずに残しています"; fail=1
    fi
    # F 行（ブロックの先頭）だけが CRLF。ブロックの範囲は F 行から始まるので、F 行の CR もそのブロックの形の崩れ
    # として見ること（前のブロックのものとして数えると、最後のブロックが CR ごとバイトのまま写される）
    sed -i '/^F\t/s/$/\r/' "$(ls $CACHE)"
    run crlf.tsv || return
    if cmp -s base.tsv crlf.tsv && [ "$PARSED" = 0 ]; then
        echo "  OK   F 行だけ CRLF のキャッシュも '\n' で書き直す（元のキャッシュと同じバイト列）"
    else
        echo "  NG   F 行だけ CRLF のキャッシュの扱いが期待と違います（新規解析=$PARSED）"
        cmp base.tsv crlf.tsv | head -2; fail=1
    fi

    # CRLF のまま 1 ファイルだけ変える。書き直すブロック（行に戻す経路）とそのまま写すブロックが混ざらないこと、
    # 全件解析と一致すること
    sed -i 's/$/\r/' "$(ls $CACHE)"
    printf '\n// comment only\n' >> work/src/inc/OneLineUser.java
    run inc.tsv || return
    local inc_csv=$OUT
    check_rows inc.tsv "CRLF のキャッシュから差分更新"
    if grep -q $'\r' inc.tsv; then
        echo "  NG   CRLF のキャッシュから差分更新 CR が残っています"; fail=1
    fi
    rm -rf .cache
    run full.tsv || return
    for f in call-hierarchy.csv methods.csv; do
        if diff --strip-trailing-cr -q "$inc_csv/$f" "$OUT/$f" > /dev/null; then
            echo "  OK   CRLF のキャッシュから差分更新 $f（差分更新 == 全件解析）"
        else
            echo "  NG   CRLF のキャッシュから差分更新 $f が全件解析と違います"
            diff --strip-trailing-cr "$inc_csv/$f" "$OUT/$f" | head -10; fail=1
        fi
    done
    if diff -q <(normalized inc.tsv) <(normalized full.tsv) > /dev/null; then
        echo "  OK   CRLF のキャッシュから差分更新 キャッシュ（差分更新 == 全件解析）"
    else
        echo "  NG   CRLF のキャッシュから差分更新 キャッシュが全件解析と違います"
        diff <(normalized inc.tsv) <(normalized full.tsv) | head -10; fail=1
    fi

    # 1 ファイルだけ変えれば書き直す（そのまま残す判定が効きすぎていないこと）
    before=$(stat -c %i:%Y "$(ls $CACHE)")
    sleep 1.1
    printf '\n// comment only\n' >> work/src/inc/OneLineUser.java
    run inc.tsv || return
    after=$(stat -c %i:%Y "$(ls $CACHE)")
    if [ "$before" != "$after" ] && [ "$PARSED" -ge 1 ]; then
        echo "  OK   1 ファイル変えれば書き直す（新規解析=$PARSED）"
    else
        echo "  NG   1 ファイル変えたのに書き直していません（新規解析=$PARSED、$before -> $after）"; fail=1
    fi
    rm -f crlf.tsv
}

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
unchanged_case

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

# --- 前の実行が残した一時ファイル ----------------------------------------
# 終了フックも動かない終わり方（SIGKILL・停電）では、依存の索引・エッジの記録・型解決に失敗した呼び出しの行が
# キャッシュのフォルダに残る。次の実行の最初に消すこと（GitHub Actions はフォルダを丸ごと保存するので、
# 残すと保存するキャッシュが無駄に大きくなる）。解析し直せば済み、利用者が対処することではないので
# warnings.txt は作らない。キャッシュ本体の一時ファイル（analysis-cache.tsv.tmp）は中断からの引き継ぎに
# 使うので、消さずに引き継ぐこと（ほかの一時ファイルと取り違えて消していないこと）
LEFTOVERS="analysis-cache.tsv.deps-1.tmp analysis-cache.tsv.edges-1.tmp analysis-cache.tsv.unresolved-2.tmp"
plant_leftovers() {   # $1=キャッシュのフォルダ
    local f
    for f in $LEFTOVERS; do
        printf 'left by a killed run\n' > "$1/$f"
    done
}
check_leftovers_gone() {   # $1=キャッシュのフォルダ  $2=ラベル
    local f left=""
    for f in $LEFTOVERS; do
        [ -e "$1/$f" ] && left="$left $f"
    done
    if [ -z "$left" ]; then
        echo "  OK   $2 前の実行が残した一時ファイル（依存の索引・エッジの記録・型解決に失敗した呼び出しの行）を消した"
    else
        echo "  NG   $2 前の実行が残した一時ファイルが残っています:$left"; fail=1
    fi
    if [ -n "${OUT:-}" ] && [ -f "$OUT/warnings.txt" ]; then
        echo "  NG   $2 一時ファイルを消しただけで warnings.txt ができています"; head -5 "$OUT/warnings.txt"; fail=1
    else
        echo "  OK   $2 warnings.txt は作らない（利用者が対処することではない）"
    fi
}

leftover_temp_case() {
    echo "== 前の実行が残した一時ファイルを消す =="
    rm -rf work .cache out out.log base.tsv inc.tsv
    mkdir -p work && cp -r src work/src
    run base.tsv || return
    local dir
    dir=$(dirname "$(ls $CACHE)")

    # 中断した実行が残すキャッシュ本体の一時ファイル（引き継ぐ）と、消すべき一時ファイルを一緒に置く
    make_partial
    plant_leftovers "$dir"
    run inc.tsv || return   # run は、実行のあとに *.tmp が 1 つでも残っていれば NG にする
    check_leftovers_gone "$dir" "引き継ぎのある実行"
    if [ "$PARSED" -lt "$TOTAL_FILES" ]; then
        echo "  OK   analysis-cache.tsv.tmp は消さずに引き継いだ（解析し直したのは $PARSED / $TOTAL_FILES 件）"
    else
        echo "  NG   analysis-cache.tsv.tmp を引き継いでいません（$PARSED / $TOTAL_FILES 件を解析し直した）"; fail=1
    fi

    # 何も変わっていない実行（キャッシュを書き直さずに終わる経路）でも消す
    plant_leftovers "$dir"
    run inc.tsv || return
    check_leftovers_gone "$dir" "何も変わっていない実行"
    if grep -q -F -- "[cache] Nothing changed" out.log; then
        echo "  OK   何も変わっていない実行 一時ファイルがあってもキャッシュはそのまま使う"
    else
        echo "  NG   何も変わっていない実行 一時ファイルがあるだけでキャッシュを書き直しています"; fail=1
    fi
}
leftover_temp_case

# --- 強制終了（SIGTERM）でも一時ファイルが残らない ---------------------------
# Ctrl+C・kill・GitHub Actions の中止や時間切れ・IDE が解析サーバーを止めたとき（SIGINT / SIGTERM）は
# finally が動かない。JVM の終了フック（jche.cache.TempFiles）が、依存の索引・エッジの記録・型解決に失敗した
# 呼び出しの行を消すこと。キャッシュ本体の一時ファイル（analysis-cache.tsv.tmp）は次の実行が引き継ぐので消さないこと。
#
# ログの「Phase 2/3」を待つのではなく、一時ファイルそのものができたのを見てから止める（止めた時点で消すべき
# ファイルがあったことが確かになる）。10 ミリ秒おきに見る。
#   フェーズ2・3 … test/demo を初めて解析し、エッジの記録か型解決に失敗した呼び出しの行ができたら止める。
#                  test/demo は小さく、グラフの構築から出力までが 0.1 秒ほどで終わるので、止める前に終わって
#                  しまったら、このツール自身のソース（依存 jar なしで読むので型解決に失敗した呼び出しが多く、
#                  出力に数秒かかる）でやり直す
#   フェーズ1   … 解析済みの test/demo のファイルを 1 つ変えて解析し、依存の索引とキャッシュ本体の一時ファイルが
#                  そろったら（パス2 の解析中）止める
# 止める前に解析が終わってしまったときだけ、理由を出して飛ばす（NG にはしない）
KW=killwork
kill_config() {   # $1=解析するプロジェクト（src を持つフォルダ）
    cat > $KW/kill.properties <<EOF
project.root=$1
source.folders=src
library.folders=
library.build.tool=none
source.encoding=UTF-8
cache.enabled=true
cache.folder=./cache
dataflow.enabled=true
output.encoding=UTF-8
output.folder=./out
EOF
}
kill_left() {   # 消すべき一時ファイル（依存の索引・エッジの記録・型解決に失敗した呼び出しの行）
    find $KW/cache \( -name '*.deps-*.tmp' -o -name '*.edges-*.tmp' -o -name '*.unresolved-*.tmp' \) 2>/dev/null
}
seen_graph_temps() {   # フェーズ2・3 の一時ファイル（エッジの記録・型解決に失敗した呼び出しの行）がある
    [ -n "$(find $KW/cache \( -name '*.edges-*.tmp' -o -name '*.unresolved-*.tmp' \) 2>/dev/null)" ]
}
seen_phase1_temps() {   # 依存の索引とキャッシュ本体の一時ファイルがそろっている（パス1 を終えて、パス2 以降にいる）
    [ -n "$(find $KW/cache -name '*.deps-*.tmp' 2>/dev/null)" ] \
        && [ -n "$(find $KW/cache -name 'analysis-cache.tsv.tmp' 2>/dev/null)" ]
}
# 解析を裏で始め、$1（条件の関数）が成り立ったら SIGTERM を送る。KILL_STATUS に終了コードを入れる
# （SIGTERM で終われば 143。0 なら止める前に終わった）
kill_when() {
    "$JAVA_BIN" -Dstdout.encoding=UTF-8 -cp "$CP" jche.CallHierarchyExporter $KW/kill.properties \
        > $KW/kill.log 2>&1 &
    local pid=$! i
    for ((i = 0; i < 12000; i++)); do   # 最大 2 分
        if "$1"; then
            kill -TERM "$pid" 2>/dev/null
            break
        fi
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.01
    done
    wait "$pid"
    KILL_STATUS=$?
}
kill_run_plain() {   # 止めずに最後まで解析する -> KILL_STATUS
    "$JAVA_BIN" -Dstdout.encoding=UTF-8 -cp "$CP" jche.CallHierarchyExporter $KW/kill.properties \
        > $KW/kill.log 2>&1
    KILL_STATUS=$?
}
check_killed() {   # $1=ラベル。止めたあとに消すべき一時ファイルが残っていないこと
    local left
    left=$(kill_left)
    if [ -z "$left" ]; then
        echo "  OK   $1 SIGTERM で止めても一時ファイルが残らない（終了フックが消した）"
    else
        echo "  NG   $1 SIGTERM で止めたあとに一時ファイルが残っています: $left"; fail=1
    fi
}

kill_case() {
    echo "== 強制終了（SIGTERM）でも一時ファイルが残らない =="
    local label project killed=""
    # フェーズ2・3。止める前に終わってしまったら、大きいプロジェクトで 1 回だけやり直す
    for project in "$ROOT/test/demo" "$ROOT"; do
        if [ "$project" = "$ROOT" ]; then label="フェーズ2・3（このツールのソース）"; else label="フェーズ2・3（demo）"; fi
        rm -rf $KW && mkdir -p $KW
        kill_config "$project"
        kill_when seen_graph_temps
        if [ "$KILL_STATUS" = 143 ]; then
            killed=1
            check_killed "$label"
            break
        elif [ "$KILL_STATUS" = 0 ]; then
            echo "  --   $label 一時ファイルを見て止める前に解析が終わりました"
        else
            echo "  NG   $label 解析が失敗しました（終了コード $KILL_STATUS）"; tail -5 $KW/kill.log; fail=1
            killed=failed
            break
        fi
    done
    if [ -z "$killed" ]; then
        echo "  SKIP フェーズ2・3 どちらのプロジェクトでも止める前に解析が終わったため、終了フックを確かめられませんでした"
    fi

    # フェーズ1。解析済みのキャッシュがあり、1 ファイル変えた実行を、依存の索引があるあいだに止める
    label="フェーズ1（demo）"
    rm -rf $KW && mkdir -p $KW
    cp -r "$ROOT/test/demo/src" $KW/src
    kill_config "$PWD/$KW"
    kill_run_plain
    if [ "$KILL_STATUS" != 0 ]; then
        echo "  NG   $label 最初の解析が失敗しました（終了コード $KILL_STATUS）"; tail -5 $KW/kill.log; fail=1; return
    fi
    printf '\n// changed\n' >> $KW/src/fx/dao/UserDaoImpl.java
    kill_when seen_phase1_temps
    if [ "$KILL_STATUS" = 0 ]; then
        echo "  SKIP $label 依存の索引を見て止める前に解析が終わったため、終了フックを確かめられませんでした"
        rm -rf $KW
        return
    elif [ "$KILL_STATUS" != 143 ]; then
        echo "  NG   $label 解析が失敗しました（終了コード $KILL_STATUS）"; tail -5 $KW/kill.log; fail=1; return
    fi
    check_killed "$label"
    # キャッシュ本体の一時ファイルは引き継ぎに使うので、終了フックは消さない。ただし、止める合図が届く前に
    # フェーズ1 を終えていれば（集計の行がログにある）、一時ファイルは本物のキャッシュに置き換わっている
    if [ -n "$(find $KW/cache -name 'analysis-cache.tsv.tmp')" ]; then
        echo "  OK   $label 中断からの引き継ぎに使う analysis-cache.tsv.tmp は消さない"
    elif LC_ALL=C grep -a -q -E '=[0-9]+.*=[0-9]+.*=[0-9]+[[:space:]]*$' $KW/kill.log; then
        echo "  --   $label 止める合図が届く前にフェーズ1 を終えていました（analysis-cache.tsv.tmp は本物に置き換わった）"
    else
        echo "  NG   $label 終了フックが analysis-cache.tsv.tmp（中断からの引き継ぎに使う）まで消しています"; fail=1
    fi
    # 次の実行は引き継いで最後まで進み、何も残さない
    kill_run_plain
    if [ "$KILL_STATUS" = 0 ] && [ -z "$(find $KW/cache -name '*.tmp' -o -name '*.partial')" ]; then
        echo "  OK   $label 次の実行は最後まで進み、一時ファイルを残さない"
    else
        echo "  NG   $label 止めたあとの実行が失敗したか、一時ファイルが残っています（終了コード $KILL_STATUS）: $(ls $KW/cache/*/)"
        fail=1
    fi
    rm -rf $KW
}
kill_case

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
