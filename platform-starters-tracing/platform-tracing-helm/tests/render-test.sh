#!/bin/sh
set -eu

TAB=$(printf '\t')
RELEASE=tracing-render-harness

die() {
  printf '%s\n' "$*" >&2
  exit 1
}

count_lines() {
  grep -c "$@" || [ $? -eq 1 ]
}

render() {
  helm template "$RELEASE" "$consumer_chart" "$@"
}

env_triples() {
  awk '
    /^---[[:space:]]*$/ { kind = ""; pending = ""; next }
    /^kind:[[:space:]]*/ { kind = $2; next }
    pending != "" {
      value = "<none>"
      if ($0 ~ /^[[:space:]]*value:[[:space:]]/) {
        value = $0
        sub(/^[[:space:]]*value:[[:space:]]*/, "", value)
        if (value ~ /^".*"$/) value = substr(value, 2, length(value) - 2)
      }
      printf "\t%s\t%s\t%s\n", kind, pending, value
      pending = ""
    }
    /^[[:space:]]*-[[:space:]]+name:[[:space:]]/ {
      pending = $0
      sub(/^[[:space:]]*-[[:space:]]+name:[[:space:]]*/, "", pending)
    }
  ' "$1"
}

assert_count() {
  actual=$(count_lines -F -- "$3" "$2")
  [ "$actual" -eq "$1" ] || die "$4: expected $1, got $actual"
}

assert_absent() {
  file=$1
  shift
  for name in "$@"; do
    actual=$(count_lines -Fw -- "$name" "$file")
    [ "$actual" -eq 0 ] || die "Unexpected $name in $(basename "$file"): $actual occurrences"
  done
}

assert_render_fails() {
  message=$1
  label=$2
  shift 2
  if render "$@" >"$invalid_render" 2>&1; then
    die "$label unexpectedly rendered successfully"
  fi
  grep -Fq -- "$message" "$invalid_render" ||
    die "$label failed without '$message': $(head -n 5 "$invalid_render")"
}

write_route_values() {
  {
    printf 'platformTracing:\n  enabled: true\n  policy:\n    sampling:\n      routeRatios:\n'
    cat
  } >"$1"
}

tests_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
helm_root=$(CDPATH='' cd -- "$tests_dir/.." && pwd)
source_chart=${1:-"$helm_root/platform-tracing"}
[ -f "$source_chart/Chart.yaml" ] || die "Library chart is missing Chart.yaml: $source_chart"

temp_root=$(mktemp -d)
trap 'rm -rf "$temp_root"' EXIT HUP INT TERM

mkdir -p "$temp_root/platform-tracing-helm/tests"
cp -R "$source_chart" "$temp_root/platform-tracing-helm/platform-tracing"
cp -R "$tests_dir/consumer" "$temp_root/platform-tracing-helm/tests/consumer"
library_chart="$temp_root/platform-tracing-helm/platform-tracing"
consumer_chart="$temp_root/platform-tracing-helm/tests/consumer"
invalid_render="$temp_root/invalid-render.log"

WORKLOAD_KINDS="Deployment Job CronJob"

helm lint --strict "$library_chart"
helm dependency build "$consumer_chart"
helm lint --strict "$consumer_chart"

disabled="$temp_root/disabled.yaml"
render >"$disabled"
env_triples "$disabled" >"$disabled.tsv"
assert_absent "$disabled" PLATFORM_TRACING_CONTROL_CHANNEL
for kind in $WORKLOAD_KINDS; do
  assert_count 1 "$disabled.tsv" "${TAB}$kind${TAB}PLATFORM_TRACING_X${TAB}caller-owned" \
    "Disabled tracing: caller-owned PLATFORM_TRACING_X in $kind"
done
assert_count 3 "$disabled.tsv" "${TAB}PLATFORM_TRACING_X${TAB}" "Disabled tracing: PLATFORM_TRACING_X entries"

minimal="$temp_root/minimal.yaml"
render --values "$consumer_chart/values-minimal.yaml" >"$minimal"
assert_absent "$minimal" \
  PLATFORM_TRACING_SAMPLING_ENABLED \
  PLATFORM_TRACING_SAMPLING_RATIO \
  PLATFORM_TRACING_SAMPLING_ROUTE_RATIOS \
  PLATFORM_TRACING_JAVAAGENT_SAMPLING_DROP_PATHS \
  PLATFORM_TRACING_JAVAAGENT_SAMPLING_FORCE_RECORD_VALUES \
  PLATFORM_TRACING_JAVAAGENT_SPAN_ATTRIBUTES_VALIDATION_LEVEL \
  PLATFORM_TRACING_EXPORTER_ENABLED \
  PLATFORM_TRACING_PROPAGATION_TRACE_CONTROL_HEADERS_ENABLED \
  PLATFORM_TRACING_DIAGNOSTICS_LOG_LEVEL

