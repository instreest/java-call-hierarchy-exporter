@echo off
rem 回帰テスト（Windows）。samples\demo を解析し、出力 CSV を expected*\ と比較する。
rem Git Bash がある場合は  bash test/regression/run.sh  でも実行できる。
rem ケースの説明は run.sh の先頭コメントを参照。
rem 期待出力を更新するときは、差分を確認したうえで output\ を expected*\ にコピーする。
setlocal
cd /d "%~dp0"
set "ROOT=%~dp0..\.."
set "JCHE=call "%ROOT%\jbangw\jbang.cmd" run "%ROOT%\src\CallHierarchyExporter.java""
set "FAIL=0"
rem 実行ログを UTF-8 で書かせ、PowerShell で UTF-8 として読む（コンソールのコードページに依存しないため）。
rem ログ検査のパターンは、この .cmd の文字コードに左右されないよう日本語を \uXXXX（.NET 正規表現のエスケープ）で書く
set "JAVA_TOOL_OPTIONS=%JAVA_TOOL_OPTIONS% -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

for %%C in (whole entry) do (
    echo == %%C ==
    call :reset "%%C"
    call :run "%%C" config.properties "1回目"
    call :compare "%%C" expected "1回目: キャッシュ無し"
    call :run "%%C" config.properties "2回目"
    call :expectlog "%%C" "\u518d\u5229\u7528=[1-9]" "2回目: キャッシュを再利用"
    call :compare "%%C" expected "2回目: キャッシュ再利用"
)

echo == jarchange ==
call :reset jarchange
call :run jarchange config-before.properties "1回目: jar 無し"
call :compare jarchange expected-before "1回目: jar 無し"
call :run jarchange config-after.properties "2回目: jar 追加"
call :expectlog jarchange "\u4f9d\u5b58jar\u306e\u5909\u66f4\u306b\u3088\u308b\u518d\u89e3\u6790=" "2回目: jar 追加で影響ファイルを再解析"
call :expectlog jarchange "\u518d\u5229\u7528=[1-9]" "2回目: 他のファイルはキャッシュを再利用"
call :compare jarchange expected-after "2回目: jar 追加"
call :run jarchange config-before.properties "3回目: jar 削除"
call :expectlog jarchange "\u4f9d\u5b58jar\u306e\u5909\u66f4\u306b\u3088\u308b\u518d\u89e3\u6790=" "3回目: jar 削除で影響ファイルを再解析"
call :compare jarchange expected-before "3回目: jar 削除"

if "%FAIL%"=="0" (echo PASS & exit /b 0) else (echo FAIL & exit /b 1)

:reset
if exist "%~1\.cache" rmdir /s /q "%~1\.cache"
if exist "%~1\output" rmdir /s /q "%~1\output"
if exist "%~1\run.log" del /q "%~1\run.log"
exit /b 0

:run
rem %1=case  %2=設定ファイル  %3=ラベル。実行ログは case\run.log に追記
%JCHE% "%~1\%~2" >> "%~1\run.log" 2>&1
if errorlevel 1 (echo   実行に失敗しました（%~3）。%~1\run.log を確認してください & set "FAIL=1")
exit /b 0

:expectlog
rem 直近の「ソース解析:」行に %2（正規表現）が含まれることを確認する
powershell -NoProfile -Command "$l=(Select-String -Path '%~1\run.log' -Encoding UTF8 -Pattern '\u30bd\u30fc\u30b9\u89e3\u6790:' | Select-Object -Last 1).Line; if ($l -match '%~2') { Write-Host '  OK   %~1 ログ (%~3)' } else { Write-Host ('  DIFF %~1 ログに %~2 がありません (%~3): ' + $l); exit 1 }"
if errorlevel 1 set "FAIL=1"
exit /b 0

:compare
rem %1=case  %2=期待出力のフォルダ  %3=ラベル。改行コード（CRLF/LF）の違いは無視して比較する
for %%F in (call-hierarchy.csv methods.csv) do (
    powershell -NoProfile -Command "$e=(Get-Content -Raw -Encoding UTF8 '%~1\%~2\%%F') -replace \"`r\",''; $o=(Get-Content -Raw -Encoding UTF8 '%~1\output\%%F') -replace \"`r\",''; if ($e -eq $o) { Write-Host '  OK   %~1/%%F (%~3)' } else { Write-Host '  DIFF %~1/%%F (%~3)'; exit 1 }"
    if errorlevel 1 set "FAIL=1"
)
exit /b 0
