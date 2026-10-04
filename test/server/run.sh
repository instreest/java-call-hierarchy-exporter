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

CONFIG=$ROOT/test/regression/whole/jche.properties
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

echo "== AT（同じ行に並ぶ宣言） =="
# 1 行に書いた 2 つのメソッドや、1 行に書いたメソッドとその中のラムダは、範囲（宣言行〜終了行）の広さが同じになる。
# 同着は宣言の位置が先のもの（同じ行なら先に宣言したもの。入れ子なら外側）を採る。以前は ID の小さいものを採っていて、
# 戻り値の出所（R 行）を持つ側（b・2 つ目のラムダ）が先に ID 化されるため、そちらが返っていた
# （docs/deterministic-row-order-qa.md の Q14）。使い捨てのプロジェクトをその場で作る
mkdir -p "$WORK/oneline/src/p"
cat > "$WORK/oneline/src/p/OneLine.java" <<'EOF'
package p;
public class OneLine {
    int a() { return 1; } Object b() { return new Object(); }
    void pair() { use(() -> ready(), () -> make()); }
    static void use(java.util.function.BooleanSupplier c, java.util.function.Supplier<Object> m) { }
    static boolean ready() { return true; }
    static Object make() { return new Object(); }
}
EOF
cat > "$WORK/oneline/jche.properties" <<EOF
project.root=$WORK/oneline
source.folders=src
library.jars=
source.encoding=UTF-8
source.level=
output.folder=$WORK/oneline/output
EOF
OUT=$(session "ANALYZE\t$WORK/oneline/jche.properties\nAT\tsrc/p/OneLine.java\t3\nAT\tsrc/p/OneLine.java\t4\nSHUTDOWN\n")
echo "$OUT" | grep -E '^(OK|NG)' | sed 's/^/       /'
grep -qE "^OK${T}how=enclosing${T}key=p\.OneLine#a\(\)${T}" <<<"$OUT" \
    && ok "1 行に並ぶ 2 つのメソッドは、先に宣言した a() を返す" \
    || fail "1 行に並ぶ 2 つのメソッドで、先に宣言した a() を返さない"
grep -qE "^OK${T}how=enclosing${T}key=p\.OneLine#pair\(\)${T}" <<<"$OUT" \
    && ok "1 行に書いたメソッドとその中のラムダは、外側の pair() を返す" \
    || fail "1 行に書いたメソッドとその中のラムダで、外側の pair() を返さない"

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

# 中止しても一時ファイル（依存の索引・エッジの記録・型解決に失敗した呼び出しの行。jche.cache.TempFiles）を残さないこと
# （サーバーは IDE が開いているあいだ動き続け、同じキャッシュのフォルダを何度も使う）。
# ここはサーバーが終わってから見る。サーバーが動いたままでの確認は、次のグラフの構築中の CANCEL で行う
temp_files_in() { find "$1" \( -name '*.deps-*.tmp' -o -name '*.edges-*.tmp' -o -name '*.unresolved-*.tmp' \) 2>/dev/null; }
LEFT=$(temp_files_in "$WORK/cache2")
[ -z "$LEFT" ] && ok "中止しても一時ファイルが残らない" || fail "中止したあとに一時ファイルが残っている: $LEFT"

# 上の CANCEL は解析の最初（ソースの読み取り）に届き、一時ファイルはまだ無い。グラフの構築中（エッジの記録＝
# 一時ファイルがあるあいだ）に届いた中止でも、その一時ファイルを消すこと。グラフの構築の進捗（#P）が 5% を
# 過ぎたのを見て（エッジの記録ができていることも確かめて）から CANCEL を送る。
# test/demo はグラフの構築が数十ミリ秒で終わり、CANCEL が構築の後に届いてしまうので、このツール自身のソースを
# 依存 jar なしで解析する（エッジが 1 万本ほどあり、構築に 0.1 秒以上かかる）。
# 応答は 1 行ずつ読む（まとめて読むと、構築を終えたしるしのログ行 Collected: を読み飛ばしうる）。
# ソースの読み取りのあいだはログ行が少なく、読み手は遅れずについていけるので、進捗が届いてから CANCEL を送るまでは短い
echo "== グラフの構築中の CANCEL も一時ファイルを残さない =="
mkdir -p "$WORK/self"
cat > "$WORK/self/c.properties" <<EOF
project.root=$ROOT
source.folders=src
library.folders=
library.build.tool=none
source.encoding=UTF-8
output.folder=$WORK/self/out
cache.folder=$WORK/self/cache
EOF
coproc SRV { java -cp "$JCHE_CP" jche.CallHierarchyExporter --server "$WORK/cache3" 2>/dev/null; }
# サーバーが終わると bash は SRV・SRV_PID を消すので、先に控えておく
SRV_OUT=${SRV[0]}
SRV_IN=${SRV[1]}
SRV_PROC=$SRV_PID
printf 'ANALYZE\t%s\n' "$WORK/self/c.properties" >&"$SRV_IN"
SENT=0 SPILL_AT_SEND="" BUILT_BEFORE_NG=0 RESPONSE=""
while IFS= read -r -t 300 line <&"$SRV_OUT"; do
    if [ "$SENT" = 0 ] && [[ "$line" == "#P${T}Building the graph${T}"* ]]; then
        IFS="$T" read -r _ _ DONE TOTAL <<<"$line"
        [ $((DONE * 20)) -gt "$TOTAL" ] || continue
        SPILL_AT_SEND=$(find "$WORK/self/cache" -name '*.edges-*.tmp' 2>/dev/null)
        printf 'CANCEL\n' >&"$SRV_IN"
        SENT=1
        continue
    fi
    # グラフを組み終えたしるし（CallGraphBuilder の graph.collected）。これが先に来たら、中止は構築の後に届いた
    [ "$SENT" = 1 ] && [[ "$line" == "#L${T}"*"Collected: "* ]] && BUILT_BEFORE_NG=1
    case "$line" in OK*|NG*) RESPONSE=$line; break ;; esac
