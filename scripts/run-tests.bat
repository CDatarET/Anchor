@echo off
rem Compile and run the JUnit 5 tests without Maven.
rem Set JUNIT_CONSOLE to the path of junit-platform-console-standalone-<version>.jar first, e.g.
rem   set JUNIT_CONSOLE=C:\libs\junit-platform-console-standalone-1.10.2.jar
cd /d "%~dp0.."
if "%JUNIT_CONSOLE%"=="" (echo Set JUNIT_CONSOLE to the junit-platform-console-standalone jar & exit /b 2)
if exist out rmdir /s /q out
mkdir out\main
mkdir out\test
dir /s /b src\main\java\*.java > out\main-sources.txt
dir /s /b src\test\java\*.java > out\test-sources.txt
javac -d out\main @out\main-sources.txt || exit /b 1
javac -d out\test -cp "out\main;%JUNIT_CONSOLE%" @out\test-sources.txt || exit /b 1
java -jar "%JUNIT_CONSOLE%" execute -cp out\main;out\test --scan-classpath out\test --disable-banner --details=summary
