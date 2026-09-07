@echo off
rem 回帰テスト（Windows）。test\demo を解析し、出力 CSV を expected*\ と比較する。
rem Git Bash がある場合は  bash test/regression/run.sh  でも実行できる。
rem ケースの説明は run.sh の先頭コメントを参照。実行ログは <case>\run-<回数>.log に残す。
rem 出力はツールが <case>\output\<解析開始日時>_<プロジェクト名>\ に書くので、最新のフォルダと比較する。
rem 期待出力を更新するときは、差分を確認したうえで最新の output\*\ の CSV を expected*\ にコピーする。
setlocal
cd /d "%~dp0"
set "ROOT=%~dp0..\.."
set "JCHE=call "%ROOT%\jbangw\jbang.cmd" run "%ROOT%\src\CallHierarchyExporter.java""
set "FAIL=0"

rem whole は cache.folder が空欄なので、キャッシュはツールのプロジェクトフォルダの .cache\demo_<ハッシュ>\ にできる
for /d %%D in ("%ROOT%\.cache\demo_*") do rmdir /s /q "%%D"
for %%C in (whole entry) do call :normalcase "%%C"
call :expectsidecar whole

rem ビルドファイル（pom.xml / build.gradle）とローカルリポジトリ（test\localrepo）から依存 jar を集めるケース。
rem 直接の依存 greeter と、その POM から辿った推移的な依存 core の jar がログの一覧に出ることを確かめる
for %%C in (maven mavenmulti gradle) do (
    call :normalcase "%%C"
    call :expectlog "%%C" 1 "greeter-1.0.jar" "1回目: 直接の依存の jar を集めた"
    call :expectlog "%%C" 1 "core-1.0.jar" "1回目: 推移的な依存の jar を集めた"
)

echo == jarchange ==
call :reset jarchange
call :run jarchange config-before.properties 1 "1回目: jar 無し"
call :compare jarchange expected-before "1回目: jar 無し"
call :run jarchange config-after.properties 2 "2回目: jar 追加"
call :expectlog jarchange 2 "jar[^=]*=[1-9]" "2回目: jar 追加で影響ファイルを再解析"
call :expectlog jarchange 2 "^[^=]*=[1-9]" "2回目: 他のファイルはキャッシュを再利用"
call :compare jarchange expected-after "2回目: jar 追加"
call :run jarchange config-before.properties 3 "3回目: jar 削除"
call :expectlog jarchange 3 "jar[^=]*=[1-9]" "3回目: jar 削除で影響ファイルを再解析"
call :compare jarchange expected-before "3回目: jar 削除"

rem 複数の設定ファイルを 1 回の起動で処理するケース。存在しない設定を 1 つ混ぜ、それが失敗しても
rem 前後の設定が処理されて出力フォルダができること、終了コードが 1 になることを見る
echo == multi ==
if exist "whole\output" rmdir /s /q "whole\output"
if exist "entry\output" rmdir /s /q "entry\output"
del /q "run-multi.log" 2>nul
%JCHE% "whole\config.properties" "no-such-config.properties" "entry\config.properties" > "run-multi.log" 2>&1
rem 終了コードは、現在の jbangw\jbang.cmd（本家そのまま）が jbang 本体の終了コードを呼び出し元へ返さないため
rem 検査できない（.github\workflows\smoke.yml の「known to fail」の項と jbangw\README.md）。ここでは結果を表示するだけで
rem 失敗扱いにはしない。ツール自身が 1 を返すことは run.sh 側（Linux）で検査している
if errorlevel 1 (echo   OK   multi 終了コード=1（存在しない設定ファイルが失敗）) else (echo   WARN multi 終了コードが 1 ではありません（jbang.cmd の終了コード伝播の既知の問題。失敗扱いにしない）)
call :expectlog_any multi "run-multi.log" "\] *OK .*whole.config.properties" "multi: whole が処理された"
call :expectlog_any multi "run-multi.log" "\] *FAIL .*no-such-config.properties" "multi: 存在しない設定が失敗と報告された"
call :expectlog_any multi "run-multi.log" "\] *OK .*entry.config.properties" "multi: entry が処理された"
call :compare whole expected "multi: whole"
call :expectrunfiles whole config.properties "multi: whole"
call :compare entry expected "multi: entry"
call :expectrunfiles entry config.properties "multi: entry"

if "%FAIL%"=="0" (echo PASS & exit /b 0) else (echo FAIL & exit /b 1)

:normalcase
rem %1=case。同じ設定で 2 回実行する（1 回目はキャッシュ無し、2 回目はキャッシュを再利用する経路）
echo == %~1 ==
call :reset "%~1"
call :run "%~1" config.properties 1 "1回目"
call :compare "%~1" expected "1回目: キャッシュ無し"
call :expectrunfiles "%~1" config.properties "1回目"
call :run "%~1" config.properties 2 "2回目"
call :expectlog "%~1" 2 "^[^=]*=[1-9]" "2回目: キャッシュを再利用"
call :compare "%~1" expected "2回目: キャッシュ再利用"
exit /b 0

:latest
rem %1=case。最新の出力フォルダ（名前の先頭が日時なので、名前の降順の先頭）を OUT に入れる
set "OUT="
for /f "delims=" %%D in ('dir /b /ad /o-n "%~1\output" 2^>nul') do if not defined OUT set "OUT=%~1\output\%%D"
exit /b 0

