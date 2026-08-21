@echo off
rem ---------------------------------------------------------------
rem 実行例:
rem   run.bat config\config.properties
rem
rem ヒープが足りない場合は JAVA_OPTS を設定してください。
rem   set JAVA_OPTS=-Xmx4g
rem ---------------------------------------------------------------
setlocal
set DIR=%~dp0
if "%~1"=="" (
  echo 使い方: %0 ^<config.properties のパス^>
  exit /b 1
)
if "%JAVA_OPTS%"=="" set JAVA_OPTS=-Xmx2g
java %JAVA_OPTS% -Dfile.encoding=UTF-8 ^
  -cp "%DIR%*;%DIR%lib\*" ^
  jp.co.example.callhierarchy.CallHierarchyExporter %1
endlocal
