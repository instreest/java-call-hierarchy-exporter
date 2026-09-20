#!/usr/bin/env bash
# java-call-hierarchy-exporter の起動コマンド（Linux / macOS / Git Bash。Windows のコマンドプロンプトは java-call-hierarchy-exporter.cmd）。
#
#   ./java-call-hierarchy-exporter.sh                          引数なし … 対話モード（メニューで設定ファイルを選んで解析する）
#   ./java-call-hierarchy-exporter.sh a.properties [b.properties…] 引数あり … 対話なしで解析する（jbang で src/jche/CallHierarchyExporter.java を直接動かすのと同じ）
#   ./java-call-hierarchy-exporter.sh --help
#
# 設定ファイルを渡したときは何も尋ねない（Issue #83）。初回で launcher.properties がまだ無ければ、
# 置き場所の質問は出さずに既定（このプロジェクトの中の .jbang）で作り、その旨を 1 行出すだけにする。
# ただし、ネットワークからの取得（JBang 本体・JDK・依存 jar）が必要なときだけは、引数の有無によらず
# 取得してよいかを確認する（Issue #86）。n なら何も取得せずに終了コード 3 で終わる。質問は標準入力ではなく
# 端末（/dev/tty）から読む。端末が無くて確認できないとき（パイプ・CI・タスクスケジューラ。端末の口が開いていても
# その先に誰も居ないときを含む）も取得せず 3 で終わるので、そこでは launcher.properties か環境変数で
# JCHE_ALLOW_DOWNLOAD=yes（尋ねずに取得する）/ no（取得しない）をあらかじめ決めておく。
#
# どこから実行してもよい（このファイルのあるフォルダを起点にする）。
#
# やること:
#   1. launcher.properties（このフォルダ直下）を読み、JDK / JBang の置き場所（JBANG_DIR 等）や JVM のオプションを
#      環境変数にする。無ければ、対話できるときだけ置き場所を尋ねて作る（初回だけ。引数があるときは尋ねずに既定で作る）。
#   2. jbangw/jbang（同梱の JBang ラッパー）で src/jche/Jche.java を動かす。
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

# --- 表示言語 -------------------------------------------------------
# 文言は英語が既定で、日本語を選んだときだけ日本語にする（Java 側の jche.util.Messages と同じ作法）。
# 決め方も同じ順にそろえてある。環境変数 JCHE_LANG（en / ja）→ ロケール（LC_ALL → LC_MESSAGES → LANG）。
# launcher.properties に JCHE_LANG=ja と書いておくこともできる。load_settings が環境変数にしたあと
# resolve_lang をもう一度呼ぶので、取得の確認はその言語で出る。ただし初回の置き場所の質問は
# launcher.properties がまだ無い時点なので、そこだけは環境変数か OS のロケールで決まる。
#
# msg <キー> [差し込む値…] で 1 行出す。日本語に無いキーは英語のまま出る（英語に重ねる作り）。
# 英語にも無ければ !キー! と出るので、訳し忘れに気づける。
resolve_lang() {
  local raw="${JCHE_LANG:-}"
  [ -n "$raw" ] || raw="${LC_ALL:-}"
  [ -n "$raw" ] || raw="${LC_MESSAGES:-}"
  [ -n "$raw" ] || raw="${LANG:-}"
  case "$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')" in
    ja|ja[-_.]*) printf 'ja' ;;
    *) printf 'en' ;;
  esac
}
JCHE_MSG_LANG=$(resolve_lang)

msg() {
  if [ "$JCHE_MSG_LANG" = ja ]; then
    msg_ja "$@" && return 0
  fi
  msg_en "$@"
}