done
# サーバーが終わる前に見る（終われば JVM の終了フックも消すので、中止の経路で消したかが分からなくなる）
LEFT=$(temp_files_in "$WORK/self/cache")
printf 'SHUTDOWN\n' >&"$SRV_IN" 2>/dev/null
cat <&"$SRV_OUT" > /dev/null 2>&1
wait "$SRV_PROC" 2>/dev/null
grep -qE "^NG${T}cancelled" <<<"$RESPONSE" && ok "CANCEL で中止される" \
    || fail "CANCEL が効いていない（${RESPONSE:0:40}）"
[ -n "$SPILL_AT_SEND" ] && ok "CANCEL を送った時点でエッジの記録（一時ファイル）があった" \
    || fail "CANCEL を送った時点でエッジの記録が無い（グラフの構築中に送れていない）"
[ "$BUILT_BEFORE_NG" = 0 ] && ok "中止はグラフの構築中に届いた" \
    || fail "中止がグラフの構築の後に届いた（この検査の前提が崩れている）"
[ -z "$LEFT" ] && ok "グラフの構築中に中止しても一時ファイルが残らない（サーバーは動いたまま）" \
    || fail "グラフの構築中に中止したあとに一時ファイルが残っている: $LEFT"

# CANCEL は、それまでに読んだ ANALYZE のうち、まだ始まっていないもの（前の要求の処理中に待ち行列にあるもの）も止める。
# 後から送った ANALYZE には効かない。要求をまとめて流し込むので、CANCEL を読んだ時点では 1 つ目の ANALYZE が
# 動いていて、2 つ目はまだ待ち行列にある（以前は ANALYZE の始まりで中止の旗を下ろしていたので、2 つ目は最後まで走った）
echo "== 始まる前の ANALYZE にも CANCEL が効く =="
OUT=$(session "ANALYZE\t$CONFIG\nANALYZE\t$CONFIG\nCANCEL\nANALYZE\t$CONFIG\nSHUTDOWN\n" | grep -E '^(OK|NG)')
echo "$OUT" | sed 's/^/       /' | cut -c1-60
RESULTS=$(cut -f1-2 <<<"$OUT" | head -3 | sed 's/analyzed=.*/analyzed/' | paste -sd' ')
[ "$RESULTS" = "NG${T}cancelled NG${T}cancelled OK${T}analyzed" ] \
    && ok "CANCEL より前に読んだ 2 つの ANALYZE は中止され、後の ANALYZE は動く" \
    || fail "CANCEL の効き方が期待と違う（$RESULTS）"

