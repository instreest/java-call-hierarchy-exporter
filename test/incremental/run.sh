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
#      中途半端に再利用せず全件解析し直すこと。1 ブロックだけが壊れていれば、全件ではなくそのファイル（と、
#      そのファイルの型を使うファイル）だけを解析し直すこと
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
# 部分型の利用者（U）が古いまま残る（docs/cache-unification-qa.md の Q44）。今は、変わった型の部分型を H 行から作る
# 索引で推移的に変わった型にするので、解析の順に依らない（Q77）
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

# 依存 jar が無いときの事実がバッチの組み方に依らない（docs/cache-unification-qa.md の Q79）。JDT は、無いパッケージの
# 名前を回復するために作った型を、同じバッチの中で使い回す。型の文脈で org.missing.pkg.Type と書いた A1Type が同じ
# バッチで先に解析されると、式の中の org.missing.pkg.Type.staticCall()（B2Expr）は「org.missing.pkg.Type cannot be
# resolved to a type」になり、名前 org.missing に回復した型 org.missing が付く。B2Expr だけを解析し直す差分更新では
# 「org.missing cannot be resolved」で、回復した型も無い。以前は B2Expr の I 行（依存する型と解決できなかった名前）が
# 全件解析と食い違った（全件解析は全ファイルを 1 つのバッチで、パスの順に解析する）
setup_batch_nojar() {
    jfile bat/A1Type.java <<'EOF'
package bat;
public class A1Type {
    Object k = org.missing.pkg.Type.class;
    org.missing.pkg.Type field;
    void f(org.missing.pkg.Type t) { t.run(); }
    org.missing.other.Ret g() { return null; }
}
EOF
    jfile bat/B2Expr.java <<'EOF'
package bat;
public class B2Expr {
    void go() {
        org.missing.pkg.Type.staticCall();
        Object o = org.missing.pkg.Type.FIELD;
        System.out.println(org.missing.other.Ret.X);
    }
}
EOF
    jfile bat/C3User.java <<'EOF'
package bat;
public class C3User {
    void go(A1Type a) { a.g(); new B2Expr().go(); org.missing.pkg.Type.staticCall(); }
}
EOF
}
case_of "依存 jar が無いとき、式に完全修飾名を書いたファイルだけを解析し直す（バッチの組み方）" \
    "printf '\n// c\n' >> work/src/bat/B2Expr.java" no setup_batch_nojar
case_of "依存 jar が無いとき、型解決に失敗したファイルの 2 つを解析し直す（バッチの組み方）" \
    "printf '\n// c\n' >> work/src/bat/B2Expr.java; printf '\n// c\n' >> work/src/bat/C3User.java" no setup_batch_nojar

# 完全修飾名の途中のパッケージ（org.missing）に型ができる・無くなる。JDT がどこまでをパッケージとして読むかが変わり、
# 回復した型の名前（I 行の依存する型と、A1Type の org.missing.pkg.Type.class のノードの値）が org.missing から
# org.missing.pkg に変わる。解決できなかった名前（書かれた名前 org.missing.pkg.Type）は変わらず、足した型の名前（Foo）にも
# 当たらないので、以前は解析し直さずに古い名前が残った（docs/cache-unification-qa.md の Q80）
edit_batch_package() {
    jfile org/missing/Foo.java <<'EOF'
package org.missing;
public class Foo { }
EOF
}
case_of "依存 jar が無いとき、完全修飾名の途中のパッケージに型を足す" edit_batch_package yes setup_batch_nojar
case_of "依存 jar が無いとき、完全修飾名の途中のパッケージの型を消す" "rm -r work/src/org" yes \
    "setup_batch_nojar; edit_batch_package"

# 選ばれなかったオーバーロードの引数の型を消す。どの候補が選ばれるか（曖昧か）は、選ばれなかった候補の引数の型にも依る
# （x.m(null) は m(Y) と m(Z) で曖昧だが、Z を消すと m(Y) に決まる）。利用者のソースにも、選ばれた呼び出し先の鍵にも Z は
# 無い。以前は候補を宣言した型の形（Q44）が変わって連鎖していたので、形をやめたら候補の引数の型を I 行に数える
# （docs/cache-unification-qa.md の Q78）。new・継承したメソッド・単純名・static import・super.m()・super(...) の
# 探し方がそれぞれ違うので、利用者を別々のファイルにする（どれか 1 つでも解析し直さなければ全件解析と違う）
setup_overload() {
    for t in Y Z Y2 Z2 Y3 Z3 Y4 Z4 Y5 Z5; do
        printf 'package ovl;\npublic class %s { }\n' $t | jfile ovl/$t.java
    done
    printf 'package ovl;\npublic class C1 {\n    public C1(Y y) { }\n    public C1(Z z) { }\n}\n' | jfile ovl/C1.java
    printf 'package ovl;\npublic class S {\n    public void m(Z z) { }\n}\n' | jfile ovl/S.java
    printf 'package ovl;\npublic class X extends S {\n    public void m(Y y) { }\n}\n' | jfile ovl/X.java
    printf 'package ovl;\npublic class S2 {\n    public void q(Y2 y) { }\n    public void q(Z2 z) { }\n}\n' | jfile ovl/S2.java
    printf 'package ovl;\npublic class St {\n    public static void s(Y3 y) { }\n    public static void s(Z3 z) { }\n}\n' \
        | jfile ovl/St.java
    printf 'package ovl;\npublic class S3 {\n    public void r(Y4 y) { }\n    public void r(Z4 z) { }\n}\n' | jfile ovl/S3.java
    printf 'package ovl;\npublic class S5 {\n    public S5(Y5 y) { }\n    public S5(Z5 z) { }\n}\n' | jfile ovl/S5.java
    printf 'package ovu;\npublic class U1 {\n    void go() { new ovl.C1(null); }\n}\n' | jfile ovu/U1.java
    printf 'package ovu;\npublic class U2 {\n    void go(ovl.X x) { x.m(null); }\n}\n' | jfile ovu/U2.java
    printf 'package ovu;\nimport static ovl.St.s;\npublic class U3 {\n    void go() { s(null); }\n}\n' | jfile ovu/U3.java
    printf 'package ovu;\npublic class V extends ovl.S2 {\n    void go() { q(null); }\n}\n' | jfile ovu/V.java
    printf 'package ovu;\npublic class W extends ovl.S3 {\n    void go() { super.r(null); }\n}\n' | jfile ovu/W.java
    printf 'package ovu;\npublic class W2 extends ovl.S5 {\n    W2() { super(null); }\n}\n' | jfile ovu/W2.java
    # ラムダを渡す呼び出し。候補の関数型インターフェース Fn の形が変わると、どちらの k が選ばれるか（曖昧か）が変わる
    printf 'package ovl;\npublic interface Fn { void apply(); }\n' | jfile ovl/Fn.java
    printf 'package ovl;\npublic class K {\n    public void k(Runnable r) { }\n    public void k(Fn f) { }\n}\n' | jfile ovl/K.java
    printf 'package ovu;\npublic class L {\n    void go(ovl.K k) { k.k(() -> { }); }\n}\n' | jfile ovu/L.java
}
case_of "選ばれなかったオーバーロードの引数の型を消す（new・継承・単純名・static import・super）" \
    "rm work/src/ovl/Z.java work/src/ovl/Z2.java work/src/ovl/Z3.java work/src/ovl/Z4.java work/src/ovl/Z5.java" \
    yes setup_overload
case_of "ラムダを渡す呼び出しの、選ばれなかった候補の関数型インターフェースの形を変える" \
    "printf 'package ovl;\npublic interface Fn { void apply(int x); }\n' > work/src/ovl/Fn.java" yes setup_overload

# 親型の本体だけを変える。部分型 Sub も変わった型になり、Sub だけを参照する User（s.n() の n は Sub の宣言）も
# 解析し直す（何が変わったかは見ない）。以前は型の形の指紋で絞り、User を再利用していた（件数を 2 と見ていた）が、
# 絞り方で取りこぼしが続いたのでやめた。差分更新が全件解析と同じであることだけを見る
# （docs/cache-unification-qa.md の Q44・Q77）
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
case_of "親型のメソッドの本体だけを変える" edit_body_only yes setup_body_only

# --- 型の形をやめたあとのレビューで見つかった取りこぼし（docs/cache-unification-qa.md の Q83〜Q87）--------
# どれも 1cba882 では「差分更新と全件解析が違う」で落ちる

# 中身の変わっていないファイルの宣言が変わる。p.X は import q.* の Foo を宣言に使っていたが、同じパッケージに足した
# p.Foo に隠される（JLS 6.4.1）。X は新しい p.Foo の単純名を I 行に持つので解析し直すが、中身は変わっていないので、
# 以前は定数の値が変わったときにしか X の型を変わった型にせず、X だけを使う r.U（q.Foo を渡す x.m(f)・x.f.run()・
# x.get().run()）を解析し直さなかった。今は自分の宣言の指紋（宣言の鍵と修飾子と定数の値。I 行の 3 列目）が
# 前回と違えば変わった型にする（Q83）。戻り値の型は H・D・V 行に無い（D 行はアノテーションの付いたメソッドにだけ
# 戻り値の型を書く）ので、行から作る指紋では拾えない
setup_shadow_decl() {
    printf 'package q;\npublic class Foo { public void run() { } }\n' | jfile q/Foo.java
    jfile p/X.java <<'EOF'
package p;
import q.*;
public class X {
    public Foo f;
    public void m(Foo foo) { System.out.println("foo"); }
    public void m(Object o) { System.out.println("obj"); }
    public Foo get() { return null; }
}
EOF
    printf 'package r;\npublic class U1 { void go(p.X x, q.Foo f) { x.m(f); } }\n' | jfile r/U1.java
    printf 'package r;\npublic class U2 { void go(p.X x) { x.f.run(); } }\n' | jfile r/U2.java
    printf 'package r;\npublic class U3 { void go(p.X x) { x.get().run(); } }\n' | jfile r/U3.java
}
case_of "同じパッケージに足した型が、中身の変わっていないファイルの宣言の型を隠す（引数・フィールド・戻り値）" \
    "printf 'package p;\npublic class Foo { public void run() { System.out.println(); } }\n' > work/src/p/Foo.java" \
    yes setup_shadow_decl

# jar の型の親（別の jar の型）にメソッドを足す。l1.Mid（l1.jar）の親 l2.Top（l2.jar）に m(String) を足すと、
# app.U の s.m("x") の解決先が変わる。
#   jar2a: ソースの app.Sub extends l1.Mid。Sub の H 行には直接の親 l1.Mid しか無く、l2 のパッケージに当たらない。
#          Sub は l2.Top を I 行に持つので jar の変化で解析し直すが、以前は中身が変わっていなければ Sub の型を
#          変わった型にしなかったので、Sub だけを使う U を解析し直さなかった。今は jar の変化で解析し直したファイルの型は
#          変わった型にする（Q84）
#   jar2b: U が jar の型 l1.Mid を直接使う。以前は U の I 行に l1.Mid しか無く、l2 のパッケージに当たらなかった。
#          今は jar の型の推移的な親型も I 行に数える（Q86）
make_chain_jars() {   # $1 = l2.Top の本体。l1.jar は最初の 1 回だけ作る（変わるのは l2.jar だけ）
    rm -rf jarchain && mkdir -p jarchain/src/l1 jarchain/src/l2 jarchain/c1 jarchain/c2 work/lib
    printf 'package l2;\npublic class Top { %s }\n' "$1" > jarchain/src/l2/Top.java
    printf 'package l1;\npublic class Mid extends l2.Top { public void m(Object o) { } }\n' > jarchain/src/l1/Mid.java
    "$JAVAC_BIN" -d jarchain/c2 jarchain/src/l2/Top.java \
        && ( cd jarchain/c2 && "$JAR_BIN" cf ../../work/lib/l2.jar l2 ) \
        || { echo "  NG   jar を作れませんでした"; fail=1; }
    if [ ! -f work/lib/l1.jar ]; then
        "$JAVAC_BIN" -cp jarchain/c2 -d jarchain/c1 jarchain/src/l1/Mid.java \
            && ( cd jarchain/c1 && "$JAR_BIN" cf ../../work/lib/l1.jar l1 ) \
            || { echo "  NG   jar を作れませんでした"; fail=1; }
    fi
    rm -rf jarchain
}
setup_jar_chain_sub() {
    make_chain_jars ''
    printf 'package app;\npublic class Sub extends l1.Mid { }\n' | jfile app/Sub.java
    printf 'package app;\npublic class U { public void go(Sub s) { s.m("x"); } }\n' | jfile app/U.java
}
setup_jar_chain_direct() {
    make_chain_jars ''
    printf 'package app;\npublic class U { public void go(l1.Mid s) { s.m("x"); } }\n' | jfile app/U.java
}
# jar の中身の更新時刻が同じ秒に収まっても指紋（中のクラスの一覧と内容）で変化を見るので、待たない
case_of "jar の親の親（別の jar）にメソッドを足す（ソースの部分型を使うファイル）" \
    "make_chain_jars 'public void m(String s) { }'" yes setup_jar_chain_sub
case_of "jar の型の親（別の jar）にメソッドを足す（jar の型を使うファイル）" \
    "make_chain_jars 'public void m(String s) { }'" yes setup_jar_chain_direct

