#!/usr/bin/env bash
# サーバーモード（jche.CallHierarchyExporter --server）の検査。
#
#   bash test/server/run.sh
#   JCHE_CP="<classpath>" bash test/server/run.sh     # 既にコンパイル済みのクラスを使う
#
# Eclipse プラグインはこのプロトコルだけを頼りに解析結果を取り出す（別プロセス・別JDK）。
# つまりここが壊れると画面には何も出ないので、要求と応答の形を1件ずつ確かめる。
# 解析そのものの正しさは test/regression が受け持つので、ここでは見ない。
#
# JCHE_CP が無ければ jbang に依存を解決させてコンパイルする（JDK と jbang が要る）。
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

CONFIG=$ROOT/test/regression/whole/config.properties
# test/demo の中で呼び出し元が多いメソッド。存在しなくなったらこの検査も直すこと
TARGET='fx.dao.UserDaoImpl#<init>()'
# 応答は TAB 区切り。grep の正規表現に \t は使えないので本物の TAB を変数で持つ
T=$(printf '\t')
ng=0
ok()   { echo "  OK   $1"; }
fail() { echo "  NG   $1"; ng=$((ng + 1)); }

if [ -z "${JCHE_CP:-}" ]; then
    echo "== classpath を用意する（jbang）"
    CP=$(bash "$ROOT/jbangw/jbang" info classpath "$ROOT/src/jche/CallHierarchyExporter.java" \
        | tr ':' '\n' | grep -v '/cache/jars/' | paste -sd:)
    if [ -z "$CP" ]; then
        echo "NG   依存を解決できませんでした"; echo "FAIL"; exit 1
    fi
    javac --release 17 -nowarn -cp "$CP" -d "$WORK/classes" -encoding UTF-8 \
        $(find "$ROOT/src" -name '*.java') || { echo "FAIL"; exit 1; }
    JCHE_CP="$CP:$WORK/classes"
fi

# 1セッションぶんの要求を流し、応答（#L のログ行を除く）を返す
session() {
    printf '%b' "$1" | java -cp "$JCHE_CP" jche.CallHierarchyExporter --server "$WORK/cache" 2>"$WORK/stderr" \
        | grep -v '^#L'
    # 何も返らないときは起動に失敗している（classpath が届いていない等）。
    # 以降の検査が全部 NG になって原因が見えなくなるので、標準エラーをそのまま見せる
    if [ ! -s "$WORK/stderr" ]; then
        return 0
    fi
    if grep -qE 'Error: |Exception in thread' "$WORK/stderr"; then
        echo "  NG   サーバーが起動できませんでした:" >&2
        sed 's/^/       /' "$WORK/stderr" >&2
    fi
}

echo "== 応答の形 =="
OUT=$(session 'HELLO\t1\nPING\nSHUTDOWN\n')
echo "$OUT" | sed 's/^/       /'
grep -qE "^OK${T}protocol=1${T}jdt=.+${T}jvm=.+${T}maxJava=" <<<"$OUT" \
    && ok "HELLO が protocol・jdt・jvm・maxJava を返す" || fail "HELLO の応答が期待と違う"
grep -qE "^OK${T}pong" <<<"$OUT" && ok "PING に応える" || fail "PING に応えない"
grep -qE "^OK${T}bye" <<<"$OUT" && ok "SHUTDOWN で終わる" || fail "SHUTDOWN の応答が無い"

echo "== 解析前の要求は断る =="
OUT=$(session "FIND\t$TARGET\nSHUTDOWN\n")
grep -qE "^NG${T}not-analyzed" <<<"$OUT" && ok "解析前の FIND は not-analyzed" || fail "解析前の FIND が断られない"

echo "== 解析と問い合わせ =="
OUT=$(session "ANALYZE\t$CONFIG\nFIND\t$TARGET\nFIND\tno.such.Type#nope()\nTREE\t$TARGET\tcallers\tdepth=3\nSHUTDOWN\n")
echo "$OUT" | grep -E '^(OK|NG)' | sed 's/^/       /'
grep -qE "^OK${T}analyzed=1${T}methods=[0-9]+${T}edges=[0-9]+${T}inbound=[0-9]+" <<<"$OUT" \
    && ok "ANALYZE が件数と時刻を返す" || fail "ANALYZE の応答が期待と違う"
