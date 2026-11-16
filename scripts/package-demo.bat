@echo off
setlocal
cd /d "%~dp0\.."
call scripts\build.bat
if errorlevel 1 exit /b %errorlevel%
if not exist build\jars mkdir build\jars
if not exist build\phase8\package mkdir build\phase8\package
if exist build\phase8\package\EmberClient rmdir /s /q build\phase8\package\EmberClient
if exist build\phase8\package\EmberServer rmdir /s /q build\phase8\package\EmberServer
jar --create --file build\jars\ember-client.jar --main-class edu.ember.client.EmberClientApp -C build\classes .
if errorlevel 1 exit /b %errorlevel%
jar --create --file build\jars\ember-server.jar --main-class edu.ember.server.EmberServer -C build\classes .
if errorlevel 1 exit /b %errorlevel%
jpackage --type app-image --input build\jars --dest build\phase8\package --name EmberClient --main-jar ember-client.jar --main-class edu.ember.client.EmberClientApp
if errorlevel 1 exit /b %errorlevel%
jpackage --type app-image --input build\jars --dest build\phase8\package --name EmberServer --main-jar ember-server.jar --main-class edu.ember.server.EmberServer
if errorlevel 1 exit /b %errorlevel%
echo Created runnable JARs and app-images under build\phase8\package. App-images bundle a runtime; app-image does not require WiX.
