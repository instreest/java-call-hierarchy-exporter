@echo off
rem 回帰テスト（Windows）。samples\demo を解析し、出力 CSV を expected\ と比較する。
rem Git Bash がある場合は  bash test/regression/run.sh  でも実行できる。
rem 期待出力を更新するときは、差分を確認したうえで output\ を expected\ にコピーする。
setlocal
cd /d "%~dp0"
set "ROOT=%~dp0..\.."
set "FAIL=0"
for %%C in (whole entry) do (
    echo == %%C ==
    if exist "%%C\.cache" rmdir /s /q "%%C\.cache"
    if exist "%%C\output" rmdir /s /q "%%C\output"
    call "%ROOT%\jbang.cmd" run "%ROOT%\src\CallHierarchyExporter.java" "%%C\config.properties" > "%%C\run.log" 2>&1
    if errorlevel 1 (echo   実行に失敗しました。%%C\run.log を確認してください & set "FAIL=1")
    call :compare "%%C" "1回目"
    call "%ROOT%\jbang.cmd" run "%ROOT%\src\CallHierarchyExporter.java" "%%C\config.properties" >> "%%C\run.log" 2>&1
    if errorlevel 1 (echo   実行に失敗しました。%%C\run.log を確認してください & set "FAIL=1")
    call :compare "%%C" "2回目"
)
if "%FAIL%"=="0" (echo PASS & exit /b 0) else (echo FAIL & exit /b 1)

:compare
rem 改行コード（CRLF/LF）の違いは無視して比較する
for %%F in (call-hierarchy.csv methods.csv) do (
    powershell -NoProfile -Command "$e=(Get-Content -Raw '%~1\expected\%%F') -replace \"`r\",''; $o=(Get-Content -Raw '%~1\output\%%F') -replace \"`r\",''; if ($e -eq $o) { Write-Host '  OK   %~1/%%F (%~2)' } else { Write-Host '  DIFF %~1/%%F (%~2)'; exit 1 }"
    if errorlevel 1 set "FAIL=1"
)
exit /b 0
