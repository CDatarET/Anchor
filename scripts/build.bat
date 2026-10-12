@echo off
rem Compile the project with plain javac (no Maven needed). Output: out\main
cd /d "%~dp0.."
if exist out\main rmdir /s /q out\main
mkdir out\main

javac -d out\main src\main\java\reliableudp\*.java src\main\java\reliableudp\metrics\*.java src\main\java\reliableudp\protocol\*.java src\main\java\reliableudp\simulation\*.java src\main\java\reliableudp\transport\*.java src\main\java\reliableudp\util\*.java
if errorlevel 1 exit /b 1

echo Compiled successfully to out\main
