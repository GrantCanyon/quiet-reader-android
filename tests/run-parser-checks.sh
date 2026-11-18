#!/usr/bin/env bash
set -euo pipefail
reader_dir="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d "${TMPDIR:-/tmp}/reader-parser-XXXXXX")"
trap 'rm -rf -- "$test_dir"' EXIT
javac_bin="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$javac_bin" -encoding UTF-8 -d "$test_dir" \
  "$reader_dir"/tests/support/android/text/*.java \
  "$reader_dir/tests/support/ru/grant/reader/BookRepository.java" \
  "$reader_dir/app/src/main/java/ru/grant/reader/BookTextParser.java" \
  "$reader_dir/app/src/main/java/ru/grant/reader/ReadingPosition.java" \
  "$reader_dir/tests/ParserRegression.java" \
  "$reader_dir/tests/StreamingFb2Regression.java" \
  "$reader_dir/tests/BookMemoryProbe.java"
"$java_bin" -Xmx128m -cp "$test_dir" ru.grant.reader.ParserRegression "$@"
"$java_bin" -Xmx128m -cp "$test_dir" ru.grant.reader.StreamingFb2Regression
