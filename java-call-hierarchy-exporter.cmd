@echo off
rem java-call-hierarchy-exporter の起動コマンド（Windows のコマンドプロンプト。Linux / macOS / Git Bash は java-call-hierarchy-exporter.sh）。
rem
rem   jche                              引数なし … 対話モード（メニューで設定ファイルを選んで解析する）
rem   jche a.properties [b.properties…] 引数あり … 対話なしで解析する（jbang で src\CallHierarchyExporter.java を直接動かすのと同じ）
rem   jche --help
rem
rem 設定ファイルを渡したときは何も尋ねない（Issue #83）。初回で launcher.properties がまだ無ければ、
rem 置き場所の質問は出さずに既定（このプロジェクトの中の .jbang）で作り、その旨を 1 行出すだけにする。
rem
rem どこから実行してもよい（このファイルのあるフォルダを起点にする）。
rem
rem やること:
rem   1. launcher.properties（このフォルダ直下）を読み、JDK / JBang の置き場所（JBANG_DIR 等）や JVM のオプションを
rem      環境変数にする。無ければ、対話できるときだけ置き場所を尋ねて作る（初回だけ。引数があるときは尋ねずに既定で作る）。
rem   2. 手元に無いもの（JBang 本体・JDK・依存 jar）があれば、取りに行ってよいか尋ねる（Issue #86）。
rem      許可されなければ実行しない。何も足りないときは jbang に --offline を渡して、外に出ないようにする。
rem   3. jbangw\jbang.cmd（同梱の JBang ラッパー）で src\Jche.java を動かす。
rem   4. アプリが「再起動して設定を反映」を要求したとき（.cache\launcher.restart ができる）は 1 からやり直す。
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

rem --- 初回: JDK / JBang の置き場所を尋ねる（引数なしで対話できるときだけ。パイプや CI では JBang の既定のまま） ---
if "%~1"=="--help" goto :main
if "%~1"=="-h" goto :main
if exist "%SETTINGS%" goto :main
2>nul >nul timeout /t 0 || goto :main
if not "%~1"=="" (call :first_run_default) else (call :first_run_prompt)

:main
if exist "%RESTART%" del /q "%RESTART%"
setlocal
call :load_settings
call :jbang_dirs
set "OFFLINE_OPT="
call :network_items
if not "%NETCOUNT%"=="0" goto :main_confirm
rem 取りに行くものが無いので外に出る必要も無い。検出が取りこぼしても出られないよう --offline を足す
rem （allow は操作者が先に許可しているので、そのまま外に出られるようにする）
if /i not "%JCHE_NETWORK%"=="allow" set "OFFLINE_OPT=--offline"
goto :main_run
:main_confirm
call :confirm_network
if errorlevel 1 exit /b 1
:main_run
set "JCHE_ROOT=%ROOT%"
call "%ROOT%\jbangw\jbang.cmd" run %OFFLINE_OPT% %JCHE_JBANG_OPTS% %R_OPTS% "%ROOT%\src\Jche.java" %*
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

:first_run_default
rem 引数ありのときは対話なしで実行する。置き場所は尋ねず、既定（このプロジェクトの中）にして知らせるだけ
call :write_settings ".jbang" ".jbang/repository"
echo java-call-hierarchy-exporter: JDK と JBang は %ROOT%\.jbang に置きます（変えるときは %SETTINGS%）。
echo.
exit /b 0

