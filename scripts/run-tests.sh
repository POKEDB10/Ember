#!/usr/bin/env sh
set -eu
"$(dirname "$0")/build.sh"
cd "$(dirname "$0")/.."
mkdir -p build/test-classes
find src/test/java -type f -name '*.java' -print > build/test-sources.txt
javac --release 17 -encoding UTF-8 -Xlint:all,-serial -Werror -cp build/classes -d build/test-classes @build/test-sources.txt
java -cp 'build/classes:build/test-classes' edu.ember.test.EmberTestSuite
