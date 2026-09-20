@echo off
rem java-call-hierarchy-exporter の起動コマンド（Windows のコマンドプロンプト。Linux / macOS / Git Bash は java-call-hierarchy-exporter.sh）。
rem
rem   jche                              引数なし … 対話モード（メニューで設定ファイルを選んで解析する）
rem   jche a.properties [b.properties…] 引数あり … 対話なしで解析する（jbang で src\jche\CallHierarchyExporter.java を直接動かすのと同じ）
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
rem   2. jbangw\jbang.cmd（同梱の JBang ラッパー）で src\jche\Jche.java を動かす。
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
rem MS932 にしてある（chcp で切り替えると画面が消えるので使わない。src\jche\CallHierarchyExporter.java の冒頭）。
rem 編集するときは MS932 のまま保存すること。書き出す launcher.properties も MS932 になり、Java 側（native.encoding）と揃う。
setlocal
set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"
set "SETTINGS=%ROOT%\launcher.properties"
rem 表示言語。JCHE_LANG（en / ja）が優先。無ければ画面のコードページで決める。
rem このファイルは MS932 なので、日本語を化けずに出せるのはコードページが 932 のときだけである。
rem 「出せるかどうか」と「出すかどうか」がそろうので、これ以上の見方（ロケールの照会）は要らない。
rem Java 側（jche.util.Messages）は JCHE_LANG → jche.lang → 設定ファイル → OS の順で、先頭はここと同じ。
rem launcher.properties に JCHE_LANG=ja と書いておくこともできる。:load_settings のあとに
rem もう一度呼ぶので、取得の確認はその言語で出る。初回の置き場所の質問はその前なので、
rem そこだけは環境変数かコードページで決まる。
call :resolve_lang
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
rem launcher.properties の JCHE_LANG を反映する（環境変数が既にあればそれが勝つ＝値は変わらない）
call :resolve_lang
set "JCHE_ROOT=%ROOT%"
rem jbang 自身の更新確認（起動のたびに新しい版があるかを問い合わせる）はネットワークに出るので止める
set "JBANG_NO_VERSION_CHECK=true"
rem ラッパーが JBang を動かすために取得する JDK の版。既定（17）のままだと、ツールを動かす JDK 25 と合わせて
rem 2 つの JDK を取得することになるので、25 にそろえて 1 つで済ませる
if not defined JBANG_DEFAULT_JAVA_VERSION set "JBANG_DEFAULT_JAVA_VERSION=25"
call :set_sizes
call :wrapper_would_download
if defined WOULD_DL goto :confirm_first
if defined FRESH goto :confirm_first
goto :run_offline

:confirm_first
rem ラッパーが jbang を動かす前に取得するものが無い（または --fresh で取り直す）。走らせる前に確認して、
rem よければ取得込みで動かす（このあと jbang が取得する JDK と依存 jar も、この 1 回の確認に含める）
call :pending_items
call :approve_download
if errorlevel 1 goto :abort
goto :run_online

:run_offline
rem 取得済みのものだけで動かす。足りなければ jbang がアプリを始める前に失敗する（目印ができない）
if exist "%STARTED%" del /q "%STARTED%"
call "%ROOT%\jbangw\jbang.cmd" run --offline %JB_OPTS% %R_OPTS% "%ROOT%\src\jche\Jche.java" %*
set "CODE=%ERRORLEVEL%"
if exist "%STARTED%" goto :done
echo.
call :msg offline.failed
call :pending_items
call :approve_download
if errorlevel 1 goto :abort

:run_online
call "%ROOT%\jbangw\jbang.cmd" run %JB_OPTS% %R_OPTS% "%ROOT%\src\jche\Jche.java" %*
set "CODE=%ERRORLEVEL%"
goto :done

:abort
set "CODE=3"

:done
endlocal & set "CODE=%CODE%"
if exist "%RESTART%" (
    call :msg restart
    goto :main
)
exit /b %CODE%