# jar の変化とソースの変化の両方に触れるファイル。Sub は I 行に l2.Top（jar）と、同じ実行で変わった
# ソースの型（app.T・パッケージ util・コンパイルエラーのあるファイルの app.X）の両方を持つ。以前は選ばれた理由が
# ソースの側になり、jar の変化による連鎖（Q84）が付かず、Sub だけを使う U を解析し直さなかった。今は選ばれた
# 理由に依らず、旧キャッシュで変わった jar に触れていたファイルの型を変わった型にする（Q89）。
# Sub が m(Object) を上書きしているので、U の呼び出しは Sub.m に結び付き、U の I 行は jar に触れない
setup_jar_chain_and_source() {
    make_chain_jars ''
    printf 'package app;\npublic class T { }\n' | jfile app/T.java
    printf 'package app;\npublic class Sub extends l1.Mid { T t; public void m(Object o) { } }\n' | jfile app/Sub.java
    printf 'package app;\npublic class U { public void go(Sub s) { s.m("x"); } }\n' | jfile app/U.java
}
setup_jar_chain_and_star() {
    make_chain_jars ''
    printf 'package util;\npublic class Other { }\n' | jfile util/Other.java
    printf 'package app;\nimport util.*;\npublic class Sub extends l1.Mid { public void m(Object o) { } }\n' | jfile app/Sub.java
    printf 'package app;\npublic class U { public void go(Sub s) { s.m("x"); } }\n' | jfile app/U.java
}
setup_jar_chain_and_error() {
    make_chain_jars ''
    printf 'package app;\npublic class X { public static void x() { } zz.Missing broken; }\n' | jfile app/X.java
    printf 'package app;\npublic class Sub extends l1.Mid { public void m(Object o) { X.x(); } }\n' | jfile app/Sub.java
    printf 'package app;\npublic class U { public void go(Sub s) { s.m("x"); } }\n' | jfile app/U.java
}
case_of "jar の親の親の変化と、同じファイルが使うソースの型の変化が重なる" \
    "make_chain_jars 'public void m(String s) { }' && printf '\n// comment only\n' >> work/src/app/T.java" \
    yes setup_jar_chain_and_source
case_of "jar の親の親の変化と、同じファイルのオンデマンド import のパッケージの変化が重なる" \
    "make_chain_jars 'public void m(String s) { }' && printf '\n// comment only\n' >> work/src/util/Other.java" \
    yes setup_jar_chain_and_star
case_of "jar の親の親の変化だけ（同じファイルがコンパイルエラーのあるファイルを使う）" \
    "make_chain_jars 'public void m(String s) { }'" yes setup_jar_chain_and_error

# 型引数にだけ現れる型の親を変える。Foo extends Bar をやめると、U1〜U4 の解決が変わる。どのファイルのソースにも
# Foo は無い（式の型の型引数 List<Foo>・Box<Foo> と、ArrayList<Foo> の add(int, E) の E）。以前は式の型を消去で
# 数えていたので List・Box しか I 行に無く、java.* の型が宣言する候補（ArrayList の add）は見ていなかった。
# 今は式と型の節の型の型引数も数え、呼び出しの候補は型引数を付けたまま辿る（java.* の型の候補は、型引数を
# 置き換えた java.* でない引数の型だけ）（Q85）
setup_type_args() {
    printf 'package tya;\npublic class Bar { }\n' | jfile tya/Bar.java
    printf 'package tya;\npublic class Foo extends Bar { }\n' | jfile tya/Foo.java
    printf 'package tya;\npublic class A {\n    public java.util.List<Foo> foos() { return null; }\n    public Box<Foo> box() { return null; }\n}\n' \
        | jfile tya/A.java
    jfile tya/X.java <<'EOF'
package tya;
public class X {
    public void m(java.util.Collection<? extends Bar> c) { System.out.println("coll"); }
    public void m(Object o) { System.out.println("obj"); }
}
EOF
    jfile tya/Box.java <<'EOF'
package tya;
public class Box<T> {
    public void put(T t) { System.out.println("t"); }
    public void put(Bar b) { System.out.println("bar"); }
}
EOF
    jfile tya/S.java <<'EOF'
package tya;
public class S extends java.util.ArrayList<Foo> {
    public void add(int i, Bar b) { System.out.println("bar"); }
}
EOF
    printf 'package tyu;\npublic class U1 { void go(tya.X x, tya.A a) { x.m(a.foos()); } }\n' | jfile tyu/U1.java
    printf 'package tyu;\npublic class U2 { void go(tya.A a) { a.box().put(null); } }\n' | jfile tyu/U2.java
    printf 'package tyu;\npublic class U3 { void go(tya.A a) { for (tya.Bar b : a.foos()) { System.out.println(b); } } }\n' \
        | jfile tyu/U3.java
    printf 'package tyu;\npublic class U4 { void go(tya.S s) { s.add(0, null); } }\n' | jfile tyu/U4.java
}
case_of "型引数にだけ現れる型の親を変える（式の型の型引数・型引数を置き換えた候補・java.* の型の候補）" \
    "sed -i 's/ extends Bar//' work/src/tya/Foo.java" yes setup_type_args

# jar のパッケージ a.b と同じ名前の型 a.b（パッケージ a のクラス b）をソースに足す。u.U の a.b.C.m() は jar の a.b.C の
# 呼び出しからエラーに変わる（JLS 6.5.2・7.1）。U の I 行には a.b.C があるが、足した型 a.b は I 行のどの型とも
# 一致しなかった。今は I 行の型の名前の頭の部分が変わった型でも触れているとみなす（Q87）。
# a.b.C がソースにあると、パッケージ a.b のファイル自身のエラー（「パッケージが型と衝突する」）は同じバッチで先に a.b を
# 解析したときにしか出ない（JDT の振る舞い。解析し直しても出ない）ので、題材は jar に置く
setup_pkg_type() {
    rm -rf jarpkg && mkdir -p jarpkg/src/a/b jarpkg/classes work/lib
    printf 'package a.b;\npublic class C { public static void m() { } }\n' > jarpkg/src/a/b/C.java
    "$JAVAC_BIN" -d jarpkg/classes jarpkg/src/a/b/C.java \
        && ( cd jarpkg/classes && "$JAR_BIN" cf ../../work/lib/ab.jar a ) \
        || { echo "  NG   jar を作れませんでした"; fail=1; }
    rm -rf jarpkg
    printf 'package a;\npublic class A0 { }\n' | jfile a/A0.java
    printf 'package u;\npublic class U { void go() { a.b.C.m(); } }\n' | jfile u/U.java
}
case_of "jar のパッケージ a.b と同じ名前の型 a.b をソースに足す" \
    "printf 'package a;\npublic class b { }\n' > work/src/a/b.java" yes setup_pkg_type

# --- 3 回目のレビューで見つかった、I 行と型の形に載っていなかった依存 ---------------------
# どれも 52453ae では「差分更新と全件解析が違う」で落ちる（docs/cache-unification-qa.md の Q51〜Q54）。
# 型の形はやめた（Q77）が、同じ書き換えで差分更新が全件解析と同じであることを見続ける

# 祖父母の型のメソッドを可変長引数にする。m(int[]) と m(int...) はキーが同じなので、型の形にも入っていなかった。
# U の d.m(1, 2) は D#m(long) の BINDING_FAILED から F#m(int[]) への呼び出しに変わる（JLS 15.12.2.4）
setup_varargs() {
    jfile vag/F.java <<'EOF'
package vag;
public class F {
    public void m(int[] a) { System.out.println(a); }
}
EOF
    jfile vag/D.java <<'EOF'
package vag;
public class D extends F {
    public void m(long x) { System.out.println(x); }
}
EOF
    jfile vag/U.java <<'EOF'
package vag;
public class U {
    public void go(D d) { d.m(1, 2); }
}
EOF
}
edit_varargs() { sed -i 's/int\[\] a/int... a/' work/src/vag/F.java; }
case_of "祖父母の型のメソッドを可変長引数にする" edit_varargs yes setup_varargs

# 拡張 for 文の式の型（a.getRepo() の Repo）を Iterable にする。Repo の名前は U のソースに無い。
# 全件解析では iterator() / hasNext() / next() の暗黙の呼び出しが増え、エラーが消える（JLS 14.14.2）
setup_foreach_type() {
    jfile fex/Repo.java <<'EOF'
package fex;
public class Repo { }
EOF
    jfile fex/A.java <<'EOF'
package fex;
public class A {
    public Repo getRepo() { return new Repo(); }
}
EOF
    jfile fex/U.java <<'EOF'
package fex;
public class U {
    public void go(A a) {
        for (Object s : a.getRepo()) { System.out.println(s); }
    }
}
EOF
}
edit_foreach_type() {
    jfile fex/Repo.java <<'EOF'
package fex;
public class Repo implements Iterable<Object> {
    public java.util.Iterator<Object> iterator() { return java.util.List.<Object>of().iterator(); }
}
EOF
}
case_of "拡張 for 文の式の型を Iterable にする" edit_foreach_type yes setup_foreach_type

# switch のセレクタの列挙型に定数を足す。s.kind() の型 Kind は U のソースに無い。case C のエラーが消える
setup_switch_enum() {
    jfile swe/Kind.java <<'EOF'
package swe;
public enum Kind { A, B }
EOF
    jfile swe/Src.java <<'EOF'
package swe;
public class Src { public Kind kind() { return Kind.A; } }
EOF
    jfile swe/U.java <<'EOF'
package swe;
public class U {
    public void go(Src s) {
        switch (s.kind()) { case C: System.out.println(1); break; default: System.out.println(2); }
    }
}
EOF
}
edit_switch_enum() { sed -i 's/A, B/A, B, C/' work/src/swe/Kind.java; }
case_of "switch のセレクタの列挙型に定数を足す" edit_switch_enum yes setup_switch_enum

# 呼び出し先の throws の型・throw する式の型を検査例外にする。どちらも U のソースに名前が無い。
# 全件解析では U に「例外を処理していない」エラーが出る（JLS 11.2）
setup_checked() {
    jfile chk/MyEx.java <<'EOF'
package chk;
public class MyEx extends RuntimeException { }
EOF
    jfile chk/D.java <<'EOF'
package chk;
public class D {
    public void m() throws MyEx { System.out.println(1); }
    public MyEx make() { return new MyEx(); }
}
EOF
    jfile chk/U.java <<'EOF'
package chk;
public class U {
    public void go(D d) { d.m(); }
    public void th(D d) { throw d.make(); }
}
EOF
}
edit_checked() { sed -i 's/extends RuntimeException/extends Exception/' work/src/chk/MyEx.java; }
case_of "例外の型を検査例外にする（throws と throw）" edit_checked yes setup_checked

# 引数・ローカル変数に付けたアノテーションの型を消す。アノテーションの型の名前は Name の節なので、
# 型の名前の節（SimpleType）では拾えていなかった
setup_ann_use() {
    jfile anu/Ann.java <<'EOF'
package anu;
public @interface Ann { }
EOF
    jfile anu/Loc.java <<'EOF'
package anu;
public @interface Loc { }
EOF
    jfile anu/U.java <<'EOF'
package anu;
public class U {
    public void m(@Ann String s) { System.out.println(s); }
}
EOF
    jfile anu/V.java <<'EOF'
package anu;
public class V {
    public void m() { @Loc String s = "x"; System.out.println(s); }
}
EOF
}
edit_ann_use() { rm work/src/anu/Ann.java work/src/anu/Loc.java; }
case_of "引数・ローカル変数のアノテーションの型を消す" edit_ann_use yes setup_ann_use

# 見えなかった型を public にする。完全修飾名で参照していた U は「The type vis.Hidden is not visible」で
# 型解決に失敗していて、I 行には vis.Hidden が無い。新しい型ではないので、新しい型の名前との突き合わせにも
# 当たらなかった。変わった型の名前と突き合わせる（Q53）
setup_visibility() {
    jfile vis/Hidden.java <<'EOF'
package vis;
class Hidden { public static void run() { System.out.println(1); } }
EOF
    jfile vis/Pub.java <<'EOF'
package vis;
public class Pub { }
EOF
    jfile vit/U.java <<'EOF'
package vit;
public class U {
    public void go() { vis.Hidden.run(); }
}
EOF
    jfile vit/U2.java <<'EOF'
package vit;
import vis.Hidden;
public class U2 {
    public void go() { Hidden.run(); }
}
EOF
}
edit_visibility() { sed -i 's/^class Hidden/public class Hidden/' work/src/vis/Hidden.java; }
case_of "見えなかった型を public にする（完全修飾名と import）" edit_visibility yes setup_visibility

# 同じパッケージの型を jar に足す。jsp.Main の Helper は import jsq.* の jsq.Helper だったが、jar の
# jsp.Helper（同じパッケージ）に隠される（JLS 6.4.1）。jar の型は新しい型として数えられないので、
# 自分のパッケージが変わった jar のパッケージなら、オンデマンド import を持つブロックを解析し直す（Q54）
setup_jar_split() {
    mkdir -p work/lib
    jfile jsq/Helper.java <<'EOF'
package jsq;
public class Helper { public static void work() { System.out.println(1); } }
EOF
    jfile jsp/Main.java <<'EOF'
package jsp;
import jsq.*;
public class Main {
    void go() { Helper.work(); }
}
EOF
}
edit_jar_split() {
    rm -rf jarsplit && mkdir -p jarsplit/src/jsp jarsplit/classes
    cat > jarsplit/src/jsp/Helper.java <<'EOF'
package jsp;
public class Helper { public static void work() { } }
EOF
    "$JAVAC_BIN" -d jarsplit/classes jarsplit/src/jsp/Helper.java \
        && ( cd jarsplit/classes && "$JAR_BIN" cf ../../work/lib/split.jar jsp ) \
        || { echo "  NG   jar を作れませんでした"; fail=1; }
    rm -rf jarsplit
}
case_of "同じパッケージの型を jar に足す（import q.* を隠す）" edit_jar_split yes setup_jar_split

