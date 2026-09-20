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
# 文言の言語を固定する（既定は英語。固定しないと実行環境のロケールで照合が変わる）
export JCHE_LANG=en
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

# session() はプロトコルの行だけを返す（#L のログは捨てる）。ログの中身を見たい検査だけが使う変種
session_log() {
    printf '%b' "$1" | java -cp "$JCHE_CP" jche.CallHierarchyExporter --server "$WORK/cache" 2>/dev/null
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

# Eclipse / VSCode プラグインは、設定ファイルをプロジェクトの中ではなく自分の作業フォルダに
# 書き出して渡す（利用者にファイルを書かせないため）。設定の相対パスの起点は「設定ファイルのフォルダ」
# なので、そこに project.root=. と書くと<作業フォルダ自身>を解析対象だと解釈してしまう。
# 実際それで「ソースフォルダを特定できませんでした」と出て解析が1行も動かなかったため、
# 「プロジェクトの外に置いた設定＋絶対パスの project.root」で動くことを、ここで固定する
# （docs/eclipse-plugin-folders-qa.md の Q1）
echo "== プロジェクトの外に置いた設定（プラグインが渡す形） =="
mkdir -p "$WORK/scratch" "$WORK/outside-output"
cat > "$WORK/scratch/generated-config.properties" <<EOF
project.root=$ROOT/test/demo
source.folders=src
library.jars=
source.encoding=UTF-8
source.level=
output.folder=$WORK/outside-output
EOF
OUT=$(session "ANALYZE\t$WORK/scratch/generated-config.properties\nFIND\t$TARGET\nSHUTDOWN\n")
echo "$OUT" | grep -E '^(OK|NG)' | sed 's/^/       /'
grep -qE "^OK${T}analyzed=1${T}methods=[0-9]+" <<<"$OUT" \
    && ok "設定がプロジェクトの外にあっても、絶対パスの project.root なら解析できる" \
    || fail "プロジェクトの外に置いた設定で解析できない"
grep -qE "^OK${T}how=exact" <<<"$OUT" && ok "その結果を FIND で引ける" || fail "外に置いた設定の結果を引けない"
# 出力先も設定ファイルのフォルダに引きずられないこと（作業フォルダに結果が積もらないように）
[ -z "$(ls "$WORK/scratch" | grep -v generated-config.properties)" ] \
    && ok "設定ファイルのフォルダに出力が作られない" \
    || fail "設定ファイルのフォルダに出力が作られている: $(ls "$WORK/scratch")"

# 同じ設定で project.root だけ相対（.）にすると失敗する。上の指定が効いていることの裏取り
echo "== 同じ場所に project.root=. と書くと失敗する（上の指定が効いている証拠） =="
sed "s|^project.root=.*|project.root=.|" "$WORK/scratch/generated-config.properties" \
    > "$WORK/scratch/relative-config.properties"
OUT=$(session "ANALYZE\t$WORK/scratch/relative-config.properties\nSHUTDOWN\n")
grep -q "Could not determine a source folder" <<<"$OUT" \
    && ok "相対の project.root は設定ファイルのフォルダを指してしまう（想定どおり失敗）" \
    || fail "project.root=. でも通ってしまう（この検査の前提が崩れている）"

# 構文エラーでファイルを読めなくても、JDT は例外を投げない（壊れた部分を飛ばした AST が返る）。
# そのため以前は「失敗=0」「OK」と報告したまま、そのファイルの呼び出しが丸ごと落ちていた。
# 「呼び出しを静かに落とさない」に反するので、数えて報告するようにした
# （docs/syntax-error-report-qa.md）。キャッシュを再利用したときも言い続けることが肝
echo "== 構文エラーのファイルを黙って落とさない =="
mkdir -p "$WORK/syntax/src/app"
# Java 5 より前の書き方。いまの JDT は source.level<1.8 を受け付けず 1.8 として読むので
# enum が予約語になり、このメソッドの本体がまるごと読めなくなる
cat > "$WORK/syntax/src/app/Legacy.java" <<'EOF'
package app;
public class Legacy {
    public void run() {
        java.util.Enumeration enum = null;
        handle(enum);
    }
    void handle(Object o) { report(o); }
    void report(Object o) { }
}
EOF
cat > "$WORK/syntax/c.properties" <<EOF
project.root=$WORK/syntax
source.folders=src
source.encoding=UTF-8
source.level=1.4
output.folder=$WORK/syntax/out
cache.folder=$WORK/syntax/cache
EOF
OUT=$(session_log "ANALYZE\t$WORK/syntax/c.properties\nSHUTDOWN\n")
grep -q "syntax errors=1" <<<"$OUT" && ok "フェーズ1の集計に構文エラーの件数が出る" \
    || fail "構文エラーが集計に出ない（黙って落ちている）"
grep -q "src/app/Legacy.java" <<<"$OUT" && ok "読めなかったファイル名がログに出る" \
    || fail "読めなかったファイル名が分からない"
grep -qE "^OK${T}analyzed=1.*${T}syntaxErrors=1" <<<"$OUT" \
    && ok "ANALYZE の応答が syntaxErrors を返す（画面がバナーに出せる）" \
    || fail "応答に syntaxErrors が無い"

# 2回目。キャッシュから書き写すだけでも、結果が欠けている事実は変わらない
OUT=$(session_log "ANALYZE\t$WORK/syntax/c.properties\nSHUTDOWN\n")
grep -q "reused=1" <<<"$OUT" && ok "2回目はキャッシュを再利用する" || fail "2回目に再利用されていない"
grep -q "syntax errors=1" <<<"$OUT" \
    && ok "再利用したときも構文エラーを言い続ける（F行に持っているため）" \
    || fail "2回目に警告が消える（キャッシュに残していない）"

# 対照。同じコードから enum だけ直せば、警告は出ず呼び出しも揃う
sed 's/\benum\b/it/g' "$WORK/syntax/src/app/Legacy.java" > "$WORK/syntax/src/app/Legacy.java.tmp"
mv "$WORK/syntax/src/app/Legacy.java.tmp" "$WORK/syntax/src/app/Legacy.java"
OUT=$(session_log "ANALYZE\t$WORK/syntax/c.properties\nSHUTDOWN\n")
grep -q "syntax errors" <<<"$OUT" && fail "直したのに構文エラーが残っている" \
    || ok "直せば警告は出ない（型解決のエラーでは警告しない）"
grep -qE "^OK${T}analyzed=1.*${T}syntaxErrors=0" <<<"$OUT" \
    && ok "応答も syntaxErrors=0 に戻る" || fail "応答が 0 に戻らない"

echo "== AT（カーソル位置から囲むメソッドを引く） =="
# 複数行のメソッド。test/demo/src/fx/dao/UserDaoImpl.java の load(long) は 7〜10 行目
AT_FILE='src/fx/dao/UserDaoImpl.java'
RANGED='fx.dao.UserDaoImpl#load(long)'
FOUND=$(session "ANALYZE\t$CONFIG\nFIND\t$RANGED\nSHUTDOWN\n" | grep -E "^OK${T}how=exact")
echo "$FOUND" | sed 's/^/       /'
field() { tr '\t' '\n' <<<"$FOUND" | grep "^$1=" | head -1 | cut -d= -f2-; }
AT_LINE=$(field line)
AT_END=$(field endLine)
[ -n "$AT_END" ] && [ "$AT_END" -gt "$AT_LINE" ] \
    && ok "FIND が複数行メソッドの endLine を返す（$AT_LINE..$AT_END）" \
    || fail "endLine が宣言行より後になっていない（$AT_LINE..$AT_END）"

# 本体の中・閉じ括弧の行・メソッドの外（フィールドも宣言も無い行）で引く
INSIDE=$((AT_LINE + 1))
OUT=$(session "ANALYZE\t$CONFIG\nAT\t$AT_FILE\t$INSIDE\nAT\t$AT_FILE\t$AT_END\nAT\t$AT_FILE\t3\nAT\t$AT_FILE\txx\nAT\nSHUTDOWN\n")
echo "$OUT" | grep -E '^(OK|NG)' | sed 's/^/       /'
HITS=$(grep -cF "how=enclosing${T}key=$RANGED${T}" <<<"$OUT")
[ "$HITS" = 2 ] && ok "本体の中と閉じ括弧の行で、囲むメソッドが引ける" \
    || fail "AT が囲むメソッドを返さない（一致 $HITS 件）"
grep -qE "^NG${T}not-found" <<<"$OUT" && ok "メソッドの外の行は not-found（直前のメソッドを返さない）" \
    || fail "メソッド外の行が not-found にならない"
grep -qE "^NG${T}bad-line" <<<"$OUT" && ok "行番号でない引数は bad-line" || fail "bad-line を返さない"
grep -qE "^NG${T}missing-position" <<<"$OUT" && ok "引数が無ければ missing-position" \
    || fail "missing-position を返さない"

echo "== キーがずれていても、一意に決まるなら拾う =="
# 引数の型名がずれたキー（プラグインはソースの型名から組み立てるのでこうなることがある）
OUT=$(session "ANALYZE\t$CONFIG\nFIND\tfx.app.Main#run(java.lang.Object[])\nSHUTDOWN\n")
grep -qE "^OK${T}how=loose${T}key=fx\.app\.Main#run\(java\.lang\.String\[\]\)" <<<"$OUT" \
    && ok "型名がずれたキーを、引数の数で一意に決めて拾う" || fail "ゆるい照合が効いていない"

echo "== AT（相対パス・絶対パス・未解析ファイルの言い分け） =="
# エディタのプラグインはキーを組み立てずに、カーソルの位置だけを送る（docs/vscode-plugin-design.md §4）。
# test/demo の DaoFactory.java は 6〜8 行目 create() / 10〜12 行目 newUserDao() / 25〜27 行目 passThrough()、28 行目はクラスの }
AT_FILE=src/fx/dao/DaoFactory.java
OUT=$(session "ANALYZE\t$CONFIG\nAT\t$AT_FILE\t7\nAT\t$AT_FILE\t1\nAT\t$AT_FILE\t28\nAT\t$ROOT/test/demo/$AT_FILE\t11\nAT\tsrc/fx/dao/NoSuchFile.java\t3\nSHUTDOWN\n")
echo "$OUT" | grep -E '^(OK|NG)' | sed 's/^/       /'
grep -qE "^OK${T}how=enclosing${T}key=fx\.dao\.DaoFactory#create\(\)${T}.*${T}line=6${T}endLine=8${T}callers=[0-9]+" <<<"$OUT" \
    && ok "本体の行（7）から、その行を囲む create()（6〜8行目）を引く" || fail "AT が囲みメソッドを引けていない"
grep -qE "^OK${T}how=enclosing${T}key=fx\.dao\.DaoFactory#newUserDao\(\)" <<<"$OUT" \
    && ok "プロジェクトルート配下の絶対パスでも引ける" || fail "絶対パスを受けられていない"
# 最初のメソッドより前（1 行目の package）も、最後のメソッドより後（28 行目のクラスの }）も not-found。
# 終了行を持っているので、直前のメソッドを返すことはない（docs/method-decl-range-qa.md）
NOTFOUND=$(grep -cE "^NG${T}not-found" <<<"$OUT")
[ "$NOTFOUND" -eq 2 ] && ok "宣言部（1行目）とクラスの末尾（28行目）は not-found" \
    || fail "メソッドの外の行が not-found にならない（$NOTFOUND 件）"
# 解析対象に無いファイルは not-found と言い分ける（設定漏れか新規ファイルかが分かるように）
grep -qE "^NG${T}file-not-analyzed" <<<"$OUT" && ok "解析していないファイルは file-not-analyzed" || fail "未解析ファイルの断り方が違う"

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
