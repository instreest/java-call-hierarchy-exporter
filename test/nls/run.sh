#!/usr/bin/env bash
# 文言（英語が既定、日本語は重ねる）の検査。
#
#   bash test/nls/run.sh
#
# 画面・ログ・エラーは利用者の言語で出し、出力 CSV は言語に関わらず英語で固定する
# （docs/nls-qa.md）。この 2 つはどちらも「動かしてみるまで気づけない」壊れ方をするので、
# 次の 6 点を見る。
#   1) src/ に日本語の文字列リテラルが残っていないこと（残っていると英語の利用者に読めない行が出る）
#   2) 使っているキーが英語の表にあり、使っていないキーが残っていないこと
#   3) 英語と日本語でキーがそろい、差し込み（{0} {1} …）の番号と数も同じこと
#   4) 起動コマンド（.sh / .cmd）の英語と日本語の表でキーがそろい、表の外に日本語の出力が無いこと
#   5) 実際に読ませて、既定が英語・環境変数 / システムプロパティ / 設定ファイルで切り替わり、
#      優先順位どおりになること。訳の無い言語は英語に落ち、無いキーは !キー! で見えること
#   6) 出力 CSV が言語で変わらないこと（同じ設定を英語と日本語で解析して突き合わせる）
#
# 1〜5 は JDK だけで動く（Messages は JDT に触らない）。6 だけ jbang が用意した JDK 25 と
# JDT の jar が要る。キャッシュは test/nls/.cache、出力は test/nls/output にできる（どちらもコミットしない）。
#
# Eclipse プラグインの画面の文言は置き場所も読む側も別なので、test/plugin-nls/run.sh が見る。
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
SRC=$ROOT/src
EN=$SRC/jche/util/MessagesEn.java
JA=$SRC/jche/util/MessagesJa.java
SH=$ROOT/java-call-hierarchy-exporter.sh
CMD=$ROOT/java-call-hierarchy-exporter.cmd
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

ng=0
ok()   { echo "  OK   $1"; }
fail() { echo "  NG   $1"; ng=$((ng + 1)); }