grep -q '^#P' <<<"$OUT" && ok "進捗（#P）が流れる" || fail "進捗が流れない"
grep -qE "^OK${T}how=exact${T}key=.*callers=[0-9]+" <<<"$OUT" && ok "FIND が見つけたメソッドを返す" || fail "FIND の応答が期待と違う"
grep -qE "^NG${T}not-found" <<<"$OUT" && ok "無いメソッドの FIND は not-found" || fail "無いメソッドが not-found にならない"

ROWS=$(grep -cE "^R${T}" <<<"$OUT")
[ "$ROWS" -gt 3 ] && ok "TREE が $ROWS 行を返す" || fail "TREE の行数が少なすぎる（$ROWS）"
grep -qE "^R${T}0${T}${TARGET//./\\.}" <<<"$OUT" && ok "先頭行が根（深さ0）" || fail "先頭行が根になっていない"
grep -qE "^R${T}1${T}" <<<"$OUT" && ok "呼び出し元（深さ1）が出る" || fail "呼び出し元が出ていない"
MAXDEPTH=$(grep -E "^R${T}" <<<"$OUT" | cut -f2 | sort -n | tail -1)
[ "$MAXDEPTH" -le 3 ] && ok "深さの上限（3）が効いている" || fail "深さ上限を超えている（$MAXDEPTH）"

echo "== キーがずれていても、一意に決まるなら拾う =="
# 引数の型名がずれたキー（プラグインはソースの型名から組み立てるのでこうなることがある）
OUT=$(session "ANALYZE\t$CONFIG\nFIND\tfx.app.Main#run(java.lang.Object[])\nSHUTDOWN\n")
grep -qE "^OK${T}how=loose${T}key=fx\.app\.Main#run\(java\.lang\.String\[\]\)" <<<"$OUT" \
    && ok "型名がずれたキーを、引数の数で一意に決めて拾う" || fail "ゆるい照合が効いていない"

echo "== AT（ファイルと行から囲みメソッドを引く） =="
# エディタのプラグインはキーを組み立てずに、カーソルの位置だけを送る（docs/vscode-plugin-design.md §4）。
# test/demo の DaoFactory.java は 6行目 create() / 10行目 newUserDao() / 25行目 passThrough() の順に並ぶ
AT_FILE=src/fx/dao/DaoFactory.java
OUT=$(session "ANALYZE\t$CONFIG\nAT\t$AT_FILE\t7\nAT\t$AT_FILE\t1\nAT\t$AT_FILE\t28\nAT\t$ROOT/test/demo/$AT_FILE\t11\nAT\tsrc/fx/dao/NoSuchFile.java\t3\nAT\t$AT_FILE\txx\nSHUTDOWN\n")
echo "$OUT" | grep -E '^(OK|NG)' | sed 's/^/       /'
grep -qE "^OK${T}how=at${T}key=fx\.dao\.DaoFactory#create\(\)${T}.*${T}line=6${T}callers=[0-9]+" <<<"$OUT" \
    && ok "本体の行（7）から、その行を囲む create()（宣言は6行目）を引く" || fail "AT が囲みメソッドを引けていない"
grep -qE "^OK${T}how=at${T}key=fx\.dao\.DaoFactory#newUserDao\(\)" <<<"$OUT" \
    && ok "プロジェクトルート配下の絶対パスでも引ける" || fail "絶対パスを受けられていない"
# 最初のメソッドより前（package 宣言）は「その行を囲むメソッドが無い」ので not-found
grep -qE "^NG${T}not-found" <<<"$OUT" && ok "宣言部（1行目）は not-found" || fail "宣言部が not-found にならない"
# 解析対象に無いファイルは not-found と言い分ける（設定漏れか新規ファイルかが分かるように）
grep -qE "^NG${T}file-not-analyzed" <<<"$OUT" && ok "解析していないファイルは file-not-analyzed" || fail "未解析ファイルの断り方が違う"
grep -qE "^NG${T}bad-line" <<<"$OUT" && ok "行番号でない引数は bad-line" || fail "壊れた行番号の断り方が違う"
# 宣言の終了行は持っていないので、メソッドの外（末尾の }）でも直前のメソッドを返す。
# これは承知のうえの割り切りで、呼び出し側は返った line= を画面に出して気づけるようにする（Issue #115）
grep -qE "^OK${T}how=at${T}key=fx\.dao\.DaoFactory#passThrough\(fx\.dao\.Dao\)${T}.*${T}line=25${T}" <<<"$OUT" \
    && ok "メソッドの外（28行目）は直前のメソッドを返す（終了行を持たない割り切り）" \
    || fail "メソッド外の扱いが期待と違う"

