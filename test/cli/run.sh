#!/usr/bin/env bash
# 起動コマンド（java-call-hierarchy-exporter.sh）と対話モード（src/Jche.java）の検査。
#
#   bash test/cli/run.sh
#   JCHE_TEST_JBANG_OPTS="--java 21" bash test/cli/run.sh   # JDK 25 を取得できない環境で手元の JDK を使う
#
# 対話モードはメニューを標準入力から読むので、答えをパイプで流し込んで動かす（端末は要らない）。
# 見るのは、
#   --help / 対話なしの解析（引数に設定ファイル）/ 状態表示 / 設定ファイルの作成ウィザード /
#   パス指定での解析 / 環境設定の変更（ヒープ上限）→ launcher.properties の書き換え → 再起動 → 反映
# の一連。解析結果の中身は見ない（それは test/regression/ の役目）。
#
# launcher.properties はこのテストが書き換えるので、あれば退避して最後に戻す。config/cli-test.properties と
# その出力（config/<日時>_demo/）、.cache/recent-configs.txt もテストが作るものなので消す。
# ログの検査は ASCII の部分だけで行う（標準出力の文字コードは端末に依るため。test/regression/run.sh と同じ方針）。
# ただし起動コマンド自身（bash）が出す行はスクリプトの文字コード（UTF-8）で出るので、そこは日本語で照合できる。
#
# 最後に、Windows 用の java-call-hierarchy-exporter.cmd についても「中身を読むだけ」の検査をする（ラベルの整合・改行・文字コード）。
# cmd.exe が要る検査は Windows のワークフロー（.github/workflows/smoke.yml の regression-windows）の役目。
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
JCHE="$ROOT/java-call-hierarchy-exporter.sh"
SETTINGS="$ROOT/launcher.properties"
BACKUP="$ROOT/launcher.properties.cli-test-backup"
CONFIG="$ROOT/config/cli-test.properties"
LOGDIR="$ROOT/test/cli"
fail=0

