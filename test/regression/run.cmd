@echo off
rem 回帰テスト（Windows）。test\demo を解析し、出力 CSV を expected*\ と比較する。
rem Git Bash がある場合は  bash test/regression/run.sh  でも実行できる。
rem ケースの説明は run.sh の先頭コメントを参照。実行ログは <case>\run-<回数>.log に残す。
rem 期待出力を更新するときは、差分を確認したうえで output\ を expected*\ にコピーする。
setlocal
cd /d "%~dp0"
set "ROOT=%~dp0..\.."
set "JCHE=call "%ROOT%\jbangw\jbang.cmd" run "%ROOT%\src\CallHierarchyExporter.java""
set "FAIL=0"

for %%C in (whole entry) do call :normalcase "%%C"

rem ビルドファイル（pom.xml / build.gradle）とローカルリポジトリ（samples\localrepo）から依存 jar を集めるケース。
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

if "%FAIL%"=="0" (echo PASS & exit /b 0) else (echo FAIL & exit /b 1)

:normalcase
rem %1=case。同じ設定で 2 回実行する（1 回目はキャッシュ無し、2 回目はキャッシュを再利用する経路）
echo == %~1 ==
call :reset "%~1"
call :run "%~1" config.properties 1 "1回目"
call :compare "%~1" expected "1回目: キャッシュ無し"
call :run "%~1" config.properties 2 "2回目"
call :expectlog "%~1" 2 "^[^=]*=[1-9]" "2回目: キャッシュを再利用"
call :compare "%~1" expected "2回目: キャッシュ再利用"
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
for %%F in (call-hierarchy.csv methods.csv) do (
    powershell -NoProfile -Command "$e=(Get-Content -Raw -Encoding UTF8 '%~1\%~2\%%F') -replace \"`r\",''; $o=(Get-Content -Raw -Encoding UTF8 '%~1\output\%%F') -replace \"`r\",''; if ($e -eq $o) { Write-Host '  OK   %~1/%%F (%~3)' } else { Write-Host '  DIFF %~1/%%F (%~3)'; $d=Compare-Object ($e -split \"`n\") ($o -split \"`n\"); if ($d) { $d | Select-Object -First 20 | ForEach-Object { Write-Host ('       ' + $_.SideIndicator + ' ' + $_.InputObject) } } else { Write-Host '       (行の集合は同じ。並び順だけが違う)' }; exit 1 }"
    if errorlevel 1 set "FAIL=1"
)
exit /b 0
