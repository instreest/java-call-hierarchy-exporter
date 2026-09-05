@echo off
rem 回帰テスト（Windows）。samples\demo を解析し、出力 CSV を expected*\ と比較する。
rem Git Bash がある場合は  bash test/regression/run.sh  でも実行できる。
rem ケースの説明は run.sh の先頭コメントを参照。実行ログは <case>\run-<回数>.log に残す。
rem 期待出力を更新するときは、差分を確認したうえで output\ を expected*\ にコピーする。
setlocal
cd /d "%~dp0"
set "ROOT=%~dp0..\.."
set "JCHE=call "%ROOT%\jbangw\jbang.cmd" run "%ROOT%\src\CallHierarchyExporter.java""
set "FAIL=0"

for %%C in (whole entry) do (
    echo == %%C ==
    call :reset "%%C"
    call :run "%%C" config.properties 1 "1回目"
    call :compare "%%C" expected "1回目: キャッシュ無し"
    call :run "%%C" config.properties 2 "2回目"
    call :expectlog "%%C" 2 "^[^=]*=[1-9]" "2回目: キャッシュを再利用"
    call :compare "%%C" expected "2回目: キャッシュ再利用"
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

if "%FAIL%"=="0" (echo PASS & exit /b 0) else (echo FAIL & exit /b 1)

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
powershell -NoProfile -Command "$l=(Select-String -Path '%~1\run-%~2.log' -Encoding Default -Pattern '=\d+.*=\d+.*=\d+\s*$' | Select-Object -First 1).Line; if ($l -match '%~3') { Write-Host '  OK   %~1 ログ (%~4)' } else { Write-Host ('  DIFF %~1 ログ (%~4): ' + $l); exit 1 }"
if errorlevel 1 set "FAIL=1"
exit /b 0

:compare
rem %1=case  %2=期待出力のフォルダ  %3=ラベル。CSV は UTF-8（BOM 付き）。改行コード（CRLF/LF）の違いは無視して比較する
for %%F in (call-hierarchy.csv methods.csv) do (
    powershell -NoProfile -Command "$e=(Get-Content -Raw -Encoding UTF8 '%~1\%~2\%%F') -replace \"`r\",''; $o=(Get-Content -Raw -Encoding UTF8 '%~1\output\%%F') -replace \"`r\",''; if ($e -eq $o) { Write-Host '  OK   %~1/%%F (%~3)' } else { Write-Host '  DIFF %~1/%%F (%~3)'; exit 1 }"
    if errorlevel 1 set "FAIL=1"
)
exit /b 0
