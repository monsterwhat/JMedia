@echo off
setlocal enabledelayedexpansion

set GRAALVM_HOME=C:\Program Files\GraalVM\graalvm-community-openjdk-25.0.1+8.1
set JAVA_HOME=C:\Program Files\GraalVM\graalvm-community-openjdk-25.0.1+8.1
set PATH=C:\Program Files\GraalVM\graalvm-community-openjdk-25.0.1+8.1\bin;C:\ProgramData\chocolatey\lib\maven\apache-maven-3.9.11\bin;%PATH%

:: ==========================================
:: JMedia Native Executable Builder
:: ==========================================
echo.
echo ==========================================
echo   JMedia - Native Executable Build
echo ==========================================
echo.

:: --- Validate GraalVM ---
echo [1/4] Checking GraalVM installation...
if not exist "%GRAALVM_HOME%" (
    echo [ERROR] GraalVM not found at: %GRAALVM_HOME%
    echo.
    echo Please update the GRAALVM_HOME path at the top of this script.
    pause
    exit /b 1
)
echo   GraalVM: %GRAALVM_HOME%
"%JAVA_HOME%\bin\java" -version 2>&1 | findstr "GraalVM" >nul
if %ERRORLEVEL% neq 0 (
    echo [WARNING] The Java runtime at GRAALVM_HOME does not appear to be GraalVM.
    echo   Native builds require a GraalVM distribution.
)
echo.

:: --- Check native-image tool ---
echo [2/4] Checking native-image tool...
where native-image >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [ERROR] native-image tool not found on PATH!
    echo.
    echo   The 'native-image' tool must be installed in your GraalVM.
    echo   To install it, run:
    echo.
    echo     "%GRAALVM_HOME%\bin\gu.cmd" install native-image
    echo.
    echo   Then re-run this script.
    pause
    exit /b 1
)
echo   native-image tool found.
echo.

:: --- Run Maven native build ---
echo [3/4] Building native executable (this may take several minutes)...
echo.
echo   Note: Native compilation is memory-intensive.
echo   If the build fails with OutOfMemoryError, edit pom.xml
echo   and increase -J-Xmx in the native profile.
echo.

:: Record start time
set _start=%time%

mvn -Pnative clean package -DskipTests
set BUILD_STATUS=%ERRORLEVEL%

:: Calculate elapsed time
set _end=%time%
echo.

:: --- Report result ---
echo [4/4] Build result
echo.
if %BUILD_STATUS% equ 0 (
    echo ==========================================
    echo   BUILD SUCCESSFUL
    echo ==========================================
    echo.
    :: Find the native executable
    set "EXE="
    for /r "target" %%f in (*.exe) do (
        if not defined EXE set "EXE=%%f"
    )
    if defined EXE (
        for %%f in ("%EXE%") do (
            echo   Executable: %EXE%
            echo   Size: %%~zf bytes
        )
        echo.
        echo   To run:
        echo     "%EXE%"
        echo.
        echo   The application will be available at:
        echo     http://localhost:8080/
    ) else (
        echo   [WARNING] No .exe found in target/. Check the build output above.
    )
) else (
    echo ==========================================
    echo   BUILD FAILED (exit code: %BUILD_STATUS%)
    echo ==========================================
    echo.
    echo   Common issues:
    echo   - Out of memory: Increase -J-Xmx in pom.xml native profile
    echo   - Missing dependencies: Check your internet connection
    echo   - GraalVM version mismatch: Ensure GraalVM for JDK 25 is installed
    echo   - native-image tool not installed: Run "gu install native-image"
    echo.
)

echo.
pause