@echo off
rem =====================================================================
rem  jbang.cmd - JBang ランチャー（Windows / cmd 単体版）
rem
rem  外部依存は Windows 10 1803 以降に標準搭載されている curl.exe と
rem  tar.exe だけ。PowerShell も .ps1 も使わない。
rem
rem  このファイルの責務は4つだけ:
rem    1. jbang 本体 (jbang.jar) を用意する。無ければ GitHub Releases から取得
rem    2. jbang.jar 自身を動かすための JDK を用意する。無ければ foojay から取得
rem    3. jbang を起動する
rem    4. 終了コード 255 のときは jbang が出力したコマンドを実行し直す
rem
rem  解析対象スクリプトの実行用 JDK（src の //JAVA 25 など）は jbang 自身が
rem  取得するので、このファイルは関与しない。保存先だけ JBANG_CACHE_DIR で
rem  そろえてあるので、同じ .jbang-cache\jdks の下に並ぶ。
rem
rem  画面に出すメッセージを英語にしているのは、日本語 Windows のコンソールが
rem  既定で CP932 のままで、UTF-8 のこのファイルから日本語を echo すると
rem  文字化けするため。rem 行は cmd が読み飛ばすのでコメントは日本語でよい。
rem
rem  遅延展開は使わない。有効にすると引数に含まれる ! が消えるため。
rem  ループはすべて goto で書いてあるのはこの制約による。
rem
rem  環境変数（すべて任意。既定値は下の設定ブロックのとおり）:
rem    JBANG_DIR                  jbang.jar の置き場所
rem    JBANG_CACHE_DIR            ダウンロード物・JDK・依存jar の置き場所
rem    JBANG_DEFAULT_JAVA_VERSION jbang 自身を動かす JDK のバージョン
rem    JBANG_JDK_VENDOR           その JDK のベンダー（foojay の distro 名）
rem    JBANG_DOWNLOAD_VERSION     取得する jbang の版（latest / 0.130.0 / early-access）
rem    JBANG_DOWNLOAD_BASEURL     jbang の配布元（社内ミラー用）
rem    JBANG_DOWNLOAD_URL         jbang.zip の URL を直接指定（上2つより優先）
rem    JBANG_DOWNLOAD_RETRY       ダウンロードの再試行回数
rem    JBANG_DOWNLOAD_RETRY_DELAY 再試行の間隔（秒）
rem    JBANG_JAVA_OPTIONS         jbang.jar を動かす java に渡す追加オプション
rem =====================================================================

setlocal EnableExtensions DisableDelayedExpansion

rem ---------------------------------------------------------------------
rem  1. 設定
rem     既定値はすべてこのスクリプトの隣。プロジェクトフォルダごと消せば
rem     取得したものも一緒に消える。%~dp0 は末尾が \ なので直接つなげる。
rem ---------------------------------------------------------------------
if not defined JBANG_DIR                   set "JBANG_DIR=%~dp0.jbang"
if not defined JBANG_CACHE_DIR             set "JBANG_CACHE_DIR=%~dp0.jbang-cache"
if not defined JBANG_DEFAULT_JAVA_VERSION  set "JBANG_DEFAULT_JAVA_VERSION=17"
if not defined JBANG_JDK_VENDOR            set "JBANG_JDK_VENDOR=temurin"
if not defined JBANG_DOWNLOAD_VERSION      set "JBANG_DOWNLOAD_VERSION=latest"
if not defined JBANG_DOWNLOAD_BASEURL      set "JBANG_DOWNLOAD_BASEURL=https://github.com/jbangdev/jbang/releases"
if not defined JBANG_DOWNLOAD_RETRY        set "JBANG_DOWNLOAD_RETRY=5"
if not defined JBANG_DOWNLOAD_RETRY_DELAY  set "JBANG_DOWNLOAD_RETRY_DELAY=2"

rem 相対パスで渡されても jbang 側と食い違わないよう絶対パスに正規化する。
rem ついでに末尾の \ も落ちる。
for %%I in ("%JBANG_DIR%")       do set "JBANG_DIR=%%~fI"
for %%I in ("%JBANG_CACHE_DIR%") do set "JBANG_CACHE_DIR=%%~fI"

