@echo off
setlocal
cd /d "%~dp0\.."
if not exist build\classes mkdir build\classes
if not exist build mkdir build
dir /s /b src\main\java\*.java > build\sources.txt
javac --release 17 -encoding UTF-8 -Xlint:all,-serial -Werror -d build\classes @build\sources.txt
if errorlevel 1 exit /b %errorlevel%
echo Build passed.