# 英語（土台）。$2 以降は差し込む値
msg_en() {
  case "$1" in
    first.title)      echo "java-call-hierarchy-exporter: first-time setup" ;;
    first.where)      echo "Choose where the JDK and JBang this tool uses (a few hundred MB together) should live." ;;
    first.local)      echo "  1) Inside this project   $2" ;;
    first.localHint)  echo "     Nothing else is touched; delete the folder to undo. Dependency jars go there too" ;;
    first.home)       echo "  2) User home             $2 (JBang's default)" ;;
    first.homeHint)   echo "     Shared with other JBang scripts. Pick this if you already use JBang" ;;
    first.changeLater) echo "To change it later, use the Environment settings screen in the app, or edit $2." ;;
    first.downloadLater) echo "(Downloading itself is confirmed again before going to the network.)" ;;
    first.prompt)     printf 'Number [1]: ' ;;
    first.saved)      echo "Saved to $2." ;;
    settings.header1) echo "# Settings read at startup by java-call-hierarchy-exporter.sh / java-call-hierarchy-exporter.cmd (the Environment settings screen of the app writes here too)." ;;
    settings.header2) echo "# Each key becomes an environment variable as is. Relative paths start from the folder holding this file. Empty means the default." ;;
    settings.jbangDir) echo "#   JBANG_DIR       where JBang itself and the JDK live (default ~/.jbang)" ;;
    settings.repo)    echo "#   JBANG_REPO      where dependency jars live (default ~/.m2/repository)" ;;
    settings.javaOpts) echo "#   JCHE_JAVA_OPTS  options for the JVM that runs the analysis (for example: -Xmx4g)" ;;
    settings.jbangOpts) echo "#   JCHE_JBANG_OPTS extra options for jbang run (for example: --offline)" ;;
    settings.allowDownload) echo "#   JCHE_ALLOW_DOWNLOAD  yes to download from the network (JBang, the JDK, dependency jars) without asking, no to never download. Empty asks every time" ;;
    item.jbang)       echo "JBang itself (about ${2}MB)" ;;
    item.jdk)         echo "JDK $2 to run the tool (about ${3}MB)" ;;
    item.deps)        echo "dependency jars (JDT and others, about ${2}MB)" ;;
    item.sep)         printf ', ' ;;
    from.jbang)       echo "github.com (JBang itself)" ;;
    from.jdk)         echo "api.foojay.io (the JDK; served from Adoptium on github.com)" ;;
    from.deps)        echo "Maven Central (dependency jars)" ;;
    note.jdk)         echo "; the JDK keeps both the unpacked files and the archive" ;;
    net.needed)       echo "java-call-hierarchy-exporter: something has to be downloaded from the network" ;;
    net.items)        echo "  To download   : $2" ;;
    net.size)         echo "  Transfer      : about ${2}MB (the location grows by about ${3}MB$4)" ;;
    net.sizeNote)     echo "                  A measured estimate. Anything already present is not fetched, so the real figure is lower" ;;
    net.from)         echo "  From          : $2" ;;
    net.into)         echo "  Into          : $2 (JBang and the JDK), $3 (dependency jars)" ;;
    net.offline)      echo "  JCHE_JBANG_OPTS contains --offline, so nothing is downloaded. Remove --offline from $2 to allow it." ;;
    net.cancelled)    echo "Download cancelled." ;;
    net.allowYes)     echo "  JCHE_ALLOW_DOWNLOAD=yes, so it downloads without asking." ;;
    net.allowNo)      echo "  JCHE_ALLOW_DOWNLOAD=no, so nothing is downloaded. Set it to yes in $2, or leave it empty to be asked every time." ;;
    net.ask)          printf 'Go to the network and download? [y/N]: ' ;;
    net.noTty)        echo "  There is no terminal to ask on. Nothing is downloaded." ;;
    net.noTtyYes)     echo "  To download without asking, set JCHE_ALLOW_DOWNLOAD=yes in $2 (or as an environment variable)." ;;
    net.noTtyOffline) echo "  To run without downloading, prepare a local JDK and the jars first (see the Pleiades/Eclipse environment section in the README)." ;;
    offline.failed)   echo "Could not start with only the JDK and dependency jars already present (the reason is in the message above)." ;;
    first.defaultDir) echo "java-call-hierarchy-exporter: the JDK and JBang go into $2 (change it in $3)." ;;
    restart)          echo "Restarting to apply the settings..." ;;
    *)                echo "!$1!" ;;
  esac
}

