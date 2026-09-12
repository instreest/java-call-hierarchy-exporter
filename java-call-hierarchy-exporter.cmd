@echo off
rem java-call-hierarchy-exporter の起動コマンド（Windows のコマンドプロンプト。Linux / macOS / Git Bash は java-call-hierarchy-exporter.sh）。
rem
rem   jche                              引数なし … 対話モード（メニューで設定ファイルを選んで解析する）
rem   jche a.properties [b.properties…] 引数あり … 対話なしで解析する（jbang で src\CallHierarchyExporter.java を直接動かすのと同じ）
rem   jche --help
rem
rem 設定ファイルを渡したときは何も尋ねない（Issue #83）。初回で launcher.properties がまだ無ければ、
rem 置き場所の質問は出さずに既定（このプロジェクトの中の .jbang）で作り、その旨を 1 行出すだけにする。
rem ただし、ネットワークからの取得（JBang 本体・JDK・依存 jar）が必要なときだけは、引数の有無によらず
rem 取得してよいかを確認する（Issue #86）。n なら何も取得せずに終了コード 3 で終わる。端末が無くて確認できない
rem とき（パイプ・CI・タスクスケジューラ）も取得せず 3 で終わるので、そこでは launcher.properties か環境変数で
rem JCHE_ALLOW_DOWNLOAD=yes（尋ねずに取得する）/ no（取得しない）をあらかじめ決めておく。
rem
rem どこから実行してもよい（このファイルのあるフォルダを起点にする）。
rem
rem やること:
rem   1. launcher.properties（このフォルダ直下）を読み、JDK / JBang の置き場所（JBANG_DIR 等）や JVM のオプションを
rem      環境変数にする。無ければ、対話できるときだけ置き場所を尋ねて作る（初回だけ。引数があるときは尋ねずに既定で作る）。
rem   2. jbangw\jbang.cmd（同梱の JBang ラッパー）で src\Jche.java を動かす。
rem      ネットワークに出るのは次の 3 段階で、いずれも操作者の確認（または JCHE_ALLOW_DOWNLOAD）なしには行わない:
rem        a. ラッパーが JBang 本体（github.com）と、JBang を動かす JDK（api.foojay.io）を取得する
rem           … Java が動く前なので、置き場所のファイルの有無を見て、無ければ走らせる前に確認する
rem        b. jbang がツールを動かす JDK 25（api.foojay.io）を取得する
rem        c. jbang が依存 jar（JDT ほか。Maven Central）を推移的な依存まで解決して取得する
rem           … b と c は、まず --offline（取得済みのものだけで動かし、足りなければ失敗する）で起動し、
rem             アプリが始まる前に失敗したときだけ確認して、--offline なしで起動し直す。
rem             アプリが始まったかは、アプリが置く目印ファイル（.cache\launcher.started）で見分ける。
rem             jbangw\jbang.cmd は jbang の終了コードを返さない（本家の既知の問題。jbangw\README.md）ので、
rem             終了コードではなくこの目印だけで判断する。
rem      jbang 自身の更新確認（新しい版があるかを問い合わせる）も JBANG_NO_VERSION_CHECK で止める。
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
set "STARTED=%ROOT%\.cache\launcher.started"

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
set "JCHE_ROOT=%ROOT%"
rem jbang 自身の更新確認（起動のたびに新しい版があるかを問い合わせる）はネットワークに出るので止める
set "JBANG_NO_VERSION_CHECK=true"
rem ラッパーが JBang を動かすために取得する JDK の版。既定（17）のままだと、ツールを動かす JDK 25 と合わせて
rem 2 つの JDK を取得することになるので、25 にそろえて 1 つで済ませる
if not defined JBANG_DEFAULT_JAVA_VERSION set "JBANG_DEFAULT_JAVA_VERSION=25"
call :missing_bootstrap
if defined MISSING goto :confirm_first
if defined FRESH goto :confirm_first
goto :run_offline

:confirm_first
rem ラッパーが jbang を動かす前に取得するものが無い（または --fresh で取り直す）。走らせる前に確認して、
rem よければ取得込みで動かす（このあと jbang が取得する JDK 25 と依存 jar も、この 1 回の確認に含める）
if defined MISSING set "MISSING=%MISSING%、ツールを動かす JDK 25、依存 jar（JDT ほか）"
if not defined MISSING set "MISSING=依存 jar（JDT ほか）を取り直す（JCHE_JBANG_OPTS の --fresh）。JDK 25 も無ければ取得する"
call :approve_download || goto :abort
goto :run_online

:run_offline
rem 取得済みのものだけで動かす。足りなければ jbang がアプリを始める前に失敗する（目印ができない）
if exist "%STARTED%" del /q "%STARTED%"
call "%ROOT%\jbangw\jbang.cmd" run --offline %JB_OPTS% %R_OPTS% "%ROOT%\src\Jche.java" %*
set "CODE=%ERRORLEVEL%"
if exist "%STARTED%" goto :done
echo.
echo 取得済みの JDK と依存 jar だけでは起動できませんでした（原因は上のメッセージ）。
set "MISSING=ツールを動かす JDK 25 か依存 jar（JDT ほか）のうち足りないもの"
call :approve_download || goto :abort

