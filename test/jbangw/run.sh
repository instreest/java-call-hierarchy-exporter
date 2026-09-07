#!/usr/bin/env bash
# jbangw/ に同梱した JBang ラッパースクリプトの検査。
#
#   bash test/jbangw/run.sh
#
# jbangw/ の 3 ファイルは JBang 本家（jbangdev/jbang）の src/main/scripts/ をそのまま
# 持ち込んだもので、本家に残っていた JDK 自動取得まわりの不具合を修正して取り込んでいる。
# 本家から取り直して差し替えると、修正が黙って巻き戻る。ここではその巻き戻りだけを検出する。
#
# ツール本体の動作は見ない（それは test/regression/ の役目）。ファイルの中身を読むだけなので
# ネットワークも JDK も要らず、OS も問わない。
#
# 修正の背景は各項目のコメントを参照。上流にこの修正が取り込まれた版へ更新する場合など、
# 内容が意図して変わったときは、対応する項目を実際のスクリプトに合わせて更新すること。
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

# $1=ファイル名  $2=含まれていてはいけない文字列  $3=何を確かめているか
absent() {
    if grep -qF -- "$2" "$ROOT/jbangw/$1"; then
        echo "  NG   $1: $3"
        echo "       修正前の内容が残っている: $2"
        fail=1
    else
        echo "  OK   $1: $3"
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
# 元は "jbang.ps1 jdk install %JBANG_DEFAULT_JAVA_VERSION%" を投げていた。この変数は未設定が
# 既定（既定値 17 は javaVersion 側）なので、バージョン引数なしの "jbang jdk install" になり
# その呼び出しは必ず失敗していた。ただし全体は壊れていない。jbang.ps1 は渡されたコマンドを
# 実行する前に自前で JDK を入れるので、必要な副作用は失敗前に済んでいたため。
# 今は意図どおり "version" を投げて副作用だけを得るので、この行自体が無い
absent   jbang.cmd 'jdk install %JBANG_DEFAULT_JAVA_VERSION%'     '未設定になりうる変数を jdk install に渡さない'
absent   jbang.cmd 'jbang.ps1" jdk install'                       'JDK 取得を no-op の jdk install に頼らない'
# powershell -Command は文字列を PowerShell のコードとして解釈するためパスの引用が要るうえ、
# スクリプトの終了コードを 0/1 に潰してしまう。-File なら両方とも起きない
absent   jbang.cmd '-Command "%~dp0jbang.ps1'                     'jbang.ps1 の委譲に -Command を使わない'
absent   jbang.cmd "-Command \"& '%~dp0jbang.ps1'"                'jbang.ps1 の委譲に -Command を使わない（引用形も）'
occurs   jbang.cmd '-File "%~dp0jbang.ps1"' 2                     'jbang.ps1 への委譲 2 箇所とも -File を使う'
# 委譲したのに JDK が無ければ、後段で分かりにくく落ちる前にここで止める
contains jbang.cmd 'if not exist "!JAVA_EXEC!"'                   'JDK が入ったことを委譲後に確認する'
# 括弧ブロックの中の %ERRORLEVEL% はブロック解析時に展開される。遅延展開の !ERRORLEVEL! でないと
# ダウンロードや JDK インストールの失敗が終了コード 0 として扱われる
occurs   jbang.cmd 'if !ERRORLEVEL! NEQ 0 ( exit /b !ERRORLEVEL! )' 2 'ブロック内のエラー伝播に遅延展開を使う'
absent   jbang.cmd 'exit /b %ERRORLEVEL% )'                       'ブロック内で解析時展開の %ERRORLEVEL% を使わない'
occurs   jbang.cmd 'exit /b !ERRORLEVEL!' 3                       '委譲先 jbang.cmd の終了コードも返す'
# Windows の実体は javac.exe。"javac" では一致せず、導入済みの JBang 管理 JDK が無視されて
# 毎回ブートストラップ JDK を取り直してしまう
contains jbang.cmd 'if exist "%JBDIR%\currentjdk\bin\javac.exe"'  'currentjdk の判定に javac.exe を使う'
contains jbang.cmd 'set JAVA_EXEC=%JBDIR%\currentjdk\bin\java.exe' 'currentjdk の java.exe を使う'

echo "== jbang.ps1 =="
contains jbang.ps1 'Test-Path "$JBDIR\currentjdk\bin\javac.exe"'  'currentjdk の判定に javac.exe を使う'
contains jbang.ps1 '$JAVA_EXEC="$JBDIR\currentjdk\bin\java.exe"'  'currentjdk の java.exe を使う'
# ネイティブバイナリ探索用には aarch64 を検出しているのに、foojay へのリクエストだけ x64 を
# 決め打ちしていた。Windows on ARM で x64 の JDK を取ってしまう
absent   jbang.ps1 "\$arch='x64'"                                 'JDK 取得のアーキテクチャを決め打ちしない'
contains jbang.ps1 '$arch = if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture' 'アーキテクチャを実行環境から検出する'
# 次の 1 件は修正前後で共通。URL 側がアーキテクチャを直書きに戻されていないことを見る
contains jbang.ps1 'architecture=$arch'                           'foojay へ検出したアーキテクチャを渡す'
contains jbang.ps1 '$jbang_arch = $arch'                          'バイナリ探索と JDK 取得で同じ値を使う'
# $javaVersion は文字列。-ge を直接使うと辞書順比較になり '9' -ge '17' が真、'100' -ge '17' が偽になる
contains jbang.ps1 '$javaVersionNum=[int]$javaVersion'            'distro 判定の前に数値へ変換する'
contains jbang.ps1 '($javaVersionNum -eq 8) -or ($javaVersionNum -eq 11) -or ($javaVersionNum -ge 17)' 'distro 判定を数値で比較する'
# 起動しない javac も成功扱いになると、壊れた JDK が本名にリネームされて以後キャッシュされ続ける
contains jbang.ps1 '$ok=($LASTEXITCODE -eq 0)'                    '展開後の javac 検査で終了コードを見る'
# break はプロセスの終了コードを 0 のままにするので、jbang.cmd 側から失敗を検知できない
occurs   jbang.ps1 'Error installing JDK"); break' 0              'JDK インストール失敗を break で抜けない'
occurs   jbang.ps1 'Error installing JDK"); exit 1' 2             'JDK インストール失敗は 2 経路とも exit 1'
# Invoke-JBang が exit していないと、スクリプトは正常終了して呼び出し元は常に 0 を見る。
# jbang 経由で動かしたものの終了コードが失われる（bash 版は exit \$err している）
contains jbang.ps1 'exit $err'                                    'jbang の終了コードを呼び出し元へ返す'

echo "== jbang =="
# 検査が反転していた（unpack が失敗したときだけ実行）うえ、展開した JDK ではなく PATH 上の
# javac を実行していた。展開は成功したが JDK が壊れている場合が検証されないまま本名にリネーム
# され、[ ! -d "$TDIR/jdks/$javaVersion" ] のガードにより永久にキャッシュされる
contains jbang '"$TDIR/jdks/$javaVersion.tmp/bin/javac" -version' '展開したばかりの javac を検査する'
absent   jbang '          javac -version > /dev/null 2>&1'        'PATH 上の javac を検査に使わない'
adjacent jbang 'unpack "$TDIR/bootstrap-jdk.$file_type" "$TDIR/jdks/$javaVersion.tmp"' \
               'if [ $retval -eq 0 ]; then'                       'unpack が成功したときに検査する'

if [ $fail = 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
