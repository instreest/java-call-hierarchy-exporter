#!/usr/bin/env bash
# jbangw/ に同梱した JBang ラッパースクリプトの検査。
#
#   bash test/jbangw/run.sh
#
# jbangw/ の 3 ファイルは JBang 本家（jbangdev/jbang）の src/main/scripts/ をそのまま
# 持ち込んだもの。ここでは「本家から取り込んだときの内容から黙って変わっていないこと」を
# 検出する。見るのは JDK の自動取得・アーキテクチャ判定・終了コードの伝播といった、
# 壊れても気づきにくい箇所。
#
# 現在の基準は本家 main の内容そのもの（jbangw/ の 3 ファイルは本家とバイト単位で同一）。
# 以前はここに当リポジトリ独自の修正を固定する項目が並んでいたが、その修正は
# コミット 314c140（本家から取り直し）でいったん巻き戻っている。修正を当て直すときは、
# 対応する項目を修正後の内容に合わせて書き換えること（背景は jbangw/README.md）。
#
# ツール本体の動作は見ない（それは test/regression/ の役目）。ファイルの中身を読むだけなので
# ネットワークも JDK も要らず、OS も問わない。
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)

# --- JBang の状態を汚さないための隔離 ---
# 今の検査はファイルを読むだけでラッパーを起動しないので、実際には効いていない。
# それでも先に置いておくのは、あとでここにラッパーを実行する検査を足したときの事故を防ぐため。
# 隔離しないとラッパーは既定の ~/.jbang と ~/.jbang/cache に書き込み、
#   - 同じ環境で動かす test/regression/run.sh が壊れる（テスト用のダミーを掴む）
#   - .github/workflows/smoke.yml は ~/.jbang を actions/cache で保存するので、壊れた状態が
#     キャッシュに残り後続の run にも波及する。restore-keys を持たないぶん、キャッシュキー
#     （jbangw/jbang と src/CallHierarchyExporter.java のハッシュ）が変わるまで直らない
# という壊れ方をする。使い捨てのディレクトリへ向けておけば、何を足しても外に漏れない。
JBANG_WORK=$(mktemp -d)
trap 'rm -rf "$JBANG_WORK"' EXIT
export JBANG_DIR="$JBANG_WORK/.jbang"
export JBANG_CACHE_DIR="$JBANG_WORK/cache"

fail=0

# $1=jbangw 配下のファイル名  $2=含まれているべき文字列  $3=何を確かめているか
contains() {
    if grep -qF -- "$2" "$ROOT/jbangw/$1"; then
        echo "  OK   $1: $3"
    else
        echo "  NG   $1: $3"
        echo "       見つからない: $2"
        fail=1
    fi
}

# $1=ファイル名  $2=ある行  $3=その次の行にあるべき文字列  $4=何を確かめているか
# grep -F は改行をパターンの区切りとして扱う（複数行を渡すと OR 検索になる）ので、
# 行のつながりを見たいときは 1 行ずつに分けて確かめる
adjacent() {
    if grep -A1 -F -- "$2" "$ROOT/jbangw/$1" | grep -qF -- "$3"; then
        echo "  OK   $1: $4"
    else
        echo "  NG   $1: $4"
        echo "       「$2」の次の行が「$3」ではない"
        fail=1
    fi
}

# $1=ファイル名  $2=文字列  $3=期待する出現回数  $4=何を確かめているか
occurs() {
    local n
    n=$(grep -oF -- "$2" "$ROOT/jbangw/$1" | wc -l | tr -d ' ')
    if [ "$n" = "$3" ]; then
        echo "  OK   $1: $4"
    else
        echo "  NG   $1: $4"
        echo "       「$2」が $3 個あるはずが $n 個"
        fail=1
    fi
}

echo "== jbang.cmd =="
# jbang.ps1 への委譲は 2 箇所（jbang 本体のブートストラップと JDK 取得）。どちらも
# powershell 経由なので、呼び出しの形が変わるとパスの引用や終了コードの扱いが変わる
occurs   jbang.cmd '-Command "%~dp0jbang.ps1' 2                   'jbang.ps1 への委譲は 2 箇所'
contains jbang.cmd 'jdk install %JBANG_DEFAULT_JAVA_VERSION%'     'JDK 取得は jbang.ps1 の jdk install に委譲する'
# 委譲先が失敗したら、そこで止めて終了コードを返す（後段で分かりにくく落ちないように）
occurs   jbang.cmd 'if !ERRORLEVEL! NEQ 0 ( exit /b %ERRORLEVEL% )' 2 '委譲の失敗を呼び出し元へ伝える'
# JBang 管理の JDK（%JBDIR%\currentjdk）があればそれを使い、無いときだけ取得しに行く
contains jbang.cmd 'if exist "%JBDIR%\currentjdk\bin\javac"'     'currentjdk があればそれを使う'
contains jbang.cmd 'set JAVA_EXEC=%JBDIR%\currentjdk\bin\java'   'currentjdk の java を使う'
# 取得した JDK を既定にしておかないと、次回また取得しに行く
contains jbang.cmd 'jdk default "%javaVersion%"'                  '取得した JDK を既定にする'

echo "== jbang.ps1 =="
contains jbang.ps1 'Test-Path "$JBDIR\currentjdk\bin\javac"'     'currentjdk があればそれを使う'
contains jbang.ps1 '$JAVA_EXEC="$JBDIR\currentjdk\bin\java"'     'currentjdk の java を使う'
# JDK 取得のアーキテクチャ（$arch）と、ネイティブバイナリ探索のアーキテクチャ（$jbang_arch）は
# 別々に決まっている。前者は x64 決め打ち、後者だけが実行環境から検出する
contains jbang.ps1 "\$arch='x64'"                                 'JDK 取得のアーキテクチャ'
contains jbang.ps1 'architecture=$arch'                           'foojay へ渡すアーキテクチャは $arch'
contains jbang.ps1 '$jbang_arch = if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture' 'バイナリ探索は実行環境から検出する'
# distro 判定。$javaVersion は文字列のまま比較している
contains jbang.ps1 '($javaVersion -eq 8) -or ($javaVersion -eq 11) -or ($javaVersion -ge 17)' 'distro 判定の条件'
# JDK インストール失敗の 2 経路（展開の失敗・展開後の javac 検査の失敗）
occurs   jbang.ps1 'Error installing JDK"); break' 2              'JDK インストール失敗は 2 経路'
# jbang が 255 を返したときは「実行すべきコマンド」を出力しているので、それを実行する
contains jbang.ps1 'Invoke-Expression "& $output"'                'jbang が出力したコマンドを実行する'

echo "== jbang =="
# JBang 管理の JDK があればそれを使う（bash 版は .exe が付かない）
contains jbang '[ -x "$JBDIR/currentjdk/bin/javac" ]'             'currentjdk があればそれを使う'
# 展開したブートストラップ JDK の検査
contains jbang 'javac -version > /dev/null 2>&1'                  '展開後に javac を検査する'
adjacent jbang 'unpack "$TDIR/bootstrap-jdk.$file_type" "$TDIR/jdks/$javaVersion.tmp"' \
               'if [ $retval -ne 0 ]; then'                       '展開の結果を見てから検査へ進む'

if [ $fail = 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