# sealed の permits に部分型を足す。U の switch は A1・A2・B で網羅的だったが、A が A3 も許すと網羅的でなくなる
# （JLS 14.11.1.1）。permits はバインディングから取れないので、case に書いた型の親（A）を U の依存に数える
setup_sealed() {
    jfile sld/S.java <<'EOF'
package sld;
public sealed interface S permits A, B { }
EOF
    jfile sld/A.java <<'EOF'
package sld;
public sealed interface A extends S permits A1, A2 { }
EOF
    jfile sld/A1.java <<'EOF'
package sld;
public record A1() implements A { }
EOF
    jfile sld/A2.java <<'EOF'
package sld;
public record A2() implements A { }
EOF
    jfile sld/B.java <<'EOF'
package sld;
public record B() implements S { }
EOF
    jfile sld/U.java <<'EOF'
package sld;
public class U {
    public int go(S s) {
        return switch (s) { case A1 a -> 1; case A2 a -> 2; case B b -> 3; };
    }
}
EOF
}
edit_sealed() {
    sed -i 's/permits A1, A2/permits A1, A2, A3/' work/src/sld/A.java
    jfile sld/A3.java <<'EOF'
package sld;
public record A3() implements A { }
EOF
}
case_of "sealed の permits に部分型を足す（switch の網羅性）" edit_sealed yes setup_sealed

# --- 親型の私的メンバー（docs/cache-unification-qa.md の Q51・Q77）--------------------------------
# 親型に足した私的メンバーのうち、親の親のメンバーと名前の当たるものは、部分型から親のメンバーを隠す・継承を止めるので、
# 部分型 C だけを参照する X の解決が変わる（X は P を参照していない）。以前は型の形にこれらの私的メンバーだけを入れて
# 連鎖させていた。今は P が変われば部分型 C も変わった型になるので、何を足しても X を解析し直す。
# 題材: G <- P <- C（別ファイル）、X は別のパッケージ
setup_private_base() {
    jfile prv/G.java <<'EOF'
package prv;
public class G {
    public int f;
    public static class Inner { public static void run() { System.out.println("gi"); } }
    public void m() { System.out.println("gm"); }
    public static void s(long x) { System.out.println("gs"); }
}
EOF
    jfile prv/P.java <<'EOF'
package prv;
public class P extends G {
    public P() { }
}
EOF
    jfile prv/C.java <<'EOF'
package prv;
public class C extends P { }
EOF
}
private_add() {   # $1=P のコンストラクタの次に足す行
    sed -i "s/^    public P() { }/    public P() { }\n    $1/" work/src/prv/P.java
}

# (a) 私的フィールドが親のフィールドを隠す（JLS 8.3）。X の c.f は G.f からエラーに変わる
setup_private_field() {
    setup_private_base
    jfile prw/X.java <<'EOF'
package prw;
public class X { public int go(prv.C c) { System.out.println(c.f); return c.f; } }
EOF
}
case_of "親に、親の親のフィールドと同じ名前の私的フィールドを足す（隠蔽）" \
    "private_add 'private int f;'" yes setup_private_field

# (b) 私的な入れ子の型が親の入れ子の型を隠す（JLS 8.5）。X の C.Inner.run() は G.Inner からエラーに変わる
setup_private_type() {
    setup_private_base
    jfile prw/X.java <<'EOF'
package prw;
public class X { public void go() { prv.C.Inner.run(); } }
EOF
}
case_of "親に、親の親の入れ子の型と同じ名前の私的な入れ子の型を足す（隠蔽）" \
    "private_add 'private static class Inner { static void run() { } }'" yes setup_private_type

# (c) 私的メソッドが親の同じシグネチャのメソッドの継承を止める（JLS 8.4.8。P はエラー）。
# (d) 私的な static メソッドと、static import した名前
setup_private_method() {
    setup_private_base
    jfile prw/X.java <<'EOF'
package prw;
import static prv.C.s;
public class X { public void go(prv.C c) { c.m(); s(1); } }
EOF
}
case_of "親に、親の親のメソッドと同じ名前の私的メソッド・static メソッドを足す" \
    "private_add 'private void m() { }\n    private static void s(int x) { }'" yes setup_private_method

# 型解決に失敗している利用者。JDT はエラーを回復するとき、見えない私的メンバーにもバインディングを返すので、
# X の c.helper2() は BINDING_FAILED から P#helper2 への呼び出しに変わる。以前は型の形に入らない（名前が当たらない）
# ので、型解決に失敗したファイルに参照した型の親（P）を依存として持たせていた。今は P の部分型 C が変わった型になる
setup_private_failing() {
    setup_private_base
    jfile prw/X.java <<'EOF'
package prw;
public class X { public int go(prv.C c) { c.helper2(); return c.extra; } }
EOF
}
case_of "型解決に失敗している利用者の、参照した型の親に私的メンバーを足す" \
    "private_add 'private int extra;\n    private void helper2() { }'" yes setup_private_failing

# (e)(f) 名前の当たらない私的メンバー（フィールド・メソッド・入れ子の型・static・コンストラクタ）を足しても、
# 外側のクラスのメンバーへの解決（JLS 6.4.1・15.12.1）、部分型の同じ名前のメソッド（上書きにも隠蔽にも
# ならない）、同じファイルの入れ子のクラスからの私的メンバーの参照は、全件解析と同じになる。
# 以前は C だけを参照する Outer・Z へは連鎖しないこと（解析し直すのは P・C・W の 3 件）も見ていたが、今は C も
# 変わった型になるので 5 件を解析し直す（Q77）。差分更新が全件解析と同じであることだけを見る
setup_private_unrelated() {
    jfile prz/P.java <<'EOF'
package prz;
public class P {
    public P() { }
    private int secret() { return 1; }
    public static class Peer { public int peek(P p) { return p.secret(); } }
}
EOF
    jfile prz/C.java <<'EOF'
package prz;
public class C extends P { }
EOF
    jfile prw/Outer.java <<'EOF'
package prw;
public class Outer {
    int f = 1;
    void m() { System.out.println("om"); }
    static class Inner { static void run() { System.out.println("oi"); } }
    class Y extends prz.C {
        int go() { m(); Inner.run(); return f; }
    }
    void anon() { new prz.C() { void h() { m(); Inner.run(); System.out.println(f); } }.h(); }
}
EOF
    jfile prw/Z.java <<'EOF'
package prw;
public class Z extends prz.C {
    public void h() { System.out.println("zh"); }
    static void h2() { System.out.println("zh2"); }
    void h4() { System.out.println("zh4"); }
    public static void use(Z z) { z.h(); h2(); z.h4(); }
}
EOF
    jfile prw/W.java <<'EOF'
package prw;
public class W { public void go() { new prz.C(); new Z().h(); Z.use(null); new prz.P.Peer().peek(null); } }
EOF
}
edit_private_unrelated() {
    sed -i 's/^    public P() { }/    public P() { }\n    private int f;\n    private void m() { }\n    private static class Inner { static void run() { } }\n    private void h() { }\n    private void h2() { }\n    private static void h4() { }\n    private P(int x) { }/' work/src/prz/P.java
    sed -i 's/return 1;/return Integer.parseInt("2");/' work/src/prz/P.java
}
case_of "名前の当たらない私的メンバーを親に足す" edit_private_unrelated yes setup_private_unrelated

# (g)(h) 私的なインターフェースのメソッド（Java 9）。親インターフェースの default メソッドと同じシグネチャなら
# 継承を止める（JLS 9.4.1）。実装するのはレコードと列挙型（暗黙の私的メンバーを持つ）
setup_private_iface() {
    jfile pri/I1.java <<'EOF'
package pri;
public interface I1 { default void m() { System.out.println("i1"); } }
EOF
    jfile pri/I2.java <<'EOF'
package pri;
public interface I2 extends I1 { }
EOF
    jfile pri/K.java <<'EOF'
package pri;
public record K(int a) implements I2 { }
EOF
    jfile pri/E.java <<'EOF'
package pri;
public enum E implements I2 { A }
EOF
    jfile prw/X.java <<'EOF'
package prw;
public class X { public void go(pri.K k) { k.m(); pri.E.A.m(); System.out.println(k.a()); } }
EOF
}
edit_private_iface() {   # $1=足す私的メソッドの名前
    sed -i "s/^public interface I2 extends I1 { }/public interface I2 extends I1 { private void $1() { } }/" \
        work/src/pri/I2.java
}
case_of "親インターフェースの default メソッドと同じ名前の私的メソッドを足す（レコード・列挙型）" \
    "edit_private_iface m" yes setup_private_iface
# 名前の当たらない私的メソッド。以前は実装する型の利用者（X）へは連鎖しないこと（I2・K・E の 3 件）も見ていた（Q77）
case_of "名前の当たらない私的なインターフェースのメソッドを足す" "edit_private_iface helper" yes setup_private_iface

# 私的メンバーが隠すのは、java.* の親型のさらに上のメンバーでもよい（docs/cache-unification-qa.md の Q65）。
# Registry extends HashMap の私的な入れ子の型 Entry は、HashMap ではなく Map が宣言する Map.Entry を隠す（JLS 8.5）。
# 部分型 Client の単純名 Entry は、継承した Map.Entry から同じパッケージの pjd.Entry に変わる。Client は Registry を
# 参照していない（NamedRegistry を通してしか届かない）。01eb510 は最初の java.* の親型（HashMap）のメンバーの
# 名前しか数えず、この Entry を型の形から外していたので、差分更新だけ Map.Entry#getKey のまま残った。今は Registry の
# 部分型（NamedRegistry・Client）がどれも変わった型になる（Q77）
setup_jdk_hide() {   # $1=Registry に最初から置く行（空なら置かない）
    jfile pjd/Registry.java <<EOF
package pjd;
public class Registry extends java.util.HashMap<String, Object> {
$1
}
EOF
    jfile pjd/NamedRegistry.java <<'EOF'
package pjd;
public class NamedRegistry extends Registry { }
EOF
    jfile pjd/Entry.java <<'EOF'
package pjd;
public class Entry {
    public Object getKey() { return audit(); }
    static Object audit() { return null; }
}
EOF
    jfile pjd/Client.java <<'EOF'
package pjd;
public class Client extends NamedRegistry {
    public static void main(String[] args) {
        Object o = args;
        ((Entry) o).getKey();
    }
}
EOF
}
case_of "java.* の親型の上（Map.Entry）を隠す私的な入れ子の型を足す" \
    "sed -i 's/^\$/    private static final class Entry { }/' work/src/pjd/Registry.java" yes "setup_jdk_hide ''"
case_of "java.* の親型の上（Map.Entry）を隠していた私的な入れ子の型を消す" \
    "sed -i '/private static final class Entry/d' work/src/pjd/Registry.java" yes \
    "setup_jdk_hide '    private static final class Entry { }'"

# 同じく、HashMap の親の AbstractMap が宣言する AbstractMap.SimpleEntry を隠す（HashMap 自身は宣言していない）
setup_jdk_hide_simple() {
    jfile pjs/Base.java <<'EOF'
package pjs;
public class Base extends java.util.HashMap<String, Object> {
}
EOF
    jfile pjs/Mid.java <<'EOF'
package pjs;
public class Mid extends Base { }
EOF
    jfile pjs/SimpleEntry.java <<'EOF'
package pjs;
public class SimpleEntry {
    public SimpleEntry(Object k, Object v) { }
    public Object getKey() { return mark(); }
    static Object mark() { return null; }
}
EOF
    jfile pjs/Client.java <<'EOF'
package pjs;
public class Client extends Mid {
    public static void main(String[] args) {
        new SimpleEntry("k", "v").getKey();
    }
}
EOF
}
case_of "java.* の親型の上（AbstractMap.SimpleEntry）を隠す私的な入れ子の型を足す" \
    "sed -i 's/^}$/    private static class SimpleEntry { }\n}/' work/src/pjs/Base.java" yes setup_jdk_hide_simple

