#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
scripts/build.sh
mkdir -p build/jars build/phase8/package
rm -rf build/phase8/package/EmberClient build/phase8/package/EmberServer
jar --create --file build/jars/ember-client.jar --main-class edu.ember.client.EmberClientApp -C build/classes .
jar --create --file build/jars/ember-server.jar --main-class edu.ember.server.EmberServer -C build/classes .
jpackage --type app-image --input build/jars --dest build/phase8/package --name EmberClient --main-jar ember-client.jar --main-class edu.ember.client.EmberClientApp
jpackage --type app-image --input build/jars --dest build/phase8/package --name EmberServer --main-jar ember-server.jar --main-class edu.ember.server.EmberServer
printf '%s\n' 'Created runnable JARs and app-images under build/phase8/package.'