:write_settings
rem %1=JBANG_DIR  %2=JBANG_REPO（相対はこのフォルダ起点。空欄は JBang の既定）。
rem 書き出す内容は Java 側（LauncherSettings.save）が書くものと同じ
> "%SETTINGS%" (
    echo # java-call-hierarchy-exporter.sh / java-call-hierarchy-exporter.cmd が起動時に読む設定（アプリの「環境設定」からも書き換えられる）。
    echo # キーはそのまま環境変数になる。相対パスはこのファイルのあるフォルダが起点。空欄は既定値。
    echo #   JBANG_DIR       JBang 本体・JDK の置き場所（既定 ~/.jbang）
    echo #   JBANG_REPO      依存 jar の置き場所（既定 ~/.m2/repository）
    echo #   JCHE_JAVA_OPTS  解析を動かす JVM のオプション（例: -Xmx4g）
    echo #   JCHE_JBANG_OPTS jbang run に足すオプション（例: --offline）
    echo #   JCHE_NETWORK    足りないもの（JDK・JBang 本体・依存 jar）を取りに行ってよいか。
    echo #                   ask=足りないときだけ尋ねる（既定） allow=尋ねずに許可 deny=禁止
    echo JBANG_DIR=%~1
    echo JBANG_REPO=%~2
    echo JCHE_JAVA_OPTS=
    echo JCHE_JBANG_OPTS=
    echo JCHE_NETWORK=
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
:jbang_dirs
rem jbangw\jbang.cmd と同じ既定で置き場所を決める（load_settings の後に呼ぶ）
if "%JBANG_DIR%"=="" (set "JBDIR=%USERPROFILE%\.jbang") else (set "JBDIR=%JBANG_DIR%")
if "%JBANG_CACHE_DIR%"=="" (set "TDIR=%JBDIR%\cache") else (set "TDIR=%JBANG_CACHE_DIR%")
if "%JBANG_REPO%"=="" (set "MREPO=%USERPROFILE%\.m2\repository") else (set "MREPO=%JBANG_REPO%")
if "%JBANG_DEFAULT_JAVA_VERSION%"=="" (set "WRAPJAVA=17") else (set "WRAPJAVA=%JBANG_DEFAULT_JAVA_VERSION%")
exit /b 0

:network_items
rem 取りに行くことになるものを NETITEM1..NETCOUNT に入れる（何も無ければ NETCOUNT=0）
set "NETCOUNT=0"
call :have_jbang
if errorlevel 1 call :add_item "JBang 本体"
call :have_wrapper_jdk
if errorlevel 1 call :add_item "JDK %WRAPJAVA%（JBang を動かす）"
call :script_java_version
call :have_jdk_version "%SJAVA%"
if errorlevel 1 call :add_item "JDK %SJAVA%（このツールを動かす。src\Jche.java の //JAVA）"
call :missing_deps
exit /b 0

:add_item
set /a NETCOUNT+=1
call set "NETITEM%%NETCOUNT%%=%~1"
exit /b 0

:print_items
setlocal enabledelayedexpansion
for /l %%I in (1,1,%NETCOUNT%) do echo   - !NETITEM%%I!
endlocal
exit /b 0

:have_jbang
rem JBang 本体が手元にあるか。探す順は jbangw\jbang.cmd に揃える
if exist "%ROOT%\jbangw\jbang.jar" exit /b 0
if exist "%ROOT%\jbangw\.jbang\jbang.jar" exit /b 0
if exist "%JBDIR%\bin\jbang.jar" exit /b 0
exit /b 1

:have_wrapper_jdk
rem jbangw\jbang.cmd が jbang.jar を動かすための JDK があるか。探す順も条件も jbangw\jbang.cmd に揃える
if "%JAVA_HOME%"=="" goto :hwj_path
if exist "%JAVA_HOME%\bin\javac.exe" exit /b 0
:hwj_path
where javac > nul 2>&1
if not errorlevel 1 exit /b 0
if exist "%JBDIR%\currentjdk\bin\javac" exit /b 0
if exist "%TDIR%\jdks\%WRAPJAVA%" exit /b 0
exit /b 1

:have_jdk_version
rem %1=メジャー版。そのバージョンの JDK が手元にあれば exit /b 0
if "%~1"=="" exit /b 0
if exist "%TDIR%\jdks\%~1" exit /b 0
set "WANT=%~1"
if "%JAVA_HOME%"=="" goto :hjv_path
if not exist "%JAVA_HOME%\bin\javac.exe" goto :hjv_path
call :javac_major "%JAVA_HOME%\bin\javac.exe"
if "%JMAJOR%"=="%WANT%" exit /b 0
:hjv_path
where javac > nul 2>&1
if errorlevel 1 goto :hjv_current
call :javac_major javac
if "%JMAJOR%"=="%WANT%" exit /b 0
:hjv_current
if not exist "%JBDIR%\currentjdk\bin\javac.exe" exit /b 1
call :javac_major "%JBDIR%\currentjdk\bin\javac.exe"
if "%JMAJOR%"=="%WANT%" exit /b 0
exit /b 1

:javac_major
rem %1=javac。名乗るメジャー版を JMAJOR に入れる（取れなければ空）。javac は "javac 25.0.3" の形で名乗る
set "JMAJOR="
set "JVER="
set "JC=%~1"
for /f "usebackq tokens=2" %%V in (`"%JC%" -version 2^>^&1`) do if not defined JVER set "JVER=%%V"
if not defined JVER exit /b 0
for /f "tokens=1 delims=." %%M in ("%JVER%") do set "JMAJOR=%%M"
exit /b 0

:script_java_version
rem src\Jche.java の //JAVA が求めるメジャー版を SJAVA に入れる（"21+" のような書き方は数字だけにする）
set "SJAVA="
for /f "usebackq tokens=2" %%V in (`findstr /b /c:"//JAVA " "%ROOT%\src\Jche.java"`) do if not defined SJAVA set "SJAVA=%%V"
if defined SJAVA set "SJAVA=%SJAVA:+=%"
exit /b 0

:missing_deps
rem //DEPS の座標のうち、ローカルリポジトリに jar が無いものを足りない側に挙げる
for /f "usebackq tokens=1,*" %%A in (`findstr /b /c:"//DEPS " "%ROOT%\src\Jche.java"`) do call :check_deps_line "%%B"
exit /b 0

:check_deps_line
for %%D in (%~1) do call :check_dep "%%D"
exit /b 0

:check_dep
rem %1=座標。g:a:v 以外の書き方（@pom、バージョン範囲など）は手元にあると言い切れないので足りない側に倒す
set "DEPOK="
for /f "tokens=1,2,3,4 delims=:" %%G in ("%~1") do if not "%%I"=="" if "%%J"=="" call :check_dep_path "%%G" "%%H" "%%I"
if not defined DEPOK call :add_item "依存 jar: %~1"
exit /b 0

:check_dep_path
set "DG=%~1"
set "DG=%DG:.=\%"
if exist "%MREPO%\%DG%\%~2\%~3\%~2-%~3.jar" set "DEPOK=1"
exit /b 0

:confirm_network
rem NETCOUNT が 0 でないときだけ呼ぶ。許可なら exit /b 0、拒否なら exit /b 1
set "NETMODE=%JCHE_NETWORK%"
if not defined NETMODE set "NETMODE=ask"
if /i "%NETMODE%"=="allow" exit /b 0
if /i "%NETMODE%"=="deny" goto :net_deny
if /i "%NETMODE%"=="ask" goto :net_ask
echo java-call-hierarchy-exporter: JCHE_NETWORK の値 '%JCHE_NETWORK%' は ask / allow / deny のいずれかにしてください。 1>&2
exit /b 1

:net_deny
echo java-call-hierarchy-exporter: 手元に無いものがありますが、JCHE_NETWORK=deny なので取りに行きません。 1>&2
call :print_items 1>&2
echo 許可するときは launcher.properties の JCHE_NETWORK を ask か allow にしてください。 1>&2
exit /b 1

:net_ask
rem 端末でないとき（パイプ・CI）は尋ねられないので取りに行かない
2>nul >nul timeout /t 0 || goto :net_notty
echo.
echo java-call-hierarchy-exporter: ネットワークにアクセスします
echo.
echo 次のものが手元に無いので、ダウンロードします。
call :print_items
echo.
echo   置き場所 %JBDIR%（依存 jar は %MREPO%）
echo   取得先   Adoptium（JDK）、GitHub（JBang 本体）、Maven Central（依存 jar）
echo.
set "NETANS=n"
set /p "NETANS=ダウンロードしてよいですか [y/N]: "
if /i "%NETANS%"=="y" exit /b 0
if /i "%NETANS%"=="yes" exit /b 0
echo 中止しました。ネットワークに出ずに動かす方法は README の「閉域ネットワーク」を参照してください。 1>&2
exit /b 1

:net_notty
echo java-call-hierarchy-exporter: 次のものが手元に無いので、ダウンロードが要ります。 1>&2
call :print_items 1>&2
echo 端末ではないため確認を取れません。許可するときは JCHE_NETWORK=allow を設定して実行してください。 1>&2
exit /b 1