OUT=$(session "AT\t$AT_FILE\t7\nSHUTDOWN\n")
grep -qE "^NG${T}not-analyzed" <<<"$OUT" && ok "解析前の AT は not-analyzed" || fail "解析前の AT が断られない"

echo "== フィルタは解析をやり直さない =="
OUT=$(session "ANALYZE\t$CONFIG\nTREE\t$TARGET\tcallers\tdepth=5\nTREE\t$TARGET\tcallers\tdepth=5\ttext=zzz-no-such-name\nSHUTDOWN\n")
WIDE=$(grep -oE "^OK${T}rows=[0-9]+" <<<"$OUT" | head -1 | grep -oE '[0-9]+')
NARROW=$(grep -oE "^OK${T}rows=[0-9]+" <<<"$OUT" | sed -n 2p | grep -oE '[0-9]+')
if [ -n "$WIDE" ] && [ -n "$NARROW" ] && [ "$NARROW" -lt "$WIDE" ]; then
    ok "絞り込み文字列で行が減る（$WIDE -> $NARROW 行）"
else
    fail "絞り込みが効いていない（$WIDE -> ${NARROW:-?}）"
fi
ANALYZED=$(grep -cE "^OK${T}analyzed=1" <<<"$OUT")
[ "$ANALYZED" -eq 1 ] && ok "TREE を2回投げても解析は1回だけ" || fail "解析が複数回走っている（$ANALYZED）"

echo "== CSV 出力 =="
OUT=$(session "ANALYZE\t$CONFIG\nEXPORT\t$TARGET\tcallers\t$WORK/out.csv\tdepth=3\nSHUTDOWN\n")
grep -qE "^OK${T}rows=[0-9]+${T}file=" <<<"$OUT" && ok "EXPORT が行数と出力先を返す" || fail "EXPORT の応答が期待と違う"
if [ -s "$WORK/out.csv" ] && head -1 "$WORK/out.csv" | grep -q 'depth,method,file,line,reason,note'; then
    ok "CSV に見出しと中身がある（$(( $(wc -l < "$WORK/out.csv") - 1 )) 行）"
else
    fail "CSV が書けていない"
fi

# 解析の最中に SHUTDOWN が届いても、その解析は完走してから終わること。
# SHUTDOWN は「積んだ要求を処理し終えてから終わる」という意味で、中止は CANCEL の役目
# （かつては読み取りスレッドが SHUTDOWN を見た時点で中止フラグを立てていたため、
#   要求をまとめて流し込むと ANALYZE が読み取りの速さ次第で中止されていた）。
# 解析を始めてから SHUTDOWN を送るために、あいだに少し間を置く
echo "== 解析中の SHUTDOWN は解析を中止しない =="
OUT=$( (printf 'ANALYZE\t%s\n' "$CONFIG"; sleep 0.3; printf 'SHUTDOWN\n') \
    | java -cp "$JCHE_CP" jche.CallHierarchyExporter --server "$WORK/cache" 2>/dev/null | grep -v '^#L')
grep -qE "^OK${T}analyzed=1" <<<"$OUT" && ok "解析は完走する" \
    || fail "解析が中止された（$(grep -m1 -E '^(OK|NG)' <<<"$OUT" | head -c 40)）"
grep -qE "^OK${T}bye" <<<"$OUT" && ok "そのあと終わる" || fail "SHUTDOWN で終わっていない"

# 中止したいときは CANCEL。こちらは実行中の解析を打ち切る
echo "== CANCEL は解析を中止する =="
OUT=$( (printf 'ANALYZE\t%s\n' "$CONFIG"; sleep 0.3; printf 'CANCEL\nSHUTDOWN\n') \
    | java -cp "$JCHE_CP" jche.CallHierarchyExporter --server "$WORK/cache2" 2>/dev/null | grep -v '^#L')
grep -qE "^NG${T}cancelled" <<<"$OUT" && ok "CANCEL で中止される" \
    || fail "CANCEL が効いていない（$(grep -m1 -E '^(OK|NG)' <<<"$OUT" | head -c 40)）"

echo "== 知らない要求 =="
OUT=$(session 'NOSUCHCOMMAND\tx\nSHUTDOWN\n')
grep -qE "^NG${T}unknown-command" <<<"$OUT" && ok "知らない要求は NG を返して落ちない" || fail "知らない要求の扱いが違う"

if [ "$ng" -eq 0 ]; then echo "PASS"; else echo "FAIL ($ng 件)"; exit 1; fi