# 拡張（plugin.folders）は ANALYZE のたびに読み直す。IDE はサーバーを動かしたまま ANALYZE を繰り返すので、
# 拡張を直したら次の ANALYZE から効くこと（以前は拡張のクラスローダを JVM の中で使い回していて、
# 直した拡張が効かず、候補を狭めた古い拡張のせいで呼び出しが黙って落ちていた）
echo "== 拡張を直したら次の ANALYZE から効く =="
PL=$WORK/plug
mkdir -p "$PL/proj/src/p" "$PL/plugins/demo"
echo 'package p; public interface Svc { void run(); }' > "$PL/proj/src/p/Svc.java"
echo 'package p; public class A implements Svc { public void run() { } }' > "$PL/proj/src/p/A.java"
echo 'package p; public class B implements Svc { public void run() { } }' > "$PL/proj/src/p/B.java"
echo 'package p; public class Main { void go(Svc s) { s.run(); } }' > "$PL/proj/src/p/Main.java"
write_pick() {   # $1=p.Svc の候補として返す型
    cat > "$PL/plugins/demo/Pick.java" <<EOF
package demo;
import java.util.List;
import jche.extension.*;
public class Pick implements TypeCandidateProvider {
    public String[] candidates(String t, String sig, List<Hint> h) { return "p.Svc".equals(t) ? new String[] {"$1"} : null; }
    public String label() { return "PICK"; }
}
EOF
}
write_pick p.A
cat > "$PL/c.properties" <<EOF
project.root=proj
source.folders=src
library.build.tool=none
source.encoding=UTF-8
output.folder=out
cache.folder=cache
plugin.folders=plugins
resolver.candidate.providers=demo.Pick
EOF
coproc PSRV { java -cp "$JCHE_CP" jche.CallHierarchyExporter --server "$PL/scache" 2>/dev/null; }
PSRV_OUT=${PSRV[0]}
PSRV_IN=${PSRV[1]}
PSRV_PROC=$PSRV_PID
# 1 つの要求を送り、OK / NG までの R 行と応答をつないで返す
psrv() {
    printf '%s\n' "$1" >&"$PSRV_IN"
    local line acc=""
    while IFS= read -r -t 300 line <&"$PSRV_OUT"; do
        case "$line" in
            R*) acc+="$(cut -f3 <<<"$line") " ;;
            OK*|NG*) printf '%s%s' "$acc" "$(cut -f1 <<<"$line")"; return ;;
        esac
    done
}
psrv "ANALYZE${T}$PL/c.properties" > /dev/null
TREE1=$(psrv "TREE${T}p.Main#go(p.Svc)${T}callees${T}depth=3")
write_pick p.B
psrv "ANALYZE${T}$PL/c.properties" > /dev/null
TREE2=$(psrv "TREE${T}p.Main#go(p.Svc)${T}callees${T}depth=3")
printf 'SHUTDOWN\n' >&"$PSRV_IN" 2>/dev/null
cat <&"$PSRV_OUT" > /dev/null 2>&1
wait "$PSRV_PROC" 2>/dev/null
[ "$TREE1" = "p.Main#go(p.Svc) p.A#run() OK" ] && ok "1 回目は拡張が返す p.A へ繋ぐ" \
    || fail "1 回目の TREE が期待と違う（$TREE1）"
[ "$TREE2" = "p.Main#go(p.Svc) p.B#run() OK" ] && ok "拡張を直した後の ANALYZE では p.B へ繋ぐ" \
    || fail "拡張を直しても古い拡張が動いている（$TREE2）"

# 同じ更新時刻のまま上書きした jar を、同じサーバーの次の ANALYZE が読むこと。JDT は開いた jar を閉じず（GC まで開いたまま）、
# 開いているあいだ JDK は同じ jar（inode と更新時刻が同じもの）の目次をプロセスの中で共有するので、依存 jar の指紋も
# JDT も前の目次を読み、「何も変わっていない」として古い事実を使い続けていた（新しいプロセスなら今の中身を読む）。
# jar は同じ inode のまま上書きし（cat で中身だけ書き換える）、更新時刻を元に戻す（unzip -o や cp -p で起きる）
echo "== 同じ更新時刻のまま上書きした jar を、同じサーバーの次の ANALYZE が読む =="
SJ=$WORK/stalejar
mkdir -p "$SJ/src/p" "$SJ/v1/q" "$SJ/v2/q" "$SJ/lib"
printf 'package p;\npublic class Main {\n    void go(q.L l) {\n        l.m("x");\n    }\n}\n' > "$SJ/src/p/Main.java"
printf 'package q;\npublic class L { public void m(Object o) { } }\n' > "$SJ/v1/q/L.java"
printf 'package q;\npublic class L { public void m(Object o) { } public void m(String s) { } }\n' > "$SJ/v2/q/L.java"
mkdir -p "$SJ/v3/q"
printf 'package q;\npublic class L { public void m(Object o) { } public void m(CharSequence s) { } }\n' > "$SJ/v3/q/L.java"
( javac -nowarn -d "$SJ/c1" "$SJ/v1/q/L.java" && javac -nowarn -d "$SJ/c2" "$SJ/v2/q/L.java" \
    && javac -nowarn -d "$SJ/c3" "$SJ/v3/q/L.java" \
    && jar cf "$SJ/lib/lib.jar" -C "$SJ/c1" q && jar cf "$SJ/v2.jar" -C "$SJ/c2" q \
    && jar cf "$SJ/v1.jar" -C "$SJ/c1" q && jar cf "$SJ/v3.jar" -C "$SJ/c3" q ) 2> /dev/null \
    || fail "検査用の jar を作れませんでした"
cat > "$SJ/c.properties" <<EOF
project.root=$SJ
source.folders=src
library.jars=$SJ/lib/lib.jar
library.build.tool=none
source.encoding=UTF-8
output.folder=$SJ/out
cache.folder=$SJ/cache
EOF
coproc SJSRV { java -cp "$JCHE_CP" jche.CallHierarchyExporter --server "$WORK/cache4" 2>/dev/null; }
SJ_OUT=${SJSRV[0]}
SJ_IN=${SJSRV[1]}
SJ_PROC=$SJSRV_PID
sj_request() {   # $1=要求。OK / NG の行までの応答（#L・#P を除く）を SJ_RESPONSE に入れる
    printf '%s\n' "$1" >&"$SJ_IN"
    SJ_RESPONSE=""
    local line
    while IFS= read -r -t 300 line <&"$SJ_OUT"; do
        case "$line" in
            '#'*) ;;
            OK*|NG*) SJ_RESPONSE+="$line"$'\n'; return ;;
            *) SJ_RESPONSE+="$line"$'\n' ;;
        esac
    done
}
sj_request "ANALYZE${T}$SJ/c.properties"
grep -qE "^OK${T}analyzed=1" <<<"$SJ_RESPONSE" || fail "1 回目の ANALYZE に失敗しました: ${SJ_RESPONSE:0:80}"
sj_request "TREE${T}p.Main#go(q.L)${T}callees${T}depth=2"
grep -q 'q.L#m(java.lang.Object)' <<<"$SJ_RESPONSE" \
    && ok "1 回目は q.L#m(Object) に解決する" || fail "1 回目の解決先が期待と違います: $SJ_RESPONSE"