cleanup() {
    rm -f "$CONFIG" "$ROOT/.cache/recent-configs.txt" "$ROOT/.cache/launcher.restart"
    rm -rf "$ROOT"/config/*_demo/
    if [ -f "$BACKUP" ]; then
        mv -f "$BACKUP" "$SETTINGS"
    else
        rm -f "$SETTINGS"
    fi
}
trap cleanup EXIT
[ -f "$SETTINGS" ] && mv -f "$SETTINGS" "$BACKUP"

write_settings() {   # $1=JCHE_JAVA_OPTS
    printf 'JBANG_DIR=\nJBANG_REPO=\nJCHE_JAVA_OPTS=%s\nJCHE_JBANG_OPTS=%s\n' "$1" "${JCHE_TEST_JBANG_OPTS:-}" > "$SETTINGS"
}

ok()   { echo "  OK   $1"; }
ng()   { echo "  NG   $1"; fail=1; }
expect_log() {   # $1=ログ  $2=ASCII の文字列  $3=ラベル
    if LC_ALL=C grep -a -q -F -- "$2" "$1"; then ok "$3"; else ng "$3（「$2」が $1 に無い）"; tail -5 "$1"; fi
}
expect_not_log() {   # $1=ログ  $2=文字列  $3=ラベル
    if LC_ALL=C grep -a -q -F -- "$2" "$1"; then ng "$3（「$2」が $1 にある）"; else ok "$3"; fi
}

echo "== --help =="
write_settings ""
"$JCHE" --help > "$LOGDIR/run-help.log" 2>&1
expect_log "$LOGDIR/run-help.log" "java-call-hierarchy-exporter.sh --help" "--help で使い方が出る"

echo "== 対話なしの解析（引数に設定ファイル）=="
rm -rf "$ROOT/test/regression/entry/output" "$ROOT/test/regression/entry/.cache"
if "$JCHE" "$ROOT/test/regression/entry/config.properties" > "$LOGDIR/run-batch.log" 2>&1; then
    ok "終了コード 0"
else
    ng "終了コードが 0 ではない"; tail -5 "$LOGDIR/run-batch.log"
fi
expect_log "$LOGDIR/run-batch.log" "call-hierarchy.csv" "解析が完了した"
if ls "$ROOT"/test/regression/entry/output/*/call-hierarchy.csv > /dev/null 2>&1; then ok "出力フォルダができた"; else ng "出力フォルダが無い"; fi
if "$JCHE" "$ROOT/no-such-config.properties" > "$LOGDIR/run-batch-fail.log" 2>&1; then
    ng "存在しない設定ファイルで終了コードが 0"
else
    ok "存在しない設定ファイルで終了コードが 0 以外"
fi
rm -rf "$ROOT/test/regression/entry/output" "$ROOT/test/regression/entry/.cache"

echo "== 状態表示（launcher.properties が無いとき）=="
rm -f "$SETTINGS"
# 標準入力が端末でないので、起動コマンドは置き場所を尋ねずに既定で進む
if [ -n "${JCHE_TEST_JBANG_OPTS:-}" ]; then
    # JDK を取得できない環境では、jbang のオプションだけ入れた設定ファイルで代用する
    write_settings ""
fi
printf '4\n\nq\n' | "$JCHE" > "$LOGDIR/run-status.log" 2>&1
expect_log "$LOGDIR/run-status.log" "launcher.properties" "状態表示に launcher.properties の行がある"
expect_log "$LOGDIR/run-status.log" "JDT" "状態表示に JDT の行がある"
expect_log "$LOGDIR/run-status.log" "java.home" "状態表示に java.home の行がある"
expect_not_log "$LOGDIR/run-status.log" "launcher.restart" "再起動の目印は出ていない"
if [ -z "${JCHE_TEST_JBANG_OPTS:-}" ] && [ -f "$SETTINGS" ]; then ng "端末でないのに launcher.properties が作られた"; fi

echo "== 設定ファイルの作成ウィザード =="
write_settings ""
rm -f "$CONFIG"
# project.root → 名前 → source.folders → library.folders → source.encoding → entry.packages → 作成の確認 → 続けて実行しない
printf '2\ntest/demo\ncli-test\n\n\n\n\ny\nn\nq\n' | "$JCHE" > "$LOGDIR/run-wizard.log" 2>&1
if [ -f "$CONFIG" ]; then ok "config/cli-test.properties ができた"; else ng "config/cli-test.properties が無い"; tail -10 "$LOGDIR/run-wizard.log"; fi
if grep -q '^project.root=../test/demo$' "$CONFIG" 2>/dev/null; then ok "project.root が config/ からの相対で書かれた"; else ng "project.root の値: $(grep '^project.root=' "$CONFIG" 2>/dev/null)"; fi
if grep -q '^source.folders=src$' "$CONFIG" 2>/dev/null; then ok "source.folders が候補（src）で埋まった"; else ng "source.folders の値: $(grep '^source.folders=' "$CONFIG" 2>/dev/null)"; fi
if grep -q '^library.folders=$' "$CONFIG" 2>/dev/null; then ok "library.folders が空欄"; else ng "library.folders の値: $(grep '^library.folders=' "$CONFIG" 2>/dev/null)"; fi
# ひな形（config/config.properties）のコメントが残っていること。max.depth のような尋ねない項目も既定値のまま写る
if [ "$(grep -c '^#' "$CONFIG" 2>/dev/null)" -gt 40 ] && grep -q '^max.depth=50$' "$CONFIG" 2>/dev/null; then
    ok "ひな形のコメントと他の項目が写っている"
else
    ng "ひな形のコメントか他の項目が写っていない"
fi

echo "== 一覧から選んで解析（p でパスを指定）=="
printf '1\np\n%s\ny\n\nq\n' "$CONFIG" | "$JCHE" > "$LOGDIR/run-analyze.log" 2>&1
expect_log "$LOGDIR/run-analyze.log" "call-hierarchy.csv" "解析が完了した"
if ls "$ROOT"/config/*_demo/call-hierarchy.csv > /dev/null 2>&1; then ok "出力が config/<日時>_demo/ にできた（output.folder の既定）"; else ng "出力が無い"; fi
if grep -q 'config/cli-test.properties' "$ROOT/.cache/recent-configs.txt" 2>/dev/null; then ok "前回の設定として記録された"; else ng "recent-configs.txt に記録が無い"; fi
# 記録された設定は一覧で既定になる（空 Enter で選ばれる）。一覧は config/config.properties、config/cli-test.properties の順
# なので v 2 で内容を出してから、空 Enter → 実行しない。出力フォルダの中の設定ファイルの複製は一覧に出ない
printf '1\nv 2\n\nn\nq\n' | "$JCHE" > "$LOGDIR/run-recent.log" 2>&1
expect_log "$LOGDIR/run-recent.log" "project.root=../test/demo" "v 番号 で設定の内容が出る"
expect_log "$LOGDIR/run-recent.log" "2) config/cli-test.properties" "作った設定が一覧の 2 番目にある"
expect_not_log "$LOGDIR/run-recent.log" "3) config/" "出力フォルダの中の複製は一覧に出ない"
expect_log "$LOGDIR/run-recent.log" "1. config/cli-test.properties" "前回の設定が既定で選ばれる"

echo "== 環境設定（ヒープ上限）→ 再起動 → 反映 =="
# 3) 環境設定 → 3) ヒープ上限 → 512m → q（戻る）→ y（再起動）。再起動後は標準入力が尽きているので、そのまま終わる
printf '3\n3\n512m\nq\ny\n' | "$JCHE" > "$LOGDIR/run-env.log" 2>&1
if grep -q '^JCHE_JAVA_OPTS=-Xmx512m$' "$SETTINGS"; then ok "launcher.properties に -Xmx512m が書かれた"; else ng "launcher.properties: $(grep JCHE_JAVA_OPTS "$SETTINGS")"; fi
if [ "${JCHE_TEST_JBANG_OPTS:-}" = "" ] || grep -q "^JCHE_JBANG_OPTS=${JCHE_TEST_JBANG_OPTS}$" "$SETTINGS"; then ok "他の項目は保たれた"; else ng "JCHE_JBANG_OPTS が失われた"; fi
expect_log "$LOGDIR/run-env.log" "設定を反映するため再起動します" "起動コマンドが再起動した"
if [ -e "$ROOT/.cache/launcher.restart" ]; then ng "再起動の目印が残っている"; else ok "再起動の目印は消えた"; fi
printf '4\n\nq\n' | "$JCHE" > "$LOGDIR/run-status2.log" 2>&1
expect_log "$LOGDIR/run-status2.log" "512 MB" "次の起動でヒープ上限が反映された"
expect_log "$LOGDIR/run-status2.log" "JCHE_JAVA_OPTS=-Xmx512m" "環境変数として渡された"

echo "== java-call-hierarchy-exporter.cmd の構造（Windows 用。ここでは中身を読むだけ）=="
# java-call-hierarchy-exporter.cmd は cmd.exe でしか動かせないので、Linux 側では「壊れていないこと」だけを見る。
# cmd は goto / call の飛び先が無いと "The system cannot find the batch label specified" で止まり、
# 実際に MS932 で保存し直したときに :main のラベルが失われて Windows の CI が赤くなったことがある。
CMD="$ROOT/java-call-hierarchy-exporter.cmd"
labels=$(LC_ALL=C grep -a -o '^:[A-Za-z_][A-Za-z0-9_]*' "$CMD" | sed 's/^://' | sort)
dups=$(printf '%s\n' "$labels" | uniq -d | tr '\n' ' ')
if [ -z "$(printf '%s' "$dups" | tr -d ' ')" ]; then ok "ラベルの二重定義が無い"; else ng "ラベルが二重定義: $dups"; fi
missing=""
for t in $(LC_ALL=C grep -a -o -E '(goto|call) :[A-Za-z_][A-Za-z0-9_]*' "$CMD" | sed 's/.*://' | sort -u); do
    printf '%s\n' "$labels" | grep -qx "$t" || missing="$missing $t"
done
if [ -z "$missing" ]; then ok "goto / call の飛び先がすべてある"; else ng "飛び先の無いラベル:$missing"; fi
# 改行と文字コード（ヘッダのコメントの約束。UTF-8 で保存し直すと日本語の echo が化ける）
if LC_ALL=C grep -qa "$(printf '\r')" "$CMD"; then ok "CRLF で保存されている"; else ng "CRLF ではない"; fi
if iconv -f CP932 -t UTF-8 "$CMD" 2> /dev/null | grep -q "設定を反映するため再起動します"; then
    ok "MS932 で保存されている"
else
    ng "MS932 として読めない（UTF-8 で保存し直された？）"
fi

if [ $fail = 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
