#!/usr/bin/env bash
# kind local reference lab — requires human approval (--execute). PR-9H-E2L.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

MODE="dry-run"
for arg in "$@"; do
  [[ "${arg}" == "--execute" ]] && MODE="execute"
  [[ "${arg}" == "--dry-run" ]] && MODE="dry-run"
done

require_cmd kind
require_cmd kubectl

CONFIG="${REPO_HARNESS}/config/local-reference-lab/kind-cluster.yaml"
[[ -f "${CONFIG}" ]] || die "Missing ${CONFIG}"

log "Mode: ${MODE}"
log "Cluster: ${LAB_KIND_CLUSTER} context: ${LAB_KUBECTX}"

dry_run_or_exec "${MODE}" "kind create cluster --name '${LAB_KIND_CLUSTER}' --config '${CONFIG}' --wait 5m"
dry_run_or_exec "${MODE}" "kubectl cluster-info --context='${LAB_KUBECTX}'"

log "After execute: run deploy-local-reference-lab.sh --dry-run"
