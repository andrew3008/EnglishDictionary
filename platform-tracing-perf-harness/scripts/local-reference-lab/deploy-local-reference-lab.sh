#!/usr/bin/env bash
# Deploy local reference lab manifests — dry-run by default. PR-9H-E2L.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

MODE="dry-run"
for arg in "$@"; do
  [[ "${arg}" == "--execute" ]] && MODE="execute"
done

require_cmd kubectl
assert_not_prod_context

GEN_DIR="${REPO_HARNESS}/build/local-reference-lab/manifests"
mkdir -p "${GEN_DIR}"

log "Mode: ${MODE}"
log "Generate overlays from templates into ${GEN_DIR}"
log "  (substitute LAB_* values — see config/local-reference-lab/)"

MANIFESTS=(
  namespace.yaml
  reference-sink-deployment.yaml
  reference-sink-service.yaml
  otel-collector-configmap.yaml
  otel-collector-deployment.yaml
  otel-collector-service.yaml
  perf-app-configmap.yaml
  perf-app-deployment.yaml
  perf-app-service.yaml
)

for m in "${MANIFESTS[@]}"; do
  src="${REPO_HARNESS}/config/local-reference-lab/${m}"
  [[ -f "${src}" ]] || src="${REPO_HARNESS}/config/k8s/${m}"
  [[ -f "${src}" ]] || die "Missing manifest source: ${m}"
  dest="${GEN_DIR}/${m}"
  sed \
    -e "s/REPLACE_NAMESPACE/${LAB_NAMESPACE}/g" \
    -e "s/REPLACE_PROFILE_ID/${LAB_PROFILE_ID}/g" \
    -e "s/REPLACE_REFERENCE_SINK_IMAGE/${LAB_REFERENCE_SINK_IMAGE}/g" \
    -e "s|REPLACE_REFERENCE_SINK_ENDPOINT|http://perf-harness-reference-sink:4318|g" \
    "${src}" > "${dest}"
  dry_run_or_exec "${MODE}" "kubectl --context='${LAB_KUBECTX}' apply -f '${dest}'"
done

K6_CM="${REPO_HARNESS}/build/k8s/k6-scenarios-configmap.yaml"
if [[ -f "${K6_CM}" ]]; then
  sed "s/REPLACE_NAMESPACE/${LAB_NAMESPACE}/g" "${K6_CM}" > "${GEN_DIR}/k6-scenarios-configmap.yaml"
  dry_run_or_exec "${MODE}" "kubectl --context='${LAB_KUBECTX}' apply -f '${GEN_DIR}/k6-scenarios-configmap.yaml'"
else
  log "WARN: run ./gradlew :platform-tracing-perf-harness:prepareK6ReferenceConfigMap first"
fi

log "Prometheus/cAdvisor: optional — see config/local-reference-lab/prometheus-notes.md"
