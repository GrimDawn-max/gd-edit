@echo off
setlocal enabledelayedexpansion

REM gd-edit launcher for Windows. Requires Java 17 or newer; see README.txt.

set "MIN_JAVA=17"
set "DOWNLOAD_URL=https://adoptium.net/"
set "DIR=%~dp0"
set "JAR=%DIR%gd-edit-standalone.jar"

if not exist "%JAR%" (
    echo ERROR: could not find gd-edit-standalone.jar next to this script.
    echo Keep gd-edit.bat and the jar together in the same folder.
    pause
    exit /b 1
)

REM ------------------------------------------------------------- find java
REM JAVA_HOME first, then whatever is on PATH. Not pinned to a specific
REM release -- pinning is what breaks when someone upgrades their JDK.
set "JAVA_CMD="

if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
)

if defined JAVA_CMD (
    REM Make sure it actually runs; a stale JAVA_HOME is worse than none.
    "%JAVA_CMD%" -version >nul 2>&1
    if !errorlevel! neq 0 set "JAVA_CMD="
)

if not defined JAVA_CMD (
    where java >nul 2>&1
    if !errorlevel! equ 0 (
        java -version >nul 2>&1
        if !errorlevel! equ 0 set "JAVA_CMD=java"
    )
)

if not defined JAVA_CMD (
    echo.
    echo   gd-edit needs Java %MIN_JAVA% or newer, and none could be found.
    echo.
    echo   Install a free build of Java from:
    echo       %DOWNLOAD_URL%
    echo.
    echo   Then run this launcher again.
    echo.
    pause
    exit /b 1
)

REM ---------------------------------------------------------- version check
REM Parses both "1.8.0_452" and "17.0.18" / "21".
set "JAVA_VER="
for /f tokens^=3 %%v in ('"%JAVA_CMD%" -version 2^>^&1 ^| findstr /i "version"') do (
    if not defined JAVA_VER set "JAVA_VER=%%~v"
)

set "JAVA_MAJOR="
for /f "tokens=1,2 delims=." %%a in ("%JAVA_VER%") do (
    if "%%a"=="1" (set "JAVA_MAJOR=%%b") else (set "JAVA_MAJOR=%%a")
)

if defined JAVA_MAJOR (
    if !JAVA_MAJOR! lss %MIN_JAVA% (
        echo.
        echo   gd-edit needs Java %MIN_JAVA% or newer, but found Java %JAVA_VER%.
        echo.
        echo   Install a newer build from:
        echo       %DOWNLOAD_URL%
        echo.
        pause
        exit /b 1
    )
)

REM Run from the install folder so settings.edn and gd-edit.log are written
REM beside the app rather than into the caller's current directory.
cd /d "%DIR%"

"%JAVA_CMD%" -Xms128m -Djna.nosys=true --enable-native-access=ALL-UNNAMED -jar "%JAR%" %*

endlocal
