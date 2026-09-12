#!/usr/bin/env bash
# java-call-hierarchy-exporter の起動コマンド（Linux / macOS / Git Bash。Windows のコマンドプロンプトは java-call-hierarchy-exporter.cmd）。
#
#   ./java-call-hierarchy-exporter.sh                          引数なし … 対話モード（メニューで設定ファイルを選んで解析する）
#   ./java-call-hierarchy-exporter.sh a.properties [b.properties…] 引数あり … 対話なしで解析する（jbang で src/CallHierarchyExporter.java を直接動かすのと同じ）
#   ./java-call-hierarchy-exporter.sh --help
#
# 設定ファイルを渡したときは何も尋ねない（Issue #83）。初回で launcher.properties がまだ無ければ、
# 置き場所の質問は出さずに既定（このプロジェクトの中の .jbang）で作り、その旨を 1 行出すだけにする。
# ただし、ネットワークからの取得（JBang 本体・JDK・依存 jar）が必要なときだけは、引数の有無によらず
# 取得してよいかを確認する（Issue #86）。n なら何も取得せずに終了コード 3 で終わる。端末が無くて確認できない
# とき（パイプ・CI・タスクスケジューラ）も取得せず 3 で終わるので、そこでは launcher.properties か環境変数で
# JCHE_ALLOW_DOWNLOAD=yes（尋ねずに取得する）/ no（取得しない）をあらかじめ決めておく。
#
# どこから実行してもよい（このファイルのあるフォルダを起点にする）。
#
# やること:
#   1. launcher.properties（このフォルダ直下）を読み、JDK / JBang の置き場所（JBANG_DIR 等）や JVM のオプションを
#      環境変数にする。無ければ、対話できるときだけ置き場所を尋ねて作る（初回だけ。引数があるときは尋ねずに既定で作る）。
#   2. jbangw/jbang（同梱の JBang ラッパー）で src/Jche.java を動かす。
#      ネットワークに出るのは次の 3 段階で、いずれも操作者の確認（または JCHE_ALLOW_DOWNLOAD）なしには行わない:
#        a. ラッパーが JBang 本体（github.com）と、JBang を動かす JDK（api.foojay.io）を取得する
#           … Java が動く前なので、置き場所のファイルの有無を見て、無ければ走らせる前に確認する
#        b. jbang がツールを動かす JDK 25（api.foojay.io）を取得する
#        c. jbang が依存 jar（JDT ほか。Maven Central）を推移的な依存まで解決して取得する
#           … b と c は、まず --offline（取得済みのものだけで動かし、足りなければ失敗する）で起動し、
#             アプリが始まる前に失敗したときだけ確認して、--offline なしで起動し直す。
#             アプリが始まったかは、アプリが置く目印ファイル（.cache/launcher.started）で見分ける
#      jbang 自身の更新確認（新しい版があるかを問い合わせる）も JBANG_NO_VERSION_CHECK で止める。
#   3. アプリが「再起動して設定を反映」を要求したとき（.cache/launcher.restart ができる）は 1 からやり直す。
#      置き場所や JVM オプションは Java が起動する前に決まるので、Java 側からは変えられない。
#
# 設定ファイルの読み込みと jbang の実行はサブシェルで行う。再起動のたびに前回の環境変数が残らないようにするため。
set -u

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SETTINGS="$ROOT/launcher.properties"
RESTART="$ROOT/.cache/launcher.restart"
STARTED="$ROOT/.cache/launcher.started"

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
  echo "（取得そのものは、このあとネットワークに出る前にもう一度確認する）"
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
# 書く内容は Java 側（LauncherSettings.save）・java-call-hierarchy-exporter.cmd と同じにしておく
write_settings() {
  cat > "$SETTINGS" <<EOS
# java-call-hierarchy-exporter.sh / java-call-hierarchy-exporter.cmd が起動時に読む設定（アプリの「環境設定」からも書き換えられる）。
# キーはそのまま環境変数になる。相対パスはこのファイルのあるフォルダが起点。空欄は既定値。
#   JBANG_DIR       JBang 本体・JDK の置き場所（既定 ~/.jbang）
#   JBANG_REPO      依存 jar の置き場所（既定 ~/.m2/repository）
#   JCHE_JAVA_OPTS  解析を動かす JVM のオプション（例: -Xmx4g）
#   JCHE_JBANG_OPTS jbang run に足すオプション（例: --offline）
#   JCHE_ALLOW_DOWNLOAD  ネットワークからの取得（JBang 本体・JDK・依存 jar）を、尋ねずに行うなら yes、行わないなら no。空欄は毎回尋ねる
JBANG_DIR=$1
JBANG_REPO=$2
JCHE_JAVA_OPTS=
JCHE_JBANG_OPTS=
JCHE_ALLOW_DOWNLOAD=
EOS
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
      # 空欄は既定値。JCHE_ALLOW_DOWNLOAD だけは、空欄（毎回尋ねる）のまま環境変数で一時的に yes / no を渡せるよう残す
      [ "$key" = JCHE_ALLOW_DOWNLOAD ] || unset "$key"
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

# --- ネットワークからの取得の確認 ---

# ラッパー（jbangw/jbang）が jbang を動かす前に取得するもの（JBang 本体、JBang を動かす JDK）のうち、
# まだ無いものを表示用の名前で出す。見る場所はラッパーと同じ（JBANG_DIR / JBANG_CACHE_DIR / JBANG_DEFAULT_JAVA_VERSION）
missing_bootstrap() {
  local jbdir="${JBANG_DIR:-$HOME/.jbang}"
  local tdir="${JBANG_CACHE_DIR:-$jbdir/cache}"
  local missing=""
  if [ ! -f "$ROOT/jbangw/jbang.jar" ] && [ ! -f "$ROOT/jbangw/.jbang/jbang.jar" ] && [ ! -f "$jbdir/bin/jbang.jar" ]; then
    missing="JBang 本体"
  fi
  if ! bootstrap_jdk_available "$jbdir" "$tdir"; then
    missing="${missing:+$missing、}JBang を動かす JDK"
  fi
  printf '%s' "$missing"
}

# $1=JBANG_DIR  $2=キャッシュのフォルダ。ラッパーの setup_java_exec と同じ順で探す
bootstrap_jdk_available() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then return 0; fi
  if command -v javac > /dev/null 2>&1; then
    # macOS の /usr/bin/javac は JDK が無くても存在する（インストールを促すだけの入れ物）
    if [ "$(uname -s)" != Darwin ] || /usr/libexec/java_home > /dev/null 2>&1; then return 0; fi
  fi
  [ -x "$1/currentjdk/bin/javac" ] && return 0
  [ -d "$2/jdks/$JBANG_DEFAULT_JAVA_VERSION" ] && return 0
  return 1
}

