#!/usr/bin/env bash
# Install kube-prometheus-stack into local kind lab (approval: --execute). PR-9H-E2L-FULL.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

MODE="dry-run"
RELEASE="${LAB_PROM_RELEASE:-local-prom}"
PROM_NS="${LAB_PROM_NAMESPACE:-monitoring}"
for arg in "$@"; do
  [[ "${arg}" == "--execute" ]] && MODE="execute"
done

require_cmd helm
require_cmd "${LAB_KUBECTL}"
assert_not_prod_context

VALUES="${REPO_HARNESS}/config/local-reference-lab/prometheus/values-kind.yaml"
[[ -f "${VALUES}" ]] || VALUES="${REPO_HARNESS}/config/local-reference-lab/prometheus/prometheus-values-kind.yaml"
[[ -f "${VALUES}" ]] || die "Missing ${VALUES}"

log "Mode: ${MODE}"
log "Release: ${RELEASE} namespace: ${PROM_NS}"
log "Values: ${VALUES}"

if [[ "${MODE}" == "execute" ]]; then
  helm repo add prometheus-community https://prometheus-community.github.io/helm-charts 2>/dev/null || true
  helm repo update prometheus-community 2>/dev/null || helm repo update
  if helm list -n "${PROM_NS}" 2>/dev/null | grep -q "${RELEASE}"; then
    log "EXEC: helm upgrade ${RELEASE} ..."
    helm upgrade "${RELEASE}" prometheus-community/kube-prometheus-stack -n "${PROM_NS}" -f "${VALUES}" --wait --timeout 10m
  else
    log "EXEC: helm install ${RELEASE} ..."
    helm install "${RELEASE}" prometheus-community/kube-prometheus-stack -n "${PROM_NS}" --create-namespace -f "${VALUES}" --wait --timeout 10m
  fi
  "${LAB_KUBECTL}" --context="${LAB_KUBECTX}" -n "${PROM_NS}" get pods
  log "Prometheus install complete. Verify with query-required-metrics.sh"
else
  log "DRY-RUN: helm install/upgrade ${RELEASE} -f ${VALUES}"
fi
