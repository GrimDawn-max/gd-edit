@echo off
setlocal enabledelayedexpansion

REM gd-edit launcher for Windows. Requires Java 17 or newer; see README.txt.

set "MIN_JAVA=17"
set "DOWNLOAD_URL=https://adoptium.net/"
REM Any vendor's build works. Temurin 21 is what gd-edit is tested against; see
REM README.txt for why a newer JDK is not automatically the better choice here.
set "RECOMMENDED=Temurin 21 recommended"
set "DIR=%~dp0"
set "JAR=%DIR%gd-edit-standalone.jar"

if not exist "%JAR%" (
    echo ERROR: could not find gd-edit-standalone.jar next to this script.
    echo Keep gd-edit.bat and the jar together in the same folder.
    pause
    exit /b 1
)

REM ------------------------------------------------------------- find java
REM Every candidate is version-checked before it is accepted, and a candidate
REM that is too old does not stop the search. This matters on Windows because
REM Oracle's java8path shim puts a Java 8 on PATH ahead of anything else, and
REM refusing to look past it would reject a machine that has a good JDK sitting
REM right there. Not pinned to a specific release -- pinning is what breaks
REM when someone upgrades their JDK.
set "JAVA_CMD="
set "FOUND_VER="
set "FOUND_AT="

if defined JAVA_HOME call :try_java "%JAVA_HOME%\bin\java.exe"

if not defined JAVA_CMD (
    for /f "delims=" %%p in ('where java 2^>nul') do (
        if not defined JAVA_CMD call :try_java "%%~p"
    )
)

REM Common install roots, newest directory first (dir /o-n sorts descending, so
REM jdk-21 is tried before jdk-17).
if not defined JAVA_CMD (
    for %%r in (
        "%ProgramFiles%\Eclipse Adoptium"
        "%ProgramFiles%\Java"
        "%ProgramFiles%\Microsoft"
        "%ProgramFiles%\Zulu"
        "%ProgramFiles%\Amazon Corretto"
        "%ProgramFiles(x86)%\Eclipse Adoptium"
        "%ProgramFiles(x86)%\Java"
    ) do (
        if not defined JAVA_CMD (
            if exist "%%~r\" (
                for /f "delims=" %%d in ('dir /b /ad /o-n "%%~r" 2^>nul') do (
                    if not defined JAVA_CMD call :try_java "%%~r\%%d\bin\java.exe"
                )
            )
        )
    )
)

if not defined JAVA_CMD (
    echo.
    if defined FOUND_VER (
        echo   gd-edit needs Java %MIN_JAVA% or newer, but the only Java found was
        echo   version !FOUND_VER! at:
        echo       !FOUND_AT!
        echo.
        echo   Install Java %MIN_JAVA% or newer -- any build will do, for example:
        echo       %DOWNLOAD_URL%  ^(%RECOMMENDED%^)
        echo.
        echo   If you already have one, set JAVA_HOME to point at it.
    ) else (
        echo   gd-edit needs Java %MIN_JAVA% or newer, and none could be found.
        echo.
        echo   Install Java %MIN_JAVA% or newer -- any build will do, for example:
        echo       %DOWNLOAD_URL%  ^(%RECOMMENDED%^)
        echo.
        echo   Then run this launcher again.
    )
    echo.
    pause
    exit /b 1
)

REM Run from the install folder so settings.edn and gd-edit.log are written
REM beside the app rather than into the caller's current directory.
cd /d "%DIR%"

REM --enable-native-access silences JNI warnings on newer JVMs, but an
REM unrecognised option is fatal, not ignored -- so ask this JVM whether it takes
REM the flag rather than inferring it from the version number.
set "NATIVE_ACCESS="
"%JAVA_CMD%" --enable-native-access=ALL-UNNAMED -version >nul 2>&1
if not errorlevel 1 set "NATIVE_ACCESS=--enable-native-access=ALL-UNNAMED"

"%JAVA_CMD%" -Xms128m -Djna.nosys=true %NATIVE_ACCESS% -jar "%JAR%" %*

endlocal
exit /b %errorlevel%

REM --------------------------------------------------------------------------
REM :try_java <path to java.exe>
REM Sets JAVA_CMD only if the candidate runs AND is new enough. Records the
REM first Java seen at any version so the failure message can name it.
:try_java
set "CAND=%~1"
if not exist "%CAND%" goto :eof
"%CAND%" -version >nul 2>&1
if errorlevel 1 goto :eof

REM Via a temp file rather than a pipe inside for/f: a quoted path with spaces
REM nested inside for/f quoting is where these scripts usually break.
set "VERFILE=%TEMP%\gd-edit-javaver.txt"
"%CAND%" -version > "%VERFILE%" 2>&1
set "V="
for /f tokens^=3 %%v in ('findstr /i "version" "%VERFILE%"') do (
    if not defined V set "V=%%~v"
)
del "%VERFILE%" >nul 2>&1
if not defined V goto :eof

if not defined FOUND_VER (
    set "FOUND_VER=%V%"
    set "FOUND_AT=%CAND%"
)

REM Handles both "1.8.0_451" (old scheme) and "17.0.18" / "21" (current).
set "MAJ="
for /f "tokens=1,2 delims=." %%a in ("%V%") do (
    if "%%a"=="1" (set "MAJ=%%b") else (set "MAJ=%%a")
)
if not defined MAJ goto :eof
if %MAJ% lss %MIN_JAVA% goto :eof

set "JAVA_CMD=%CAND%"
goto :eof