# 日本語（英語に重ねる）。知らないキーは 1 を返して英語へ落とす
msg_ja() {
  case "$1" in
    first.title)      echo "java-call-hierarchy-exporter: 初回の設定" ;;
    first.where)      echo "このツールが使う JDK と JBang（合わせて数百 MB）の置き場所を選んでください。" ;;
    first.local)      echo "  1) このプロジェクトの中   $2" ;;
    first.localHint)  echo "     他の環境を汚さず、フォルダごと消せば元に戻る。依存 jar も同じ場所に置く" ;;
    first.home)       echo "  2) ユーザーのホーム       $2（JBang の既定）" ;;
    first.homeHint)   echo "     他の JBang スクリプトと共有する。既に JBang を使っているならこちら" ;;
    first.changeLater) echo "後から変えるときは、アプリの「環境設定」か、$2 を編集する。" ;;
    first.downloadLater) echo "（取得そのものは、このあとネットワークに出る前にもう一度確認する）" ;;
    first.prompt)     printf '番号 [1]: ' ;;
    first.saved)      echo "$2 に保存しました。" ;;
    settings.header1) echo "# java-call-hierarchy-exporter.sh / java-call-hierarchy-exporter.cmd が起動時に読む設定（アプリの「環境設定」からも書き換えられる）。" ;;
    settings.header2) echo "# キーはそのまま環境変数になる。相対パスはこのファイルのあるフォルダが起点。空欄は既定値。" ;;
    settings.jbangDir) echo "#   JBANG_DIR       JBang 本体・JDK の置き場所（既定 ~/.jbang）" ;;
    settings.repo)    echo "#   JBANG_REPO      依存 jar の置き場所（既定 ~/.m2/repository）" ;;
    settings.javaOpts) echo "#   JCHE_JAVA_OPTS  解析を動かす JVM のオプション（例: -Xmx4g）" ;;
    settings.jbangOpts) echo "#   JCHE_JBANG_OPTS jbang run に足すオプション（例: --offline）" ;;
    settings.allowDownload) echo "#   JCHE_ALLOW_DOWNLOAD  ネットワークからの取得（JBang 本体・JDK・依存 jar）を、尋ねずに行うなら yes、行わないなら no。空欄は毎回尋ねる" ;;
    item.jbang)       echo "JBang 本体（約 ${2}MB）" ;;
    item.jdk)         echo "ツールを動かす JDK $2（約 ${3}MB）" ;;
    item.deps)        echo "依存 jar（JDT ほか。約 ${2}MB）" ;;
    item.sep)         printf '、' ;;
    from.jbang)       echo "github.com（JBang 本体）" ;;
    from.jdk)         echo "api.foojay.io（JDK。実体は Adoptium の github.com）" ;;
    from.deps)        echo "Maven Central（依存 jar）" ;;
    note.jdk)         echo "。JDK は展開したものとアーカイブの両方が残るため" ;;
    net.needed)       echo "java-call-hierarchy-exporter: ネットワークからの取得が必要です" ;;
    net.items)        echo "  取得するもの : $2" ;;
    net.size)         echo "  通信量の目安 : 約 ${2}MB（置き場所は約 ${3}MB 増える$4）" ;;
    net.sizeNote)     echo "                 実測に基づく目安。すでに手元にあるものは取得しないので、実際はこれ以下になる" ;;
    net.from)         echo "  取得元       : $2" ;;
    net.into)         echo "  置き場所     : $2（JBang 本体・JDK）、$3（依存 jar）" ;;
    net.offline)      echo "  JCHE_JBANG_OPTS に --offline があるので取得しません。取得するには $2 の --offline を外してください。" ;;
    net.cancelled)    echo "取得を取りやめました。" ;;
    net.allowYes)     echo "  JCHE_ALLOW_DOWNLOAD=yes なので、尋ねずに取得します。" ;;
    net.allowNo)      echo "  JCHE_ALLOW_DOWNLOAD=no なので取得しません。取得するには $2 で yes にするか空欄（毎回尋ねる）にしてください。" ;;
    net.ask)          printf 'ネットワークにアクセスして取得しますか？ [y/N]: ' ;;
    net.noTty)        echo "  端末が無いため確認できません。取得しません。" ;;
    net.noTtyYes)     echo "  尋ねずに取得するには $2（または環境変数）で JCHE_ALLOW_DOWNLOAD=yes にしてください。" ;;
    net.noTtyOffline) echo "  取得せずに動かすには、先に手元の JDK と jar を用意してください（README の「Pleiades/Eclipse環境（閉域ネットワーク等）」）。" ;;
    offline.failed)   echo "取得済みの JDK と依存 jar だけでは起動できませんでした（原因は上のメッセージ）。" ;;
    first.defaultDir) echo "java-call-hierarchy-exporter: JDK と JBang は $2 に置きます（変えるときは $3）。" ;;
    restart)          echo "設定を反映するため再起動します..." ;;
    *)                return 1 ;;
  esac
}

