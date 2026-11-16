@echo off
setlocal
cd /d "%~dp0\.."
call scripts\build.bat
if errorlevel 1 exit /b %errorlevel%
if not exist config\ember-server.p12 call scripts\gen-keys.bat
if errorlevel 1 exit /b %errorlevel%
start "Ember demo server" /D "%CD%" java -cp build\classes edu.ember.server.EmberServer --port 7443 --demo-seed --admin-user alice
timeout /t 3 /nobreak > nul
start "Ember Alice" /D "%CD%" javaw -cp build\classes edu.ember.client.EmberClientApp --host localhost --port 7443 --profile alice
start "Ember Bob" /D "%CD%" javaw -cp build\classes edu.ember.client.EmberClientApp --host localhost --port 7443 --profile bob
start "Ember Charlie" /D "%CD%" javaw -cp build\classes edu.ember.client.EmberClientApp --host localhost --port 7443 --profile charlie
echo Demo server and three client windows launched. Demo passwords are in README.md.
