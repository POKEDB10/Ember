@echo off
setlocal
cd /d "%~dp0\.."
if not exist config mkdir config
if not defined EMBER_KEYSTORE_PASS if exist config\secrets.properties for /f "usebackq tokens=1,* delims==" %%A in ("config\secrets.properties") do if "%%A"=="EMBER_KEYSTORE_PASS" set "EMBER_KEYSTORE_PASS=%%B"
if not defined EMBER_KEYSTORE_PASS for /f %%P in ('powershell -NoProfile -Command "$r=[Security.Cryptography.RandomNumberGenerator]::Create();$b=New-Object byte[] 32;$r.GetBytes($b);[Convert]::ToBase64String($b)"') do set "EMBER_KEYSTORE_PASS=%%P"
if not defined EMBER_KEYSTORE_PASS exit /b 2
if not exist config\secrets.properties > config\secrets.properties echo EMBER_KEYSTORE_PASS=%EMBER_KEYSTORE_PASS%
set "EMBER_SAN=dns:localhost,ip:127.0.0.1"
if defined EMBER_HOSTS set "EMBER_SAN=%EMBER_SAN%,%EMBER_HOSTS%"
keytool -genkeypair -alias ember-server -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=localhost, OU=Ember, O=Ember, L=Local, ST=Local, C=AE" -ext "SAN=%EMBER_SAN%" -storetype PKCS12 -keystore config\ember-server.p12 -storepass "%EMBER_KEYSTORE_PASS%" -keypass "%EMBER_KEYSTORE_PASS%"
if errorlevel 1 exit /b %errorlevel%
keytool -exportcert -alias ember-server -keystore config\ember-server.p12 -storepass "%EMBER_KEYSTORE_PASS%" -file config\ember-server.cer
if errorlevel 1 exit /b %errorlevel%
if not exist build mkdir build
set "EMBER_TRUSTSTORE_TEMP=build\ember-client-trust-%RANDOM%.p12"
keytool -importcert -noprompt -alias ember-server -file config\ember-server.cer -storetype PKCS12 -keystore "%EMBER_TRUSTSTORE_TEMP%" -storepass "%EMBER_KEYSTORE_PASS%"
if errorlevel 1 exit /b %errorlevel%
copy /y "%EMBER_TRUSTSTORE_TEMP%" config\ember-client-trust.p12 > nul
if errorlevel 1 exit /b %errorlevel%
echo Client truststore refreshed from the generated server certificate.