# --- 1) ソースに日本語の文字列リテラルが残っていないこと ---------------------
echo "== 1) src/ に日本語の文字列リテラルが残っていないこと =="
# コメント・文字列・文字リテラルを頭から順に読み分けて、文字列の中の仮名・漢字を探す。
# コメントを先に消す作りにはしない。文字列の中の // や /*（URL や正規表現）から先を
# 消してしまい、検査が黙って何も見なくなる（test/plugin-nls/run.sh の同じ検査で実際に起きた）。
# perl には -CSD が要る。無いと元のバイト列のまま扱われ、\x{3040}- の範囲が 1 文字も当たらない。
# MessagesJa.java は日本語の表そのものなので外す
LEFTOVER=$(find "$SRC" -name '*.java' ! -name 'MessagesJa.java' -print0 | xargs -0 perl -CSD -0777 -ne '
    while (m{(/\*.*?\*/)|(//[^\n]*)|("(?:[^"\\]|\\.)*")|(\x27(?:[^\x27\\]|\\.)*\x27)|.}gs) {
        my $lit = $3;
        next unless defined $lit;
        print "$ARGV: $lit\n" if $lit =~ /[\x{3040}-\x{30ff}\x{4e00}-\x{9fff}\x{ff01}-\x{ff60}]/;
    }' 2>/dev/null)
if [ -z "$LEFTOVER" ]; then
    ok "日本語の文字列リテラルは残っていない"
else
    fail "日本語の文字列リテラルが残っている（文言は MessagesEn / MessagesJa へ）"
    echo "$LEFTOVER" | sed 's/^/       /' | head -10
fi

# --- 2) 使っているキーと英語の表 ---------------------------------------------
echo "== 2) 使っているキーが英語の表にあり、使っていないキーが残っていないこと =="
# 表そのもののキーを集める（"キー", "値", が 1 行に 1 組並ぶ）
keys_of() {
    perl -CSD -ne 'print "$1\n" if /^\s*"([a-zA-Z0-9._]+)",\s*"/' "$1" | sort
}
keys_of "$EN" > "$WORK/en.txt"
keys_of "$JA" > "$WORK/ja.txt"
# 使っているキーを集める。行をまたぐ呼び出しと、向きで出し分ける三項演算子
#   Messages.get(cond ? "a" : "b")
# があるので行単位では拾えない。第1引数（最初のカンマまで）の中の文字列だけを見る
find "$SRC" -name '*.java' -print0 | xargs -0 perl -CSD -0777 -ne '
    while (/Messages\s*\.\s*(?:get|format)\s*\(/g) {
        my ($rest, $depth, $arg) = (substr($_, pos($_)), 0, "");
        while ($rest =~ /\G("(?:[^"\\]|\\.)*"|[(]|[)]|,|.)/gs) {
            my $t = $1;
            last if $depth == 0 && ($t eq "," || $t eq ")");
            $depth++ if $t eq "(";
            $depth-- if $t eq ")";
            $arg .= $t;
        }
        print "$1\n" while $arg =~ /"([a-zA-Z0-9._]+)"/g;
    }' | sort -u > "$WORK/used.txt"
MISSING=$(comm -23 "$WORK/used.txt" "$WORK/en.txt")
if [ -z "$MISSING" ]; then
    ok "使っているキー $(wc -l < "$WORK/used.txt" | tr -d ' ') 件はすべて英語の表にある"
else
    fail "英語の表に無いキーを使っている（画面に !キー! が出る）"
    echo "$MISSING" | sed 's/^/       /' | head -10
fi
UNUSED=$(comm -13 "$WORK/used.txt" "$WORK/en.txt")
if [ -z "$UNUSED" ]; then
    ok "使われていないキーは無い"
else
    fail "使われていないキーが残っている（消し忘れ）"
    echo "$UNUSED" | sed 's/^/       /' | head -10
fi

# --- 3) 英語と日本語のそろい -------------------------------------------------
echo "== 3) 英語と日本語でキーと差し込みがそろっていること =="
DIFF=$(diff "$WORK/en.txt" "$WORK/ja.txt")
if [ -z "$DIFF" ]; then
    ok "キーがそろっている（$(wc -l < "$WORK/en.txt" | tr -d ' ') 件）"
else
    fail "キーがそろっていない（< は英語だけ、> は日本語だけ）"
    echo "$DIFF" | sed 's/^/       /' | head -10
fi
# 差し込みの番号の集合を「キー<TAB>{0},{1},…」の形で取り出して突き合わせる。
# 数だけでなく番号を見るのは、{0} と {1} を取り違えた訳を見つけるため
placeholders_of() {
    perl -CSD -ne '
        next unless /^\s*"([a-zA-Z0-9._]+)",\s*"((?:[^"\\]|\\.)*)"/;
        my ($key, $value) = ($1, $2);
        my %seen;
        $seen{$1} = 1 while $value =~ /\{(\d+)\}/g;
        print "$key\t", join(",", sort { $a <=> $b } keys %seen), "\n";
    ' "$1" | sort
}
placeholders_of "$EN" > "$WORK/en-ph.txt"
placeholders_of "$JA" > "$WORK/ja-ph.txt"
PH=$(diff "$WORK/en-ph.txt" "$WORK/ja-ph.txt")
if [ -z "$PH" ]; then
    ok "差し込み（{0} {1} …）の番号と数がそろっている"
else
    fail "差し込みが英語と日本語で食い違う（値が出ない・{0} がそのまま出る）"
    echo "$PH" | sed 's/^/       /' | head -10
fi

# --- 4) 起動コマンドの表 -----------------------------------------------------
echo "== 4) 起動コマンド（.sh / .cmd）の文言 =="
# .sh は msg_en / msg_ja の case のラベル、.cmd は if "%~1"=="キー" のキー
sh_keys() {   # $1=関数名
    perl -CSD -0777 -ne '
        my $name = $ARGV[0];
        if (/^'"$1"'\(\)\s*\{(.*?)^\}/ms) {
            my $body = $1;
            print "$1\n" while $body =~ /^\s{4}([a-zA-Z0-9._]+)\)/mg;
        }' "$SH" | sort
}
sh_keys msg_en > "$WORK/sh-en.txt"
sh_keys msg_ja > "$WORK/sh-ja.txt"
SHDIFF=$(diff "$WORK/sh-en.txt" "$WORK/sh-ja.txt")
if [ -s "$WORK/sh-en.txt" ] && [ -z "$SHDIFF" ]; then
    ok ".sh の英語と日本語でキーがそろっている（$(wc -l < "$WORK/sh-en.txt" | tr -d ' ') 件）"
else
    fail ".sh のキーがそろっていない（または表を読めない）"
    echo "$SHDIFF" | sed 's/^/       /' | head -10