# --- 別のファイルからのフィールドへの書き込み（J 行は書いた側のブロックに載る） ------------------------
# private でないフィールドには、別のファイルの型（子クラスのコンストラクタなど）からも書ける。その J 行は書いた側の
# ブロックにあり、宣言した側（V 行）のブロックとは別。読み手（jche.graph.FieldFacts）はブロックをまたいで集めて、
# ソースが引数でない値を入れるフィールドを DI の段 5 で唯一の Bean に絞らない（docs/spring-di-qa.md の Q15）。
# 書き手のファイルを足す・書き換える・消すと、宣言した側のブロックは再利用のまま結論だけが変わる。全件解析と同じになること。
# inc.Main から ow.Svc.go を呼んで、call-hierarchy.csv にも結論（SPRING_DI か CHA か）が出るようにする
setup_other_writer() {
    sed -i 's/new Awkward().separators();/new Awkward().separators();\n        ow.Svc.entry();/' work/src/inc/Main.java
    jfile ow/Service.java <<'EOF'
package ow;

public @interface Service {
}
EOF
    jfile ow/Repository.java <<'EOF'
package ow;

public @interface Repository {
}
EOF
    jfile ow/Autowired.java <<'EOF'
package ow;

public @interface Autowired {
}
EOF
    jfile ow/Dao.java <<'EOF'
package ow;

public interface Dao {
    void find();
}
EOF
    jfile ow/DaoA.java <<'EOF'
package ow;

public class DaoA implements Dao {
    public void find() {
    }
}
EOF
    jfile ow/DaoB.java <<'EOF'
package ow;

@Repository
public class DaoB implements Dao {
    public void find() {
    }
}
EOF
    jfile ow/Svc.java <<'EOF'
package ow;

@Service
public class Svc {
    @Autowired
    protected Dao dao;

    public void go() {
        dao.find();
    }

    public static void entry() {
        new Svc().go();
    }
}
EOF
}
write_other_writer() {
    jfile ow/Sub.java <<'EOF'
package ow;

@Service
public class Sub extends Svc {
    public Sub() {
        this.dao = new DaoA();
    }
}
EOF
}
# $1=期待（cha=DaoA と DaoB の両方が出る / di=段 5 で DaoB に絞る）  $2=ラベル
other_writer_outcome() {
    local rows
    rows=$(grep -a '^at ow\.Svc\.go(' "$OUT/call-hierarchy.csv")
    if [ "$1" = cha ] && grep -q ',DaoA\.find,' <<< "$rows" && grep -q ',DaoB\.find,' <<< "$rows"; then
        echo "  OK   $2: Svc.go の dao.find() は DaoA・DaoB の両方が出る（よその書き込みを集めた）"
    elif [ "$1" = di ] && grep -q ',DaoB\.find,RESOLVED:SPRING_DI,' <<< "$rows" && ! grep -q ',DaoA\.find,' <<< "$rows"; then
        echo "  OK   $2: Svc.go の dao.find() は段 5 で DaoB に絞る（よその書き込みが無い）"
    else
        echo "  NG   $2: Svc.go の dao.find() の結論が期待（$1）と違います"; echo "$rows" | head -3; fail=1
    fi
}
case_of "別のファイルの子クラスが private でないフィールドに new を書く（書き手のファイルを足す）" \
    write_other_writer yes setup_other_writer
other_writer_outcome cha "書き手のファイルを足した"
case_of "別のファイルの子クラスが private でないフィールドに new を書く（書き込みを消す）" \
    "sed -i 's/this.dao = new DaoA();/super.go();/' work/src/ow/Sub.java" yes "setup_other_writer; write_other_writer"
other_writer_outcome di "書き込みを消した"
case_of "別のファイルの子クラスが private でないフィールドに new を書く（書き手のファイルを消す）" \
    "rm work/src/ow/Sub.java" yes "setup_other_writer; write_other_writer"
other_writer_outcome di "書き手のファイルを消した"

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
# 検査値が合わないので、ソースが変わったブロックと同じく、そのファイル（と、そのファイルの型を使うファイル）を
# 解析し直す（ほかのブロックは再利用する）。検査値が無ければ
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
    echo "== ブロックの中身が壊れていたら、そのファイル（と依存するファイル）だけ解析し直す =="
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

# --- キャッシュの健全性（同じフォルダを使う実行・解析のあいだの書き換え・ソースフォルダの並び・同じクラスが 2 つ） ---
# 作業フォルダは integrity/。検査用の小さなプログラム（tools/）もここにコンパイルする
IW=integrity
rm -rf $IW && mkdir -p $IW
if ! "$JAVAC_BIN" --release 17 -encoding UTF-8 -cp "$CP" -d $IW/tools \
        tools/*.java tools/jche/analysis/*.java 2> $IW/javac.log; then
    echo "  NG   検査用のプログラム（tools/）のコンパイルに失敗しました"; grep -v JAVA_TOOL_OPTIONS $IW/javac.log | head -5
    echo "FAIL"; exit 1
fi
TOOLS_CP="$IW/tools:$CP"

integrity_config() {   # $1=設定ファイル  $2=project.root  $3=source.folders  $4=キャッシュのフォルダ  $5=出力フォルダ
    cat > "$1" <<EOF
project.root=$2
source.folders=$3
library.folders=
library.build.tool=none
source.encoding=UTF-8
source.level=17
exclude.packages=java.**,javax.**
cache.enabled=true
cache.folder=$4
dataflow.enabled=true
output.encoding=UTF-8
output.folder=$5
EOF
}
# 解析を 1 回。終了コードを IRC に、出力フォルダを IOUT に、集計の再利用・新規解析の件数を IREUSED・IPARSED に入れる
integrity_run() {   # $1=設定ファイル  $2=ログ  （環境変数はそのまま渡る）
    "$JAVA_BIN" -Dstdout.encoding=UTF-8 -cp "$CP" jche.CallHierarchyExporter "$1" > "$2" 2>&1
    IRC=$?
    IOUT=$(ls -d "$(dirname "$1")"/out-"$(basename "$1" .properties)"/*/ 2>/dev/null | sort | tail -1 | sed 's#/$##')
    local summary
    summary=$(LC_ALL=C grep -a -E -m1 '=[0-9]+.*=[0-9]+.*=[0-9]+[[:space:]]*$' "$2")
    IREUSED=$(LC_ALL=C sed -E 's/^[^=]*=([0-9]+).*$/\1/' <<< "$summary")
    IPARSED=$(LC_ALL=C sed -E 's/^[^=]*=[0-9]+[^=]*=([0-9]+).*$/\1/' <<< "$summary")
}
# 設定ファイル（$1）と同じフォルダに、出力フォルダ out-<設定ファイル名>/ とキャッシュ cache/ を使う設定を書く
integrity_cfg() {   # $1=設定ファイル  $2=project.root  $3=source.folders  [$4=キャッシュのフォルダ]
    local d
    d=$(cd "$(dirname "$1")" && pwd)
    integrity_config "$1" "$2" "$3" "${4:-$d/cache}" "$d/out-$(basename "$1" .properties)"
}
same_csv() {   # $1=ラベル  $2=出力フォルダ  $3=比べる出力フォルダ
    local f
    for f in call-hierarchy.csv methods.csv; do
        if [ -s "$2/$f" ] && diff --strip-trailing-cr -q "$2/$f" "$3/$f" > /dev/null; then
            echo "  OK   $1 $f（全件解析と同じ）"
        else
            echo "  NG   $1 $f が全件解析と違います（または空）"
            diff --strip-trailing-cr "$2/$f" "$3/$f" 2>&1 | head -10; fail=1
        fi
    done
}
wait_for_file() {   # $1=ファイル。最大 60 秒待つ。できれば 0
    local i
    for ((i = 0; i < 600; i++)); do
        [ -e "$1" ] && return 0
        sleep 0.1
    done
    return 1
}

# 同じキャッシュのフォルダを使う実行（CLI と Eclipse・VS Code のプラグインの解析サーバー、同じフォルダを使う CI の
# ジョブ）は、フォルダの錠（jche.cache.CacheLock）で 1 つずつにする。以前は錠が無く、片方がもう片方の書きかけの
# 一時ファイル（analysis-cache.tsv.tmp）を引き継ぎに奪って本物に差し替え、呼び出しの 1 本も無い CSV を「成功」として
# 出すことがあった（docs/cache-unification-qa.md の Q56）
lock_case() {
    echo "== 同じキャッシュのフォルダを使う実行は 1 つずつ（錠） =="
    local d=$IW/lock
    rm -rf $d && mkdir -p $d && cp -r src $d/src
    integrity_cfg $d/a.properties "$PWD/$d" src
    integrity_cfg $d/b.properties "$PWD/$d" src "$PWD/$d/cache"
    integrity_cfg $d/full.properties "$PWD/$d" src "$PWD/$d/fullcache"
    integrity_run $d/a.properties $d/a0.log
    [ "$IRC" = 0 ] || { echo "  NG   最初の解析に失敗しました"; tail -5 $d/a0.log; fail=1; return; }
    local cache
    cache=$(ls $d/cache/*/analysis-cache.tsv)

    # (1) 待つ上限を過ぎたら、何を待っていたかを言って失敗する（錠なしで進めない）
    "$JAVA_BIN" -cp "$TOOLS_CP" LockHolder "$cache" 15000 $d/held1 > $d/holder1.log 2>&1 &
    local holder=$!
    wait_for_file $d/held1 || { echo "  NG   錠を持つ役のプログラムが錠を取れません"; cat $d/holder1.log; fail=1; return; }
    printf '\n// changed 1\n' >> $d/src/inc/Log.java
    JCHE_CACHE_LOCK_WAIT_SECONDS=1 integrity_run $d/a.properties $d/a1.log
    if [ "$IRC" != 0 ] && grep -q -F "kept using the cache folder" $d/a1.log \
            && [ -n "$IOUT" ] && grep -q -F "The run failed" "$IOUT/warnings.txt" 2>/dev/null; then
        echo "  OK   錠を持たれたまま待つ上限を過ぎたら、理由を言って失敗する（warnings.txt の「The run failed」）"
    else
        echo "  NG   錠を持たれているのに、待たずに進んだか、失敗の理由が出ていません（終了コード $IRC）"
        grep -a -E 'WARN|ERROR|cache\]' $d/a1.log | head -5; fail=1
    fi
    kill $holder 2>/dev/null; wait $holder 2>/dev/null

    # (2) ほかの実行が錠を放すまで待ち、放されたら最後まで進む
    "$JAVA_BIN" -cp "$TOOLS_CP" LockHolder "$cache" 3000 $d/held2 > $d/holder2.log 2>&1 &
    holder=$!
    wait_for_file $d/held2 || { echo "  NG   錠を持つ役のプログラムが錠を取れません"; cat $d/holder2.log; fail=1; return; }
    integrity_run $d/a.properties $d/a2.log
    wait $holder 2>/dev/null
    local waited_out=$IOUT
    if [ "$IRC" = 0 ] && grep -q -F "waiting until it finishes" $d/a2.log; then
        echo "  OK   ほかの実行が錠を持っているあいだは待ち、放されたら最後まで進む"
    else
        echo "  NG   錠を待っていません（終了コード $IRC）"; grep -a -E 'WARN|ERROR|cache\]' $d/a2.log | head -5; fail=1
    fi
    integrity_run $d/full.properties $d/full2.log
    same_csv "錠を待った実行" "$waited_out" "$IOUT"

    # (3) 2 つの実行を同時に始める（どちらも書き直す）。どちらも成功し、どちらの出力も全件解析と同じ
    printf '\n// changed 3\n' >> $d/src/inc/Log.java
    printf '\n// changed 3\n' >> $d/src/inc/Client.java
    "$JAVA_BIN" -Dstdout.encoding=UTF-8 -cp "$CP" jche.CallHierarchyExporter $d/a.properties > $d/a3.log 2>&1 &
    local pa=$!
    "$JAVA_BIN" -Dstdout.encoding=UTF-8 -cp "$CP" jche.CallHierarchyExporter $d/b.properties > $d/b3.log 2>&1 &
    local pb=$!
    wait $pa; local ra=$?
    wait $pb; local rb=$?
    rm -rf $d/fullcache
    integrity_run $d/full.properties $d/full3.log
    local full_out=$IOUT
    if [ "$ra" = 0 ] && [ "$rb" = 0 ]; then
        echo "  OK   同時に始めた 2 つの実行がどちらも成功する"
    else
        echo "  NG   同時に始めた実行が失敗しました（$ra / $rb）"; tail -n 3 $d/a3.log $d/b3.log; fail=1
    fi
    same_csv "同時に始めた実行 1" "$(ls -d $d/out-a/*/ | sort | tail -1 | sed 's#/$##')" "$full_out"
    same_csv "同時に始めた実行 2" "$(ls -d $d/out-b/*/ | sort | tail -1 | sed 's#/$##')" "$full_out"
    # 残ったキャッシュは壊れておらず、そのまま再利用できる
    integrity_run $d/a.properties $d/a4.log
    if [ "$IRC" = 0 ] && [ "$IPARSED" = 0 ] && ! grep -q -F "failed the integrity check" $d/a4.log; then
        echo "  OK   同時に始めた実行のあとのキャッシュは壊れておらず、全件再利用できる"
    else
        echo "  NG   同時に始めた実行のあとのキャッシュを再利用できません（新規解析=$IPARSED）"; fail=1
    fi
    same_csv "同時に始めた実行のあとの再利用" "$IOUT" "$full_out"
    local left
    left=$(find $d/cache -name '*.tmp' -o -name '*.partial')
    if [ -z "$left" ]; then
        echo "  OK   一時ファイルが残らない（錠のファイル analysis-cache.tsv.lock だけが残る）"
    else
        echo "  NG   一時ファイルが残っています: $left"; fail=1
    fi

    # (4) グラフの構築は、書き終えていない・別の版のキャッシュから組まない（黙って空の CSV を出さない）
    if "$JAVA_BIN" -cp "$TOOLS_CP" GraphCheck "$(ls $d/cache/*/analysis-cache.tsv)" $d/graph > $d/graph.log 2>&1; then
        grep -a -E '^  (OK|NG)' $d/graph.log
    else
        grep -a -E '^  (OK|NG)|Exception' $d/graph.log | head -10
        echo "  NG   グラフの構築が、書き終えていないキャッシュを受け付けました（test/incremental/$d/graph.log）"; fail=1
    fi
}
lock_case