:run_online
call "%ROOT%\jbangw\jbang.cmd" run %JB_OPTS% %R_OPTS% "%ROOT%\src\Jche.java" %*
set "CODE=%ERRORLEVEL%"
goto :done

:abort
set "CODE=3"

:done
endlocal & set "CODE=%CODE%"
if exist "%RESTART%" (
    echo 設定を反映するため再起動します...
    goto :main
)
exit /b %CODE%

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
echo （取得そのものは、このあとネットワークに出る前にもう一度確認する）
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
rem 書き出す内容は Java 側（LauncherSettings.save）・java-call-hierarchy-exporter.sh が書くものと同じ
> "%SETTINGS%" (
    echo # java-call-hierarchy-exporter.sh / java-call-hierarchy-exporter.cmd が起動時に読む設定（アプリの「環境設定」からも書き換えられる）。
    echo # キーはそのまま環境変数になる。相対パスはこのファイルのあるフォルダが起点。空欄は既定値。
    echo #   JBANG_DIR       JBang 本体・JDK の置き場所（既定 ~/.jbang）
    echo #   JBANG_REPO      依存 jar の置き場所（既定 ~/.m2/repository）
    echo #   JCHE_JAVA_OPTS  解析を動かす JVM のオプション（例: -Xmx4g）
    echo #   JCHE_JBANG_OPTS jbang run に足すオプション（例: --offline）
    echo #   JCHE_ALLOW_DOWNLOAD  ネットワークからの取得（JBang 本体・JDK・依存 jar）を、尋ねずに行うなら yes、行わないなら no。空欄は毎回尋ねる
    echo JBANG_DIR=%~1
    echo JBANG_REPO=%~2
    echo JCHE_JAVA_OPTS=
    echo JCHE_JBANG_OPTS=
    echo JCHE_ALLOW_DOWNLOAD=
)
exit /b 0

:load_settings
rem launcher.properties の KEY=VALUE 行をそのまま環境変数にする（# で始まる行は読み飛ばす。値が空なら未設定にする）
set "R_OPTS="
set "JB_OPTS="
set "OFFLINE_FORCED="
if not exist "%SETTINGS%" goto :load_settings_done
for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%SETTINGS%") do call :set_one "%%A" "%%B"
rem 相対パスはこのフォルダ起点の絶対パスにする（jbang は作業ディレクトリに依らず同じ場所を見る）
if defined JBANG_DIR call :absolutize JBANG_DIR
if defined JBANG_CACHE_DIR call :absolutize JBANG_CACHE_DIR
if defined JBANG_REPO call :absolutize JBANG_REPO
rem JVM のオプションは jbang run の -R で 1 つずつ渡す（-Xmx4g -Xss2m → -R-Xmx4g -R-Xss2m）
if defined JCHE_JAVA_OPTS for %%O in (%JCHE_JAVA_OPTS%) do call set "R_OPTS=%%R_OPTS%% -R%%O"
:load_settings_done
rem jbang のオプション。--offline は起動コマンド自身が付けるので、利用者の指定は「ネットワークに出ない」という
rem 意思として覚えておく（短い -o は見分けないので、--offline と書くこと）。
rem --fresh（依存 jar を取り直す）は --offline と同時に指定できないので、「取り直す」という意思として覚えておき、
rem --offline での起動を飛ばして先に確認する
set "FRESH="
if defined JCHE_JBANG_OPTS set "JB_OPTS=%JCHE_JBANG_OPTS:--offline=%"
if defined JCHE_JBANG_OPTS if not "%JB_OPTS%"=="%JCHE_JBANG_OPTS%" set "OFFLINE_FORCED=1"
if defined JB_OPTS if not "%JB_OPTS:--fresh=%"=="%JB_OPTS%" set "FRESH=1"
exit /b 0

:set_one
rem %1=キー  %2=値（前後の空白は取り除く。キーは英大文字と _ で始まるものだけ）
set "K=%~1"
set "V=%~2"
for /f "tokens=* delims= " %%K in ("%K%") do set "K=%%K"
if "%K%"=="" exit /b 0
echo %K%| findstr /r /c:"^[A-Z_][A-Z0-9_]*$" > nul || exit /b 0
if not "%V%"=="" for /f "tokens=* delims= " %%V in ("%V%") do set "V=%%V"
if "%V%"=="" goto :set_one_empty
set "%K%=%V%"
exit /b 0
:set_one_empty
rem 空欄は既定値。JCHE_ALLOW_DOWNLOAD だけは、空欄（毎回尋ねる）のまま環境変数で一時的に yes / no を渡せるよう消さない
if /i not "%K%"=="JCHE_ALLOW_DOWNLOAD" set "%K%="
exit /b 0