fi
cmd_keys() {   # $1=ラベル名
    LC_ALL=C.UTF-8 perl -CSD -0777 -ne '
        if (/^:'"$1"'\r?$(.*?)^exit \/b 0\r?$/ms) {
            my $body = $1;
            print "$1\n" while $body =~ /if "%~1"=="([a-zA-Z0-9._]+)"/g;
        }' "$WORK/cmd.txt" | sort
}
# .cmd は MS932 なので UTF-8 に直してから読む
if iconv -f CP932 -t UTF-8 "$CMD" > "$WORK/cmd.txt" 2>/dev/null; then
    cmd_keys msg_en > "$WORK/cmd-en.txt"
    cmd_keys msg_ja > "$WORK/cmd-ja.txt"
    CMDDIFF=$(diff "$WORK/cmd-en.txt" "$WORK/cmd-ja.txt")
    if [ -s "$WORK/cmd-en.txt" ] && [ -z "$CMDDIFF" ]; then
        ok ".cmd の英語と日本語でキーがそろっている（$(wc -l < "$WORK/cmd-en.txt" | tr -d ' ') 件）"
    else
        fail ".cmd のキーがそろっていない（または表を読めない）"
        echo "$CMDDIFF" | sed 's/^/       /' | head -10
    fi
    # 表（msg_ja）の外に日本語の出力が残っていないこと。コメント（rem）は日本語のままでよい
    STRAY=$(perl -CSD -ne '
        $in = 1 if /^:msg_ja\r?$/;
        $in = 0 if $in && /^exit \/b 0\r?$/ && $seen++;
        next if $in;
        next if /^\s*rem\b/;
        print "$.: $_" if /^\s*(echo|set \/p|choice)\b.*[\x{3040}-\x{30ff}\x{4e00}-\x{9fff}]/;
    ' "$WORK/cmd.txt")
    if [ -z "$STRAY" ]; then
        ok ".cmd の表の外に日本語の出力が残っていない"
    else
        fail ".cmd の表の外に日本語の出力が残っている（英語の利用者に読めない行が出る）"
        echo "$STRAY" | sed 's/^/       /' | head -10
    fi
else
    fail ".cmd を MS932 として読めない（UTF-8 で保存し直された？）"
fi
# .sh も同じく、表の外に日本語の出力が無いこと
SHSTRAY=$(perl -CSD -ne '
    $in = 1 if /^msg_(en|ja)\(\)/;
    $in = 0 if $in && /^\}/;
    next if $in;
    next if /^\s*#/;
    print "$.: $_" if /^\s*(echo|printf)\b.*[\x{3040}-\x{30ff}\x{4e00}-\x{9fff}]/;
' "$SH")
if [ -z "$SHSTRAY" ]; then
    ok ".sh の表の外に日本語の出力が残っていない"
else
    fail ".sh の表の外に日本語の出力が残っている"
    echo "$SHSTRAY" | sed 's/^/       /' | head -10
fi

# --- 5) 実際に読ませる -------------------------------------------------------
echo "== 5) 実際に読ませて、言語の決まり方を見る =="
# Messages は JDT に触らないので、この 3 ファイルと検査プログラムだけでコンパイルできる
if ! javac --release 17 -nowarn -d "$WORK/classes" -encoding UTF-8 \
        "$SRC/jche/util/Messages.java" "$EN" "$JA" NlsProbe.java 2> "$WORK/javac.log"; then
    fail "Messages を JDT 無しでコンパイルできない"
    sed 's/^/       /' "$WORK/javac.log" | head -10
else
    ok "Messages が JDT の jar 無しでコンパイルできる"
    # $1=説明  $2=期待する行  $3=環境変数（VAR=値 を空白区切り。無ければ ""）  $4…=java への引数。
    # -D で始まるものは JVM のオプション、それ以外は NlsProbe への引数として渡す。
    # サブシェルで囲わない（囲うと失敗を数える ng が外に伝わらず、NG が出ていても PASS になる）
    probe() {
        local label=$1 want=$2 envs=$3
        shift 3
        local a out
        local -a jvm=() prog=()
        for a in "$@"; do
            case "$a" in -*) jvm+=("$a") ;; *) prog+=("$a") ;; esac
        done
        # shellcheck disable=SC2086  # $envs は VAR=値 の並びとして分割させたい
        out=$(env -u JCHE_LANG $envs java -cp "$WORK/classes" ${jvm[@]+"${jvm[@]}"} NlsProbe \
                ${prog[@]+"${prog[@]}"} 2>&1)
        if grep -qxF -- "$want" <<<"$out"; then
            ok "$label"
        else
            fail "$label（「$want」が出力に無い）"
            echo "$out" | sed 's/^/       /' | head -5
        fi
    }
    # 既定（言語を何も指定しない）は英語
    probe "既定は英語" "menu= q) Quit" "LC_ALL=C LANG=C"
    probe "差し込みが効く" "format=  No such file: X.properties" "LC_ALL=C LANG=C"
    probe "無いキーは !キー! で見える" "missing=!no.such.key!" "LC_ALL=C LANG=C"
    # OS の言語（JVM の既定ロケール）。ja のロケールが無い環境でも見られるよう user.language で与える
    probe "OS の言語が日本語なら日本語" "language=ja" "" -Duser.language=ja
    # 環境変数・システムプロパティ
    probe "JCHE_LANG=ja で日本語" "menu= q) 終了" "JCHE_LANG=ja"
    probe "jche.lang=ja で日本語" "menu= q) 終了" "" -Djche.lang=ja
    # 設定ファイル（message.language）
    probe "設定ファイルの message.language=ja が効く" "language=ja" "" configured=ja
    # 1 つの JVM で設定を続けて読む（引数に設定を複数渡す・対話モードで繰り返す・サーバーの ANALYZE）とき、
    # 空欄の設定は前の設定の言語を引き継がず、OS の言語に戻る
    probe "空欄の設定は前の設定の言語を引き継がない（OS が英語）" "language=en" "LC_ALL=C LANG=C" \
        configured=ja configured=
    probe "空欄の設定は前の設定の言語を引き継がない（OS が日本語）" "language=ja" "" -Duser.language=ja \
        configured=en configured=
    # 優先順位: 環境変数・システムプロパティ > 設定ファイル
    probe "環境変数は設定ファイルより強い" "language=en" "JCHE_LANG=en" configured=ja
    probe "システムプロパティも設定ファイルより強い" "language=en" "" -Djche.lang=en configured=ja
    # 訳の無い言語・地域付き
    probe "訳の無い言語は英語に落ちる" "language=en" "JCHE_LANG=fr"
    probe "地域付き（ja_JP）も日本語" "language=ja" "JCHE_LANG=ja_JP"
