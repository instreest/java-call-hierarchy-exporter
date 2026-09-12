#!/usr/bin/env bash
# java-call-hierarchy-exporter の起動コマンド（Linux / macOS / Git Bash。Windows のコマンドプロンプトは java-call-hierarchy-exporter.cmd）。
#
#   ./java-call-hierarchy-exporter.sh                          引数なし … 対話モード（メニューで設定ファイルを選んで解析する）
#   ./java-call-hierarchy-exporter.sh a.properties [b.properties…] 引数あり … 対話なしで解析する（jbang で src/CallHierarchyExporter.java を直接動かすのと同じ）
#   ./java-call-hierarchy-exporter.sh --help
#
# 設定ファイルを渡したときは何も尋ねない（Issue #83）。初回で launcher.properties がまだ無ければ、
# 置き場所の質問は出さずに既定（このプロジェクトの中の .jbang）で作り、その旨を 1 行出すだけにする。
#
# どこから実行してもよい（このファイルのあるフォルダを起点にする）。
#
# やること:
#   1. launcher.properties（このフォルダ直下）を読み、JDK / JBang の置き場所（JBANG_DIR 等）や JVM のオプションを
#      環境変数にする。無ければ、対話できるときだけ置き場所を尋ねて作る（初回だけ。引数があるときは尋ねずに既定で作る）。
#   2. jbangw/jbang（同梱の JBang ラッパー）で src/Jche.java を動かす。JDK と依存 jar は初回に自動で取得される。
#   3. アプリが「再起動して設定を反映」を要求したとき（.cache/launcher.restart ができる）は 1 からやり直す。
#      置き場所や JVM オプションは Java が起動する前に決まるので、Java 側からは変えられない。
#
# 設定ファイルの読み込みと jbang の実行はサブシェルで行う。再起動のたびに前回の環境変数が残らないようにするため。
set -u

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SETTINGS="$ROOT/launcher.properties"
RESTART="$ROOT/.cache/launcher.restart"

# --- 初回: JDK / JBang の置き場所を尋ねる（引数なしで対話できるときだけ。パイプや CI では JBang の既定のまま） ---
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
#   JCHE_NETWORK    足りないもの（JDK・JBang 本体・依存 jar）を取りに行ってよいか。
#                   ask=足りないときだけ尋ねる（既定） allow=尋ねずに許可 deny=禁止
#                   この項目だけは空欄でも環境変数を消さない（1 回だけ許可するときに使えるように）
JBANG_DIR=$1
JBANG_REPO=$2
JCHE_JAVA_OPTS=
JCHE_JBANG_OPTS=
JCHE_NETWORK=
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
      # 空欄は「既定値」なので環境変数を消す。ただし JCHE_NETWORK だけは残す。
      # この項目は「この実行だけ許可したい」と環境変数で渡すことがあり、他と同じに
      # 消すと JCHE_NETWORK=allow ./java-call-hierarchy-exporter.sh が
      # launcher.properties があるだけで効かなくなる
      case "$key" in
        JCHE_NETWORK) ;;
        *) unset "$key" ;;
      esac
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

# --- ネットワークアクセスの確認（Issue #86）-----------------------------------
#
# このツールが外に出るのは、手元に無いものを取りに行くときだけで、行き先は 3 種類。
#
#   - JBang 本体   jbangw/jbang が GitHub から取る
#   - JDK          jbangw/jbang が動くための JDK と、src/Jche.java の //JAVA が
#                  求める JDK。前者は jbangw/jbang が、後者は jbang が取る
#   - 依存 jar     //DEPS に書いた座標を jbang が Maven リポジトリから取る
#
# どれも手元にあるなら外に出る必要が無いので何も尋ねない。足りないものがあるとき
# だけ、何を取りに行くのかを挙げて尋ねる。
#
# 尋ねる前提の検出は取りこぼしうる（推移的な依存だけが欠けている場合など）ので、
# 許可されていないときは jbang に --offline も渡す。検出が外れても黙って外に出る
# ことはなく、jbang が「オフラインなので解決できない」と言って止まる。AGENTS.md
# の「迷ったら安全側に倒す」に従う。
#
#   JCHE_NETWORK=ask    既定。足りないものがあるときだけ尋ねる
#   JCHE_NETWORK=allow  尋ねずに許可する（端末が無くて尋ねられない CI 向け）
#   JCHE_NETWORK=deny   尋ねずに禁止する

# jbangw/jbang と同じ既定で置き場所を決める（load_settings の後に呼ぶ）
jbang_dirs() {
  if [ -n "${JBANG_DIR:-}" ]; then JBDIR=$JBANG_DIR; else JBDIR="$HOME/.jbang"; fi
  if [ -n "${JBANG_CACHE_DIR:-}" ]; then TDIR=$JBANG_CACHE_DIR; else TDIR="$JBDIR/cache"; fi
  if [ -n "${JBANG_REPO:-}" ]; then MREPO=$JBANG_REPO; else MREPO="$HOME/.m2/repository"; fi
  if [ -n "${JBANG_DEFAULT_JAVA_VERSION:-}" ]; then wrapperJava=$JBANG_DEFAULT_JAVA_VERSION; else wrapperJava=17; fi
}

