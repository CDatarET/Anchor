@echo off
title Anchor Client
cd /d "%~dp0"

echo ========================================================
echo    ANCHOR CLIENT (Reliable UDP)
echo ========================================================
echo.

if not exist "out\main\reliableudp\Client.class" (
    echo Compiling project first...
    call scripts\build.bat
    if errorlevel 1 (
        echo Compilation failed. Ensure Java 17+ is installed.
        pause
        exit /b 1
    )
    echo.
)

set /p SERVER_IP="Enter Server IP Address (default: localhost): "
if "%SERVER_IP%"=="" set SERVER_IP=localhost

set /p FILE_PATH="Enter text file path to send (default: sample.txt): "
if "%FILE_PATH%"=="" set FILE_PATH=sample.txt

echo.
echo Sending "%FILE_PATH%" to %SERVER_IP%:5000 ...
java -cp out\main reliableudp.Client %SERVER_IP% 5000 "%FILE_PATH%" --verbose
pause