fi

# --- 6) 出力 CSV は言語で変わらない -----------------------------------------
echo "== 6) 出力 CSV が言語で変わらないこと =="
JBANG="bash $ROOT/jbangw/jbang"
CP=$($JBANG info classpath "$ROOT/src/jche/CallHierarchyExporter.java" | tr ':' '\n' | grep -v '/cache/jars/' | paste -sd:)
JAVA_HOME_25=$($JBANG jdk home 25)
if [ -z "$CP" ] || [ -z "$JAVA_HOME_25" ]; then
    fail "jbang から JDT の classpath または JDK 25 を取得できませんでした"
else
    rm -rf build .cache output
    if ! "$JAVA_HOME_25/bin/javac" --release 17 -nowarn -encoding UTF-8 \
            -cp "$CP" -d build $(find "$SRC" -name '*.java') 2> "$WORK/javac2.log"; then
        fail "ツール本体をコンパイルできない"
        sed 's/^/       /' "$WORK/javac2.log" | head -10
    else
        latest() { ls -d output/*/ 2>/dev/null | sort | tail -1 | sed 's#/$##'; }
        run_in() {   # $1=言語 -> 出力フォルダを $OUT に入れる
            JCHE_LANG=$1 "$JAVA_HOME_25/bin/java" -Dstdout.encoding=UTF-8 -cp "build:$CP" \
                jche.CallHierarchyExporter config.properties > "$WORK/run-$1.log" 2>&1
            OUT=$(latest)
        }
        run_in en; EN_OUT=$OUT
        run_in ja; JA_OUT=$OUT
        if [ -z "$EN_OUT" ] || [ -z "$JA_OUT" ] || [ "$EN_OUT" = "$JA_OUT" ]; then
            fail "2 回ぶんの出力フォルダができていない"
            tail -5 "$WORK/run-ja.log" | sed 's/^/       /'
        else
            same=1
            for f in call-hierarchy.csv methods.csv call-conditions.csv; do
                if diff -q "$EN_OUT/$f" "$JA_OUT/$f" > /dev/null 2>&1; then
                    ok "$f は言語で変わらない"
                else
                    fail "$f が言語で変わっている（CSV のセルは英語で固定する）"
                    diff "$EN_OUT/$f" "$JA_OUT/$f" 2>&1 | sed 's/^/       /' | head -6
                    same=0
                fi
            done
            # ログのほうは言語で変わること（変わらないなら、そもそも切り替わっていない）
            if diff -q "$WORK/run-en.log" "$WORK/run-ja.log" > /dev/null 2>&1; then
                fail "ログが言語で変わっていない（言語が切り替わっていない）"
            else
                ok "ログは言語で変わる"
            fi
        fi
    fi
    rm -rf build .cache output
fi

if [ "$ng" = 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
