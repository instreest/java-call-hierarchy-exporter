@echo off
rem java-call-hierarchy-exporter の起動コマンド（Windows のコマンドプロンプト。Linux / macOS / Git Bash は jche）。
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
rem 画面に出す文（echo）はすべて ASCII にしてある。このファイルは UTF-8 で保存されているが、cmd は日本語 Windows では
rem MS932 として読むので、日本語の echo は化ける（chcp で切り替えると画面が消える。src\CallHierarchyExporter.java の冒頭）。
rem 日本語の案内は、この直後に起動する Java 側（コンソールの文字コードで書く）に任せる。
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
    echo Restarting to apply the new settings...
    goto :main
)
exit /b %ERRORLEVEL%

:first_run_prompt
echo java-call-hierarchy-exporter: first-time setup
echo.
echo Choose where to keep the JDK and JBang this tool uses (several hundred MB):
echo   1^) inside this project    %ROOT%\.jbang
echo      keeps your environment untouched; delete the folder to undo. Dependency jars go there too.
echo   2^) your user profile      %USERPROFILE%\.jbang  (JBang default)
echo      shared with other JBang scripts; pick this if you already use JBang.
echo You can change this later from the app's environment menu or by editing
echo   %SETTINGS%
echo.
set "CHOICE=1"
set /p "CHOICE=Number [1]: "
if "%CHOICE%"=="2" (call :write_settings "" "") else (call :write_settings ".jbang" ".jbang/repository")
echo Saved: %SETTINGS%
echo.
exit /b 0

:write_settings
rem %1=JBANG_DIR  %2=JBANG_REPO（相対はこのフォルダ起点。空欄は JBang の既定）。
rem 書き出す内容も ASCII だけ。Java 側が書き換えるときに日本語のコメントを付け直す
> "%SETTINGS%" (
    echo # Read by jche / jche.cmd at startup. The app's environment menu rewrites this file.
    echo # Keys become environment variables. Relative paths are relative to this folder. Empty = default.
    echo #   JBANG_DIR       where JBang and the JDK are kept ^(default ~/.jbang^)
    echo #   JBANG_REPO      where dependency jars are kept ^(default ~/.m2/repository^)
    echo #   JCHE_JAVA_OPTS  JVM options for the analysis ^(e.g. -Xmx4g^)
    echo #   JCHE_JBANG_OPTS extra options for jbang run ^(e.g. --offline^)
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