# ネットワークから取得してよいか。$1=取得するもの（表示用）。よければ 0、だめなら 1
# 順に、JCHE_JBANG_OPTS の --offline → JCHE_ALLOW_DOWNLOAD（yes / no）→ 端末があれば尋ねる → 端末が無ければ取得しない
approve_download() {
  local jbdir="${JBANG_DIR:-$HOME/.jbang}"
  local repo="${JBANG_REPO:-$HOME/.m2/repository}"
  local allow
  allow=$(printf '%s' "${JCHE_ALLOW_DOWNLOAD:-}" | tr '[:upper:]' '[:lower:]')
  echo
  echo "java-call-hierarchy-exporter: ネットワークからの取得が必要です"
  echo "  取得するもの : $1"
  echo "  取得元       : github.com（JBang 本体）、api.foojay.io（JDK）、Maven Central（依存 jar）"
  echo "  置き場所     : $jbdir（JBang 本体・JDK）、$repo（依存 jar）"
  echo "  大きさ       : 初回は合わせて数百 MB"
  if [ "$OFFLINE_FORCED" = 1 ]; then
    echo "  JCHE_JBANG_OPTS に --offline があるので取得しません。取得するには $SETTINGS の --offline を外してください。"
    echo "取得を取りやめました。"
    return 1
  fi
  case "$allow" in
    yes|y|true|1)
      echo "  JCHE_ALLOW_DOWNLOAD=yes なので、尋ねずに取得します。"
      return 0 ;;
    no|n|false|0)
      echo "  JCHE_ALLOW_DOWNLOAD=no なので取得しません。取得するには $SETTINGS で yes にするか空欄（毎回尋ねる）にしてください。"
      echo "取得を取りやめました。"
      return 1 ;;
  esac
  if [ ! -t 0 ] || [ ! -t 1 ]; then
    echo "  端末が無いため確認できません。取得しません。"
    echo "  尋ねずに取得するには $SETTINGS（または環境変数）で JCHE_ALLOW_DOWNLOAD=yes にしてください。"
    echo "  取得せずに動かすには、先に手元の JDK と jar を用意してください（README の「Pleiades/Eclipse環境（閉域ネットワーク等）」）。"
    echo "取得を取りやめました。"
    return 1
  fi
  local answer
  printf 'ネットワークにアクセスして取得しますか？ [y/N]: '
  IFS= read -r answer || answer=""
  case "$(printf '%s' "$answer" | tr '[:upper:]' '[:lower:]')" in
    y|yes) return 0 ;;
  esac
  echo "取得を取りやめました。"
  return 1
}

