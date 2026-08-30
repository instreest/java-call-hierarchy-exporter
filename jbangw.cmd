@echo off
rem ---------------------------------------------------------------------------
rem jbangw.cmd — JBang wrapper（Windows用。役割は jbangw と同じ）
rem
rem   1. jbang 本体（jbang.jar）が無ければ Maven Central から取得する
rem      （SHA-256 を照合してから使う。取得先は JBANGW_JAR_URL で差し替え可能）
rem   2. JDKが1つも無ければ取得する（jbang本体を動かすにもJDKが要るため）
rem   3. jbang が使うフォルダをプロジェクト内（.jbang\）に固定する
rem   4. jbang の実行規約（終了コード255＝標準出力のコマンドラインを実行）を仲介する
rem
rem さらにWindowsではターミナルをUTF-8（コードページ65001）に切り替えます。
rem ツールのログはUTF-8で出るため（ソースの //JAVA_OPTIONS 参照）、既定の
rem コードページ（日本語環境ではMS932）のままだと文字化けします。
rem 元のコードページは終了時に戻します（利用者の端末設定を持ち帰らせないため。
rem chcp はコンソールの属性なので setlocal では戻らず、明示的に戻す必要がある）。
rem ---------------------------------------------------------------------------
setlocal enabledelayedexpansion

set "APP_HOME=%~dp0"
rem %~dp0 は末尾に \ が付く
set "JBANG_VERSION=0.132.1"
set "JBANG_JAR_SHA256=f977075849cff866f45b27997f5671ab8a336a5dd3cf9e702b3479128338f3a7"

rem JDKが1つも無いときに取得する版。ソースの //JAVA と揃えること。
rem 本家の既定は17だが、//JAVA 25 と揃えないと起動用と実行用で2つ入るため25にする
if not defined JBANG_DEFAULT_JAVA_VERSION (set "JAVA_VERSION=25") else (set "JAVA_VERSION=%JBANG_DEFAULT_JAVA_VERSION%")

if not defined JBANGW_JAR_URL set "JBANGW_JAR_URL=https://repo1.maven.org/maven2/dev/jbang/jbang-cli/%JBANG_VERSION%/jbang-cli-%JBANG_VERSION%-all.jar"
if not defined JBANG_DIR set "JBANG_DIR=%APP_HOME%.jbang"
if not defined JBANG_REPO set "JBANG_REPO=%JBANG_DIR%\repository"
if not defined JBANG_JDK_VENDOR set "JBANG_JDK_VENDOR=temurin"
rem jbang本体もJBANG_DIRの下に置く（外から共有キャッシュを指されたときに二重に置かない）
set "JBANG_JAR=%JBANG_DIR%\jbang-cli-%JBANG_VERSION%-all.jar"
set "JDK_DIR=%JBANG_DIR%\cache\jdks\%JAVA_VERSION%"
set "ERR=0"

rem --- ターミナルをUTF-8にする（元の値を控えて :done で戻す） ---
rem chcp の出力は「Active code page: 932」「現在のコード ページ: 932」等。
rem どの表記でもコロンの後ろが番号なので、そこを取る
set "OLD_CP="
for /f "tokens=2 delims=:" %%A in ('chcp') do for /f "tokens=1" %%B in ("%%A") do set "OLD_CP=%%B"
chcp 65001 >nul

rem --- jbang を起動するJDKを決める（jbangはコンパイルするので javac が要る） ---
rem 入れ子の括弧と call を混ぜるとバッチでは壊れやすいので、ラベルで分岐する
set "JAVA_EXE="
if not defined JAVA_HOME goto :try_path
if exist "%JAVA_HOME%\bin\javac.exe" (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
    goto :have_java
)
echo [jbangw] JAVA_HOME は設定されていますが、JDKではないようです（javac が見つかりません）。 1>&2

:try_path
where javac >nul 2>&1
if not errorlevel 1 (
    set "JAVA_EXE=java.exe"
    goto :have_java
)
if exist "%JBANG_DIR%\currentjdk\bin\javac.exe" (
    set "JAVA_HOME=%JBANG_DIR%\currentjdk"
    set "JAVA_EXE=%JBANG_DIR%\currentjdk\bin\java.exe"
    goto :have_java
)
set "JAVA_HOME=%JDK_DIR%"
set "JAVA_EXE=%JDK_DIR%\bin\java.exe"
if exist "%JDK_DIR%\bin\javac.exe" goto :have_java
call :install_jdk
if errorlevel 1 goto :fail

:have_java

