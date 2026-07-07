#!/usr/bin/env bash
# k3s local reference lab — MANUAL APPROVAL ONLY. Not automated in PR-9H-E2L.
# Install/start k3s requires sudo on Gentoo — document steps; do not run curl|sh here.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

MODE="dry-run"
for arg in "$@"; do
  [[ "${arg}" == "--execute" ]] && MODE="execute"
done

log "k3s setup is NOT automated. Requires explicit human approval on Gentoo."
log ""
log "Manual steps (after approval):"
log "  1. Install k3s per Gentoo/SRE policy (no curl|sh in CI)"
log "  2. export KUBECONFIG=~/.kube/k3s.yaml"
log "  3. kubectl config rename-context default ${LAB_KUBECTX}"
log "  4. deploy-local-reference-lab.sh --execute"
log ""
log "Prefer kind if Docker is healthy (see setup-kind-lab.sh)."

if [[ "${MODE}" == "execute" ]]; then
  die "Refusing automated k3s install. Use manual section in PR-9H-E2L runbook."
fi
