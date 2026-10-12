@echo off
title Anchor Server (Port 5000)
cd /d "%~dp0"

echo ========================================================
echo    ANCHOR SERVER (Reliable UDP)
echo    Port: 5000
echo ========================================================
echo.

if not exist "out\main\reliableudp\Server.class" (
    echo Compiling project first...
    call scripts\build.bat
    if errorlevel 1 (
        echo Compilation failed. Ensure Java 17+ is installed.
        pause
        exit /b 1
    )
    echo.
)

echo Server is starting...
echo (Check 'ipconfig' to find your local IPv4 address if connecting from another PC)
echo.

java -cp out\main reliableudp.Server 5000 --verbose
pause
