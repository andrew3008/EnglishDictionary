#!/usr/bin/env bash
# Collect compact Prometheus summary if Prom reachable; else record missingMetrics. PR-9H-E2L-METRICS.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

RUN_ID="${1:-$(date +%Y%m%d-%H%M%S)}"
OUT="${REPO_HARNESS}/build/perf-results/local-reference-lab/${LAB_PROFILE_ID}/${RUN_ID}"
mkdir -p "${OUT}"

PROM_URL="${LAB_PROM_URL:-}"
POD="${2:-perf-harness-app}"

if [[ -z "${PROM_URL}" ]]; then
  log "LAB_PROM_URL not set — Prometheus not queried."
  cat > "${OUT}/prometheus-compact-summary.json" <<EOF
{
  "status": "unavailable",
  "reason": "Prometheus not installed or LAB_PROM_URL not set",
  "missingMetrics": [
    "container_cpu_usage_seconds_total",
    "container_cpu_cfs_throttled_periods_total",
    "container_cpu_cfs_periods_total",
    "container_memory_working_set_bytes",
    "container_memory_rss"
  ],
  "labTier": "LOCAL_REFERENCE_LAB",
  "w004Eligible": false
}
EOF
  log "Wrote placeholder: ${OUT}/prometheus-compact-summary.json"
  exit 0
fi

require_cmd curl
NS="${LAB_NAMESPACE}"
queries=(
  "rate(container_cpu_usage_seconds_total{namespace=\"${NS}\",container!=\"\",pod=~\"${POD}.*\"}[5m])"
  "rate(container_cpu_cfs_throttled_periods_total{namespace=\"${NS}\",container!=\"\",pod=~\"${POD}.*\"}[5m])"
  "rate(container_cpu_cfs_periods_total{namespace=\"${NS}\",container!=\"\",pod=~\"${POD}.*\"}[5m])"
  "container_memory_working_set_bytes{namespace=\"${NS}\",container!=\"\",pod=~\"${POD}.*\"}"
  "container_memory_rss{namespace=\"${NS}\",container!=\"\",pod=~\"${POD}.*\"}"
)

{
  echo '{'
  echo '  "status": "partial",'
  echo '  "labTier": "LOCAL_REFERENCE_LAB",'
  echo '  "w004Eligible": false,'
  echo '  "queries": {'
  local first=1
  for q in "${queries[@]}"; do
    key=$(echo "$q" | sed 's/{.*//' | tr '.' '_')
    enc=$(python3 -c "import urllib.parse; print(urllib.parse.quote('''${q}'''))" 2>/dev/null || echo "$q")
    result=$(curl -sS -m 15 "${PROM_URL}/api/v1/query?query=${enc}" 2>/dev/null || echo '{"status":"error"}')
    [[ "${first}" -eq 1 ]] && first=0 || echo ','
    printf '    "%s": %s' "${key}" "${result}"
  done
  echo ''
  echo '  }'
  echo '}'
} > "${OUT}/prometheus-compact-summary.json"
log "Wrote ${OUT}/prometheus-compact-summary.json"