touch -r "$SJ/lib/lib.jar" "$SJ/stamp"
INODE_BEFORE=$(ls -i "$SJ/lib/lib.jar" | cut -d' ' -f1)
cat "$SJ/v2.jar" > "$SJ/lib/lib.jar"
touch -r "$SJ/stamp" "$SJ/lib/lib.jar"
INODE_AFTER=$(ls -i "$SJ/lib/lib.jar" | cut -d' ' -f1)
[ "$INODE_BEFORE" = "$INODE_AFTER" ] || fail "jar を同じ inode のまま上書きできていません（この検査の前提が崩れている）"
sj_request "ANALYZE${T}$SJ/c.properties"
grep -qE "^OK${T}analyzed=1" <<<"$SJ_RESPONSE" || fail "2 回目の ANALYZE に失敗しました: ${SJ_RESPONSE:0:80}"
sj_request "TREE${T}p.Main#go(q.L)${T}callees${T}depth=2"
grep -q 'q.L#m(java.lang.String)' <<<"$SJ_RESPONSE" \
    && ok "2 回目の ANALYZE は上書きした jar の q.L#m(String) に解決する（新しいプロセスと同じ）" \
    || fail "2 回目の ANALYZE が上書きする前の jar のまま: $(grep -o 'q.L#m([^)]*)' <<<"$SJ_RESPONSE")"
printf 'SHUTDOWN\n' >&"$SJ_IN" 2>/dev/null
cat <&"$SJ_OUT" > /dev/null 2>&1
wait "$SJ_PROC" 2>/dev/null

# 鍵を戻す上書き（Issue #170。docs/cache-unification-qa.md の Q136）。JDK は閉じられていない ZipFile が持つすべての鍵
# （fileKey・更新時刻）で目次を共有する（パスには依らない）。以前はパスごとに最後に見た鍵を 1 組だけ覚えていたので、
# v1（更新時刻 M）→ v2（M+100。M の記録を上書きして忘れる）→ v3（M に戻す）の 3 回目は「前に見た鍵と違う」として
# 確かめず、JDT は 1 回目に開いたままの v1 の目次で読んだ（警告も出ない）。同じ inode を別のパス（シンボリックリンク）で
# 読む形も、新しいパスは「初めて見た」として確かめなかった。どちらも前の検査の GC に紛れないよう、新しいサーバーで見る
sj_start() {   # 新しいサーバーを立てる
    coproc SJSRV { java -cp "$JCHE_CP" jche.CallHierarchyExporter --server "$WORK/cache4" 2>/dev/null; }
    SJ_OUT=${SJSRV[0]}
    SJ_IN=${SJSRV[1]}
    SJ_PROC=$SJSRV_PID
}
sj_stop() {
    printf 'SHUTDOWN\n' >&"$SJ_IN" 2>/dev/null
    cat <&"$SJ_OUT" > /dev/null 2>&1
    wait "$SJ_PROC" 2>/dev/null
}
sj_put() {   # $1=中身の jar  $2=更新時刻を写すファイル。lib.jar を同じ inode のまま上書きする
    cat "$1" > "$SJ/lib/lib.jar"
    touch -r "$2" "$SJ/lib/lib.jar"
}
sj_resolves() {   # $1=設定  $2=何回目か  → 解決先（q.L#m(...)）を SJ_TARGETS に入れる
    sj_request "ANALYZE${T}$1"
    grep -qE "^OK${T}analyzed=1" <<<"$SJ_RESPONSE" || fail "$2 回目の ANALYZE に失敗しました: ${SJ_RESPONSE:0:80}"
    sj_request "TREE${T}p.Main#go(q.L)${T}callees${T}depth=2"
    SJ_TARGETS=$(grep -o 'q.L#m([^)]*)' <<<"$SJ_RESPONSE" | sort -u | paste -sd' ')
}
touch -d '@'$(( $(stat -c %Y "$SJ/stamp") + 100 )) "$SJ/stamp100"
sed -e "s#cache.folder=.*#cache.folder=$SJ/cache3#" "$SJ/c.properties" > "$SJ/c3.properties"
sj_start
sj_put "$SJ/v1.jar" "$SJ/stamp"
sj_resolves "$SJ/c3.properties" 1
sj_put "$SJ/v2.jar" "$SJ/stamp100"
sj_resolves "$SJ/c3.properties" 2
[ "$SJ_TARGETS" = "q.L#m(java.lang.String)" ] || fail "鍵を戻す上書きの 2 回目の解決先が期待と違います: $SJ_TARGETS"
sj_put "$SJ/v3.jar" "$SJ/stamp"
sj_resolves "$SJ/c3.properties" 3
[ "$SJ_TARGETS" = "q.L#m(java.lang.CharSequence)" ] \
    && ok "更新時刻を M → M+100 → M と戻して上書きした jar も、今の中身の q.L#m(CharSequence) に解決する" \
    || fail "更新時刻を前の鍵に戻して上書きした jar を、その鍵で開いたままの目次で読んでいます: $SJ_TARGETS"
