@echo off
setlocal
call "%~dp0build.bat"
if errorlevel 1 exit /b %errorlevel%
cd /d "%~dp0\.."
java -cp build\classes edu.ember.EmberScaffold