# --- 初回: JDK / JBang の置き場所を尋ねる（引数なしで対話できるときだけ。パイプや CI では JBang の既定のまま） ---
first_run_prompt() {
  msg first.title
  echo
  msg first.where
  msg first.local "$ROOT/.jbang"
  msg first.localHint
  msg first.home "${HOME}/.jbang"
  msg first.homeHint
  msg first.changeLater "$SETTINGS"
  msg first.downloadLater
  echo
  local choice
  msg first.prompt
  IFS= read -r choice || choice=1
  case "$choice" in
    2) write_settings "" "" ;;
    *) write_settings ".jbang" ".jbang/repository" ;;
  esac
  msg first.saved "$SETTINGS"
  echo
}

# $1=JBANG_DIR  $2=JBANG_REPO（相対はこのフォルダ起点。空欄は JBang の既定）
# 書く内容は Java 側（LauncherSettings.save）・java-call-hierarchy-exporter.cmd と同じにしておく
write_settings() {
  {
    msg settings.header1
    msg settings.header2
    msg settings.jbangDir
    msg settings.repo
    msg settings.javaOpts
    msg settings.jbangOpts
    msg settings.allowDownload
    echo "JBANG_DIR=$1"
    echo "JBANG_REPO=$2"
    echo "JCHE_JAVA_OPTS="
    echo "JCHE_JBANG_OPTS="
    echo "JCHE_ALLOW_DOWNLOAD="
  } > "$SETTINGS"
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

# 取得しうるものの目安サイズ（MB）。net はダウンロード量、disk は置き場所が増える量。
# 実測値で、測り直し方は docs/network-download-confirm-qa.md の Q15 にある。版が上がれば少し変わるので「約」として出す。
SIZE_JBANG_NET=15;  SIZE_JBANG_DISK=30    # jbang.tar / jbang.zip。bin に展開し、アーカイブも cache/urls に残る
SIZE_JDK_NET=135;   SIZE_JDK_DISK=440     # Temurin 25 x64。展開後 300MB ほどと、アーカイブ 135MB の両方が残る
SIZE_DEPS_NET=15;   SIZE_DEPS_DISK=15     # JDT 一式（19 個の jar）

# ラッパー（jbangw/jbang）が jbang を動かす前にネットワークに出るか。
# ラッパーには --offline のような抑止が無く、呼んだ時点で取得が始まるので、呼ぶ前に確認する必要がある。
# 見る場所はラッパーと同じ（JBANG_DIR / JBANG_CACHE_DIR / JBANG_DEFAULT_JAVA_VERSION）
wrapper_would_download() {
  local jbdir="${JBANG_DIR:-$HOME/.jbang}"
  local tdir="${JBANG_CACHE_DIR:-$jbdir/cache}"
  jbang_jar_available "$jbdir" || return 0
  bootstrap_jdk_available "$jbdir" "$tdir" || return 0
  return 1
}

# $1=JBANG_DIR。ラッパーが jbang 本体を探す順（同梱 → JBANG_DIR/bin）
jbang_jar_available() {
  [ -f "$ROOT/jbangw/jbang.jar" ] && return 0
  [ -f "$ROOT/jbangw/.jbang/jbang.jar" ] && return 0
  # JBANG_DIR に入っているものを使えるのは jbang.jar と起動スクリプトの両方が揃っているときだけ。
  # ラッパー（jbangw/jbang）は片方でも欠けていると取りに行く（jbangw/jbang の
  # 「! -f "$JBDIR/bin/jbang.jar" || ! -f "$JBDIR/bin/jbang"」）。jar だけを見ていると、
  # 取りに行くのに「ある」と判断して確認を飛ばしてしまう
  [ -f "$1/bin/jbang.jar" ] && [ -f "$1/bin/jbang" ] && return 0
  return 1
}

# $1=JBANG_DIR  $2=キャッシュのフォルダ。ラッパーの setup_java_exec と同じ順で探す
bootstrap_jdk_available() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then return 0; fi
  if command -v javac > /dev/null 2>&1; then
    # macOS の /usr/bin/javac は JDK が無くても存在する（インストールを促すだけの入れ物）
    if [ "$(uname -s)" != Darwin ] || /usr/libexec/java_home > /dev/null 2>&1; then return 0; fi
  fi
  [ -x "$1/currentjdk/bin/javac" ] && return 0
  jdk_available "$2" && return 0
  return 1
}