sj_stop
# シンボリックリンク。1 回目は lib.jar、2 回目は同じ inode をリンクの別のパスから読む（キャッシュは別。更新時刻は同じ）
mkdir -p "$SJ/lib2"
ln -sf "$SJ/lib/lib.jar" "$SJ/lib2/link.jar"
sed -e "s#library.jars=.*#library.jars=$SJ/lib2/link.jar#" -e "s#cache.folder=.*#cache.folder=$SJ/cache5#" \
    "$SJ/c.properties" > "$SJ/c5.properties"
sed -e "s#cache.folder=.*#cache.folder=$SJ/cache6#" "$SJ/c.properties" > "$SJ/c6.properties"
sj_start
sj_put "$SJ/v1.jar" "$SJ/stamp"
sj_resolves "$SJ/c6.properties" 1
sj_put "$SJ/v3.jar" "$SJ/stamp"
sj_resolves "$SJ/c5.properties" 2
[ "$SJ_TARGETS" = "q.L#m(java.lang.CharSequence)" ] \
    && ok "同じ inode をシンボリックリンクの別のパスで読んでも、上書きした jar の q.L#m(CharSequence) に解決する" \
    || fail "シンボリックリンクで読んだ jar が、別のパスで開いたままの目次のまま: $SJ_TARGETS"
sj_stop

# フィールドの呼び出し元（TREE <型#フィールド> field）。Eclipse プラグインの「フィールドの呼び出し元」がこれを使う。
# 根がフィールド、深さ 1 がそれを参照しているメソッド（理由の列に read / write / read/write）、その下が呼び出し元。
# 参照はキャッシュの A 行から拾う（グラフに持たない）ので、キャッシュが解析結果と食い違えば断ることも確かめる
# （docs/field-callers-qa.md）
echo "== フィールドの呼び出し元（TREE … field） =="
FP=$WORK/fieldproj
mkdir -p "$FP/src/app" "$FP/test/app"
cat > "$FP/src/app/Order.java" <<'EOF'
package app;
public class Order {
    private String status = "NEW";
    int count;
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public void bump() { count++; }
    public void ship() {
        if ("NEW".equals(status)) {
            setStatus("SHIPPED");
        }
        Runnable r = () -> System.out.println(status);
        r.run();
    }
    static class Inner { void touch(Order o) { o.status = "X"; } }
}
EOF
cat > "$FP/src/app/Service.java" <<'EOF'
package app;
public class Service {
    void handle(Order o) { o.ship(); o.bump(); }
    void report(Order o) { System.out.println(o.getStatus()); }
    void top(Order o) { handle(o); report(o); }
    void viaLambda(Order o) {
        Runnable r = () -> {
            o.bump();
        };
        r.run();
    }
}
EOF
# 同じ名前のローカル変数と、入れ子のクラスの同じ名前のフィールド（FIELDAT の取り違えの検査に使う）
cat > "$FP/src/app/Twin.java" <<'EOF'
package app;
public class Twin {
    int value;
    void set(int v) {
        int value = v;
        this.value = value;
    }
    static class Inner {
        int value;
    }
}
EOF
cat > "$FP/test/app/OrderTest.java" <<'EOF'
package app;
public class OrderTest {
    void t() { new Order().setStatus("T"); }
}
EOF
cat > "$FP/c.properties" <<EOF
project.root=$FP
source.folders=src,test
source.encoding=UTF-8
output.folder=$FP/out
cache.folder=$FP/cache
EOF
F='app.Order#status'
OUT=$(session "ANALYZE\t$FP/c.properties\nTREE\t$F\tfield\tdepth=10\ttests=1\nSHUTDOWN\n")
echo "$OUT" | grep -E "^(R|OK|NG)" | sed 's/^/       /'
grep -qE "^R${T}0${T}app\.Order#status${T}app\.Order\.status${T}src/app/Order\.java${T}0${T}${T}field$" <<<"$OUT" \
    && ok "根はフィールド（宣言のファイル、印 field）" || fail "根の行が期待と違う"
grep -qE "^R${T}1${T}${T}${T}src/app/Order\.java${T}0${T}write${T}access,initializer$" <<<"$OUT" \
    && ok "宣言の初期化子を書き込みの行として出す（A 行には無い）" || fail "初期化子の行が無い"
