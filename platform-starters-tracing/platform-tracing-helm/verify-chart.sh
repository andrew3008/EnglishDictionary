#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fixture="$root/verify-fixture"

helm dependency build "$fixture" --skip-refresh
helm lint --strict "$fixture"

helm unittest "$fixture"

rendered="$(helm template fixture "$fixture")"

require_match() {
  if [[ "$rendered" != *"$1"* ]]; then
    echo "$2" >&2
    exit 1
  fi
}

require_no_match() {
  if [[ "$rendered" == *"$1"* ]]; then
    echo "$2" >&2
    exit 1
  fi
}

require_match "OTEL_PROPAGATORS" "managed OTEL_PROPAGATORS missing"
require_match "tracecontext,baggage,b3" "propagators must match Instrumentation CR"
require_match "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL" "protocol missing"
require_match "grpc" "protocol must be grpc"
require_match "otel-collector.ops-system.svc.cluster.local:4317" "default collector endpoint missing"
require_match "JDK_JAVA_OPTIONS" "application JDK_JAVA_OPTIONS was not preserved"
require_match "secretKeyRef" "application valueFrom was not preserved"
require_match "PLATFORM_TRACING_CONFIGURATION_AUTHORITY" "configuration authority env missing"
require_match "HELM" "fixture must emit HELM authority"
require_no_match "JAVA_TOOL_OPTIONS" "helper must not emit JAVA_TOOL_OPTIONS"
require_no_match "-javaagent" "helper must not emit -javaagent"
require_no_match "PLATFORM_ENVIRONMENT" "chart must not emit PLATFORM_ENVIRONMENT"
require_no_match "platform.tracing.validation.mode=LENIENT" "chart must not hardcode LENIENT"

conflict_values="$(mktemp)"
trap 'rm -f "$conflict_values"' EXIT
cat > "$conflict_values" <<'EOF'
deployment:
  env:
    - name: JAVA_TOOL_OPTIONS
      value: "-Dotel.propagators=tracecontext"
EOF

if helm template fixture "$fixture" -f "$conflict_values" >/dev/null 2>&1; then
  echo "expected render failure for competing -Dotel.propagators" >&2
  exit 1
fi

echo "platform-tracing library chart verified"
