#!/usr/bin/env bash
# Port-forward local Prometheus (read-only check + optional forward). PR-9H-E2L-METRICS.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

MODE="dry-run"
LOCAL_PORT="${LAB_PROM_PORT:-9090}"
for arg in "$@"; do
  [[ "${arg}" == "--execute" ]] && MODE="execute"
done

require_cmd "${LAB_KUBECTL}"
assert_not_prod_context

PROM_NS=""
PROM_SVC=""
while IFS= read -r line; do
  ns=$(echo "$line" | awk '{print $1}')
  name=$(echo "$line" | awk '{print $2}')
  PROM_NS="${ns}"
  PROM_SVC="${name}"
  break
done < <("${LAB_KUBECTL}" get svc -A 2>/dev/null | grep -i prometheus | grep -v alertmanager | head -1 || true)

if [[ -z "${PROM_SVC}" ]]; then
  log "NO Prometheus service found in cluster."
  log "Install options (approval required): see config/local-reference-lab/prometheus/README.md"
  exit 2
fi

log "Found Prometheus service: ${PROM_NS}/${PROM_SVC}"
CMD="${LAB_KUBECTL} --context=${LAB_KUBECTX} -n ${PROM_NS} port-forward svc/${PROM_SVC} ${LOCAL_PORT}:9090"
dry_run_or_exec "${MODE}" "${CMD}"
log "Then query: http://127.0.0.1:${LOCAL_PORT}/api/v1/query?query=up"