grep -qE "^R${T}1${T}app\.Order#setStatus\(java\.lang\.String\)${T}[^$T]*${T}src/app/Order\.java${T}6${T}write${T}access$" <<<"$OUT" \
    && ok "書き込むメソッドが深さ 1 に、参照の行と write で出る" || fail "setter の行が期待と違う"
grep -qE "^R${T}1${T}app\.Order#getStatus\(\)${T}[^$T]*${T}src/app/Order\.java${T}5${T}read${T}access$" <<<"$OUT" \
    && ok "読むメソッドが深さ 1 に、read で出る" || fail "getter の行が期待と違う"
! grep -q 'lambda\$' <<<"$OUT" \
    && ok "ラムダの合成メソッド（lambda\$…）は木に出ない（ラムダの中の参照は、ラムダを書いたメソッドの行にまとまる）" \
    || fail "ラムダの合成メソッドが木に出ている"
grep -qE "^R${T}1${T}app\.Order\.Inner#touch\(app\.Order\)${T}[^$T]*${T}[^$T]*${T}15${T}write${T}" <<<"$OUT" \
    && ok "別の型（内部クラス）からの書き込みも出る" || fail "内部クラスからの書き込みが出ない"
[ "$(grep -cE "^R${T}1${T}app\.Order#ship\(\)${T}" <<<"$OUT")" = 1 ] \
    && ok "同じメソッドの参照は 1 行にまとめる（ship は 2 か所で読むが 1 行）" || fail "同じメソッドの参照がまとまっていない"
grep -qE "^R${T}2${T}app\.OrderTest#t\(\)${T}" <<<"$OUT" \
    && ok "参照しているメソッドの呼び出し元が深さ 2 に出る（tests=1）" || fail "呼び出し元が続いていない"
grep -qE "^R${T}3${T}app\.Service#top\(app\.Order\)${T}" <<<"$OUT" \
    && ok "呼び出し元を最後まで辿る（ship → handle → top。ラムダの段は無い）" || fail "呼び出し元が途中で切れている"
grep -qE "^OK${T}rows=[0-9]+${T}accesses=6$" <<<"$OUT" \
    && ok "応答が参照の数（初期化子を含めて 6）を返す" || fail "応答の accesses が期待と違う"

# ラムダの合成メソッド（lambda$…）は、メソッドの木にも出さない。Eclipse・VSCode プラグインの木はこの応答を描くだけ。
# ラムダの中の行（AT）は、ラムダを書いたメソッド。呼び出し先の向きではラムダの本体の呼び出しがそのメソッドの子に、
# 呼び出し元の向きではラムダの中から呼んでいるなら、そのラムダを書いたメソッドが呼び出し元になる
echo "== ラムダの合成メソッドを木に出さない（AT・TREE callers / callees） =="
OUT=$(session "ANALYZE\t$FP/c.properties\nAT\tsrc/app/Service.java\t8\nTREE\tapp.Service#viaLambda(app.Order)\tcallees\tdepth=2\nTREE\tapp.Order#bump()\tcallers\tdepth=2\nSHUTDOWN\n")
echo "$OUT" | grep -E "^(R|OK|NG)" | sed 's/^/       /'
grep -qE "^OK${T}how=enclosing${T}key=app\.Service#viaLambda\(app\.Order\)${T}" <<<"$OUT" \
    && ok "ラムダの中の行（AT）は、ラムダを書いたメソッド viaLambda を返す" || fail "ラムダの中の行が viaLambda にならない"
grep -qE "^R${T}1${T}app\.Order#bump\(\)${T}" <<<"$OUT" \
    && ok "呼び出し先の向き: ラムダの本体の呼び出し（bump）が、ラムダを書いたメソッドの子（深さ 1）に出る" || fail "ラムダの本体の呼び出しが出ない"
grep -qE "^R${T}1${T}app\.Service#viaLambda\(app\.Order\)${T}" <<<"$OUT" \
    && ok "呼び出し元の向き: ラムダの中から呼ぶ bump の呼び出し元に、ラムダを書いたメソッド viaLambda が出る" || fail "ラムダを書いたメソッドが呼び出し元に出ない"
! grep -q 'lambda\$' <<<"$OUT" \
    && ok "どの向きでも lambda\$… の行が出ない" || fail "ラムダの合成メソッドが木に出ている"

# 応答ごとに見分けたいので、要求ごとにセッションを分ける（2 回目からはキャッシュを再利用するので速い）
WRITE_OUT=$(session "ANALYZE\t$FP/c.properties\nTREE\t$F\tfield\taccess=write\nSHUTDOWN\n")
READ_OUT=$(session "ANALYZE\t$FP/c.properties\nTREE\t$F\tfield\taccess=read\tdepth=1\nSHUTDOWN\n")
OUT=$(session "ANALYZE\t$FP/c.properties\nTREE\tapp.Order#count\tfield\nTREE\tapp.Order#nope\tfield\nTREE\t$F(\tfield\nEXPORT\t$F\tfield\t$FP/field.csv\nSHUTDOWN\n")
echo "$OUT" | grep -E "^(OK|NG)" | sed 's/^/       /'
WRITE_ROWS=$(grep -cE "^R${T}1${T}" <<<"$WRITE_OUT")
[ "$WRITE_ROWS" = 3 ] && ! grep -qE "${T}read${T}" <<<"$WRITE_OUT" \
    && ok "access=write は書き込みだけ（初期化子・setter・内部クラスの 3 行）" || fail "access=write の絞り込みが違う（$WRITE_ROWS 行）"
! grep -qE "${T}write${T}" <<<"$READ_OUT" && grep -qE "^R${T}1${T}app\.Order#getStatus\(\)${T}.*${T}access,truncated$" <<<"$READ_OUT" \
    && ok "access=read は読み取りだけ。depth=1 なら先がある行に truncated が付く" || fail "access=read / depth=1 が期待と違う"
! grep -qE "^R${T}2${T}app\.OrderTest#" <<<"$WRITE_OUT" \
    && ok "tests=0（既定）ならテストからの呼び出し元は出ない" || fail "tests=0 でもテストが出ている"
grep -qE "^R${T}1${T}app\.Order#bump\(\)${T}.*${T}7${T}read/write${T}access$" <<<"$OUT" \
    && ok "count++ は read/write" || fail "複合の参照が read/write にならない"
grep -qE "^NG${T}not-found$" <<<"$OUT" && ok "宣言も参照も無いフィールドは not-found" || fail "無いフィールドが not-found にならない"
grep -qE "^NG${T}bad-field " <<<"$OUT" && ok "フィールドの形でないキーは bad-field" || fail "bad-field を返さない"
if head -1 "$FP/field.csv" 2>/dev/null | grep -q 'depth,method,file,line,reason,note' \
        && grep -q '^0,app.Order#status,src/app/Order.java,0,,$' "$FP/field.csv" \
        && grep -q '^1,app.Order#setStatus(java.lang.String),src/app/Order.java,6,write,$' "$FP/field.csv"; then
    ok "EXPORT … field がメソッドの木と同じ列で書く"
else
    fail "フィールドの木の CSV が期待と違う"
fi

# FIELDAT（カーソル位置からフィールドを引く。VSCode プラグインが使う）。
# JDT の無いプラグインに代わり、ファイル・行・カーソルの下の単語から、参照（A 行）か宣言（V 行）で引く
OUT=$(session "ANALYZE\t$FP/c.properties\nFIELDAT\tsrc/app/Order.java\t6\tstatus\nFIELDAT\t$FP/src/app/Order.java\t15\tstatus\nFIELDAT\tsrc/app/Order.java\t3\tstatus\nFIELDAT\tsrc/app/Order.java\t5\tgetStatus\nFIELDAT\tsrc/app/Twin.java\t5\tvalue\nFIELDAT\tsrc/app/Twin.java\t6\tvalue\nFIELDAT\tsrc/app/Twin.java\t3\tvalue\nFIELDAT\tno/such/File.java\t1\tx\nFIELDAT\tsrc/app/Order.java\txx\tstatus\nFIELDAT\tsrc/app/Order.java\t3\nSHUTDOWN\n")
echo "$OUT" | grep -E "^(OK|NG)" | sed 's/^/       /'
FA=$(grep -E "^(OK${T}how=|NG)" <<<"$OUT")
fa_line() { sed -n "${1}p" <<<"$FA"; }
[ "$(fa_line 1)" = "OK${T}how=access${T}key=app.Order#status${T}keys=app.Order#status" ] \
    && ok "参照の行（this.status = s）からフィールドを引く" || fail "参照の行から引けない: $(fa_line 1)"
[ "$(fa_line 2)" = "OK${T}how=access${T}key=app.Order#status${T}keys=app.Order#status" ] \
    && ok "別の型からの参照（o.status）も、絶対パスでも引ける" || fail "別の型からの参照が引けない: $(fa_line 2)"
[ "$(fa_line 3)" = "OK${T}how=declaration${T}key=app.Order#status${T}keys=app.Order#status" ] \
    && ok "宣言の行から引く" || fail "宣言の行から引けない: $(fa_line 3)"
[ "$(fa_line 4)" = "NG${T}not-found" ] \
    && ok "フィールドでない名前（メソッド名）は not-found" || fail "メソッド名で not-found にならない: $(fa_line 4)"
[ "$(fa_line 5)" = "NG${T}not-found" ] \
    && ok "メソッドの中の同じ名前のローカル変数の宣言を、フィールドと取り違えない" || fail "ローカル変数をフィールドにした: $(fa_line 5)"
[ "$(fa_line 6)" = "OK${T}how=access${T}key=app.Twin#value${T}keys=app.Twin#value" ] \
    && ok "this.value = value は、フィールドの参照だけを拾う（ローカル変数の value は拾わない）" || fail "this.value の行が期待と違う: $(fa_line 6)"
[ "$(fa_line 7)" = "OK${T}how=declaration${T}key=app.Twin#value${T}keys=app.Twin#value,app.Twin.Inner#value" ] \
    && ok "宣言で候補が複数なら、黙って 1 つを選ばずすべて返す" || fail "候補が複数のときの応答が違う: $(fa_line 7)"
[ "$(fa_line 8)" = "NG${T}file-not-analyzed" ] && ok "解析結果に無いファイルは file-not-analyzed" || fail "file-not-analyzed にならない: $(fa_line 8)"
[[ "$(fa_line 9)" == "NG${T}bad-line "* ]] && ok "行番号でない引数は bad-line" || fail "bad-line にならない: $(fa_line 9)"
[ "$(fa_line 10)" = "NG${T}missing-position" ] && ok "名前が無ければ missing-position" || fail "missing-position にならない: $(fa_line 10)"

# 解析の後にキャッシュが書き換わった（解析し直しが途中で終わった、など）なら、古い結果と新しい行を混ぜない
# 錠を待たない（下で「ほかの実行が錠を持っている間は読まない」ことを見るため。待つと検査が止まる）
coproc FSRV { JCHE_CACHE_LOCK_WAIT_SECONDS=0 java -cp "$JCHE_CP" jche.CallHierarchyExporter --server "$WORK/cache5" 2>/dev/null; }
FS_OUT=${FSRV[0]}
FS_IN=${FSRV[1]}
FS_PROC=$FSRV_PID
fs_request() {   # $1=要求。OK / NG の行を FS_RESPONSE に入れる
    printf '%s\n' "$1" >&"$FS_IN"
    FS_RESPONSE=""
    local line
    while IFS= read -r -t 300 line <&"$FS_OUT"; do
        case "$line" in
            OK*|NG*) FS_RESPONSE=$line; return ;;
        esac
    done
}
fs_request "ANALYZE${T}$FP/c.properties"
touch -d '2001-01-01' "$(find "$FP/cache" -name 'analysis-cache.tsv' | head -1)"
fs_request "TREE${T}$F${T}field"
[[ "$FS_RESPONSE" == "NG${T}stale-cache" ]] \
    && ok "キャッシュが解析結果と違えば stale-cache で断る" || fail "書き換わったキャッシュを読んでいる: $FS_RESPONSE"