rem これ以降の派生パス。JB_JDK_DIR を JBANG_CACHE_DIR\jdks にしているのは、
rem jbang 自身が管理する JDK の置き場所と同じにするため。
set "JB_JAR=%JBANG_DIR%\bin\jbang.jar"
set "JB_DL_DIR=%JBANG_CACHE_DIR%\downloads"
set "JB_JDK_DIR=%JBANG_CACHE_DIR%\jdks"
set "JB_TMP_DIR=%JBANG_CACHE_DIR%\tmp"

set "JB_ARCH=x64"
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "JB_ARCH=aarch64"

rem ---------------------------------------------------------------------
rem  2. 前提コマンドの確認
rem ---------------------------------------------------------------------
where curl.exe >nul 2>&1
if errorlevel 1 (
  echo [jbang] ERROR: curl.exe not found. Windows 10 1803 or later is required. 1>&2
  exit /b 1
)
where tar.exe >nul 2>&1
if errorlevel 1 (
  echo [jbang] ERROR: tar.exe not found. Windows 10 1803 or later is required. 1>&2
  exit /b 1
)

rem ---------------------------------------------------------------------
rem  3. jbang 本体を用意する
rem ---------------------------------------------------------------------
rem jbang が自分を更新すると jbang.jar.new が置かれる。ここで確定させる。
if exist "%JB_JAR%.new" move /y "%JB_JAR%.new" "%JB_JAR%" >nul

if not exist "%JB_JAR%" (
  call :install_jbang
  if errorlevel 1 exit /b 1
)

rem ---------------------------------------------------------------------
rem  4. jbang.jar を動かす JDK を決める
rem ---------------------------------------------------------------------
call :resolve_java
if errorlevel 1 exit /b 1

rem ---------------------------------------------------------------------
rem  5. jbang を起動する
rem ---------------------------------------------------------------------
set "JBANG_RUNTIME_SHELL=cmd"
set "JBANG_LAUNCH_CMD=%~f0"
rem stdin が端末かどうかを jbang に伝える。timeout は端末が無いと失敗するので
rem それを判定に使う（jbang 公式ラッパーと同じ手口）。
2>nul >nul timeout /t 0 && (set "JBANG_STDIN_NOTTY=false") || (set "JBANG_STDIN_NOTTY=true")

rem jbang の標準出力は一時ファイルへ逃がす。終了コード 255 のときだけ中身が
rem 「実行すべきコマンド」になるため、そのまま画面に流すわけにいかない。
rem 標準エラーは素通しなので、ダウンロードの進捗などはその場で見える。
if not exist "%JB_TMP_DIR%" mkdir "%JB_TMP_DIR%"
:pick_tmpfile
set "JB_OUT=%JB_TMP_DIR%\%RANDOM%%RANDOM%.out"
if exist "%JB_OUT%" goto :pick_tmpfile

"%JB_JAVA%" %JBANG_JAVA_OPTIONS% -jar "%JB_JAR%" %* > "%JB_OUT%"
set "JB_EXIT=%ERRORLEVEL%"

rem ---------------------------------------------------------------------
rem  6. 終了コードの処理
rem     255 = 「実行すべきコマンドを標準出力の1行目に書いた」という jbang の合図。
rem     jbang run の実体はこれで、ここで java を起動し直すのがラッパーの本題。
rem ---------------------------------------------------------------------
if not "%JB_EXIT%"=="255" goto :passthrough

set "JB_CMD="
for /f "usebackq delims=" %%L in ("%JB_OUT%") do if not defined JB_CMD set "JB_CMD=%%L"
del /f /q "%JB_OUT%" >nul 2>&1
if not defined JB_CMD (
  echo [jbang] ERROR: jbang returned 255 but produced no command line. 1>&2
  exit /b 1
)
%JB_CMD%
exit /b %ERRORLEVEL%

:passthrough
if exist "%JB_OUT%" (
  type "%JB_OUT%"
  del /f /q "%JB_OUT%" >nul 2>&1
)
exit /b %JB_EXIT%


rem =====================================================================
rem  以降サブルーチン
rem =====================================================================

