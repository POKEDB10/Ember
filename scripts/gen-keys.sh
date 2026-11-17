#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p config
if [ -z "${EMBER_KEYSTORE_PASS:-}" ] && [ -f config/secrets.properties ]; then
  EMBER_KEYSTORE_PASS=$(sed -n 's/^EMBER_KEYSTORE_PASS=//p' config/secrets.properties | head -n 1)
fi
if [ -z "${EMBER_KEYSTORE_PASS:-}" ]; then
  EMBER_KEYSTORE_PASS=$(dd if=/dev/urandom bs=32 count=1 2>/dev/null | base64 | tr -d '\n')
  printf 'EMBER_KEYSTORE_PASS=%s\n' "$EMBER_KEYSTORE_PASS" > config/secrets.properties
fi
export EMBER_KEYSTORE_PASS
keytool -genkeypair -alias ember-server -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=localhost, OU=Ember, O=Ember, L=Local, ST=Local, C=AE' -ext "SAN=dns:localhost,ip:127.0.0.1${EMBER_HOSTS:+,$EMBER_HOSTS}" -storetype PKCS12 -keystore config/ember-server.p12 -storepass "$EMBER_KEYSTORE_PASS" -keypass "$EMBER_KEYSTORE_PASS"
keytool -exportcert -alias ember-server -keystore config/ember-server.p12 -storepass "$EMBER_KEYSTORE_PASS" -file config/ember-server.cer
mkdir -p build
trust_tmp="build/ember-client-trust-$$.p12"
keytool -importcert -noprompt -alias ember-server -file config/ember-server.cer -storetype PKCS12 -keystore "$trust_tmp" -storepass "$EMBER_KEYSTORE_PASS"
cp "$trust_tmp" config/ember-client-trust.p12
printf '%s\n' 'Client truststore refreshed from the generated server certificate.'
