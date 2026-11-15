#!/usr/bin/env sh
set -eu
"$(dirname "$0")/build.sh"
cd "$(dirname "$0")/.."
exec java -cp build/classes edu.ember.server.EmberServer "$@"
