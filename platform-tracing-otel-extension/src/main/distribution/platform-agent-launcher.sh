#!/bin/sh
set -eu

distribution_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$distribution_dir"

test -r opentelemetry-javaagent.jar
test -r platform-tracing-extension.jar
test -r SHA256SUMS
sha256sum -c SHA256SUMS

exec java \
  -javaagent:"$distribution_dir/opentelemetry-javaagent.jar" \
  -Dotel.javaagent.extensions="$distribution_dir/platform-tracing-extension.jar" \
  "$@"