rem --- jbang.jar が無ければ取得し、SHA-256 を照合する ---
if exist "%JBANG_JAR%" goto :have_jbang
echo [jbangw] jbang %JBANG_VERSION% を取得します: %JBANGW_JAR_URL% 1>&2
if not exist "%JBANG_DIR%" mkdir "%JBANG_DIR%"
powershell -NoProfile -ExecutionPolicy Bypass -NonInteractive -Command ^
    "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue';" ^
    "try {" ^
    "  $part = $env:JBANG_JAR + '.part';" ^
    "  Invoke-WebRequest -Uri $env:JBANGW_JAR_URL -OutFile $part;" ^
    "  $h = (Get-FileHash -Algorithm SHA256 $part).Hash.ToLower();" ^
    "  if ($h -ne $env:JBANG_JAR_SHA256) { Remove-Item $part -Force; throw ('SHA-256 mismatch: ' + $h) };" ^
    "  Move-Item -Force $part $env:JBANG_JAR" ^
    "} catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }"
if errorlevel 1 (
    echo [jbangw] jbang の取得に失敗しました。%JBANGW_JAR_URL% を手動で 1>&2
    echo [jbangw] ダウンロードし、%JBANG_JAR% に置いてください。 1>&2
    goto :fail
)

:have_jbang

rem --- 実行規約: 終了コード255のとき、標準出力が「実行すべきコマンドライン」 ---
rem コマンドラインはクラスパスを含み変数の上限（8191文字）を超えうるため、
rem 環境変数で受けずに一時cmdファイル経由で実行する
set "JBANG_OUT=%TEMP%\jbangw-%RANDOM%%RANDOM%.cmd"
"%JAVA_EXE%" -jar "%JBANG_JAR%" %* > "%JBANG_OUT%"
set "ERR=%ERRORLEVEL%"
if "%ERR%"=="255" (
    rem setlocal で整えた JBANG_DIR 等は子プロセスにも引き継がれるので、
    rem endlocal せずにそのまま実行する
    call "%JBANG_OUT%"
    set "ERR=!ERRORLEVEL!"
) else (
    type "%JBANG_OUT%"
)
del "%JBANG_OUT%" >nul 2>&1
goto :done

rem ---------------------------------------------------------------------------
:install_jdk
rem foojay の Disco API は「条件に合うJDKの実体へリダイレクトするURL」を返す。
rem 本家の jbang スクリプトと同じ組み立て方
echo [jbangw] JDK %JAVA_VERSION% が見つからないため取得します。数分かかります。 1>&2
set "JDK_URL=https://api.foojay.io/disco/v3.0/directuris?distro=%JBANG_JDK_VENDOR%&javafx_bundled=false&libc_type=c_std_lib&archive_type=zip&operating_system=windows&package_type=jdk&version=%JAVA_VERSION%&architecture=x64&latest=available"
powershell -NoProfile -ExecutionPolicy Bypass -NonInteractive -Command ^
    "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue';" ^
    "try {" ^
    "  $tmp = Join-Path $env:JBANG_DIR 'cache\bootstrap-jdk.zip';" ^
    "  $null = New-Item -ItemType Directory -Force -Path (Split-Path $tmp);" ^
    "  Invoke-WebRequest -Uri $env:JDK_URL -OutFile $tmp;" ^
    "  $t = $env:JDK_DIR + '.tmp';" ^
    "  Remove-Item -LiteralPath $t -Recurse -Force -ErrorAction Ignore;" ^
    "  Expand-Archive -Path $tmp -DestinationPath $t;" ^
    "  foreach ($d in Get-ChildItem -Directory -Path $t) { Move-Item -Path ($d.FullName + '\*') -Destination $t -Force };" ^
    "  Remove-Item $tmp -Force;" ^
    "  if (-not (Test-Path (Join-Path $t 'bin\javac.exe'))) { throw 'JDKの展開に失敗しました' };" ^
    "  Remove-Item -LiteralPath $env:JDK_DIR -Recurse -Force -ErrorAction Ignore;" ^
    "  Move-Item $t $env:JDK_DIR" ^
    "} catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }"
if errorlevel 1 (
    echo [jbangw] JDKの取得に失敗しました（取得元: api.foojay.io）。 1>&2
    echo [jbangw] 社内プロキシ等で到達できない場合は、JDK %JAVA_VERSION% を手動で 1>&2
    echo [jbangw] インストールして JAVA_HOME を設定するか、展開したものを 1>&2
    echo [jbangw] %JDK_DIR% に置いてください。 1>&2
    exit /b 1
)
exit /b 0

rem ---------------------------------------------------------------------------
:fail
set "ERR=1"

:done
if defined OLD_CP chcp %OLD_CP% >nul
exit /b %ERR%
