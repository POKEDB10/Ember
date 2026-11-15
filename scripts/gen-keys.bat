@echo off
setlocal
cd /d "%~dp0\.."
if "%EMBER_KEYSTORE_PASS%"=="" (
  echo Set EMBER_KEYSTORE_PASS before generating local TLS keys. 1>&2
  exit /b 2
)
if not exist config mkdir config
keytool -genkeypair -alias ember-server -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=localhost, OU=Ember, O=Ember, L=Local, ST=Local, C=AE" -ext "SAN=dns:localhost,ip:127.0.0.1" -storetype PKCS12 -keystore config\ember-server.p12 -storepass "%EMBER_KEYSTORE_PASS%" -keypass "%EMBER_KEYSTORE_PASS%"
if errorlevel 1 exit /b %errorlevel%
keytool -exportcert -alias ember-server -keystore config\ember-server.p12 -storepass "%EMBER_KEYSTORE_PASS%" -file config\ember-server.cer
if errorlevel 1 exit /b %errorlevel%
keytool -importcert -noprompt -alias ember-server -file config\ember-server.cer -storetype PKCS12 -keystore config\ember-client-trust.p12 -storepass "%EMBER_KEYSTORE_PASS%"