:expectrunfiles
rem %1=case  %2=設定ファイル名  %3=ラベル。出力フォルダに設定ファイルの複製と run.log があること。
rem 括弧ブロックの中の echo でラベルを半角の ( ) で囲むと、) がブロックの終端と解釈されて
rem 「) was unexpected at this time.」で止まるので、全角の（ ）を使う
call :latest "%~1"
if "%OUT%"=="" (echo   DIFF %~1 出力フォルダがありません（%~3） & set "FAIL=1" & exit /b 0)
fc /b "%~1\%~2" "%OUT%\%~2" >nul 2>&1
if errorlevel 1 (echo   DIFF %~1 設定ファイルの複製がありません: %OUT%\%~2（%~3） & set "FAIL=1") else (echo   OK   %~1 設定ファイルの複製（%~3）)
if exist "%OUT%\run.log" (echo   OK   %~1 run.log（%~3）) else (echo   DIFF %~1 run.log がありません: %OUT%\run.log（%~3） & set "FAIL=1")
exit /b 0

:expectsidecar
rem %1=case。キャッシュがツールのプロジェクトフォルダの .cache\demo_* にあり、ケースのフォルダには無いこと
set "SIDECAR="
for /d %%D in ("%ROOT%\.cache\demo_*") do if exist "%%D\analysis-cache.tsv" set "SIDECAR=%%D"
if not "%SIDECAR%"=="" if not exist "%~1\.cache" (echo   OK   %~1 キャッシュの場所 %SIDECAR% & exit /b 0)
echo   DIFF %~1 キャッシュがツールのプロジェクトフォルダの .cache\demo_* にありません & set "FAIL=1"
exit /b 0

:expectlog_any
rem %1=case  %2=ログファイル  %3=正規表現  %4=ラベル。ログ全体から探す
powershell -NoProfile -Command "if (Select-String -Path '%~2' -Encoding Default -Pattern '%~3' -Quiet) { Write-Host '  OK   %~1 ログ (%~4)' } else { Write-Host '  DIFF %~1 ログ (%~4): 「%~3」がありません'; exit 1 }"
if errorlevel 1 set "FAIL=1"
exit /b 0

:reset
if exist "%~1\.cache" rmdir /s /q "%~1\.cache"
if exist "%~1\output" rmdir /s /q "%~1\output"
del /q "%~1\run-*.log" 2>nul
exit /b 0

:run
rem %1=case  %2=設定ファイル  %3=何回目  %4=ラベル。ログは case\run-N.log
%JCHE% "%~1\%~2" > "%~1\run-%~3.log" 2>&1
if errorlevel 1 (echo   実行に失敗しました（%~4）。%~1\run-%~3.log を確認してください & set "FAIL=1")
exit /b 0

:expectlog
rem %1=case  %2=何回目  %3=正規表現  %4=ラベル
rem ツールは標準出力をコンソールの文字コードで書く（固定しない）ので、日本語は環境によって化けうる。
rem そのためフェーズ1の集計行「ソース解析: 再利用=N 新規解析=M（…） 失敗=F」を
rem 「=数字」が3つ以上あって数字で終わる最初の行として探し、ASCII の部分だけを検査する。
rem   ^[^=]*=[1-9]   … 最初の「=N」（再利用）が 0 でない
rem   jar[^=]*=[1-9] … 「依存jarの変更による再解析=N」の N が 0 でない
rem 集計行に無い文字列（ビルドツールのコマンド行など）はログ全体から探す。集計行が見つからないときも同様
powershell -NoProfile -Command "$l=(Select-String -Path '%~1\run-%~2.log' -Encoding Default -Pattern '=\d+.*=\d+.*=\d+\s*$' | Select-Object -First 1).Line; if ($l -match '%~3') { Write-Host '  OK   %~1 ログ (%~4)' } elseif (Select-String -Path '%~1\run-%~2.log' -Encoding Default -SimpleMatch -Pattern '%~3' -Quiet) { Write-Host '  OK   %~1 ログ (%~4)' } else { Write-Host ('  DIFF %~1 ログ (%~4): ' + $l); exit 1 }"
if errorlevel 1 set "FAIL=1"
exit /b 0

:compare
rem %1=case  %2=期待出力のフォルダ  %3=ラベル。CSV は UTF-8（BOM 付き）。改行コード（CRLF/LF）の違いは無視して比較する
rem 一致しないときは差分を先頭 20 行まで出す（<= が期待側だけ、=> が実際の出力だけにある行）。
rem Compare-Object は集合として比べるので、差分が出ないのに一致しない場合は並び順だけが違う
call :latest "%~1"
if "%OUT%"=="" (echo   DIFF %~1 出力フォルダがありません（%~3） & set "FAIL=1" & exit /b 0)
for %%F in (call-hierarchy.csv methods.csv) do (
    powershell -NoProfile -Command "$e=(Get-Content -Raw -Encoding UTF8 '%~1\%~2\%%F') -replace \"`r\",''; $o=(Get-Content -Raw -Encoding UTF8 '%OUT%\%%F') -replace \"`r\",''; if ($e -eq $o) { Write-Host '  OK   %~1/%%F (%~3)' } else { Write-Host '  DIFF %~1/%%F (%~3)'; $d=Compare-Object ($e -split \"`n\") ($o -split \"`n\"); if ($d) { $d | Select-Object -First 20 | ForEach-Object { Write-Host ('       ' + $_.SideIndicator + ' ' + $_.InputObject) } } else { Write-Host '       (行の集合は同じ。並び順だけが違う)' }; exit 1 }"
    if errorlevel 1 set "FAIL=1"
)
exit /b 0
