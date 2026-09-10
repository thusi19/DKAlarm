#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
java -m jdk.compiler/com.sun.tools.javac.Main -d "$OUT" app/src/main/java/com/dkalarm/app/core/*.java tests/CoreTests.java
java -cp "$OUT" CoreTests