run_once() {
  load_settings
  local -a opts=()
  local o
  OFFLINE_FORCED=0
  local fresh=0
  # shellcheck disable=SC2086  # 空白区切りで複数のオプションを書けるよう、意図して分割する
  for o in ${JCHE_JBANG_OPTS:-}; do
    case "$o" in
      # --offline は起動コマンド自身が付けるので、利用者の指定は「ネットワークに出ない」という意思として覚えておく
      --offline|-o) OFFLINE_FORCED=1 ;;
      # --fresh（依存 jar を取り直す）は --offline と同時に指定できないので、「取り直す」という意思として覚えておき、
      # --offline での起動を飛ばして先に確認する
      --fresh) fresh=1; opts+=("$o") ;;
      *) opts+=("$o") ;;
    esac
  done
  # shellcheck disable=SC2086
  for o in ${JCHE_JAVA_OPTS:-}; do opts+=("-R$o"); done
  export JCHE_ROOT="$ROOT"
  # jbang 自身の更新確認（起動のたびに新しい版があるかを問い合わせる）はネットワークに出るので止める
  export JBANG_NO_VERSION_CHECK=true
  # ラッパーが JBang を動かすために取得する JDK の版。既定（17）のままだと、ツールを動かす JDK 25 と合わせて
  # 2 つの JDK を取得することになるので、25 にそろえて 1 つで済ませる
  export JBANG_DEFAULT_JAVA_VERSION="${JBANG_DEFAULT_JAVA_VERSION:-25}"
  local jbang="$ROOT/jbangw/jbang"
  local script="$ROOT/src/Jche.java"
  local missing
  missing=$(missing_bootstrap)
  if [ -n "$missing" ] || [ "$fresh" = 1 ]; then
    # ラッパーが jbang を動かす前に取得するものが無い（または --fresh で取り直す）。走らせる前に確認して、
    # よければ取得込みで動かす（このあと jbang が取得する JDK 25 と依存 jar も、この 1 回の確認に含める）
    local what="$missing、ツールを動かす JDK 25、依存 jar（JDT ほか）"
    [ -n "$missing" ] || what="依存 jar（JDT ほか）を取り直す（JCHE_JBANG_OPTS の --fresh）。JDK 25 も無ければ取得する"
    approve_download "$what" || return 3
    # ${opts[@]+"${opts[@]}"} は、要素が無いときに bash 3.2（macOS）の set -u で落ちないための書き方
    "$jbang" run ${opts[@]+"${opts[@]}"} "$script" "$@"
    return $?
  fi
  # 取得済みのものだけで動かす。足りなければ jbang がアプリを始める前に失敗する
  rm -f "$STARTED"
  "$jbang" run --offline ${opts[@]+"${opts[@]}"} "$script" "$@"
  local code=$?
  [ "$code" = 0 ] && return 0
  [ -f "$STARTED" ] && return $code
  echo
  echo "取得済みの JDK と依存 jar だけでは起動できませんでした（原因は上のメッセージ）。"
  approve_download "ツールを動かす JDK 25 か依存 jar（JDT ほか）のうち足りないもの" || return 3
  "$jbang" run ${opts[@]+"${opts[@]}"} "$script" "$@"
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