fs_request "ANALYZE${T}$FP/c.properties"
fs_request "TREE${T}$F${T}field"
[[ "$FS_RESPONSE" == "OK${T}rows="* ]] \
    && ok "解析し直せば引ける" || fail "解析し直しても引けない: $FS_RESPONSE"
# キャッシュを読むのはフォルダの錠の内側（CONTRIBUTING.md。書きかけのキャッシュを読まない）。
# ほかのプロセスが錠を持っている間は読まず（待つ上限 0 秒なので断る）、放されれば引ける
cat > "$WORK/HoldLock.java" <<'EOF'
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
public class HoldLock {
    public static void main(String[] args) throws Exception {
        try (FileChannel ch = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = ch.lock()) {
            System.out.println("locked");
            System.in.read();   // 標準入力が閉じるまで持つ
        }
    }
}
EOF
LOCK_FILE="$(find "$FP/cache" -name 'analysis-cache.tsv' | head -1).lock"
coproc HOLD { java "$WORK/HoldLock.java" "$LOCK_FILE" 2>/dev/null; }
HOLD_OUT=${HOLD[0]}
HOLD_IN=${HOLD[1]}
HOLD_PROC=$HOLD_PID
IFS= read -r -t 120 HOLD_LINE <&"$HOLD_OUT"
if [ "$HOLD_LINE" = locked ]; then
    fs_request "TREE${T}$F${T}field"
    [[ "$FS_RESPONSE" == "NG${T}error "*"Another run kept using the cache folder"* ]] \
        && ok "ほかのプロセスが錠を持っている間はキャッシュを読まない" \
        || fail "錠を持たれていてもキャッシュを読んでいる: ${FS_RESPONSE:0:120}"
else
    fail "錠を持つ補助のプロセスが起動しない: $HOLD_LINE"
fi
exec {HOLD_IN}>&-
wait "$HOLD_PROC" 2>/dev/null
fs_request "TREE${T}$F${T}field"
[[ "$FS_RESPONSE" == "OK${T}rows="* ]] \
    && ok "錠が放されれば引ける" || fail "錠が放された後も引けない: $FS_RESPONSE"
printf 'SHUTDOWN\n' >&"$FS_IN" 2>/dev/null
cat <&"$FS_OUT" > /dev/null 2>&1
wait "$FS_PROC" 2>/dev/null

echo "== 知らない要求 =="
OUT=$(session 'NOSUCHCOMMAND\tx\nSHUTDOWN\n')
grep -qE "^NG${T}unknown-command" <<<"$OUT" && ok "知らない要求は NG を返して落ちない" || fail "知らない要求の扱いが違う"

if [ "$ng" -eq 0 ]; then echo "PASS"; else echo "FAIL ($ng 件)"; exit 1; fi