rem ---------------------------------------------------------------------
rem  install_jbang : jbang.zip を取得して jbang.jar だけ取り出す
rem    公式の zip には bin\jbang / jbang.cmd / jbang.ps1 も入っているが、
rem    このラッパーが自前でやるので jar 以外は捨てる。
rem ---------------------------------------------------------------------
:install_jbang
call :build_jbang_url
echo [jbang] Downloading jbang %JBANG_DOWNLOAD_VERSION% from %JB_URL% 1>&2
if not exist "%JB_DL_DIR%" mkdir "%JB_DL_DIR%"
call :download "%JB_URL%" "%JB_DL_DIR%\jbang.zip"
if errorlevel 1 (
  echo [jbang] ERROR: failed to download jbang from %JB_URL% 1>&2
  exit /b 1
)
echo [jbang] Extracting jbang... 1>&2
if exist "%JB_DL_DIR%\jbang" rd /s /q "%JB_DL_DIR%\jbang"
mkdir "%JB_DL_DIR%\jbang"
rem zip の中身は jbang\bin\... と1階層かぶっているので剥がす
tar.exe -xf "%JB_DL_DIR%\jbang.zip" -C "%JB_DL_DIR%\jbang" --strip-components=1
if errorlevel 1 (
  echo [jbang] ERROR: failed to extract "%JB_DL_DIR%\jbang.zip" 1>&2
  exit /b 1
)
if not exist "%JB_DL_DIR%\jbang\bin\jbang.jar" (
  echo [jbang] ERROR: bin\jbang.jar was not found in the downloaded archive. 1>&2
  exit /b 1
)
if not exist "%JBANG_DIR%\bin" mkdir "%JBANG_DIR%\bin"
copy /y "%JB_DL_DIR%\jbang\bin\jbang.jar" "%JB_JAR%" >nul
if errorlevel 1 (
  echo [jbang] ERROR: failed to install jbang.jar to "%JB_JAR%" 1>&2
  exit /b 1
)
rd /s /q "%JB_DL_DIR%\jbang"
exit /b 0

rem ---------------------------------------------------------------------
rem  build_jbang_url : 取得先 URL を JB_URL に組み立てる
rem ---------------------------------------------------------------------
:build_jbang_url
if defined JBANG_DOWNLOAD_URL (
  set "JB_URL=%JBANG_DOWNLOAD_URL%"
  exit /b 0
)
if /i "%JBANG_DOWNLOAD_VERSION%"=="latest" (
  set "JB_URL=%JBANG_DOWNLOAD_BASEURL%/latest/download/jbang.zip"
  exit /b 0
)
rem 数字とドットだけの版には v を付ける（0.130.0 -> v0.130.0）。
rem early-access のような名前付きタグはそのまま使う。
echo %JBANG_DOWNLOAD_VERSION%| findstr /r /c:"^[0-9][0-9.]*$" >nul
if errorlevel 1 (
  set "JB_URL=%JBANG_DOWNLOAD_BASEURL%/download/%JBANG_DOWNLOAD_VERSION%/jbang.zip"
) else (
  set "JB_URL=%JBANG_DOWNLOAD_BASEURL%/download/v%JBANG_DOWNLOAD_VERSION%/jbang.zip"
)
exit /b 0

rem ---------------------------------------------------------------------
rem  download <url> <outfile>
rem    curl の再試行機能をそのまま使う。プロキシは HTTPS_PROXY 等の
rem    環境変数を curl が自動で読むので、ここでは何もしない。
rem ---------------------------------------------------------------------
:download
curl.exe -fL --progress-bar --retry %JBANG_DOWNLOAD_RETRY% --retry-delay %JBANG_DOWNLOAD_RETRY_DELAY% -o %2 %1
exit /b %ERRORLEVEL%

rem ---------------------------------------------------------------------
rem  resolve_java : jbang.jar を動かす java.exe を JB_JAVA に入れる
rem    探索順:
rem      1. JAVA_HOME
rem      2. PATH 上の java/javac
rem      3. %JBANG_DIR%\currentjdk       … jbang が設定した既定 JDK
rem      4. %JBANG_CACHE_DIR%\jdks\<版>  … このスクリプトが入れた JDK
rem      5. どれも無ければ 4 の場所へ取得する
rem    javac の有無で判定するのは、jbang がソースのコンパイルに JDK を必要と
rem    するため（JRE では動かない）。
rem ---------------------------------------------------------------------
:resolve_java
set "JB_JAVA="
if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\javac.exe" (
    set "JB_JAVA=%JAVA_HOME%\bin\java.exe"
  ) else (
    echo [jbang] WARNING: JAVA_HOME is set but is not a JDK: "%JAVA_HOME%" 1>&2
  )
)
if defined JB_JAVA exit /b 0