# $1=キャッシュのフォルダ。ツールを動かす JDK（//JAVA 25。ラッパーが取る JDK と同じ版にそろえてある）が取得済みか
jdk_available() {
  [ -d "$1/jdks/$JBANG_DEFAULT_JAVA_VERSION" ]
}

# 動かすスクリプトの //JAVA が求めるメジャー版。読めなければ 25（現状の値）にしておく。
# ここを定数で持つと、ソース側の //JAVA を上げたときに黙ってずれ、ラッパーが取る JDK と
# jbang が取る JDK が別々になって 2 つ取得してしまう
script_java_version() {
  local v
  v=$(sed -n 's|^//JAVA[[:space:]][[:space:]]*\([0-9][0-9]*\).*|\1|p' "$ROOT/src/jche/Jche.java" 2> /dev/null | head -1)
  printf '%s' "${v:-25}"
}

# 取得しうるもののうち、まだ手元に無いものの鍵（jbang / jdk / deps）を空白区切りで出す。
# 依存 jar は手元にあるかを確かめようがないので（推移的な依存まで数えることになる。Q3）常に挙げる
pending_items() {
  local jbdir="${JBANG_DIR:-$HOME/.jbang}"
  local tdir="${JBANG_CACHE_DIR:-$jbdir/cache}"
  local items=""
  jbang_jar_available "$jbdir" || items="jbang"
  jdk_available "$tdir" || items="${items:+$items }jdk"
  printf '%s' "${items:+$items }deps"
}

# $1=鍵の一覧。表示用の文（DL_ITEMS / DL_FROM）と合計サイズ（DL_NET / DL_DISK）を作る
describe_items() {
  DL_ITEMS=""; DL_FROM=""; DL_NET=0; DL_DISK=0; DL_NOTE=""
  local key sep
  sep=$(msg item.sep)
  for key in $1; do
    case "$key" in
      jbang)
        DL_ITEMS="${DL_ITEMS:+$DL_ITEMS$sep}$(msg item.jbang "$SIZE_JBANG_NET")"
        DL_FROM="${DL_FROM:+$DL_FROM$sep}$(msg from.jbang)"
        DL_NET=$((DL_NET + SIZE_JBANG_NET)); DL_DISK=$((DL_DISK + SIZE_JBANG_DISK)) ;;
      jdk)
        DL_ITEMS="${DL_ITEMS:+$DL_ITEMS$sep}$(msg item.jdk "$JBANG_DEFAULT_JAVA_VERSION" "$SIZE_JDK_NET")"
        DL_FROM="${DL_FROM:+$DL_FROM$sep}$(msg from.jdk)"
        DL_NOTE="$(msg note.jdk)"
        DL_NET=$((DL_NET + SIZE_JDK_NET)); DL_DISK=$((DL_DISK + SIZE_JDK_DISK)) ;;
      deps)
        DL_ITEMS="${DL_ITEMS:+$DL_ITEMS$sep}$(msg item.deps "$SIZE_DEPS_NET")"
        DL_FROM="${DL_FROM:+$DL_FROM$sep}$(msg from.deps)"
        DL_NET=$((DL_NET + SIZE_DEPS_NET)); DL_DISK=$((DL_DISK + SIZE_DEPS_DISK)) ;;
    esac
  done
}

