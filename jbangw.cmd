@echo off
rem ---------------------------------------------------------------------------
rem jbangw.cmd - JBang wrapper for Windows (same role as the POSIX "jbangw").
rem
rem   1. Fetch jbang itself (jbang-cli-<ver>-all.jar) from Maven Central if
rem      missing, and verify its SHA-256 before using it.
rem      Override the URL with JBANGW_JAR_URL for a corporate mirror.
rem   2. Fetch a JDK if none is installed - running jbang itself needs a JDK.
rem   3. Pin the folders jbang uses to .jbang\ inside the project.
rem   4. Bridge the jbang run protocol (exit code 255 means "stdout holds the
rem      command line to execute").
rem
rem   IMPORTANT: this file must stay pure ASCII - no Japanese, no non-ASCII at
rem   all. cmd.exe resumes reading a batch file from a stored offset, and under
rem   code page 65001 (which "chcp 65001" below switches to, and which a
rem   PowerShell host may already be using) that offset drifts by one position
rem   per multi-byte character. Once the drift exceeds a line, cmd.exe resumes
rem   in the middle of a line and executes the tail of a "rem" comment as a
rem   command. The Japanese commentary for this file lives in
rem   docs/QA-build.md (Q6, Q11).
rem ---------------------------------------------------------------------------
setlocal enabledelayedexpansion

set "APP_HOME=%~dp0"
rem %~dp0 already ends with a backslash.
set "JBANG_VERSION=0.132.1"
set "JBANG_JAR_SHA256=f977075849cff866f45b27997f5671ab8a336a5dd3cf9e702b3479128338f3a7"

rem JDK version fetched when none is found. Keep it in sync with //JAVA in the
rem source. Upstream jbang defaults to 17, but that would install two JDKs
rem (17 to boot, 25 for //JAVA 25), so this wrapper uses 25 for both.
if not defined JBANG_DEFAULT_JAVA_VERSION (set "JAVA_VERSION=25") else (set "JAVA_VERSION=%JBANG_DEFAULT_JAVA_VERSION%")

if not defined JBANGW_JAR_URL set "JBANGW_JAR_URL=https://repo1.maven.org/maven2/dev/jbang/jbang-cli/%JBANG_VERSION%/jbang-cli-%JBANG_VERSION%-all.jar"
if not defined JBANG_DIR set "JBANG_DIR=%APP_HOME%.jbang"
if not defined JBANG_REPO set "JBANG_REPO=%JBANG_DIR%\repository"
if not defined JBANG_JDK_VENDOR set "JBANG_JDK_VENDOR=temurin"
rem Keep jbang's own jar under JBANG_DIR too, so pointing JBANG_DIR at a shared
rem cache does not leave a second copy inside the project.
set "JBANG_JAR=%JBANG_DIR%\jbang-cli-%JBANG_VERSION%-all.jar"
set "JDK_DIR=%JBANG_DIR%\cache\jdks\%JAVA_VERSION%"
set "ERR=0"

rem Switch the console to UTF-8 so the tool's Japanese log renders (the source
rem sets -Dstdout.encoding=UTF-8 via //JAVA_OPTIONS). The original code page is
rem restored at :done - chcp is a console attribute, so setlocal does not undo
rem it and the user's terminal would otherwise stay changed.
set "OLD_CP="
for /f "tokens=2 delims=:" %%A in ('chcp') do for /f "tokens=1" %%B in ("%%A") do set "OLD_CP=%%B"
chcp 65001 >nul

rem --- Pick the JDK that will run jbang (jbang compiles, so javac is required)
rem Label-based flow on purpose: nesting "call" inside parenthesised blocks is
rem a well-known way to break batch parsing.
set "JAVA_EXE="
if not defined JAVA_HOME goto :try_path
if exist "%JAVA_HOME%\bin\javac.exe" (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
    goto :have_java
)
echo [jbangw] JAVA_HOME is set but has no bin\javac.exe - it is not a JDK. 1>&2

:try_path
where javac >nul 2>&1
if not errorlevel 1 (
    set "JAVA_EXE=java.exe"
    goto :have_java
)
if exist "%JBANG_DIR%\currentjdk\bin\javac.exe" (
    set "JAVA_HOME=%JBANG_DIR%\currentjdk"
    set "JAVA_EXE=%JBANG_DIR%\currentjdk\bin\java.exe"
    goto :have_java
)
set "JAVA_HOME=%JDK_DIR%"
set "JAVA_EXE=%JDK_DIR%\bin\java.exe"
if exist "%JDK_DIR%\bin\javac.exe" goto :have_java
call :install_jdk
if errorlevel 1 goto :fail

:have_java

rem --- Fetch jbang.jar if missing, verifying SHA-256 before use
if exist "%JBANG_JAR%" goto :have_jbang
rem Quote the interpolated values: an "&" in a mirror URL or a folder name
rem would otherwise be parsed as a command separator by echo.
echo [jbangw] Downloading jbang %JBANG_VERSION% from "%JBANGW_JAR_URL%" 1>&2
if not exist "%JBANG_DIR%" mkdir "%JBANG_DIR%"
powershell -NoProfile -ExecutionPolicy Bypass -NonInteractive -Command ^
    "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue';" ^
    "try {" ^
    "  $part = $env:JBANG_JAR + '.part';" ^
    "  Invoke-WebRequest -Uri $env:JBANGW_JAR_URL -OutFile $part;" ^
    "  $h = (Get-FileHash -Algorithm SHA256 $part).Hash.ToLower();" ^
    "  if ($h -ne $env:JBANG_JAR_SHA256) { Remove-Item $part -Force; throw ('SHA-256 mismatch: ' + $h) };" ^
    "  Move-Item -Force $part $env:JBANG_JAR" ^
    "} catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }"
if errorlevel 1 (
    echo [jbangw] Failed to download jbang. Download "%JBANGW_JAR_URL%" manually 1>&2
    echo [jbangw] and place it at "%JBANG_JAR%" 1>&2
    goto :fail
)

:have_jbang

rem --- Run protocol: on exit code 255, stdout holds the command line to run.
rem The command line embeds the whole classpath and can exceed the 8191-char
rem environment variable limit, so it is captured to a temporary cmd file
rem instead of a variable.
set "JBANG_OUT=%TEMP%\jbangw-%RANDOM%%RANDOM%.cmd"
"%JAVA_EXE%" -jar "%JBANG_JAR%" %* > "%JBANG_OUT%"
set "ERR=%ERRORLEVEL%"
if "%ERR%"=="255" (
    call "%JBANG_OUT%"
    set "ERR=!ERRORLEVEL!"
) else (
    type "%JBANG_OUT%"
)
del "%JBANG_OUT%" >nul 2>&1
goto :done

rem ---------------------------------------------------------------------------
:install_jdk
rem foojay's Disco API returns a redirect to the matching JDK archive. The URL
rem is built the same way the upstream jbang script builds it.
echo [jbangw] JDK %JAVA_VERSION% not found - downloading it. This takes a few minutes. 1>&2
set "JDK_URL=https://api.foojay.io/disco/v3.0/directuris?distro=%JBANG_JDK_VENDOR%&javafx_bundled=false&libc_type=c_std_lib&archive_type=zip&operating_system=windows&package_type=jdk&version=%JAVA_VERSION%&architecture=x64&latest=available"
powershell -NoProfile -ExecutionPolicy Bypass -NonInteractive -Command ^
    "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue';" ^
    "try {" ^
    "  $tmp = Join-Path $env:JBANG_DIR 'cache\bootstrap-jdk.zip';" ^
    "  $null = New-Item -ItemType Directory -Force -Path (Split-Path $tmp);" ^
    "  Invoke-WebRequest -Uri $env:JDK_URL -OutFile $tmp;" ^
    "  $t = $env:JDK_DIR + '.tmp';" ^
    "  Remove-Item -LiteralPath $t -Recurse -Force -ErrorAction Ignore;" ^
    "  Expand-Archive -Path $tmp -DestinationPath $t;" ^
    "  foreach ($d in Get-ChildItem -Directory -Path $t) { Move-Item -Path ($d.FullName + '\*') -Destination $t -Force };" ^
    "  Remove-Item $tmp -Force;" ^
    "  if (-not (Test-Path (Join-Path $t 'bin\javac.exe'))) { throw 'failed to unpack the JDK' };" ^
    "  Remove-Item -LiteralPath $env:JDK_DIR -Recurse -Force -ErrorAction Ignore;" ^
    "  Move-Item $t $env:JDK_DIR" ^
    "} catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }"
if errorlevel 1 (
    echo [jbangw] Failed to download the JDK from api.foojay.io. 1>&2
    echo [jbangw] If a proxy blocks it, install JDK %JAVA_VERSION% yourself and set 1>&2
    echo [jbangw] JAVA_HOME, or unpack it into "%JDK_DIR%" 1>&2
    exit /b 1
)
exit /b 0

rem ---------------------------------------------------------------------------
:fail
set "ERR=1"

:done
if defined OLD_CP chcp %OLD_CP% >nul
exit /b %ERR%
