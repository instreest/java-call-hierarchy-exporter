@echo off
rem java-call-hierarchy-exporter の起動コマンド（Windows のコマンドプロンプト。Linux / macOS / Git Bash は jche.sh）。
rem
rem   jche                              対話モード（メニューで設定ファイルを選んで解析する）
rem   jche a.properties [b.properties…] 対話なしで解析する（jbang で src\CallHierarchyExporter.java を直接動かすのと同じ）
rem   jche --help
rem
rem どこから実行してもよい（このファイルのあるフォルダを起点にする）。
rem
rem やること:
rem   1. launcher.properties（このフォルダ直下）を読み、JDK / JBang の置き場所（JBANG_DIR 等）や JVM のオプションを
rem      環境変数にする。無ければ、対話できるときだけ置き場所を尋ねて作る（初回だけ）。
rem   2. jbangw\jbang.cmd（同梱の JBang ラッパー）で src\Jche.java を動かす。JDK と依存 jar は初回に自動で取得される。
rem   3. アプリが「再起動して設定を反映」を要求したとき（.cache\launcher.restart ができる）は 1 からやり直す。
rem      置き場所や JVM オプションは Java が起動する前に決まるので、Java 側からは変えられない。
rem
rem 設定の読み込みと jbang の実行は setlocal / endlocal で囲む。再起動のたびに前回の環境変数が残らないようにするため。
rem 括弧ブロックの中では %VAR% がブロックの解析時に展開されるので、値を使う箇所は call やサブルーチンにしてある。
rem
rem このファイルの文字コードは MS932（Shift_JIS）、改行は CRLF。他のファイルは UTF-8 だが、cmd はバッチファイルを
rem 画面のコードページ（日本語 Windows では MS932）として読むので、日本語の echo を化けさせないためにこのファイルだけ
rem MS932 にしてある（chcp で切り替えると画面が消えるので使わない。src\CallHierarchyExporter.java の冒頭）。
rem 編集するときは MS932 のまま保存すること。書き出す launcher.properties も MS932 になり、Java 側（native.encoding）と揃う。
setlocal
set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"
set "SETTINGS=%ROOT%\launcher.properties"
set "RESTART=%ROOT%\.cache\launcher.restart"

rem --- 初回: JDK / JBang の置き場所を尋ねる（対話できるときだけ。パイプや CI では JBang の既定のまま） ---
if "%~1"=="--help" goto :main
if "%~1"=="-h" goto :main
if exist "%SETTINGS%" goto :main
2>nul >nul timeout /t 0 || goto :main
call :first_run_prompt

:main
if exist "%RESTART%" del /q "%RESTART%"
setlocal
call :load_settings
set "JCHE_ROOT=%ROOT%"
call "%ROOT%\jbangw\jbang.cmd" run %JCHE_JBANG_OPTS% %R_OPTS% "%ROOT%\src\Jche.java" %*
endlocal
if exist "%RESTART%" (
    echo 設定を反映するため再起動します...
    goto :main
)
exit /b %ERRORLEVEL%

:first_run_prompt
echo java-call-hierarchy-exporter: 初回の設定
echo.
echo このツールが使う JDK と JBang（合わせて数百 MB）の置き場所を選んでください。
echo   1^) このプロジェクトの中   %ROOT%\.jbang
echo      他の環境を汚さず、フォルダごと消せば元に戻る。依存 jar も同じ場所に置く
echo   2^) ユーザーのホーム       %USERPROFILE%\.jbang（JBang の既定）
echo      他の JBang スクリプトと共有する。既に JBang を使っているならこちら
echo 後から変えるときは、アプリの「環境設定」か、次のファイルを編集する。
echo   %SETTINGS%
echo.
set "CHOICE=1"
set /p "CHOICE=番号 [1]: "
if "%CHOICE%"=="2" (call :write_settings "" "") else (call :write_settings ".jbang" ".jbang/repository")
echo %SETTINGS% に保存しました。
echo.
exit /b 0

:write_settings
rem %1=JBANG_DIR  %2=JBANG_REPO（相対はこのフォルダ起点。空欄は JBang の既定）。
rem 書き出す内容は Java 側（LauncherSettings.save）が書くものと同じ
> "%SETTINGS%" (
    echo # jche.sh / jche.cmd が起動時に読む設定（アプリの「環境設定」からも書き換えられる）。
    echo # キーはそのまま環境変数になる。相対パスはこのファイルのあるフォルダが起点。空欄は既定値。
    echo #   JBANG_DIR       JBang 本体・JDK の置き場所（既定 ~/.jbang）
    echo #   JBANG_REPO      依存 jar の置き場所（既定 ~/.m2/repository）
    echo #   JCHE_JAVA_OPTS  解析を動かす JVM のオプション（例: -Xmx4g）
    echo #   JCHE_JBANG_OPTS jbang run に足すオプション（例: --offline）
    echo JBANG_DIR=%~1
    echo JBANG_REPO=%~2
    echo JCHE_JAVA_OPTS=
    echo JCHE_JBANG_OPTS=
)
exit /b 0

:load_settings
rem launcher.properties の KEY=VALUE 行をそのまま環境変数にする（# で始まる行は読み飛ばす。値が空なら未設定にする）
set "R_OPTS="
if not exist "%SETTINGS%" exit /b 0
for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%SETTINGS%") do call :set_one "%%A" "%%B"
rem 相対パスはこのフォルダ起点の絶対パスにする（jbang は作業ディレクトリに依らずここを見る）
if defined JBANG_DIR call :absolutize JBANG_DIR
if defined JBANG_CACHE_DIR call :absolutize JBANG_CACHE_DIR
if defined JBANG_REPO call :absolutize JBANG_REPO
rem JVM のオプションは jbang run の -R で 1 つずつ渡す（-Xmx4g -Xss2m → -R-Xmx4g -R-Xss2m）
if defined JCHE_JAVA_OPTS for %%O in (%JCHE_JAVA_OPTS%) do call set "R_OPTS=%%R_OPTS%% -R%%O"
exit /b 0

:set_one
rem %1=キー  %2=値（前後の空白は取り除く。キーは英大文字と _ で始まるものだけ）
set "K=%~1"
set "V=%~2"
for /f "tokens=* delims= " %%K in ("%K%") do set "K=%%K"
if "%K%"=="" exit /b 0
echo %K%| findstr /r /c:"^[A-Z_][A-Z0-9_]*$" > nul || exit /b 0
if not "%V%"=="" for /f "tokens=* delims= " %%V in ("%V%") do set "V=%%V"
if "%V%"=="" (set "%K%=") else (set "%K%=%V%")
exit /b 0

:absolutize
rem %1=環境変数名。ドライブ文字（C:）や \\ で始まらなければ、このフォルダ起点の絶対パスにし、/ を \ にする
call set "V=%%%~1%%"
set "V=%V:/=\%"
if "%V:~1,1%"==":" goto :absolutize_done
if "%V:~0,2%"=="\\" goto :absolutize_done
set "V=%ROOT%\%V%"
:absolutize_done
set "%~1=%V%"
exit /b 0
