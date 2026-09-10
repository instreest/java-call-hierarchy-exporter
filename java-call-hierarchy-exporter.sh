#!/usr/bin/env bash
# java-call-hierarchy-exporter の起動コマンド（Linux / macOS / Git Bash。Windows のコマンドプロンプトは java-call-hierarchy-exporter.cmd）。
#
#   ./java-call-hierarchy-exporter.sh                          対話モード（メニューで設定ファイルを選んで解析する）
#   ./java-call-hierarchy-exporter.sh a.properties [b.properties…] 対話なしで解析する（jbang で src/CallHierarchyExporter.java を直接動かすのと同じ）
#   ./java-call-hierarchy-exporter.sh --help
#
# どこから実行してもよい（このファイルのあるフォルダを起点にする）。
#
# やること:
#   1. launcher.properties（このフォルダ直下）を読み、JDK / JBang の置き場所（JBANG_DIR 等）や JVM のオプションを
#      環境変数にする。無ければ、対話できるときだけ置き場所を尋ねて作る（初回だけ）。
#   2. jbangw/jbang（同梱の JBang ラッパー）で src/Jche.java を動かす。JDK と依存 jar は初回に自動で取得される。
#   3. アプリが「再起動して設定を反映」を要求したとき（.cache/launcher.restart ができる）は 1 からやり直す。
#      置き場所や JVM オプションは Java が起動する前に決まるので、Java 側からは変えられない。
#
# 設定ファイルの読み込みと jbang の実行はサブシェルで行う。再起動のたびに前回の環境変数が残らないようにするため。
set -u

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SETTINGS="$ROOT/launcher.properties"
RESTART="$ROOT/.cache/launcher.restart"

# --- 初回: JDK / JBang の置き場所を尋ねる（対話できるときだけ。パイプや CI では JBang の既定のまま） ---
first_run_prompt() {
  echo "java-call-hierarchy-exporter: 初回の設定"
  echo
  echo "このツールが使う JDK と JBang（合わせて数百 MB）の置き場所を選んでください。"
  echo "  1) このプロジェクトの中   $ROOT/.jbang"
  echo "     他の環境を汚さず、フォルダごと消せば元に戻る。依存 jar も同じ場所に置く"
  echo "  2) ユーザーのホーム       ${HOME}/.jbang（JBang の既定）"
  echo "     他の JBang スクリプトと共有する。既に JBang を使っているならこちら"
  echo "後から変えるときは、アプリの「環境設定」か、$SETTINGS を編集する。"
  echo
  local choice
  printf '番号 [1]: '
  IFS= read -r choice || choice=1
  case "$choice" in
    2) write_settings "" "" ;;
    *) write_settings ".jbang" ".jbang/repository" ;;
  esac
  echo "$SETTINGS に保存しました。"
  echo
}

# $1=JBANG_DIR  $2=JBANG_REPO（相対はこのフォルダ起点。空欄は JBang の既定）
write_settings() {
  cat > "$SETTINGS" <<EOF
# java-call-hierarchy-exporter.sh / java-call-hierarchy-exporter.cmd が起動時に読む設定（アプリの「環境設定」からも書き換えられる）。
# キーはそのまま環境変数になる。相対パスはこのファイルのあるフォルダが起点。空欄は既定値。
#   JBANG_DIR       JBang 本体・JDK の置き場所（既定 ~/.jbang）
#   JBANG_REPO      依存 jar の置き場所（既定 ~/.m2/repository）
#   JCHE_JAVA_OPTS  解析を動かす JVM のオプション（例: -Xmx4g）
#   JCHE_JBANG_OPTS jbang run に足すオプション（例: --offline）
JBANG_DIR=$1
JBANG_REPO=$2
JCHE_JAVA_OPTS=
JCHE_JBANG_OPTS=
EOF
}

# launcher.properties を環境変数にする（サブシェル内で呼ぶ）
load_settings() {
  [ -f "$SETTINGS" ] || return 0
  local line key val
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%$'\r'}
    case "$line" in ''|'#'*) continue ;; esac
    case "$line" in *=*) ;; *) continue ;; esac
    key=${line%%=*}; val=${line#*=}
    key=$(printf '%s' "$key" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    val=$(printf '%s' "$val" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    case "$key" in
      [A-Z_]*) ;;
      *) continue ;;
    esac
    if [ -z "$val" ]; then
      unset "$key"
      continue
    fi
    case "$key" in
      JBANG_DIR|JBANG_CACHE_DIR|JBANG_REPO)
        case "$val" in
          /*|[A-Za-z]:*) ;;
          *) val="$ROOT/$val" ;;
        esac
        # Git Bash では /c/... の形を Windows の形（C:/...）にして Java に渡す（Java は /c/... を C:\c\... と読む）
        if command -v cygpath > /dev/null 2>&1; then val=$(cygpath -m "$val"); fi ;;
    esac
    export "$key=$val"
  done < "$SETTINGS"
}

run_once() {
  load_settings
  local -a opts=()
  local o
  # shellcheck disable=SC2086  # 空白区切りで複数のオプションを書けるよう、意図して分割する
  for o in ${JCHE_JBANG_OPTS:-}; do opts+=("$o"); done
  # shellcheck disable=SC2086
  for o in ${JCHE_JAVA_OPTS:-}; do opts+=("-R$o"); done
  export JCHE_ROOT="$ROOT"
  # ${opts[@]+"${opts[@]}"} は、要素が無いときに bash 3.2（macOS）の set -u で落ちないための書き方
  exec "$ROOT/jbangw/jbang" run ${opts[@]+"${opts[@]}"} "$ROOT/src/Jche.java" "$@"
}

case " $* " in
  *" --help "*|*" -h "*) ;;
  *) if [ ! -f "$SETTINGS" ] && [ -t 0 ] && [ -t 1 ]; then first_run_prompt; fi ;;
esac

while :; do
  rm -f "$RESTART"
  ( run_once "$@" )
  code=$?
  [ -f "$RESTART" ] || exit $code
  echo "設定を反映するため再起動します..."
done
