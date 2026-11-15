@echo off
setlocal
call "%~dp0build.bat"
if errorlevel 1 exit /b %errorlevel%
cd /d "%~dp0\.."
if not exist build\test-classes mkdir build\test-classes
if not exist build mkdir build
dir /s /b src\test\java\*.java > build\test-sources.txt
javac --release 17 -encoding UTF-8 -Xlint:all,-serial -Werror -cp build\classes -d build\test-classes @build\test-sources.txt
if errorlevel 1 exit /b %errorlevel%
java -cp build\classes;build\test-classes edu.ember.test.EmberTestSuite
