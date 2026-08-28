#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT=${TMPDIR:-/tmp}/headroom-core-selftest
rm -rf "$OUT"
mkdir -p "$OUT/classes"

find "$ROOT/src/main/java/io/github/limmeswe/headroom/core" -name '*.java' -print \
  | sort > "$OUT/sources.txt"

javac --release 21 -Xlint:all -Werror -d "$OUT/classes" @"$OUT/sources.txt" \
  "$ROOT/tools/selftest/CoreSelfTest.java"
java -ea -cp "$OUT/classes" CoreSelfTest
