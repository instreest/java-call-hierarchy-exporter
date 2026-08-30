@echo off
rem ---------------------------------------------------------------------------
rem jbangw.cmd — JBang wrapper（Windows用。役割は jbangw と同じ）
rem
rem   1. jbang 本体（jbang.jar）が無ければ Maven Central から取得する
rem      （SHA-256 を照合してから使う。取得先は JBANGW_JAR_URL で差し替え可能）
rem   2. jbang が使うフォルダをプロジェクト内（.jbang\）に固定する
rem   3. jbang の実行規約（終了コード255＝標準出力のコマンドラインを実行）を仲介する
rem
rem さらにWindowsではターミナルをUTF-8（コードページ65001）に切り替える。
rem ツールのログはUTF-8で出るため（ソースの //JAVA_OPTIONS 参照）、既定の
rem コードページ（日本語環境ではMS932）のままだと文字化けする。
rem ---------------------------------------------------------------------------
setlocal enabledelayedexpansion

rem ターミナルをUTF-8にする。setlocal の外に影響させないため、この端末の
rem コードページはスクリプト終了時に元へ戻らない点に注意（意図した仕様。
rem 戻すと、リダイレクトせずに続けて表示したCSV等が化けるため）
chcp 65001 >nul

set "APP_HOME=%~dp0"
rem %~dp0 は末尾に \ が付く（gradlew.bat時代に %APP_HOME%.gradle-home で踏んだ罠と逆）
set "JBANG_VERSION=0.132.1"
set "JBANG_JAR_SHA256=f977075849cff866f45b27997f5671ab8a336a5dd3cf9e702b3479128338f3a7"
if not defined JBANGW_JAR_URL set "JBANGW_JAR_URL=https://repo1.maven.org/maven2/dev/jbang/jbang-cli/%JBANG_VERSION%/jbang-cli-%JBANG_VERSION%-all.jar"
if not defined JBANG_DIR set "JBANG_DIR=%APP_HOME%.jbang"
if not defined JBANG_REPO set "JBANG_REPO=%JBANG_DIR%\repository"
rem jbang本体もJBANG_DIRの下に置く（外から共有キャッシュを指されたときに二重に置かない）
set "JBANG_JAR=%JBANG_DIR%\jbang-cli-%JBANG_VERSION%-all.jar"

rem --- jbang を起動する Java を探す（8以上なら何でもよい） ---
set "JAVA_EXE=java.exe"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
"%JAVA_EXE%" -version >nul 2>&1
if errorlevel 1 (
    echo [jbangw] Java が見つかりません（JAVA_HOME も PATH も未設定）。 1>&2
    echo [jbangw] jbang の起動には Java 8 以上が1つ必要です。README の 1>&2
    echo [jbangw] 「Javaが入っていない環境」を参照してください。 1>&2
    exit /b 1
)

rem --- jbang.jar が無ければ取得し、SHA-256 を照合する ---
if not exist "%JBANG_JAR%" (
    echo [jbangw] jbang %JBANG_VERSION% を取得します: %JBANGW_JAR_URL% 1>&2
    if not exist "%JBANG_DIR%" mkdir "%JBANG_DIR%"
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
        "$ProgressPreference='SilentlyContinue';" ^
        "Invoke-WebRequest -Uri $env:JBANGW_JAR_URL -OutFile ($env:JBANG_JAR + '.part');" ^
        "$h=(Get-FileHash -Algorithm SHA256 ($env:JBANG_JAR + '.part')).Hash.ToLower();" ^
        "if ($h -ne $env:JBANG_JAR_SHA256) { Remove-Item ($env:JBANG_JAR + '.part'); Write-Error ('SHA-256 mismatch: ' + $h); exit 1 };" ^
        "Move-Item ($env:JBANG_JAR + '.part') $env:JBANG_JAR"
    if errorlevel 1 (
        echo [jbangw] jbang の取得に失敗しました。%JBANGW_JAR_URL% を手動で 1>&2
        echo [jbangw] ダウンロードし、%JBANG_JAR% に置いてください。 1>&2
        exit /b 1
    )
)

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
exit /b %ERR%
