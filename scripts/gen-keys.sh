#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
: "${EMBER_KEYSTORE_PASS:?Set EMBER_KEYSTORE_PASS before generating local TLS keys}"
mkdir -p config
keytool -genkeypair -alias ember-server -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=localhost, OU=Ember, O=Ember, L=Local, ST=Local, C=AE' -ext 'SAN=dns:localhost,ip:127.0.0.1' -storetype PKCS12 -keystore config/ember-server.p12 -storepass "$EMBER_KEYSTORE_PASS" -keypass "$EMBER_KEYSTORE_PASS"
keytool -exportcert -alias ember-server -keystore config/ember-server.p12 -storepass "$EMBER_KEYSTORE_PASS" -file config/ember-server.cer
keytool -importcert -noprompt -alias ember-server -file config/ember-server.cer -storetype PKCS12 -keystore config/ember-client-trust.p12 -storepass "$EMBER_KEYSTORE_PASS"
