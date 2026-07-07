#!/usr/bin/env bash
# Query Prometheus HTTP API (local port-forward or LAB_PROM_URL). PR-9H-E2L-METRICS.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

PROM_URL="${LAB_PROM_URL:-http://127.0.0.1:9090}"
QUERY="${1:-up}"
ENCODED=$(python3 -c "import urllib.parse; print(urllib.parse.quote('''${QUERY}'''))" 2>/dev/null \
  || printf '%s' "${QUERY}" | sed 's/ /%20/g')

require_cmd curl
log "Prometheus URL: ${PROM_URL}"
log "Query: ${QUERY}"
curl -sS -m 30 "${PROM_URL}/api/v1/query?query=${ENCODED}" | head -c 8192
echo ""
