#!/usr/bin/env bash
# Compiles and runs the pipeline against fixtures/corpus-a.jsonl.
# Needs a JDK 21 and nothing else - no network, no database, no Gradle.
#
# The docstore/ package (MongoDB document store, Task 4) is excluded: it needs
# the Mongo driver, and the corpus pipeline (SelfCheck) does not touch it.
set -euo pipefail
cd "$(dirname "$0")"

echo "==> compiling"
rm -rf build/selfcheck && mkdir -p build/selfcheck
javac -d build/selfcheck $(find src/main/java -name '*.java' ! -path '*/docstore/*')

echo
echo "==> running"
java -cp build/selfcheck in.simplifymoney.ledgersync.SelfCheck "$@"
