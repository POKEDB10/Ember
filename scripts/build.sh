#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/classes
find src/main/java -type f -name '*.java' -print > build/sources.txt
javac --release 17 -encoding UTF-8 -Xlint:all,-serial -Werror -d build/classes @build/sources.txt
cp -R src/main/resources/. build/classes/
printf '%s\n' 'Build passed.'
