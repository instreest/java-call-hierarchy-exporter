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
rem   code page 65001 (which a PowerShell host may already be using) that offset
rem   drifts by one position per multi-byte character. Once the drift exceeds a
rem   line, cmd.exe resumes in the middle of a line and executes the tail of a
rem   "rem" comment as a command.
rem
rem   This script deliberately does NOT run "chcp". On Japanese/Chinese/Korean
rem   Windows, switching the console code page between a DBCS page (932 etc.)
rem   and 65001 makes conhost reset the screen buffer, which wipes the log the
rem   tool just printed. The Japanese log stays readable because the tool does
rem   not force its stdout encoding either: JDK 19+ writes System.out in the
rem   console own encoding. See docs/QA-build.md (Q6, Q11).
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
if not defined JBANG_CACHE_DIR set "JBANG_CACHE_DIR=%JBANG_DIR%\cache"
if not defined JBANG_JDK_VENDOR set "JBANG_JDK_VENDOR=temurin"

rem Architecture for the JDK download. Windows on ARM is a real target now
rem (Copilot+ PCs); fetching an x64 JDK there would run under emulation.
set "JBANGW_ARCH=x64"
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "JBANGW_ARCH=aarch64"
if /i "%PROCESSOR_ARCHITEW6432%"=="ARM64" set "JBANGW_ARCH=aarch64"
rem Keep jbang's own jar under JBANG_DIR too, so pointing JBANG_DIR at a shared
rem cache does not leave a second copy inside the project.
set "JBANG_JAR=%JBANG_DIR%\jbang-cli-%JBANG_VERSION%-all.jar"
set "JDK_DIR=%JBANG_CACHE_DIR%\jdks\%JAVA_VERSION%"
set "ERR=0"

rem Tell jbang which shell will execute the command line it prints. jbang escapes
rem its output per shell (bash uses single quotes, cmd uses carets); without this
rem it defaults to bash, and cmd.exe/java.exe cannot parse that - the classpath
rem arrives wrapped in single quotes and the main class is not found.
set "JBANG_RUNTIME_SHELL=cmd"
set "JBANG_LAUNCH_CMD=%~f0"

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
rem
rem Delayed expansion must be OFF from here on: the line jbang prints is escaped
rem for cmd (carets, quotes) and any "!" in it would be eaten. The line is read
rem into a variable and executed directly, rather than being called as a .cmd
rem file, so it goes through exactly one round of cmd parsing - which is what
rem the escaping is written for. jbang keeps the line within the Windows command
rem line limit itself (it switches to an @argfile) now that it knows the shell.
setlocal disabledelayedexpansion
set "JBANG_OUT=%TEMP%\jbangw-%RANDOM%%RANDOM%.tmp"
"%JAVA_EXE%" %JBANG_JAVA_OPTIONS% -jar "%JBANG_JAR%" %* > "%JBANG_OUT%" || goto :capture_failed
set "ERR=%ERRORLEVEL%"
goto :captured

:capture_failed
rem A non-zero exit is normal here (255 is the run protocol), but an exit code
rem of 0 on this branch means the redirect itself failed - e.g. TEMP is not
rem writable. Do not let that be reported as success.
set "ERR=%ERRORLEVEL%"
if "%ERR%"=="0" set "ERR=1"

:captured
if not "%ERR%"=="255" goto :show_output

set "OUTPUT="
for /f "usebackq delims=" %%A in ("%JBANG_OUT%") do (
    set "OUTPUT=%%A"
    goto :run_output
)
:run_output
del /f /q "%JBANG_OUT%" >nul 2>&1
%OUTPUT%
endlocal & exit /b %ERRORLEVEL%

:show_output
type "%JBANG_OUT%"
del /f /q "%JBANG_OUT%" >nul 2>&1
endlocal & exit /b %ERR%

rem ---------------------------------------------------------------------------
:install_jdk
rem foojay's Disco API returns a redirect to the matching JDK archive. The URL
rem is built the same way the upstream jbang script builds it.
echo [jbangw] JDK %JAVA_VERSION% not found - downloading it. This takes a few minutes. 1>&2
set "JDK_URL=https://api.foojay.io/disco/v3.0/directuris?distro=%JBANG_JDK_VENDOR%&javafx_bundled=false&libc_type=c_std_lib&archive_type=zip&operating_system=windows&package_type=jdk&version=%JAVA_VERSION%&architecture=%JBANGW_ARCH%&latest=available"
powershell -NoProfile -ExecutionPolicy Bypass -NonInteractive -Command ^
    "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue';" ^
    "try {" ^
    "  $tmp = Join-Path $env:JBANG_CACHE_DIR 'bootstrap-jdk.zip';" ^
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
exit /b 1