# 解析のあいだに書き換えたソース。F 行の内容ハッシュは解析の前（パス1）に取るので、JDT が読む前に書き換えられると、
# 前の中身のハッシュと後の中身の事実が組になって残る。そのあとで元に戻すと、ハッシュが一致して古い事実を
# 再利用し続けていた（docs/cache-unification-qa.md の Q57）。書き換えは jche.analysis.EditDuringRunCheck が
# 決まった時点（そのファイルを含むバッチを JDT に渡す直前）で行う
edit_project() {   # $1=フォルダ
    mkdir -p "$1/src/e"
    cat > "$1/src/e/Main.java" <<'EOF'
package e;

public class Main {
    public static void main(String[] args) {
        new Worker().work();
    }
}
EOF
    cat > "$1/src/e/Worker.java" <<'EOF'
package e;

public class Worker {
    void work() {
        Helper.one();
    }
}
EOF
    cat > "$1/src/e/Helper.java" <<'EOF'
package e;

public class Helper {
    static void one() {
    }

    static void two() {
    }
}
EOF
}
edit_during_run_case() {   # $1=ラベル  $2=書き換えるファイル（src/e/ の名前）  $3=書き換えた後の中身
    echo "== 解析のあいだに書き換えたソース（$1） =="
    local d=$IW/edit
    rm -rf $d && mkdir -p $d && edit_project $d
    integrity_cfg $d/c.properties "$PWD/$d" src
    integrity_cfg $d/full.properties "$PWD/$d" src "$PWD/$d/fullcache"
    integrity_run $d/c.properties $d/c0.log
    [ "$IRC" = 0 ] || { echo "  NG   最初の解析に失敗しました"; tail -5 $d/c0.log; fail=1; return; }
    # Worker を変える（この実行で解析する）。解析の直前に $2 を $3 に書き換え、実行のあとで元に戻す
    sed -i 's/Helper.one();/Helper.one();\n        Helper.one();/' $d/src/e/Worker.java
    cp $d/src/e/$2 $d/before.java
    printf '%s\n' "$3" > $d/during.java
    "$JAVA_BIN" -Dstdout.encoding=UTF-8 -cp "$TOOLS_CP" jche.analysis.EditDuringRunCheck $d/c.properties \
        src/e/Worker.java $d/src/e/$2 $d/during.java > $d/c1.log 2>&1
    local rc=$?
    cp $d/before.java $d/src/e/$2
    if [ "$rc" != 0 ]; then
        echo "  NG   書き換えを挟んだ解析が失敗しました（終了コード $rc）"; grep -a -E 'NG|ERROR' $d/c1.log | head -5; fail=1; return
    fi
    if grep -q -F "changed while the analysis was running" $d/c1.log; then
        echo "  OK   解析のあいだに書き換えられたことをログに出す"
    else
        echo "  NG   解析のあいだに書き換えられたことがログに出ていません"; fail=1
    fi
    # 元に戻したソースで差分更新 -> 全件解析と同じであること（書き換えた中身の事実を再利用しない）
    integrity_run $d/c.properties $d/c2.log
    local inc_out=$IOUT
    if [ "$IRC" = 0 ] && [ "${IPARSED:-0}" -ge 1 ]; then
        echo "  OK   書き換えられたファイルを次の実行で解析し直した（新規解析=$IPARSED）"
    else
        echo "  NG   書き換えられたファイルを次の実行で解析し直していません（新規解析=${IPARSED:-?}）"; fail=1
    fi
    integrity_run $d/full.properties $d/full.log
    same_csv "解析のあいだに書き換えたソース（$1）" "$inc_out" "$IOUT"
    if diff -q <(normalized "$(ls $d/cache/*/analysis-cache.tsv)") \
               <(normalized "$(ls $d/fullcache/*/analysis-cache.tsv)") > /dev/null; then
        echo "  OK   解析のあいだに書き換えたソース（$1） キャッシュ（差分更新 == 全件解析）"
    else
        echo "  NG   解析のあいだに書き換えたソース（$1） キャッシュが全件解析と違います"
        diff <(normalized "$(ls $d/cache/*/analysis-cache.tsv)") \
             <(normalized "$(ls $d/fullcache/*/analysis-cache.tsv)") | head -10; fail=1
    fi
}
# 解析するファイルそのものを、JDT が読む前に書き換える（呼び出し先を Helper.two に）
edit_during_run_case "解析するファイル" Worker.java "$(printf 'package e;\n\npublic class Worker {\n    void work() {\n        Helper.two();\n    }\n}')"
# 解析するファイルが参照するファイル（再利用するブロック）を書き換える。JDT はソースパスから書き換え後の中身を読む
edit_during_run_case "参照されるファイル" Helper.java "$(printf 'package e;\n\npublic class Helper {\n    static void oneRenamed() {\n    }\n}')"

# 同じクラスが 2 つのソースフォルダにある。JDT は同じバッチの 2 つ目に「型が重複している」エラーを出してその型を
# 捨て、別々のバッチならどちらも読む。差分更新が片方だけを解析すると全件解析と事実が違っていた（Q61）。
# ソースフォルダの並びを入れ替えると、どちらのファイルがエラーになるかも変わる（Q58）
dup_project() {   # $1=フォルダ
    mkdir -p "$1/s1/p" "$1/s2/p"
    cat > "$1/s1/p/Main.java" <<'EOF'
package p;

public class Main {
    public static void main(String[] args) {
        new Dup().a();
        new Dup().b();
    }

    static void x() {
    }

    static void y() {
    }
}
EOF
    cat > "$1/s1/p/Dup.java" <<'EOF'
package p;

public class Dup {
    public void a() {
        Main.x();
    }
}
EOF
    cat > "$1/s2/p/Dup.java" <<'EOF'
package p;

public class Dup {
    public void b() {
        Main.y();
    }
}
EOF
}
dup_case() {
    echo "== 同じクラスが 2 つのソースフォルダにある =="
    local d=$IW/dup
    rm -rf $d && mkdir -p $d && dup_project $d
    integrity_cfg $d/c.properties "$PWD/$d" s1,s2
    integrity_cfg $d/full.properties "$PWD/$d" s1,s2 "$PWD/$d/fullcache"
    integrity_run $d/c.properties $d/c0.log
    [ "$IRC" = 0 ] || { echo "  NG   最初の解析に失敗しました"; tail -5 $d/c0.log; fail=1; return; }
    printf '\n// changed\n' >> $d/s2/p/Dup.java
    integrity_run $d/c.properties $d/c1.log
    local inc_out=$IOUT
    integrity_run $d/full.properties $d/full.log
    same_csv "片方だけを書き換えた差分更新" "$inc_out" "$IOUT"
    if diff -q <(normalized "$(ls $d/cache/*/analysis-cache.tsv)") \
               <(normalized "$(ls $d/fullcache/*/analysis-cache.tsv)") > /dev/null; then
        echo "  OK   片方だけを書き換えた差分更新 キャッシュ（差分更新 == 全件解析）"
    else
        echo "  NG   片方だけを書き換えた差分更新 キャッシュが全件解析と違います"
        diff <(normalized "$(ls $d/cache/*/analysis-cache.tsv)") \
             <(normalized "$(ls $d/fullcache/*/analysis-cache.tsv)") | head -10; fail=1
    fi
    # 重複を黙って通さない（2 つ目のファイルはコンパイルエラーとして warnings.txt に載る。全件解析でも差分更新でも）
    if grep -q -F -- "- s2/p/Dup.java" "$inc_out/warnings.txt" 2>/dev/null \
            && grep -q -F -- "- s2/p/Dup.java" "$IOUT/warnings.txt" 2>/dev/null; then
        echo "  OK   重複した型のファイルが warnings.txt に載る（差分更新でも全件解析でも）"
    else
        echo "  NG   重複した型のファイルが warnings.txt に載っていません"; fail=1
    fi
    # 型が重複しているエラーの引数には、ソースファイルの絶対パスが入る。解決できなかった名前（I 行の 2 列目）に
    # パスの途中のフォルダ名（home・incremental など）を拾うと、同じソースでも置き場所でキャッシュの事実が変わる
    # （docs/cache-unification-qa.md の Q64）
    local names part leaked=""
    names=$(awk -F'\t' '$1 == "F" { f = $2 } $1 == "I" && f == "s2/p/Dup.java" { print $3 }' \
        "$(ls $d/cache/*/analysis-cache.tsv)")
    for part in $(printf '%s' "$PWD/$d" | tr -c 'A-Za-z0-9_$' ' '); do
        case ",$names," in *",$part,"*) leaked="$leaked $part" ;; esac
    done
    if [ -n "$names" ] && [ -z "$leaked" ]; then
        echo "  OK   重複した型のファイルの解決できなかった名前（$names）に、置き場所のフォルダ名が入らない"
    else
        echo "  NG   重複した型のファイルの解決できなかった名前が空か、置き場所のフォルダ名を含みます（${names:-空}。${leaked# }）"
        fail=1
    fi

    echo "== ソースフォルダの並びを入れ替える =="
    integrity_cfg $d/c.properties "$PWD/$d" s2,s1
    integrity_cfg $d/full.properties "$PWD/$d" s2,s1 "$PWD/$d/fullcache2"
    integrity_run $d/c.properties $d/c2.log
    inc_out=$IOUT
    if [ "$IRC" = 0 ] && [ "$IREUSED" = 0 ] && grep -q -F "source folder order" $d/c2.log; then
        echo "  OK   並びを入れ替えたらキャッシュを再利用しない"
    else
        echo "  NG   並びを入れ替えたのにキャッシュを再利用しています（再利用=${IREUSED:-?}）"; fail=1
    fi
    integrity_run $d/full.properties $d/full2.log
    same_csv "ソースフォルダの並びを入れ替えた実行" "$inc_out" "$IOUT"
    # 入れ替えで解決先が実際に変わっていること（変わらなければ検査が素通りする）
    if diff -q "$inc_out/call-hierarchy.csv" "$(ls -d $d/out-c/*/ | sort | head -1)call-hierarchy.csv" > /dev/null; then
        echo "  NG   並びを入れ替えても出力が変わっていません（検査が素通りします）"; fail=1
    else
        echo "  OK   並びを入れ替えると出力が変わる"
    fi
}
dup_case

# 差分更新と全件解析で、CSV・warnings.txt・キャッシュ（ブロックの並べ替え後）が同じこと
same_all() {   # $1=ラベル  $2=差分更新の出力フォルダ  $3=全件解析の出力フォルダ  $4=差分更新のキャッシュのフォルダ  $5=全件解析のキャッシュのフォルダ
    same_csv "$1" "$2" "$3"
    if diff -q <(cat "$2/warnings.txt" 2>/dev/null) <(cat "$3/warnings.txt" 2>/dev/null) > /dev/null; then
        echo "  OK   $1 warnings.txt（全件解析と同じ）"
    else
        echo "  NG   $1 warnings.txt が全件解析と違います"
        diff <(cat "$2/warnings.txt" 2>/dev/null) <(cat "$3/warnings.txt" 2>/dev/null) | head -10; fail=1
    fi
    if diff -q <(normalized "$(ls $4/*/analysis-cache.tsv)") <(normalized "$(ls $5/*/analysis-cache.tsv)") > /dev/null; then
        echo "  OK   $1 キャッシュ（差分更新 == 全件解析）"
    else
        echo "  NG   $1 キャッシュが全件解析と違います"
        diff <(normalized "$(ls $4/*/analysis-cache.tsv)") <(normalized "$(ls $5/*/analysis-cache.tsv)") | head -10
        fail=1
    fi
}

