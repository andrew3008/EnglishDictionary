#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fixture="$root/verify-fixture"

helm dependency build "$fixture" --skip-refresh
helm lint --strict "$fixture"

# Значения, пути и failedTemplate-контракты проверяются декларативно в verify-fixture/tests.
helm unittest "$fixture"

# Единственный инвариант, который нельзя выразить путём в YAML: запуск-контракт принадлежит образу,
# поэтому строка -javaagent не должна появляться нигде в отрендеренном манифесте.
if helm template fixture "$fixture" | grep -q -- "-javaagent"; then
  echo "chart must not emit -javaagent anywhere in the rendered manifest" >&2
  exit 1
fi

echo "platform-tracing library chart verified"
