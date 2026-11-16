#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
scripts/build.sh
if [ ! -f config/ember-server.p12 ]; then scripts/gen-keys.sh; fi
java -cp build/classes edu.ember.server.EmberServer --port 7443 --demo-seed --admin-user alice &
server_pid=$!
trap 'kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true' EXIT INT TERM
sleep 3
java -cp build/classes edu.ember.client.EmberClientApp --host localhost --port 7443 --profile alice &
alice_pid=$!
java -cp build/classes edu.ember.client.EmberClientApp --host localhost --port 7443 --profile bob &
bob_pid=$!
java -cp build/classes edu.ember.client.EmberClientApp --host localhost --port 7443 --profile charlie &
charlie_pid=$!
wait "$alice_pid"
wait "$bob_pid"
wait "$charlie_pid"
