#!/usr/bin/env bash
# Verify required PromQL series exist in local lab Prometheus. PR-9H-E2L-FULL.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

PROM_URL="${LAB_PROM_URL:-http://127.0.0.1:9090}"
NS="${LAB_NAMESPACE}"
POD_PREFIX="${1:-perf-harness-app}"

require_cmd curl
assert_not_prod_context

log "Prometheus: ${PROM_URL} namespace=${NS} pod~=${POD_PREFIX}.*"

queries=(
  "container_cpu_usage_seconds_total{namespace=\"${NS}\",container!=\"\",pod=~\"${POD_PREFIX}.*\"}"
  "container_cpu_cfs_throttled_periods_total{namespace=\"${NS}\",container!=\"\",pod=~\"${POD_PREFIX}.*\"}"
  "container_cpu_cfs_periods_total{namespace=\"${NS}\",container!=\"\",pod=~\"${POD_PREFIX}.*\"}"
  "container_memory_working_set_bytes{namespace=\"${NS}\",container!=\"\",pod=~\"${POD_PREFIX}.*\"}"
  "container_memory_rss{namespace=\"${NS}\",container!=\"\",pod=~\"${POD_PREFIX}.*\"}"
  "otelcol_exporter_queue_size"
  "otelcol_receiver_accepted_spans"
)

missing=0
for q in "${queries[@]}"; do
  enc=$(python3 -c "import urllib.parse; print(urllib.parse.quote('''${q}'''))" 2>/dev/null || echo "$q")
  resp=$(curl -sS -m 20 "${PROM_URL}/api/v1/query?query=${enc}" 2>/dev/null || echo '{"status":"error"}')
  count=$(echo "${resp}" | python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d.get('data',{}).get('result',[])))" 2>/dev/null || echo 0)
  if [[ "${count}" -gt 0 ]]; then
    log "OK (${count} series): ${q}"
  else
    log "MISSING: ${q}"
    missing=$((missing + 1))
  fi
done

if [[ "${missing}" -gt 0 ]]; then
  log "Targets check:"
  curl -sS -m 15 "${PROM_URL}/api/v1/targets" | python3 -c "
import json,sys
d=json.load(sys.stdin)
for t in d.get('data',{}).get('activeTargets',[]):
  print(t.get('labels',{}).get('job','?'), t.get('health','?'), t.get('lastError',''))
" 2>/dev/null || true
  exit 2
fi
log "All required metric families present."
exit 0
