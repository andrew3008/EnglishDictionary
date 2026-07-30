#!/usr/bin/env bash
# DEV_ACTUATOR typed leaf examples (management port). Adjust host/port/base-path.
# Requires: authority=ACTUATOR AND platform.environment=LOCAL
set -euo pipefail

BASE="${MANAGEMENT_BASE:-http://127.0.0.1:8081}"
CTL="${BASE}/tracingControl"

curl -sS -X POST "${CTL}/sampling/enabled" \
  -H 'Content-Type: application/json' \
  -d '{"enabled":true}'

curl -sS -X POST "${CTL}/sampling/ratio" \
  -H 'Content-Type: application/json' \
  -d '{"ratio":0.5}'

curl -sS -X PUT "${CTL}/sampling/route-ratios" \
  -H 'Content-Type: application/json' \
  -d '{"routeRatios":{"/api/checkout":1.0,"/api/search":0.01}}'

curl -sS -X POST "${CTL}/validation/enabled" \
  -H 'Content-Type: application/json' \
  -d '{"enabled":true}'

curl -sS -X POST "${CTL}/validation/strict" \
  -H 'Content-Type: application/json' \
  -d '{"strict":false}'

curl -sS -X POST "${CTL}/exporter/enabled" \
  -H 'Content-Type: application/json' \
  -d '{"enabled":true}'

curl -sS -X POST "${CTL}/propagation/trace-control-headers-enabled" \
  -H 'Content-Type: application/json' \
  -d '{"enabled":true}'

curl -sS -X POST "${CTL}/diagnostics/log-level" \
  -H 'Content-Type: application/json' \
  -d '{"level":"INFO"}'

# Outcomes: 200 APPLIED/UNCHANGED, 422 REJECTED, 409 STALE (no auto-retry), 503 FAILED