positive="$temp_root/positive.yaml"
render --values "$consumer_chart/values-positive.yaml" >"$positive"
env_triples "$positive" >"$positive.tsv"
while IFS='=' read -r name value; do
  for kind in $WORKLOAD_KINDS; do
    assert_count 1 "$positive.tsv" "${TAB}$kind${TAB}${name}${TAB}${value}" "$kind $name=$value"
  done
  assert_count 3 "$positive.tsv" "${TAB}${name}${TAB}" "$name entries"
done <<'EOF'
PLATFORM_TRACING_SAMPLING_ENABLED=false
PLATFORM_TRACING_SAMPLING_RATIO=0
PLATFORM_TRACING_SAMPLING_ROUTE_RATIOS=/orders=0.25,/payments=0
PLATFORM_TRACING_JAVAAGENT_SAMPLING_DROP_PATHS=/actuator/health,/actuator/prometheus
PLATFORM_TRACING_JAVAAGENT_SAMPLING_FORCE_RECORD_VALUES=support,incident
PLATFORM_TRACING_JAVAAGENT_SPAN_ATTRIBUTES_VALIDATION_LEVEL=ERROR
PLATFORM_TRACING_EXPORTER_ENABLED=false
PLATFORM_TRACING_PROPAGATION_TRACE_CONTROL_HEADERS_ENABLED=false
PLATFORM_TRACING_DIAGNOSTICS_LOG_LEVEL=DEBUG
OTEL_PROPAGATORS=tracecontext,baggage,b3
OTEL_METRICS_EXPORTER=none
OTEL_LOGS_EXPORTER=none
OTEL_EXPORTER_OTLP_ENDPOINT=http://collector.test:4317
OTEL_EXPORTER_OTLP_PROTOCOL=grpc
OTEL_SERVICE_NAME=tracing-render-harness
OTEL_RESOURCE_ATTRIBUTES=service.version=2.0.0,deployment.environment.name=test
APPLICATION_MODE=render-test
EOF
assert_absent "$positive" \
  PLATFORM_TRACING_CONTROL_CHANNEL \
  PLATFORM_TRACING_METRICS_ENABLED \
  OTEL_TRACES_EXPORTER \
  OTEL_EXPORTER_OTLP_TRACES_ENDPOINT \
  OTEL_EXPORTER_OTLP_TRACES_PROTOCOL

assert_render_fails "platformTracing.controlChannel is not a supported key" "controlChannel with tracing enabled" \
  --values "$consumer_chart/values-positive.yaml" --set-string platformTracing.controlChannel=CONFIG_SERVER
assert_render_fails "platformTracing.export is not a supported key" "platformTracing.export" \
  --values "$consumer_chart/values-positive.yaml" --set-string platformTracing.export.endpoint=http://collector.test:4317
assert_render_fails "platformTracing.controlChannel is not a supported key" "controlChannel with tracing disabled" \
  --values "$consumer_chart/values.yaml" --set-string platformTracing.controlChannel=NONE

for managed_name in OTEL_EXPORTER_OTLP_PROTOCOL OTEL_EXPORTER_OTLP_TRACES_PROTOCOL OTEL_PROPAGATORS \
  PLATFORM_TRACING_SAMPLING_RATIO PLATFORM_TRACING_CONTROL_CHANNEL PLATFORM_TRACING_X; do
  assert_render_fails "env variable $managed_name is managed by platform-tracing" "Managed env $managed_name" \
    --values "$consumer_chart/values-positive.yaml" --set-string "containerEnv[0].name=$managed_name"
done

route_number=1
while [ "$route_number" -le 101 ]; do
  printf '        /route-%03d: 0.5\n' "$route_number"
  route_number=$((route_number + 1))
done | write_route_values "$temp_root/too-many-routes.yaml"
assert_render_fails "routeRatios must contain at most 100 entries; got 101" "101 route ratios" \
  --values "$temp_root/too-many-routes.yaml"

long_route_prefix=$(awk 'BEGIN { for (i = 0; i < 128; i++) printf "я" }')
printf '        "%s": 0.5\n' "$long_route_prefix" | write_route_values "$temp_root/long-route.yaml"
assert_render_fails "routeRatios key must be at most 255 UTF-8 bytes; got 256" "256-byte route prefix" \
  --values "$temp_root/long-route.yaml"