:first_run_prompt
call :msg first.title
echo.
call :msg first.where
call :msg first.local "%ROOT%\.jbang"
call :msg first.localHint
call :msg first.home "%USERPROFILE%\.jbang"
call :msg first.homeHint
call :msg first.changeLater
echo   %SETTINGS%
call :msg first.downloadLater
echo.
set "CHOICE=1"
call :msgv first.prompt
set /p "CHOICE=%MSG%"
if "%CHOICE%"=="2" (call :write_settings "" "") else (call :write_settings ".jbang" ".jbang/repository")
call :msg first.saved "%SETTINGS%"
echo.
exit /b 0

:first_run_default
rem 引数ありのときは対話なしで実行する。置き場所は尋ねず、既定（このプロジェクトの中）にして知らせるだけ
call :write_settings ".jbang" ".jbang/repository"
call :msg first.defaultDir "%ROOT%\.jbang" "%SETTINGS%"
echo.
exit /b 0

:write_settings
rem %1=JBANG_DIR  %2=JBANG_REPO（相対はこのフォルダ起点。空欄は JBang の既定）。
rem 書き出す内容は Java 側（LauncherSettings.save）・java-call-hierarchy-exporter.sh が書くものと同じ
> "%SETTINGS%" (
    call :msg settings.header1
    call :msg settings.header2
    call :msg settings.jbangDir
    call :msg settings.repo
    call :msg settings.javaOpts
    call :msg settings.jbangOpts
    call :msg settings.allowDownload
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
rem jbang のオプションを 1 つずつ見る。--offline は起動コマンド自身が付けるので、利用者の指定は
rem 「ネットワークに出ない」という意思として OFFLINE_FORCED で覚え、jbang には渡さない（同じオプションを 2 回渡さないため）。
rem --fresh（依存 jar を取り直す）は --offline と同時に指定できないので、「取り直す」という意思として覚えておき、
rem --offline での起動を飛ばして先に確認する（こちらは jbang にもそのまま渡す）。
rem ここで %VAR:検索=置換% の文字列置換は使わない。変数が未定義のとき cmd がこの書き方を展開しきれず、
rem 壊れた if 行になって「set was unexpected at this time.」でバッチごと落ちる
rem （docs/network-download-confirm-qa.md の Q16。test/cli/run.sh がこの書き方の混入を検出する）
set "FRESH="
set "JB_OPTS="
if "%JCHE_JBANG_OPTS%"=="" exit /b 0
for %%O in (%JCHE_JBANG_OPTS%) do call :one_jbang_opt "%%~O"
exit /b 0

:one_jbang_opt
rem %1=jbang のオプション 1 つ。--offline / -o は JB_OPTS に入れず、覚えるだけにする
if "%~1"=="--offline" goto :one_jbang_opt_offline
if "%~1"=="-o" goto :one_jbang_opt_offline
if "%~1"=="--fresh" set "FRESH=1"
if "%JB_OPTS%"=="" goto :one_jbang_opt_first
set "JB_OPTS=%JB_OPTS% %~1"
exit /b 0
:one_jbang_opt_first
set "JB_OPTS=%~1"
exit /b 0
:one_jbang_opt_offline
set "OFFLINE_FORCED=1"
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

:set_sizes
rem 取得しうるものの目安サイズ（MB）。NET はダウンロード量、DISK は置き場所が増える量。
rem 実測値で、測り直し方は docs\network-download-confirm-qa.md の Q15 にある。版が上がれば少し変わるので「約」として出す。
set "SIZE_JBANG_NET=15"
set "SIZE_JBANG_DISK=30"
set "SIZE_JDK_NET=135"
set "SIZE_JDK_DISK=440"
set "SIZE_DEPS_NET=15"
set "SIZE_DEPS_DISK=15"
rem 確認の待ち時間（秒）。端末はあるが誰も居ないとき、ここで打ち切って取りやめる
set "ASK_TIMEOUT=60"
exit /b 0

:jbdirs
rem JBang 本体・JDK の置き場所（JBDIR / TDIR）と依存 jar の置き場所（REPO）。ラッパーと同じ決め方
set "JBDIR=%USERPROFILE%\.jbang"
if defined JBANG_DIR set "JBDIR=%JBANG_DIR%"
set "TDIR=%JBDIR%\cache"
if defined JBANG_CACHE_DIR set "TDIR=%JBANG_CACHE_DIR%"
set "REPO=%USERPROFILE%\.m2\repository"
if defined JBANG_REPO set "REPO=%JBANG_REPO%"
exit /b 0

:jbang_jar_available
rem ラッパーが jbang 本体を探す順（同梱 → JBANG_DIR\bin）
if exist "%ROOT%\jbangw\jbang.jar" exit /b 0
if exist "%ROOT%\jbangw\.jbang\jbang.jar" exit /b 0
if exist "%JBDIR%\bin\jbang.jar" exit /b 0
exit /b 1

:jdk25_available
rem ツールを動かす JDK（//JAVA 25。ラッパーが取る JDK と同じ版にそろえてある）が取得済みか
if exist "%TDIR%\jdks\%JBANG_DEFAULT_JAVA_VERSION%\." exit /b 0
exit /b 1

:bootstrap_jdk_available
rem JBang を動かす JDK。ラッパーと同じ順で探す（JAVA_HOME → PATH の javac → currentjdk → 取得済みの JDK）
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" exit /b 0
where javac > nul 2>&1
if not errorlevel 1 exit /b 0
if exist "%JBDIR%\currentjdk\bin\javac" exit /b 0
call :jdk25_available
if not errorlevel 1 exit /b 0
exit /b 1

:wrapper_would_download
rem ラッパー（jbangw\jbang.cmd）が jbang を動かす前にネットワークに出るか（WOULD_DL に入れる）。
rem ラッパーには --offline のような抑止が無く、呼んだ時点で取得が始まるので、呼ぶ前に確認する必要がある
call :jbdirs
set "WOULD_DL="
call :jbang_jar_available
if errorlevel 1 set "WOULD_DL=1"
call :bootstrap_jdk_available
if errorlevel 1 set "WOULD_DL=1"
exit /b 0

:pending_items
rem 取得しうるもののうち、まだ手元に無いものの鍵（jbang / jdk / deps）を DL_LIST に入れる。
rem 依存 jar は手元にあるかを確かめようがないので（推移的な依存まで数えることになる）常に挙げる
call :jbdirs
set "DL_LIST="
call :jbang_jar_available
if errorlevel 1 set "DL_LIST=jbang"
call :jdk25_available
if errorlevel 1 set "DL_LIST=%DL_LIST% jdk"
set "DL_LIST=%DL_LIST% deps"
exit /b 0

:describe_items
rem DL_LIST から、表示用の文（DL_ITEMS / DL_FROM）と合計サイズ（DL_NET / DL_DISK）を作る。
rem 値を使う箇所は call のサブルーチンにする（括弧ブロックの中では %VAR% がブロックの解析時に展開されるため）
set "DL_ITEMS="
set "DL_FROM="
set "DL_NOTE="
set /a DL_NET=0
set /a DL_DISK=0
for %%I in (%DL_LIST%) do call :add_item %%I
exit /b 0

:add_item
rem %1=鍵（jbang / jdk / deps）。2 つめ以降は区切り（item.sep）を前に置く。
rem 括弧のブロックにしないのは、その中では %MSG%（:msgv が入れる値）が
rem ブロックの解析時に展開されてしまい、常に空になるため
set "SEP="
if defined DL_ITEMS call :set_sep
if "%~1"=="jbang" goto :add_jbang
if "%~1"=="jdk" goto :add_jdk
if "%~1"=="deps" goto :add_deps
exit /b 0

:set_sep
call :msgv item.sep
set "SEP=%MSG%"
exit /b 0

:add_jbang
call :msgv item.jbang "%SIZE_JBANG_NET%"
set "DL_ITEMS=%DL_ITEMS%%SEP%%MSG%"
call :msgv from.jbang
set "DL_FROM=%DL_FROM%%SEP%%MSG%"
set /a DL_NET+=SIZE_JBANG_NET
set /a DL_DISK+=SIZE_JBANG_DISK
exit /b 0

:add_jdk
call :msgv item.jdk "%JBANG_DEFAULT_JAVA_VERSION%" "%SIZE_JDK_NET%"
set "DL_ITEMS=%DL_ITEMS%%SEP%%MSG%"
call :msgv from.jdk
set "DL_FROM=%DL_FROM%%SEP%%MSG%"
call :msgv note.jdk
set "DL_NOTE=%MSG%"
set /a DL_NET+=SIZE_JDK_NET
set /a DL_DISK+=SIZE_JDK_DISK
exit /b 0

:add_deps
call :msgv item.deps "%SIZE_DEPS_NET%"
set "DL_ITEMS=%DL_ITEMS%%SEP%%MSG%"
call :msgv from.deps
set "DL_FROM=%DL_FROM%%SEP%%MSG%"
set /a DL_NET+=SIZE_DEPS_NET
set /a DL_DISK+=SIZE_DEPS_DISK
exit /b 0

:approve_download
rem ネットワークから取得してよいか。DL_LIST=取得しうるものの鍵（:pending_items が入れる）。よければ 0、だめなら 1 を返す。
rem 順に、JCHE_JBANG_OPTS の --offline → JCHE_ALLOW_DOWNLOAD（yes / no）→ 端末があれば尋ねる → 端末が無ければ取得しない
call :jbdirs
call :describe_items
echo.
call :msg net.needed
call :msg net.items "%DL_ITEMS%"
call :msg net.size "%DL_NET%" "%DL_DISK%" "%DL_NOTE%"
call :msg net.sizeNote
call :msg net.from "%DL_FROM%"
call :msg net.into "%JBDIR%" "%REPO%"
if defined OFFLINE_FORCED goto :approve_offline
if /i "%JCHE_ALLOW_DOWNLOAD%"=="yes" goto :approve_yes
if /i "%JCHE_ALLOW_DOWNLOAD%"=="y" goto :approve_yes
if /i "%JCHE_ALLOW_DOWNLOAD%"=="true" goto :approve_yes
if "%JCHE_ALLOW_DOWNLOAD%"=="1" goto :approve_yes
if /i "%JCHE_ALLOW_DOWNLOAD%"=="no" goto :approve_no
if /i "%JCHE_ALLOW_DOWNLOAD%"=="n" goto :approve_no
if /i "%JCHE_ALLOW_DOWNLOAD%"=="false" goto :approve_no
if "%JCHE_ALLOW_DOWNLOAD%"=="0" goto :approve_no
rem 標準入力が端末でなければ尋ねない（同梱の jbangw\jbang.cmd と同じ判定）
2>nul >nul timeout /t 0 || goto :approve_notty
rem 端末はあっても、その先に誰も居ないことがある（コンソールを割り当てるタスクスケジューラ等）。
rem cmd には bash の /dev/tty のような「読んだら即座に終わる」合図が無く、set /p はそういう場でも待ち続ける
rem （CON から読む手も、コンソールが無い環境で永久に待つので使えない。実測は
rem  docs/network-download-confirm-qa.md の Q18）。choice の /t と /d で待ち時間に上限を設け、
rem 時間切れなら取りやめ（n）に倒す。errorlevel は選んだ番号（1=y 2=n）、読めなければ 255、Ctrl+C なら 0。
rem if errorlevel は「N 以上」なので、大きい順に見る（choice のドキュメントにある決まり）
call :msgv net.askTimeout "%ASK_TIMEOUT%"
choice /c yn /n /t %ASK_TIMEOUT% /d n /m "%MSG%"
if errorlevel 255 goto :approve_notty
if errorlevel 2 goto :approve_declined
if errorlevel 1 exit /b 0
rem 0 は Ctrl+C / Ctrl+Break
:approve_declined
echo.
call :msg net.cancelled
exit /b 1
:approve_offline
call :msg net.offline "%SETTINGS%"
call :msg net.cancelled
exit /b 1
:approve_yes
call :msg net.allowYes
exit /b 0
:approve_no
call :msg net.allowNo "%SETTINGS%"
call :msg net.cancelled
exit /b 1
:approve_notty
call :msg net.noTty
call :msg net.noTtyYes "%SETTINGS%"
call :msg net.noTtyOffline
call :msg net.cancelled
exit /b 1

:resolve_lang
rem 表示言語を JCHE_MSG_LANG に入れる。JCHE_LANG（en / ja）が優先。
rem 無ければ画面のコードページで決める。このファイルは MS932 なので、日本語を化けずに
rem 出せるのはコードページが 932 のときだけである。「出せるかどうか」と「出すかどうか」が
rem そろうので、これ以上の見方（ロケールの照会）は要らない。
rem Java 側（jche.util.Messages）は JCHE_LANG → jche.lang → 設定ファイル → OS の順で、先頭はここと同じ。
set "JCHE_MSG_LANG=en"
if defined JCHE_LANG goto :lang_from_env
for /f "tokens=2 delims=:" %%C in ('chcp') do call :lang_from_cp %%C
exit /b 0

:lang_from_cp
rem %1=コードページの番号（chcp の「…: 932」の後ろ）。call の引数として受け取ると前後の空白が落ちるので、
rem %VAR:検索=置換% を使わずに済む（未定義の変数にあの書き方をすると cmd がバッチごと落ちる。
rem docs/network-download-confirm-qa.md の Q16）
if "%~1"=="932" set "JCHE_MSG_LANG=ja"
exit /b 0

:lang_from_env
if /i "%JCHE_LANG:~0,2%"=="ja" set "JCHE_MSG_LANG=ja"
exit /b 0

:msgv
rem 文言を MSG に入れる（画面には出さない）。%1=キー、%2… 差し込む値。
rem 日本語を選んでいれば日本語の表を先に見て、無ければ英語（土台）へ落とす。
rem どちらにも無ければ !キー! を返すので、訳し忘れに気づける。
set "MSG="
if "%JCHE_MSG_LANG%"=="ja" call :msg_ja %*
if not defined MSG call :msg_en %*
if not defined MSG set "MSG=!%~1!"
exit /b 0

:msg
rem 文言を 1 行出す。%1=キー、%2… 差し込む値
call :msgv %*
echo %MSG%
exit /b 0

:msg_en
rem 英語（土台）。括弧のブロックを使わないのは、%VAR% がブロックの解析時に展開されてしまうため
if "%~1"=="first.title" set "MSG=java-call-hierarchy-exporter: first-time setup"
if "%~1"=="first.where" set "MSG=Choose where the JDK and JBang this tool uses (a few hundred MB together) should live."
if "%~1"=="first.local" set "MSG=  1) Inside this project   %~2"
if "%~1"=="first.localHint" set "MSG=     Nothing else is touched; delete the folder to undo. Dependency jars go there too"
if "%~1"=="first.home" set "MSG=  2) User home             %~2 (JBang's default)"
if "%~1"=="first.homeHint" set "MSG=     Shared with other JBang scripts. Pick this if you already use JBang"
if "%~1"=="first.changeLater" set "MSG=To change it later, use the Environment settings screen in the app, or edit the file below."
if "%~1"=="first.downloadLater" set "MSG=(Downloading itself is confirmed again before going to the network.)"
if "%~1"=="first.prompt" set "MSG=Number [1]: "
if "%~1"=="first.saved" set "MSG=Saved to %~2."
if "%~1"=="first.defaultDir" set "MSG=java-call-hierarchy-exporter: the JDK and JBang go into %~2 (change it in %~3)."
if "%~1"=="settings.header1" set "MSG=# Settings read at startup by java-call-hierarchy-exporter.sh / java-call-hierarchy-exporter.cmd (the Environment settings screen of the app writes here too)."
if "%~1"=="settings.header2" set "MSG=# Each key becomes an environment variable as is. Relative paths start from the folder holding this file. Empty means the default."
if "%~1"=="settings.jbangDir" set "MSG=#   JBANG_DIR       where JBang itself and the JDK live (default ~/.jbang)"
if "%~1"=="settings.repo" set "MSG=#   JBANG_REPO      where dependency jars live (default ~/.m2/repository)"
if "%~1"=="settings.javaOpts" set "MSG=#   JCHE_JAVA_OPTS  options for the JVM that runs the analysis (for example: -Xmx4g)"
if "%~1"=="settings.jbangOpts" set "MSG=#   JCHE_JBANG_OPTS extra options for jbang run (for example: --offline)"
if "%~1"=="settings.allowDownload" set "MSG=#   JCHE_ALLOW_DOWNLOAD  yes to download from the network (JBang, the JDK, dependency jars) without asking, no to never download. Empty asks every time"
if "%~1"=="item.sep" set "MSG=, "
if "%~1"=="item.jbang" set "MSG=JBang itself (about %~2MB)"
if "%~1"=="item.jdk" set "MSG=JDK %~2 to run the tool (about %~3MB)"
if "%~1"=="item.deps" set "MSG=dependency jars (JDT and others, about %~2MB)"
if "%~1"=="from.jbang" set "MSG=github.com (JBang itself)"
if "%~1"=="from.jdk" set "MSG=api.foojay.io (the JDK; served from Adoptium on github.com)"
if "%~1"=="from.deps" set "MSG=Maven Central (dependency jars)"
if "%~1"=="note.jdk" set "MSG=; the JDK keeps both the unpacked files and the archive"
if "%~1"=="net.needed" set "MSG=java-call-hierarchy-exporter: something has to be downloaded from the network"
if "%~1"=="net.items" set "MSG=  To download   : %~2"
if "%~1"=="net.size" set "MSG=  Transfer      : about %~2MB (the location grows by about %~3MB%~4)"
if "%~1"=="net.sizeNote" set "MSG=                  A measured estimate. Anything already present is not fetched, so the real figure is lower"
if "%~1"=="net.from" set "MSG=  From          : %~2"
if "%~1"=="net.into" set "MSG=  Into          : %~2 (JBang and the JDK), %~3 (dependency jars)"
if "%~1"=="net.offline" set "MSG=  JCHE_JBANG_OPTS contains --offline, so nothing is downloaded. Remove --offline from %~2 to allow it."
if "%~1"=="net.cancelled" set "MSG=Download cancelled."
if "%~1"=="net.allowYes" set "MSG=  JCHE_ALLOW_DOWNLOAD=yes, so it downloads without asking."
if "%~1"=="net.allowNo" set "MSG=  JCHE_ALLOW_DOWNLOAD=no, so nothing is downloaded. Set it to yes in %~2, or leave it empty to be asked every time."
if "%~1"=="net.askTimeout" set "MSG=Go to the network and download? [y/N] (cancelled after %~2 seconds): "
if "%~1"=="net.noTty" set "MSG=  There is no terminal to ask on. Nothing is downloaded."
if "%~1"=="net.noTtyYes" set "MSG=  To download without asking, set JCHE_ALLOW_DOWNLOAD=yes in %~2 (or as an environment variable)."
if "%~1"=="net.noTtyOffline" set "MSG=  To run without downloading, prepare a local JDK and the jars first (see the Pleiades/Eclipse environment section in the README)."
if "%~1"=="offline.failed" set "MSG=Could not start with only the JDK and dependency jars already present (the reason is in the message above)."
if "%~1"=="restart" set "MSG=Restarting to apply the settings..."
exit /b 0

:msg_ja
rem 日本語（英語に重ねる）。キーは英語の表とそろえる（test/nls/run.sh が突き合わせる）
if "%~1"=="first.title" set "MSG=java-call-hierarchy-exporter: 初回の設定"
if "%~1"=="first.where" set "MSG=このツールが使う JDK と JBang（合わせて数百 MB）の置き場所を選んでください。"
if "%~1"=="first.local" set "MSG=  1) このプロジェクトの中   %~2"
if "%~1"=="first.localHint" set "MSG=     他の環境を汚さず、フォルダごと消せば元に戻る。依存 jar も同じ場所に置く"
if "%~1"=="first.home" set "MSG=  2) ユーザーのホーム       %~2（JBang の既定）"
if "%~1"=="first.homeHint" set "MSG=     他の JBang スクリプトと共有する。既に JBang を使っているならこちら"
if "%~1"=="first.changeLater" set "MSG=後から変えるときは、アプリの「環境設定」か、次のファイルを編集する。"
if "%~1"=="first.downloadLater" set "MSG=（取得そのものは、このあとネットワークに出る前にもう一度確認する）"
if "%~1"=="first.prompt" set "MSG=番号 [1]: "
if "%~1"=="first.saved" set "MSG=%~2 に保存しました。"
if "%~1"=="first.defaultDir" set "MSG=java-call-hierarchy-exporter: JDK と JBang は %~2 に置きます（変えるときは %~3）。"
if "%~1"=="settings.header1" set "MSG=# java-call-hierarchy-exporter.sh / java-call-hierarchy-exporter.cmd が起動時に読む設定（アプリの「環境設定」からも書き換えられる）。"
if "%~1"=="settings.header2" set "MSG=# キーはそのまま環境変数になる。相対パスはこのファイルのあるフォルダが起点。空欄は既定値。"
if "%~1"=="settings.jbangDir" set "MSG=#   JBANG_DIR       JBang 本体・JDK の置き場所（既定 ~/.jbang）"
if "%~1"=="settings.repo" set "MSG=#   JBANG_REPO      依存 jar の置き場所（既定 ~/.m2/repository）"
if "%~1"=="settings.javaOpts" set "MSG=#   JCHE_JAVA_OPTS  解析を動かす JVM のオプション（例: -Xmx4g）"
if "%~1"=="settings.jbangOpts" set "MSG=#   JCHE_JBANG_OPTS jbang run に足すオプション（例: --offline）"
if "%~1"=="settings.allowDownload" set "MSG=#   JCHE_ALLOW_DOWNLOAD  ネットワークからの取得（JBang 本体・JDK・依存 jar）を、尋ねずに行うなら yes、行わないなら no。空欄は毎回尋ねる"
if "%~1"=="item.sep" set "MSG=、"
if "%~1"=="item.jbang" set "MSG=JBang 本体（約 %~2MB）"
if "%~1"=="item.jdk" set "MSG=ツールを動かす JDK %~2（約 %~3MB）"
if "%~1"=="item.deps" set "MSG=依存 jar（JDT ほか。約 %~2MB）"
if "%~1"=="from.jbang" set "MSG=github.com（JBang 本体）"
if "%~1"=="from.jdk" set "MSG=api.foojay.io（JDK。実体は Adoptium の github.com）"
if "%~1"=="from.deps" set "MSG=Maven Central（依存 jar）"
if "%~1"=="note.jdk" set "MSG=。JDK は展開したものとアーカイブの両方が残るため"
if "%~1"=="net.needed" set "MSG=java-call-hierarchy-exporter: ネットワークからの取得が必要です"
if "%~1"=="net.items" set "MSG=  取得するもの : %~2"
if "%~1"=="net.size" set "MSG=  通信量の目安 : 約 %~2MB（置き場所は約 %~3MB 増える%~4）"
if "%~1"=="net.sizeNote" set "MSG=                 実測に基づく目安。すでに手元にあるものは取得しないので、実際はこれ以下になる"
if "%~1"=="net.from" set "MSG=  取得元       : %~2"
if "%~1"=="net.into" set "MSG=  置き場所     : %~2（JBang 本体・JDK）、%~3（依存 jar）"
if "%~1"=="net.offline" set "MSG=  JCHE_JBANG_OPTS に --offline があるので取得しません。取得するには %~2 の --offline を外してください。"
if "%~1"=="net.cancelled" set "MSG=取得を取りやめました。"
if "%~1"=="net.allowYes" set "MSG=  JCHE_ALLOW_DOWNLOAD=yes なので、尋ねずに取得します。"
if "%~1"=="net.allowNo" set "MSG=  JCHE_ALLOW_DOWNLOAD=no なので取得しません。取得するには %~2 で yes にするか空欄（毎回尋ねる）にしてください。"
if "%~1"=="net.askTimeout" set "MSG=ネットワークにアクセスして取得しますか？ [y/N]（%~2 秒で取りやめ）: "
if "%~1"=="net.noTty" set "MSG=  端末が無いため確認できません。取得しません。"
if "%~1"=="net.noTtyYes" set "MSG=  尋ねずに取得するには %~2（または環境変数）で JCHE_ALLOW_DOWNLOAD=yes にしてください。"
if "%~1"=="net.noTtyOffline" set "MSG=  取得せずに動かすには、先に手元の JDK と jar を用意してください（README の「Pleiades/Eclipse環境（閉域ネットワーク等）」）。"
if "%~1"=="offline.failed" set "MSG=取得済みの JDK と依存 jar だけでは起動できませんでした（原因は上のメッセージ）。"
if "%~1"=="restart" set "MSG=設定を反映するため再起動します..."
exit /b 0

