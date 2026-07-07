#!/usr/bin/env bash
# Cleanup local reference lab — requires --execute --confirm-delete. PR-9H-E2L.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

EXECUTE=false
CONFIRM=false
for arg in "$@"; do
  [[ "${arg}" == "--execute" ]] && EXECUTE=true
  [[ "${arg}" == "--confirm-delete" ]] && CONFIRM=true
done

if [[ "${EXECUTE}" != true ]] || [[ "${CONFIRM}" != true ]]; then
  log "DRY-RUN cleanup plan:"
  log "  kubectl --context=${LAB_KUBECTX} delete namespace ${LAB_NAMESPACE} --ignore-not-found"
  log "  kind delete cluster --name ${LAB_KIND_CLUSTER}"
  log "To execute: $0 --execute --confirm-delete"
  exit 0
fi

assert_not_prod_context
kubectl --context="${LAB_KUBECTX}" delete namespace "${LAB_NAMESPACE}" --ignore-not-found --wait=false
kind delete cluster --name "${LAB_KIND_CLUSTER}" || true
log "Cleanup requested — verify namespace/cluster removed"