# --- クラスパスとソースの形（シンボリックリンク・クラスフォルダの .java・jmod・同じ名前のエントリ・1 件ずつ指定した
#     クラスフォルダ・解析のあいだの依存 jar の書き換え）---------------------------------------------------------
# JDT はソースパスとクラスパスのフォルダをリンクをたどって読み、クラスフォルダの .java もソースとして読み、jmod は
# classes/ の下をパッケージとして読み、同じ名前の jar のエントリは目次の後ろのものを使う。一覧と指紋（L 行）がこれと
# 食い違うと、変化を検知できずに差分更新だけが古い事実を再利用していた（docs/cache-unification-qa.md）。
# 題材は $IW/shape/p に作り、設定の source.folders と足す行はケースごとに渡す
SHAPE=$IW/shape
shape_cfg() {   # $1=設定ファイル  $2=source.folders  $3=キャッシュのフォルダ  $4=足す行（改行区切り。省略可）
    local d
    d=$(cd "$(dirname "$1")" && pwd)
    cat > "$1" <<EOF
project.root=$d/p
source.folders=$2
library.build.tool=none
source.encoding=UTF-8
source.level=17
exclude.packages=java.**,javax.**
cache.enabled=true
cache.folder=$3
dataflow.enabled=true
output.encoding=UTF-8
output.folder=$d/out-$(basename "$1" .properties)
${4:-}
EOF
}
# 1 ケース: 題材を作る -> 解析 -> 書き換え -> 差分更新 -> キャッシュなしの全件解析 -> 一致と、書き換えが効いていることを見る。
# 用意と書き換えの関数は $SHAPE の中で動く。最初の解析の結果は SHAPE_BASE_OUT・SHAPE_BASE_PARSED に残す
shape_case() {   # $1=ラベル  $2=用意する関数  $3=書き換える関数  $4=source.folders  $5=足す設定の行
    echo "== $1 =="
    local d=$SHAPE inc_out
    SHAPE_BASE_OUT="" SHAPE_BASE_PARSED=""
    rm -rf $d && mkdir -p $d/p
    ( cd $d && $2 ) > $d/setup.log 2>&1 || { echo "  NG   $1 題材を作れませんでした"; tail -5 $d/setup.log; fail=1; return; }
    shape_cfg $d/c.properties "$4" "$PWD/$d/cache" "${5:-}"
    shape_cfg $d/full.properties "$4" "$PWD/$d/fullcache" "${5:-}"
    integrity_run $d/c.properties $d/c0.log
    [ "$IRC" = 0 ] || { echo "  NG   $1 最初の解析に失敗しました"; tail -5 $d/c0.log; fail=1; return; }
    SHAPE_BASE_OUT=$IOUT SHAPE_BASE_PARSED=$IPARSED
    cp $d/cache/*/analysis-cache.tsv $d/base.tsv
    ( cd $d && $3 ) >> $d/setup.log 2>&1 || { echo "  NG   $1 書き換えに失敗しました"; tail -5 $d/setup.log; fail=1; return; }
    integrity_run $d/c.properties $d/c1.log
    [ "$IRC" = 0 ] || { echo "  NG   $1 差分更新に失敗しました"; tail -5 $d/c1.log; fail=1; return; }
    inc_out=$IOUT
    integrity_run $d/full.properties $d/full.log
    [ "$IRC" = 0 ] || { echo "  NG   $1 全件解析に失敗しました"; tail -5 $d/full.log; fail=1; return; }
    same_all "$1" "$inc_out" "$IOUT" $d/cache $d/fullcache
    if diff -q <(normalized_facts $d/base.tsv) <(normalized_facts "$(ls $d/fullcache/*/analysis-cache.tsv)") \
            > /dev/null; then
        echo "  NG   $1 書き換えが事実に効いていません（検査が素通りします）"; fail=1
    else
        echo "  OK   $1 書き換えが事実に効いている"
    fi
}
# シンボリックリンクを作れる環境か（Windows の Git Bash は権限が無いと複製になる）
can_symlink() {
    local t=$IW/symlink-probe
    rm -rf $t && mkdir -p $t/a && ln -s a $t/b 2> /dev/null && [ -L $t/b ]
    local rc=$?
    rm -rf $t
    return $rc
}
shape_src() {   # $1=ソースフォルダ（p からの相対）  標準入力=クラス（1 行目の package から置き場所を決める）
    local body pkg name
    body=$(cat)
    pkg=$(sed -n 's/^package \([a-z.]*\);.*/\1/p' <<< "$body" | head -1)
    name=$(sed -n 's/.*public class \([A-Za-z0-9]*\).*/\1/p' <<< "$body" | head -1)
    mkdir -p "p/$1/${pkg//.//}"
    printf '%s\n' "$body" > "p/$1/${pkg//.//}/$name.java"
}
shape_javac() {   # $1=コンパイル先  $2...=ソース
    local out=$1
    shift
    mkdir -p "$out" && "$JAVAC_BIN" -nowarn -encoding UTF-8 -d "$out" "$@"
}
# Maven のリアクター。b が a に依存し、b の bp.U が解析対象でない gen.G を使う（a/target/classes に置く）
shape_reactor() {
    local parent='<parent><groupId>g</groupId><artifactId>root</artifactId><version>1</version></parent>'
    mkdir -p p/a/target p/b
    printf '<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><modules><module>a</module><module>b</module></modules></project>\n' > p/pom.xml
    printf '<project><modelVersion>4.0.0</modelVersion>%s<artifactId>a</artifactId></project>\n' "$parent" > p/a/pom.xml
    printf '<project><modelVersion>4.0.0</modelVersion>%s<artifactId>b</artifactId><dependencies><dependency><groupId>g</groupId><artifactId>a</artifactId><version>1</version></dependency></dependencies></project>\n' "$parent" > p/b/pom.xml
    printf 'package ap;\npublic class A { }\n' | shape_src a/src/main/java
    printf 'package bp;\npublic class U {\n    public void go() {\n        new gen.G().m("x");\n    }\n}\n' | shape_src b/src/main/java
}
shape_gen() {   # $1=gen.G の本体  $2=コンパイル先
    mkdir -p gsrc/gen && printf 'package gen;\npublic class G { %s }\n' "$1" > gsrc/gen/G.java && shape_javac "$2" gsrc/gen/G.java
}
REACTOR_FOLDERS=a/src/main/java,b/src/main/java
REACTOR_CFG=library.build.tool=maven

# ソースフォルダそのものがリンク（src -> real）。以前は 1 件も一覧に入らず、何も解析しなかった
setup_src_link() {
    mkdir -p p/real
    printf 'package app;\npublic class U {\n    public void go(V v) {\n        v.m("x");\n    }\n}\n' | shape_src real
    printf 'package app;\npublic class V {\n    public void m(Object o) {\n        w();\n    }\n    void w() {\n    }\n}\n' | shape_src real
    ln -s real p/src
}
edit_src_link() {
    printf 'package app;\npublic class V {\n    public void m(Object o) {\n        w();\n    }\n    public void m(String s) {\n    }\n    void w() {\n    }\n}\n' | shape_src real
}
# ソースフォルダの中のパッケージのフォルダがリンク（src/shared -> ../shared-src/shared）
setup_pkg_link() {
    printf 'package shared;\npublic class S {\n    public void m(Object o) {\n    }\n}\n' | shape_src shared-src
    printf 'package app;\npublic class U {\n    public void go(shared.S s) {\n        s.m("x");\n    }\n}\n' | shape_src src
    ln -s ../shared-src/shared p/src/shared
}
edit_pkg_link() {
    printf 'package shared;\npublic class S {\n    public void n(Object o) {\n    }\n}\n' | shape_src shared-src
}
# 祖先を指すリンク（src/app/loop -> ..）。輪の先へは入らない（同じファイルを 2 度数えない・止まらない）
setup_loop_link() {
    setup_src_link_plain
    ln -s .. p/src/app/loop
}
setup_src_link_plain() {
    printf 'package app;\npublic class U {\n    public void go(V v) {\n        v.m("x");\n    }\n}\n' | shape_src src
    printf 'package app;\npublic class V {\n    public void m(Object o) {\n    }\n}\n' | shape_src src
}
edit_loop_link() {
    printf 'package app;\npublic class V {\n    public void m(Object o) {\n    }\n    public void m(String s) {\n    }\n}\n' | shape_src src
}
# クラスフォルダ（兄弟モジュールの target/classes）そのものがリンク
setup_cls_link() {
    shape_reactor
    shape_gen 'public void m(Object o) { }' elsewhere/cls
    ln -s "$PWD/elsewhere/cls" p/a/target/classes
}
edit_cls_link() {
    shape_gen 'public void n(Object o) { }' elsewhere/cls
}
# クラスフォルダの中のパッケージのフォルダがリンク
setup_cls_pkg_link() {
    shape_reactor
    mkdir -p p/a/target/classes
    shape_gen 'public void m(Object o) { }' elsewhere/cls
    ln -s "$PWD/elsewhere/cls/gen" p/a/target/classes/gen
}
edit_cls_pkg_link() {
    shape_gen 'public void m(Object o) { } public void m(String s) { }' elsewhere/cls
}
# クラスフォルダの .java だけのクラス（JDT はソースとして読む）
setup_cls_source() {
    shape_reactor
    mkdir -p p/a/target/classes/gen
    printf 'package gen;\npublic class G { public void m(Object o) { } }\n' > p/a/target/classes/gen/G.java
}
edit_cls_source() {
    printf 'package gen;\npublic class G { public void n(Object o) { } }\n' > p/a/target/classes/gen/G.java
}
# 同じ名前の .class と .java があり、JDT は更新時刻の新しい方を読む。中身を変えずに .java を新しくする
setup_cls_newer() {
    shape_reactor
    shape_gen 'public void n(Object o) { }' p/a/target/classes
    printf 'package gen;\npublic class G { public void m(Object o) { } }\n' > p/a/target/classes/gen/G.java
    touch -t 202001010000 p/a/target/classes/gen/G.java
}
edit_cls_newer() {
    touch p/a/target/classes/gen/G.java
}
# jmod（library.jars）。クラスは classes/ の下にある
shape_jmod() {   # $1=l.D の本体
    rm -rf msrc mcls p/lib/l.jmod && mkdir -p msrc/l p/lib
    printf 'module lmod { exports l; }\n' > msrc/module-info.java
    printf 'package l;\npublic class D { %s }\n' "$1" > msrc/l/D.java
    shape_javac mcls msrc/module-info.java msrc/l/D.java && "$JMOD_BIN" create --class-path mcls p/lib/l.jmod
}
setup_jmod() {
    shape_jmod 'public void m(Object o) { }'
    printf 'package app;\npublic class U {\n    public void go(l.D d) {\n        d.m("x");\n    }\n}\n' | shape_src src
}
edit_jmod() {
    shape_jmod 'public void n(Object o) { }'
}
# 同じ名前のエントリが 2 つある jar。JDK と JDT は目次の後ろのものを使う。書き換えは 2 つの順を入れ替えるだけ
shape_dup_jar() {   # $1=先に置くクラスのフォルダ  $2=後に置くクラスのフォルダ
    python3 - "$1/l/A.class" "$2/l/A.class" p/lib/l.jar <<'PY'
import sys, warnings, zipfile
warnings.simplefilter('ignore')
first, second, jar = sys.argv[1:4]
with zipfile.ZipFile(jar, 'w') as z:
    z.write(first, 'l/A.class')
    z.write(second, 'l/A.class')
PY
}
setup_dup_entry() {
    mkdir -p jm/l jn/l p/lib
    printf 'package l;\npublic class A { public void m(Object o) { } }\n' > jm/l/A.java
    printf 'package l;\npublic class A { public void n(Object o) { } }\n' > jn/l/A.java
    shape_javac cm jm/l/A.java && shape_javac cn jn/l/A.java && shape_dup_jar cm cn
    printf 'package app;\npublic class U {\n    public void go(l.A a) {\n        a.m("x");\n    }\n}\n' | shape_src src
}
edit_dup_entry() {
    shape_dup_jar cn cm
}
# library.jars に書いたクラスフォルダ・.classpath の kind="lib" のクラスフォルダ。以前は jar を集めたフォルダとして
# 展開し、中に jar が無いので何も渡していなかった（呼び出しがどれも型解決に失敗した）
setup_cls_entry() {
    mkdir -p jb/l
    printf 'package l;\npublic class A { public void m(String s) { } }\n' > jb/l/A.java
    shape_javac p/cls jb/l/A.java
    printf 'package app;\npublic class U {\n    public void go() {\n        new l.A().m("x");\n    }\n}\n' | shape_src src
}
setup_cls_dotclasspath() {
    setup_cls_entry
    printf '<?xml version="1.0" encoding="UTF-8"?>\n<classpath>\n\t<classpathentry kind="src" path="src"/>\n\t<classpathentry kind="lib" path="cls"/>\n</classpath>\n' > p/.classpath
}
edit_cls_entry() {
    printf 'package l;\npublic class A { public void m(Object o) { } }\n' > jb/l/A.java
    shape_javac p/cls jb/l/A.java
}
cls_entry_resolved() {   # $1=ラベル。最初の解析で、クラスフォルダの型の呼び出しが解決できていて、警告が無いこと
    if grep -q -F 'A.m,RESOLVED' "$SHAPE_BASE_OUT/call-hierarchy.csv" 2> /dev/null \
            && [ ! -f "$SHAPE_BASE_OUT/warnings.txt" ]; then
        echo "  OK   $1 クラスフォルダの型を解決できる（warnings.txt も無い）"
    else
        echo "  NG   $1 クラスフォルダの型を解決できていない（または warnings.txt がある）"
        head -3 "$SHAPE_BASE_OUT/call-hierarchy.csv" 2> /dev/null; head -12 "$SHAPE_BASE_OUT/warnings.txt" 2> /dev/null
        fail=1
    fi
}