:absolutize
rem %1=環境変数名。ドライブ文字（C:）か \\ で始まらなければ、このフォルダ起点の絶対パスにし、/ を \ にする
call set "V=%%%~1%%"
set "V=%V:/=\%"
if "%V:~1,1%"==":" goto :absolutize_done
if "%V:~0,2%"=="\\" goto :absolutize_done
set "V=%ROOT%\%V%"
:absolutize_done
set "%~1=%V%"
exit /b 0

:missing_bootstrap
rem ラッパー（jbangw\jbang.cmd）が jbang を動かす前に取得するもの（JBang 本体、JBang を動かす JDK）のうち、
rem まだ無いものを MISSING に表示用の名前で入れる。見る場所はラッパーと同じ（JBANG_DIR / JBANG_CACHE_DIR / JBANG_DEFAULT_JAVA_VERSION）
set "MISSING="
set "JBDIR=%USERPROFILE%\.jbang"
if defined JBANG_DIR set "JBDIR=%JBANG_DIR%"
set "TDIR=%JBDIR%\cache"
if defined JBANG_CACHE_DIR set "TDIR=%JBANG_CACHE_DIR%"
if exist "%ROOT%\jbangw\jbang.jar" goto :missing_bootstrap_jdk
if exist "%ROOT%\jbangw\.jbang\jbang.jar" goto :missing_bootstrap_jdk
if exist "%JBDIR%\bin\jbang.jar" goto :missing_bootstrap_jdk
set "MISSING=JBang 本体"
:missing_bootstrap_jdk
rem JBang を動かす JDK。ラッパーと同じ順で探す（JAVA_HOME → PATH の javac → currentjdk → 取得済みの既定の版）
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" exit /b 0
where javac > nul 2>&1
if not errorlevel 1 exit /b 0
if exist "%JBDIR%\currentjdk\bin\javac" exit /b 0
if exist "%TDIR%\jdks\%JBANG_DEFAULT_JAVA_VERSION%\" exit /b 0
if defined MISSING (set "MISSING=%MISSING%、JBang を動かす JDK") else (set "MISSING=JBang を動かす JDK")
exit /b 0

:approve_download
rem ネットワークから取得してよいか。MISSING=取得するもの（表示用）。よければ 0、だめなら 1 を返す。
rem 順に、JCHE_JBANG_OPTS の --offline → JCHE_ALLOW_DOWNLOAD（yes / no）→ 端末があれば尋ねる → 端末が無ければ取得しない
set "JBDIR=%USERPROFILE%\.jbang"
if defined JBANG_DIR set "JBDIR=%JBANG_DIR%"
set "REPO=%USERPROFILE%\.m2\repository"
if defined JBANG_REPO set "REPO=%JBANG_REPO%"
echo.
echo java-call-hierarchy-exporter: ネットワークからの取得が必要です
echo   取得するもの : %MISSING%
echo   取得元       : github.com（JBang 本体）、api.foojay.io（JDK）、Maven Central（依存 jar）
echo   置き場所     : %JBDIR%（JBang 本体・JDK）、%REPO%（依存 jar）
echo   大きさ       : 初回は合わせて数百 MB
if defined OFFLINE_FORCED goto :approve_offline
if /i "%JCHE_ALLOW_DOWNLOAD%"=="yes" goto :approve_yes
if /i "%JCHE_ALLOW_DOWNLOAD%"=="y" goto :approve_yes
if /i "%JCHE_ALLOW_DOWNLOAD%"=="true" goto :approve_yes
if "%JCHE_ALLOW_DOWNLOAD%"=="1" goto :approve_yes
if /i "%JCHE_ALLOW_DOWNLOAD%"=="no" goto :approve_no
if /i "%JCHE_ALLOW_DOWNLOAD%"=="n" goto :approve_no
if /i "%JCHE_ALLOW_DOWNLOAD%"=="false" goto :approve_no
if "%JCHE_ALLOW_DOWNLOAD%"=="0" goto :approve_no
2>nul >nul timeout /t 0 || goto :approve_notty
set "ANSWER="
set /p "ANSWER=ネットワークにアクセスして取得しますか？ [y/N]: "
if /i "%ANSWER%"=="y" exit /b 0
if /i "%ANSWER%"=="yes" exit /b 0
echo 取得を取りやめました。
exit /b 1
:approve_offline
echo   JCHE_JBANG_OPTS に --offline があるので取得しません。取得するには %SETTINGS% の --offline を外してください。
echo 取得を取りやめました。
exit /b 1
:approve_yes
echo   JCHE_ALLOW_DOWNLOAD=yes なので、尋ねずに取得します。
exit /b 0
:approve_no
echo   JCHE_ALLOW_DOWNLOAD=no なので取得しません。取得するには %SETTINGS% で yes にするか空欄（毎回尋ねる）にしてください。
echo 取得を取りやめました。
exit /b 1
:approve_notty
echo   端末が無いため確認できません。取得しません。
echo   尋ねずに取得するには %SETTINGS%（または環境変数）で JCHE_ALLOW_DOWNLOAD=yes にしてください。
echo   取得せずに動かすには、先に手元の JDK と jar を用意してください（README の「Pleiades/Eclipse環境（閉域ネットワーク等）」）。
echo 取得を取りやめました。
exit /b 1