where javac.exe >nul 2>&1
if errorlevel 1 goto :resolve_java_currentjdk
where java.exe >nul 2>&1
if errorlevel 1 goto :resolve_java_currentjdk
rem PATH の JDK を使う。壊れた JAVA_HOME を jbang に引き継がせない。
set "JAVA_HOME="
set "JB_JAVA=java.exe"
exit /b 0

:resolve_java_currentjdk
if exist "%JBANG_DIR%\currentjdk\bin\javac.exe" (
  set "JAVA_HOME=%JBANG_DIR%\currentjdk"
  set "JB_JAVA=%JBANG_DIR%\currentjdk\bin\java.exe"
  exit /b 0
)

set "JB_BOOT_JDK=%JB_JDK_DIR%\%JBANG_DEFAULT_JAVA_VERSION%"
if not exist "%JB_BOOT_JDK%\bin\javac.exe" (
  call :install_jdk
  if errorlevel 1 exit /b 1
)
set "JAVA_HOME=%JB_BOOT_JDK%"
set "JB_JAVA=%JB_BOOT_JDK%\bin\java.exe"
exit /b 0

rem ---------------------------------------------------------------------
rem  install_jdk : jbang.jar 起動用の JDK を foojay から取得する
rem    ここで入れるのは「jbang 自身を動かすための JDK」だけ。
rem    //JAVA で指定された実行用 JDK は jbang が同じ jdks フォルダに取りに行く。
rem    呼び出し前に JB_BOOT_JDK が設定されていること。
rem ---------------------------------------------------------------------
:install_jdk
set "JB_JDK_URL=https://api.foojay.io/disco/v3.0/directuris?distro=%JBANG_JDK_VENDOR%&javafx_bundled=false&libc_type=c_std_lib&archive_type=zip&operating_system=windows&package_type=jdk&version=%JBANG_DEFAULT_JAVA_VERSION%&architecture=%JB_ARCH%&latest=available"
echo [jbang] Downloading JDK %JBANG_DEFAULT_JAVA_VERSION% [%JBANG_JDK_VENDOR%/%JB_ARCH%]. This can take several minutes... 1>&2
if not exist "%JB_DL_DIR%" mkdir "%JB_DL_DIR%"
call :download "%JB_JDK_URL%" "%JB_DL_DIR%\bootstrap-jdk.zip"
if errorlevel 1 (
  echo [jbang] ERROR: failed to download JDK %JBANG_DEFAULT_JAVA_VERSION%. 1>&2
  exit /b 1
)
echo [jbang] Extracting JDK... 1>&2
set "JB_JDK_TMP=%JB_BOOT_JDK%.tmp"
if exist "%JB_JDK_TMP%" rd /s /q "%JB_JDK_TMP%"
mkdir "%JB_JDK_TMP%"
rem JDK の zip は jdk-17.0.x+y\... と1階層かぶっているので剥がす
tar.exe -xf "%JB_DL_DIR%\bootstrap-jdk.zip" -C "%JB_JDK_TMP%" --strip-components=1
if errorlevel 1 goto :install_jdk_failed
if not exist "%JB_JDK_TMP%\bin\javac.exe" goto :install_jdk_failed
"%JB_JDK_TMP%\bin\javac.exe" -version >nul 2>&1
if errorlevel 1 goto :install_jdk_failed
if exist "%JB_BOOT_JDK%" rd /s /q "%JB_BOOT_JDK%"
rem 検証が通ってから正式な名前にする。途中で失敗しても .tmp が残るだけで、
rem 次回は最初からやり直せる。
move /y "%JB_JDK_TMP%" "%JB_BOOT_JDK%" >nul
if errorlevel 1 goto :install_jdk_failed
del /f /q "%JB_DL_DIR%\bootstrap-jdk.zip" >nul 2>&1
rem 入れた JDK を jbang の既定として登録する（%JBANG_DIR%\currentjdk が作られる）。
rem 失敗しても致命的ではないので結果は見ない。
"%JB_BOOT_JDK%\bin\java.exe" -jar "%JB_JAR%" jdk default %JBANG_DEFAULT_JAVA_VERSION% >nul 2>&1
exit /b 0

:install_jdk_failed
echo [jbang] ERROR: failed to install JDK %JBANG_DEFAULT_JAVA_VERSION%. 1>&2
echo [jbang]        Leftovers: "%JB_JDK_TMP%" 1>&2
exit /b 1