if can_symlink; then
    shape_case "ソースフォルダそのものがシンボリックリンク" setup_src_link edit_src_link src
    if [ "${SHAPE_BASE_PARSED:-0}" = 2 ]; then
        echo "  OK   リンクの先のソースを一覧に入れて解析した（新規解析=2）"
    else
        echo "  NG   リンクの先のソースを解析していません（新規解析=${SHAPE_BASE_PARSED:-?}）"; fail=1
    fi
    shape_case "ソースフォルダの中のパッケージのフォルダがシンボリックリンク" setup_pkg_link edit_pkg_link src
    shape_case "ソースフォルダの中に祖先を指すシンボリックリンク" setup_loop_link edit_loop_link src
    if [ "${SHAPE_BASE_PARSED:-0}" = 2 ]; then
        echo "  OK   輪になったリンクの先を 2 度数えない（新規解析=2）"
    else
        echo "  NG   輪になったリンクの扱いが期待と違います（新規解析=${SHAPE_BASE_PARSED:-?}）"; fail=1
    fi
    shape_case "クラスフォルダそのものがシンボリックリンク" setup_cls_link edit_cls_link "$REACTOR_FOLDERS" "$REACTOR_CFG"
    shape_case "クラスフォルダの中のパッケージのフォルダがシンボリックリンク" setup_cls_pkg_link edit_cls_pkg_link \
        "$REACTOR_FOLDERS" "$REACTOR_CFG"
else
    echo "== シンボリックリンクのケース（シンボリックリンクを作れない環境なので見ない） =="
fi
shape_case "クラスフォルダの .java だけのクラスを書き換える" setup_cls_source edit_cls_source \
    "$REACTOR_FOLDERS" "$REACTOR_CFG"
shape_case "クラスフォルダの同じ名前の .class と .java の新しさが入れ替わる" setup_cls_newer edit_cls_newer \
    "$REACTOR_FOLDERS" "$REACTOR_CFG"
JMOD_BIN="$(dirname "$(command -v "$JAVAC_BIN")")/jmod"
if [ -x "$JMOD_BIN" ]; then
    shape_case "library.jars の jmod の中身を変える" setup_jmod edit_jmod src "library.jars=$PWD/$SHAPE/p/lib/l.jmod"
else
    echo "== library.jars の jmod（jmod コマンドが無いので見ない） =="
fi
shape_case "jar の同じ名前の 2 つのエントリの順を入れ替える" setup_dup_entry edit_dup_entry src "library.folders=lib"
shape_case "library.jars のクラスフォルダ" setup_cls_entry edit_cls_entry src "library.jars=$PWD/$SHAPE/p/cls"
cls_entry_resolved "library.jars のクラスフォルダ"
shape_case ".classpath の kind=\"lib\" のクラスフォルダ" setup_cls_dotclasspath edit_cls_entry ""
cls_entry_resolved ".classpath の kind=\"lib\" のクラスフォルダ"

# 解析のあいだに依存 jar・クラスフォルダを書き換え、終わったあとで同じ中身に戻す（兄弟モジュールの clean ビルドなど）。
# 指紋（L 行）はパス0 で取るので戻した中身と一致し、書き換えた中身で解析したブロックを再利用し続けていた。
# 書き換えは jche.analysis.ClasspathSwapDuringRunCheck が最初のバッチを JDT に渡す直前に行う（最初の実行・キャッシュ無し）
classpath_swap_case() {   # $1=ラベル  $2=用意する関数  $3=書き換えるファイル（$SHAPE からの相対）  $4=書き換えた後の中身（- なら消す）
                          # $5=source.folders  $6=足す設定の行
    echo "== 解析のあいだに書き換えて戻した依存 jar・クラスフォルダ（$1） =="
    local d=$SHAPE inc_out rc
    rm -rf $d && mkdir -p $d/p
    ( cd $d && $2 ) > $d/setup.log 2>&1 || { echo "  NG   題材を作れませんでした"; tail -5 $d/setup.log; fail=1; return; }
    shape_cfg $d/c.properties "$5" "$PWD/$d/cache" "${6:-}"
    shape_cfg $d/full.properties "$5" "$PWD/$d/fullcache" "${6:-}"
    cp "$d/$3" $d/original.bin
    local replacement=-
    [ "$4" = - ] || replacement=$PWD/$d/$4
    "$JAVA_BIN" -Dstdout.encoding=UTF-8 -cp "$TOOLS_CP" jche.analysis.ClasspathSwapDuringRunCheck $d/c.properties \
        "$PWD/$d/$3" "$replacement" > $d/c0.log 2>&1
    rc=$?
    cp $d/original.bin "$d/$3"
    if [ "$rc" != 0 ]; then
        echo "  NG   書き換えを挟んだ解析が失敗しました（終了コード $rc）"; grep -a -E 'NG|ERROR' $d/c0.log | head -5; fail=1; return
    fi
    if grep -q -F "changed while the analysis was running" $d/c0.log; then
        echo "  OK   $1 解析のあいだに書き換えられたことをログに出す"
    else
        echo "  NG   $1 解析のあいだに書き換えられたことがログに出ていません"; fail=1
    fi
    integrity_run $d/c.properties $d/c1.log
    inc_out=$IOUT
    if [ "$IRC" = 0 ] && [ "${IPARSED:-0}" -ge 1 ]; then
        echo "  OK   $1 次の実行で解析し直した（新規解析=$IPARSED）"
    else
        echo "  NG   $1 次の実行で解析し直していません（新規解析=${IPARSED:-?}）"; fail=1
    fi
    integrity_run $d/full.properties $d/full.log
    same_all "解析のあいだに書き換えて戻した依存 jar・クラスフォルダ（$1）" "$inc_out" "$IOUT" $d/cache $d/fullcache
}
setup_swap_jar() {
    mkdir -p ja/la jb/la p/lib
    printf 'package la;\npublic class L { public void m(Object o) { } public void m(String s) { } }\n' > ja/la/L.java
    printf 'package la;\npublic class Other { }\n' > jb/la/Other.java
    shape_javac ca ja/la/L.java && shape_javac cb jb/la/Other.java \
        && ( cd ca && "$JAR_BIN" cf ../p/lib/l.jar la ) && ( cd cb && "$JAR_BIN" cf ../temp.jar la )
    printf 'package app;\npublic class C1 {\n    void go() {\n        new la.L().m("x");\n    }\n}\n' | shape_src src
    printf 'package app;\npublic class C2 {\n    void go() {\n        new la.L().m("y");\n    }\n}\n' | shape_src src
}
setup_swap_cls() {
    shape_reactor
    shape_gen 'public void m(Object o) { } public void m(String s) { }' p/a/target/classes
}
classpath_swap_case "jar を差し替える" setup_swap_jar p/lib/l.jar temp.jar src "library.folders=lib"
classpath_swap_case "クラスフォルダのクラスを消す" setup_swap_cls p/a/target/classes/gen/G.class - \
    "$REACTOR_FOLDERS" "$REACTOR_CFG"

# アノテーションの付いた package-info.java が 2 つのソースフォルダにある。JDT はアノテーションの付いたパッケージ宣言に
# package-info という型を作るので、同じバッチの 2 つ目は「型が重複している」エラーになり、別々のバッチならエラーにならない。
# 01eb510 は package-info.java を同じ名前の組にせず、片方だけを書き換えた差分更新では warnings.txt（コンパイルエラーの
# ファイルの一覧）が全件解析と食い違った（docs/cache-unification-qa.md の Q66）。どちらを書き換えても同じであること
pkginfo_case() {
    local d=$IW/pkginfo which inc_out
    for which in s2 s1; do
        echo "== アノテーションの付いた package-info.java が 2 つのソースフォルダにある（$which を書き換える） =="
        rm -rf $d && mkdir -p $d/s1/p $d/s2/p
        printf '@Deprecated\npackage p;\n' > $d/s1/p/package-info.java
        printf '@Deprecated\npackage p;\n' > $d/s2/p/package-info.java
        printf 'package p;\n\npublic class A {\n    static void x() {\n    }\n\n    public static void main(String[] args) {\n        x();\n    }\n}\n' \
            > $d/s1/p/A.java
        integrity_cfg $d/c.properties "$PWD/$d" s1,s2
        integrity_cfg $d/full.properties "$PWD/$d" s1,s2 "$PWD/$d/fullcache"
        integrity_run $d/c.properties $d/c0.log
        [ "$IRC" = 0 ] || { echo "  NG   最初の解析に失敗しました"; tail -5 $d/c0.log; fail=1; return; }
        printf '// touched\n' >> $d/$which/p/package-info.java
        integrity_run $d/c.properties $d/c1.log
        inc_out=$IOUT
        integrity_run $d/full.properties $d/full.log
        same_all "package-info.java（$which を書き換えた差分更新）" "$inc_out" "$IOUT" $d/cache $d/fullcache
        if grep -q -F -- "- s2/p/package-info.java" "$inc_out/warnings.txt" 2>/dev/null; then
            echo "  OK   後ろのフォルダの package-info.java が、差分更新でも重複した型として warnings.txt に載る"
        else
            echo "  NG   後ろのフォルダの package-info.java が warnings.txt に載っていません（題材が効いていない）"; fail=1
        fi
    done
}
pkginfo_case

# 同じ名前のファイルの組の片方を消す・そのフォルダを source.folders から外す。組が同じバッチにいたあいだ、後ろのほうには
# 「型が重複している」エラーが付いている。f491e2e は組を今のソースの一覧からしか作らず、消えたほうと組にならないので、
# 残ったほうのブロック（エラー付き）を再利用し、全件解析（エラーなし）と warnings.txt・キャッシュが食い違った
# （docs/cache-unification-qa.md の Q71）。消えたファイルの名前は旧キャッシュのヘッダ行のフォルダの一覧で求める
# （外したフォルダは今の設定に無い）。アノテーションの付いた package-info.java と、同じ名前の普通のクラスの両方で見る
pair_delete_case() {
    local d=$IW/pairdel kind route inc_out
    for kind in pkginfo dup; do
        for route in delete unlist; do
            echo "== 同じ名前のファイルの組の片方を消す（$kind・$route） =="
            rm -rf $d && mkdir -p $d/s1/p $d/s2/p
            if [ $kind = pkginfo ]; then
                printf '@Deprecated\npackage p;\n' > $d/s1/p/package-info.java
                printf '@Deprecated\npackage p;\n' > $d/s2/p/package-info.java
            else
                printf 'package p;\n\npublic class Dup {\n    void a() {\n        A.x();\n    }\n}\n' > $d/s1/p/Dup.java
                printf 'package p;\n\npublic class Dup {\n    void b() {\n        A.x();\n    }\n}\n' > $d/s2/p/Dup.java
            fi
            printf 'package p;\n\npublic class A {\n    static void x() {\n    }\n\n    public static void main(String[] args) {\n        x();\n    }\n}\n' \
                > $d/s2/p/A.java
            integrity_cfg $d/c.properties "$PWD/$d" s1,s2
            integrity_run $d/c.properties $d/c0.log
            [ "$IRC" = 0 ] || { echo "  NG   最初の解析に失敗しました"; tail -5 $d/c0.log; fail=1; return; }
            if [ $kind = pkginfo ] && ! grep -q -F -- "- s2/p/package-info.java" "$IOUT/warnings.txt" 2>/dev/null; then
                echo "  NG   組がそろっているあいだ、後ろの package-info.java に重複のエラーが付いていません（題材が効いていない）"; fail=1
            fi
            if [ $route = delete ]; then
                rm $d/s1/p/*.java
                integrity_cfg $d/c.properties "$PWD/$d" s1,s2
                integrity_cfg $d/full.properties "$PWD/$d" s1,s2 "$PWD/$d/fullcache"
            else
                integrity_cfg $d/c.properties "$PWD/$d" s2
                integrity_cfg $d/full.properties "$PWD/$d" s2 "$PWD/$d/fullcache"
            fi
            integrity_run $d/c.properties $d/c1.log
            inc_out=$IOUT
            integrity_run $d/full.properties $d/full.log
            same_all "組の片方を消した差分更新（$kind・$route）" "$inc_out" "$IOUT" $d/cache $d/fullcache
            if grep -q -F -- "s2/p/" "$inc_out/warnings.txt" 2>/dev/null; then
                echo "  NG   残ったほうに前のエラーが残っています（$kind・$route）"; grep -F -- "s2/p/" "$inc_out/warnings.txt" | head -3; fail=1
            else
                echo "  OK   残ったほうのファイルは、もうエラーとして載らない（$kind・$route）"
            fi
        done
    done
}
pair_delete_case

# ソースフォルダを足す・外す。ヘッダ行のソースフォルダの一覧は、両方にあるフォルダの並びが同じで入れ子が無ければ
# 旧キャッシュを使い続けてよい（足したフォルダのファイルは足したファイル、外したフォルダのファイルは消したファイル）。
# 01eb510 は一覧のハッシュを鍵にしていたので、同じ project.root・同じキャッシュのフォルダで source.folders だけが
# 違う 2 つの設定（src と src,test）を交互に動かすと、毎回全件解析していた（docs/cache-unification-qa.md の Q67）。
# 題材は、足すフォルダ s2 に s1 と同じ名前のファイル（p/Dup.java。先に並べると解決先が変わる）と、s1 の型を使う
# ファイルを置く。入れ子のフォルダを足したときは使い続けない（コンパイル単位の名前が変わる）
folders_case() {
    local d=$IW/folders step folders inc_out
    rm -rf $d && mkdir -p $d/s1/p $d/s2/p $d/s2/q
    cat > $d/s1/p/Main.java <<'EOF'
package p;

public class Main {
    public static void main(String[] args) {
        new Dup().a();
        new Dup().b();
        Helper.h();
    }

    static void x() {
    }

    static void y() {
    }
}
EOF
    printf 'package p;\n\npublic class Dup {\n    public void a() {\n        Main.x();\n    }\n}\n' > $d/s1/p/Dup.java
    printf 'package p;\n\npublic class Helper {\n    static void h() {\n    }\n}\n' > $d/s1/p/Helper.java
    printf 'package p;\n\npublic class Dup {\n    public void a() {\n        Main.y();\n    }\n\n    public void b() {\n        Main.y();\n    }\n}\n' \
        > $d/s2/p/Dup.java
    printf 'package q;\n\npublic class T {\n    void t() {\n        p.Main.main(null);\n    }\n}\n' > $d/s2/q/T.java
    integrity_cfg $d/c.properties "$PWD/$d" s1
    integrity_run $d/c.properties $d/c0.log
    [ "$IRC" = 0 ] || { echo "  NG   最初の解析に失敗しました"; tail -5 $d/c0.log; fail=1; return; }
    local first_out=$IOUT
    step=0
    for folders in s2,s1 s1 s1,s2; do
        step=$((step + 1))
        echo "== ソースフォルダを足す・外す（$folders） =="
        integrity_cfg $d/c.properties "$PWD/$d" $folders
        integrity_run $d/c.properties $d/c$step.log
        inc_out=$IOUT
        if [ "$IRC" = 0 ] && [ "${IREUSED:-0}" -ge 1 ] && grep -q -F "Source folders were added or removed" $d/c$step.log; then
            echo "  OK   並びの変わらないフォルダの追加・削除では、キャッシュを使い続ける（再利用=$IREUSED 新規解析=$IPARSED）"
        else
            echo "  NG   フォルダを足した・外しただけなのにキャッシュを使っていません（再利用=${IREUSED:-?}）"; fail=1
        fi
        rm -rf $d/fullcache
        integrity_cfg $d/full.properties "$PWD/$d" $folders "$PWD/$d/fullcache"
        integrity_run $d/full.properties $d/full$step.log
        same_all "ソースフォルダを $folders にした差分更新" "$inc_out" "$IOUT" $d/cache $d/fullcache
    done
    # 先に並べた s2 の Dup に解決先が変わっていること（変わらなければ、足したフォルダの扱いを見ていない）
    if diff -q "$first_out/call-hierarchy.csv" "$(ls -d $d/out-c/*/ | sort | sed -n 2p)call-hierarchy.csv" > /dev/null; then
        echo "  NG   s2 を先に足しても出力が変わっていません（検査が素通りします）"; fail=1
    else
        echo "  OK   s2 を先に足すと出力が変わる"
    fi

    echo "== 入れ子のソースフォルダを足す =="
    mkdir -p $d/s1/gen/r
    printf 'package r;\n\npublic class G {\n    void g() {\n        p.Main.main(null);\n    }\n}\n' > $d/s1/gen/r/G.java
    integrity_cfg $d/c.properties "$PWD/$d" s1,s1/gen
    integrity_run $d/c.properties $d/c9.log
    inc_out=$IOUT
    if [ "$IRC" = 0 ] && [ "$IREUSED" = 0 ] && grep -q -F "source folder order" $d/c9.log; then
        echo "  OK   入れ子のフォルダを足したらキャッシュを使い続けない（破棄したことをログに出す）"
    else
        echo "  NG   入れ子のフォルダを足したのに、キャッシュを使い続けたか、破棄したことがログに出ていません（再利用=${IREUSED:-?}）"
        fail=1
    fi
    rm -rf $d/fullcache
    integrity_cfg $d/full.properties "$PWD/$d" s1,s1/gen "$PWD/$d/fullcache"
    integrity_run $d/full.properties $d/full9.log
    same_all "入れ子のソースフォルダを足した実行" "$inc_out" "$IOUT" $d/cache $d/fullcache
}
folders_case