# $1（java か javac の実行ファイル）が名乗るメジャー版。取れなければ空。
# 1.8.0 形式は 8、25.0.3 形式は 25 になる
java_major_of() {
  "$1" -version 2>&1 | sed -n '1s/.*"1\.\([0-9][0-9]*\).*/\1/p;1s/.*"\([0-9][0-9]*\).*/\1/p' | head -1
}

# src/Jche.java の //JAVA が求めるメジャー版（"25" や "21+" の数字の部分）
script_java_version() {
  sed -n 's|^//JAVA[[:space:]][[:space:]]*\([0-9][0-9]*\).*|\1|p' "$ROOT/src/Jche.java" | head -1
}

# メジャー版 $1 の JDK が手元にあるか（jbang がこのツールを動かすのに使える形で）
have_jdk_version() {
  local want=$1 m
  [ -n "$want" ] || return 0
  [ -d "$TDIR/jdks/$want" ] && return 0
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    m=$(java_major_of "$JAVA_HOME/bin/javac")
    [ "$m" = "$want" ] && return 0
  fi
  if command -v javac > /dev/null 2>&1; then
    m=$(java_major_of javac)
    [ "$m" = "$want" ] && return 0
  fi
  if [ -x "$JBDIR/currentjdk/bin/javac" ]; then
    m=$(java_major_of "$JBDIR/currentjdk/bin/javac")
    [ "$m" = "$want" ] && return 0
  fi
  return 1
}

# jbangw/jbang が jbang.jar を動かすための JDK があるか。探す順は jbangw/jbang に揃える
have_wrapper_jdk() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then return 0; fi
  if command -v javac > /dev/null 2>&1; then
    # jbangw/jbang は mac では /usr/libexec/java_home が通ることも条件にしている
    case "$(uname -s)" in
      Darwin*) if /usr/libexec/java_home > /dev/null 2>&1; then return 0; fi ;;
      *) return 0 ;;
    esac
  fi
  [ -x "$JBDIR/currentjdk/bin/javac" ] && return 0
  [ -d "$TDIR/jdks/$wrapperJava" ] && return 0
  return 1
}

# JBang 本体が手元にあるか。探す順は jbangw/jbang に揃える
have_jbang() {
  [ -f "$ROOT/jbangw/jbang.jar" ] && return 0
  [ -f "$ROOT/jbangw/.jbang/jbang.jar" ] && return 0
  [ -f "$JBDIR/bin/jbang.jar" ] && [ -f "$JBDIR/bin/jbang" ] && return 0
  return 1
}

# //DEPS の座標のうち、ローカルリポジトリに jar が無いものを 1 行ずつ出す。
# g:a:v 以外の書き方（@pom、バージョン範囲、プロパティ展開など）は手元にあると
# 言い切れないので「足りない」側に倒す
missing_deps() {
  sed -n 's|^//DEPS[[:space:]][[:space:]]*||p' "$ROOT/src/Jche.java" \
    | tr ' \t\r' '\n\n\n' \
    | while IFS= read -r coord; do
        [ -n "$coord" ] || continue
        if [ "$(printf '%s' "$coord" | awk -F: '{print NF}')" != 3 ]; then
          printf '%s\n' "$coord"
          continue
        fi
        g=$(printf '%s' "$coord" | cut -d: -f1)
        a=$(printf '%s' "$coord" | cut -d: -f2)
        v=$(printf '%s' "$coord" | cut -d: -f3)
        [ -f "$MREPO/$(printf '%s' "$g" | tr '.' '/')/$a/$v/$a-$v.jar" ] || printf '%s\n' "$coord"
      done
}

# 取りに行くことになるものを 1 行ずつ挙げる。何も無ければ何も出さない
network_items() {
  local sv deps
  have_jbang || echo "  - JBang 本体"
  have_wrapper_jdk || echo "  - JDK $wrapperJava（JBang を動かす）"
  sv=$(script_java_version)
  if [ -n "$sv" ] && ! have_jdk_version "$sv"; then
    echo "  - JDK $sv（このツールを動かす。src/Jche.java の //JAVA）"
  fi
  deps=$(missing_deps)
  if [ -n "$deps" ]; then
    printf '  - 依存 jar: %s\n' $deps
  fi
}

# 尋ねる相手が居ないときに、何を取りに行こうとしたのかと許可の仕方を出す
no_terminal() {
  echo "java-call-hierarchy-exporter: 次のものが手元に無いので、ダウンロードが要ります。" 1>&2
  printf '%s\n' "$1" 1>&2
  echo "端末ではないため確認を取れません。許可するときは JCHE_NETWORK=allow を設定して実行してください。" 1>&2
}

