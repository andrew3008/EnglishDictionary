#!/bin/sh
set -eu

distribution_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$distribution_dir"

exec java \
  -javaagent:"$distribution_dir/opentelemetry-javaagent.jar" \
  "$@"