# ソースフォルダを足したら、JDT がその実行のクラスパス・ソースパスを受け付けなくなった（Linux で名前に \ を含むフォルダ。
# JDT は \ もパスの区切りとして読むので、そのフォルダが見つからない）。全件解析ではどのファイルも解析できない。
# f491e2e はフォルダを足しただけとして旧キャッシュのブロックを使い続け、差分更新だけが前の設定の結果を出した
# （docs/cache-unification-qa.md の Q73）。今は JDT が受け付けないなら旧キャッシュを使わない
env_rejected_case() {
    local d=$IW/envrej inc_out
    case "$(uname -s)" in
        Linux*) ;;
        *) echo "== ソースフォルダを足したら JDT が受け付けない（Linux でだけ見る。名前に \\ を含むフォルダを作れない） =="; return ;;
    esac
    echo "== ソースフォルダを足したら JDT が受け付けない（名前に \\ を含むフォルダ） =="
    rm -rf $d && mkdir -p $d/s1/p "$d/b\\x/q"
    printf 'package p;\n\npublic class A {\n    public static void main(String[] args) {\n        q.C.c();\n    }\n}\n' > $d/s1/p/A.java
    printf 'package q;\n\npublic class C {\n    public static void c() {\n    }\n}\n' > "$d/b\\x/q/C.java"
    integrity_cfg $d/c.properties "$PWD/$d" s1
    integrity_run $d/c.properties $d/c0.log
    [ "$IRC" = 0 ] || { echo "  NG   最初の解析に失敗しました"; tail -5 $d/c0.log; fail=1; return; }
    # properties の値では \ を \\ と書く
    integrity_cfg $d/c.properties "$PWD/$d" 's1,b\\x'
    integrity_cfg $d/full.properties "$PWD/$d" 's1,b\\x' "$PWD/$d/fullcache"
    integrity_run $d/c.properties $d/c1.log
    inc_out=$IOUT
    if [ "${IREUSED:-1}" = 0 ] && grep -q -F "does not accept the class path" $d/c1.log; then
        echo "  OK   JDT が受け付けないなら旧キャッシュを使わない（ログに理由を出す）"
    else
        echo "  NG   JDT が受け付けないのに旧キャッシュを使いました（再利用=${IREUSED:-?}）"
        grep -a -E 'cache\]|WARN' $d/c1.log | head -5; fail=1
    fi
    integrity_run $d/full.properties $d/full.log
    if grep -q -F "invalid environment settings" $d/full.log; then
        echo "  OK   全件解析でも JDT が受け付けない（題材が効いている）"
    else
        echo "  NG   全件解析で JDT が受け付けています（題材が効いていない）"; fail=1
    fi
    same_all "JDT が受け付けない設定の差分更新" "$inc_out" "$IOUT" $d/cache $d/fullcache
}
env_rejected_case

# 名前に \ を含むフォルダ（Linux・macOS では \ は名前の中のただの文字）を、入れ子のフォルダ x/y と同じキーにしない。
# f491e2e（形式 v39）までの書き手はパスの \ を / に置き換えてキー（F 行・T 行・ヘッダ行のソースフォルダ）にしていたので、
# x/y を x\y という名前に変えても「ソースフォルダもファイルも変わっていない」と読み、旧キャッシュのブロックを使い続けた
# （JDT は x\y を受け付けないので、全件解析ではどのファイルも解析できない。上の env_rejected_case の確かめ（Q73）も、
# フォルダが変わっていないと読むので走らない）。中断した実行の一時ファイル（.partial）からの引き継ぎも同じで、
# ヘッダ行とソース一覧が同じに見えて、当時のブロックを書き写した（docs/cache-unification-qa.md の Q75）
backslash_folder_case() {
    local d=$IW/bsfold inc_out mode c
    case "$(uname -s)" in
        Linux*) ;;
        *) echo "== 入れ子のフォルダを名前に \\ を含むフォルダに変える（Linux でだけ見る。名前に \\ を含むフォルダを作れない） =="; return ;;
    esac
    for mode in cache partial; do
        echo "== 入れ子のフォルダ x/y を、名前に \\ を含むフォルダ x\\y に変える（$mode） =="
        rm -rf $d && mkdir -p $d/s1/p $d/x/y/q
        printf 'package p;\n\npublic class A {\n    public static void main(String[] args) {\n        q.C.c();\n    }\n}\n' > $d/s1/p/A.java
        printf 'package q;\n\npublic class C {\n    public static void c() {\n    }\n}\n' > $d/x/y/q/C.java
        integrity_cfg $d/c.properties "$PWD/$d" s1,x/y
        integrity_run $d/c.properties $d/c0.log
        [ "$IRC" = 0 ] || { echo "  NG   最初の解析に失敗しました"; tail -5 $d/c0.log; fail=1; return; }
        if [ $mode = partial ]; then
            # 書き終えたキャッシュを、中断した実行が残した一時ファイルにする（次の実行はこれを引き継ぎに使う。
            # 旧キャッシュは無いので、どのファイルも「これから解析する」ファイルになる）
            c=$(ls $d/cache/*/analysis-cache.tsv)
            mv "$c" "$c.tmp"
        fi
        mv $d/x/y "$d/x\\y"
        rmdir $d/x
        # properties の値では \ を \\ と書く
        integrity_cfg $d/c.properties "$PWD/$d" 's1,x\\y'
        integrity_cfg $d/full.properties "$PWD/$d" 's1,x\\y' "$PWD/$d/fullcache"
        integrity_run $d/c.properties $d/c1.log
        inc_out=$IOUT
        if [ $mode = cache ]; then
            if [ "${IREUSED:-1}" = 0 ] && grep -q -F "does not accept the class path" $d/c1.log; then
                echo "  OK   フォルダが変わったと読み、JDT が受け付けないので旧キャッシュを使わない"
            else
                echo "  NG   x\\y を x/y と同じフォルダと読んで旧キャッシュを使いました（再利用=${IREUSED:-?}）"
                grep -a -E 'cache\]|WARN' $d/c1.log | head -5; fail=1
            fi
        else
            if ! grep -q -F "Carried over the results" $d/c1.log; then
                echo "  OK   中断した実行の一時ファイルから引き継がない（ヘッダ行のソースフォルダが違う）"
            else
                echo "  NG   x\\y を x/y と同じフォルダと読んで、中断した実行のブロックを引き継ぎました"
                grep -a -E 'cache\]' $d/c1.log | head -5; fail=1
            fi
        fi
        integrity_run $d/full.properties $d/full.log
        if grep -q -F "invalid environment settings" $d/full.log; then
            echo "  OK   全件解析では JDT が受け付けない（題材が効いている）"
        else
            echo "  NG   全件解析で JDT が受け付けています（題材が効いていない）"; fail=1
        fi
        same_all "x/y を x\\y に変えた実行（$mode）" "$inc_out" "$IOUT" $d/cache $d/fullcache
    done
}
backslash_folder_case

# 受け手（キャッシュへ書く側）の中でスタックが溢れたファイルも、そのファイルの失敗として数え、ほかのファイルの
# 解析を続ける。01eb510 は受け手の StackOverflowError を一括パースのファイルごとには捕まえず、そのファイルは
# 受け取ったとも失敗したとも数えられずに消え、1 ファイルずつの解析の経路では例外が外へ抜けた
# （docs/cache-unification-qa.md の Q69。jche.analysis.SinkOverflowCheck）
sink_overflow_case() {
    echo "== 受け手の中でスタックが溢れたファイルも失敗として数える =="
    local d=$IW/sink
    rm -rf $d && mkdir -p $d/src/p $d/deep/p
    printf 'package p;\n\npublic class A {\n    void a() {\n        B.b();\n    }\n}\n' > $d/src/p/A.java
    printf 'package p;\n\npublic class B {\n    static void b() {\n    }\n}\n' > $d/src/p/B.java
    printf 'package p;\n\npublic class C {\n    void c() {\n        B.b();\n    }\n}\n' > $d/src/p/C.java
    {
        printf 'package p;\n\npublic class Deep {\n    String chain() {\n        return new StringBuilder()'
        for ((i = 0; i < 10000; i++)); do printf '.append(%d)' "$i"; done
        printf '.toString();\n    }\n}\n'
    } > $d/deep/p/Deep.java
    integrity_cfg $d/c1.properties "$PWD/$d" src
    integrity_cfg $d/c2.properties "$PWD/$d" deep,src
    local rc
    "$JAVA_BIN" -Dstdout.encoding=UTF-8 -cp "$TOOLS_CP" jche.analysis.SinkOverflowCheck $d/c1.properties \
        src/p/B.java > $d/c1.log 2>&1
    rc=$?
    grep -a -E '^  (OK|NG)' $d/c1.log
    [ "$rc" = 0 ] || { echo "  NG   一括パースの受け手で溢れたファイルの扱いが期待と違います（test/incremental/$d/c1.log）"; fail=1; }
    "$JAVA_BIN" -Dstdout.encoding=UTF-8 -cp "$TOOLS_CP" jche.analysis.SinkOverflowCheck $d/c2.properties \
        src/p/B.java deep/p/Deep.java > $d/c2.log 2>&1
    rc=$?
    grep -a -E '^  (OK|NG)' $d/c2.log
    [ "$rc" = 0 ] || { echo "  NG   1 ファイルずつの解析の受け手で溢れたファイルの扱いが期待と違います（test/incremental/$d/c2.log）"; fail=1; }
    if grep -q -F "analyzed one at a time" $d/c2.log; then
        echo "  OK   深い式のファイルで一括パースが溢れ、残りを 1 ファイルずつ解析する経路を通った"
    else
        echo "  NG   一括パースが溢れず、1 ファイルずつの解析の経路を通っていません（題材が効いていない）"; fail=1
    fi
}
sink_overflow_case

if [ $fail = 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