# $1 に挙げたものを取りに行ってよいか尋ねる。許可なら 0、拒否なら 1
confirm_network() {
  local items=$1 mode answer
  mode=$(printf '%s' "${JCHE_NETWORK:-ask}" | tr 'A-Z' 'a-z')
  case "$mode" in
    allow) return 0 ;;
    ask) ;;
    deny)
      echo "java-call-hierarchy-exporter: 手元に無いものがありますが、JCHE_NETWORK=deny なので取りに行きません。" 1>&2
      printf '%s\n' "$items" 1>&2
      echo "許可するときは launcher.properties の JCHE_NETWORK を ask か allow にしてください。" 1>&2
      return 1 ;;
    *)
      echo "java-call-hierarchy-exporter: JCHE_NETWORK の値 '$JCHE_NETWORK' は ask / allow / deny のいずれかにしてください。" 1>&2
      return 1 ;;
  esac
  # 端末から直接読む。標準入力はメニューの操作に使われることがあり、そこから
  # 1 行取ってしまうとアプリ側の入力がずれるため
  if ! { exec 3<>/dev/tty; } 2>/dev/null; then
    no_terminal "$items"
    return 1
  fi
  {
    echo
    echo "java-call-hierarchy-exporter: ネットワークにアクセスします"
    echo
    echo "次のものが手元に無いので、ダウンロードします。"
    printf '%s\n' "$items"
    echo
    echo "  置き場所 $JBDIR（依存 jar は $MREPO）"
    echo "  取得先   Adoptium（JDK）、GitHub（JBang 本体）、Maven Central（依存 jar）"
    echo
    printf 'ダウンロードしてよいですか [y/N]: '
  } >&3
  # 端末の口が開いても、その先に誰も居ないことがある（Git Bash はパイプで動かして
  # いても /dev/tty を渡すので、read がすぐ終わる）。端末が無いのと同じ扱いにする
  if ! IFS= read -r answer <&3; then
    exec 3>&-
    echo 1>&2
    no_terminal "$items"
    return 1
  fi
  echo >&3
  exec 3>&-
  case "$answer" in
    y|Y|yes|YES|Yes) return 0 ;;
  esac
  echo "中止しました。ネットワークに出ずに動かす方法は README の「閉域ネットワーク」を参照してください。" 1>&2
  return 1
}

run_once() {
  load_settings
  jbang_dirs
  local -a opts=()
  local o
  # shellcheck disable=SC2086  # 空白区切りで複数のオプションを書けるよう、意図して分割する
  for o in ${JCHE_JBANG_OPTS:-}; do opts+=("$o"); done
  # shellcheck disable=SC2086
  for o in ${JCHE_JAVA_OPTS:-}; do opts+=("-R$o"); done
  # 足りないものがあれば尋ね、拒まれたら実行しない。何も足りないときは外に出る
  # 必要が無いので --offline を足して、検出が取りこぼしても出られないようにする
  # （allow は操作者が先に許可しているので、そのまま外に出られるようにする）
  local items
  items=$(network_items)
  if [ -n "$items" ]; then
    confirm_network "$items" || exit 1
  elif [ "$(printf '%s' "${JCHE_NETWORK:-ask}" | tr 'A-Z' 'a-z')" != allow ]; then
    opts+=("--offline")
  fi
  export JCHE_ROOT="$ROOT"
  # ${opts[@]+"${opts[@]}"} は、要素が無いときに bash 3.2（macOS）の set -u で落ちないための書き方
  exec "$ROOT/jbangw/jbang" run ${opts[@]+"${opts[@]}"} "$ROOT/src/Jche.java" "$@"
}

# 引数の種類を見る（--help なら何もしない。設定ファイルが1つでもあれば対話なしの実行）
show_help=0
has_config=0
for arg in "$@"; do
  case "$arg" in
    --help|-h) show_help=1 ;;
    *) has_config=1 ;;
  esac
done

if [ "$show_help" = 0 ] && [ ! -f "$SETTINGS" ] && [ -t 0 ] && [ -t 1 ]; then
  if [ "$has_config" = 1 ]; then
    # 引数ありは対話なしで実行する。置き場所は尋ねず、既定（このプロジェクトの中）にして知らせるだけ
    write_settings ".jbang" ".jbang/repository"
    echo "java-call-hierarchy-exporter: JDK と JBang は $ROOT/.jbang に置きます（変えるときは $SETTINGS）。"
    echo
  else
    first_run_prompt
  fi
fi

while :; do
  rm -f "$RESTART"
  ( run_once "$@" )
  code=$?
  [ -f "$RESTART" ] || exit $code
  echo "設定を反映するため再起動します..."
done