# ネットワークから取得してよいか。$1=取得しうるものの鍵の一覧（pending_items の出力）。よければ 0、だめなら 1
# 順に、JCHE_JBANG_OPTS の --offline → JCHE_ALLOW_DOWNLOAD（yes / no）→ 端末があれば尋ねる → 端末が無ければ取得しない
approve_download() {
  local jbdir="${JBANG_DIR:-$HOME/.jbang}"
  local repo="${JBANG_REPO:-$HOME/.m2/repository}"
  local allow
  allow=$(printf '%s' "${JCHE_ALLOW_DOWNLOAD:-}" | tr '[:upper:]' '[:lower:]')
  describe_items "$1"
  echo
  msg net.needed
  msg net.items "$DL_ITEMS"
  msg net.size "$DL_NET" "$DL_DISK" "$DL_NOTE"
  msg net.sizeNote
  msg net.from "$DL_FROM"
  msg net.into "$jbdir" "$repo"
  if [ "$OFFLINE_FORCED" = 1 ]; then
    msg net.offline "$SETTINGS"
    msg net.cancelled
    return 1
  fi
  case "$allow" in
    yes|y|true|1)
      msg net.allowYes
      return 0 ;;
    no|n|false|0)
      msg net.allowNo "$SETTINGS"
      msg net.cancelled
      return 1 ;;
  esac
  # 質問は標準入力ではなく端末（/dev/tty）から読む。標準入力は対話モードのメニュー操作に使われるので、
  # ここで 1 行取るとアプリ側の入力が 1 行ずれる。開けないときは尋ねる相手が居ない（パイプ・CI・タスクスケジューラ）
  if ! { exec 3<>/dev/tty; } 2> /dev/null; then
    cannot_ask
    return 1
  fi
  local answer
  msg net.ask >&3
  # 端末の口が開いても、その先に誰も居ないことがある（Git Bash はパイプで動かしていても /dev/tty を
  # 渡すので、read がすぐ終わる）。そのときは端末が無いのと同じ扱いにする
  if ! IFS= read -r answer <&3; then
    exec 3>&-
    echo
    cannot_ask
    return 1
  fi
  echo >&3
  exec 3>&-
  case "$(printf '%s' "$answer" | tr '[:upper:]' '[:lower:]')" in
    y|yes) return 0 ;;
  esac
  msg net.cancelled
  return 1
}

# 尋ねる相手が居ないときの案内。取得しなかったことと、先に決めておく方法を出す
cannot_ask() {
  msg net.noTty
  msg net.noTtyYes "$SETTINGS"
  msg net.noTtyOffline
  msg net.cancelled
}

run_once() {
  load_settings
  # launcher.properties の JCHE_LANG を反映する（環境変数が既にあればそれが勝つ＝値は変わらない）
  JCHE_MSG_LANG=$(resolve_lang)
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
  # ラッパーが JBang を動かすために取得する JDK の版。既定（17）のままだと、ツールを動かす JDK と合わせて
  # 2 つの JDK を取得することになるので、スクリプトの //JAVA にそろえて 1 つで済ませる
  export JBANG_DEFAULT_JAVA_VERSION="${JBANG_DEFAULT_JAVA_VERSION:-$(script_java_version)}"
  local jbang="$ROOT/jbangw/jbang"
  local script="$ROOT/src/jche/Jche.java"
  if wrapper_would_download || [ "$fresh" = 1 ]; then
    # ラッパーが jbang を動かす前に取得するものが無い（または --fresh で取り直す）。走らせる前に確認して、
    # よければ取得込みで動かす（このあと jbang が取得する JDK と依存 jar も、この 1 回の確認に含める）
    approve_download "$(pending_items)" || return 3
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
  msg offline.failed
  approve_download "$(pending_items)" || return 3
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
    msg first.defaultDir "$ROOT/.jbang" "$SETTINGS"
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
  msg restart
done
