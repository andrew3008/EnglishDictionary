#!/usr/bin/env bash
# Shared helpers for local reference lab scripts (PR-9H-E2L).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_HARNESS="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ENV_FILE="${SCRIPT_DIR}/local-reference-lab.env"

if [[ -f "${ENV_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
fi

LAB_PROFILE_ID="${LAB_PROFILE_ID:-gentoo-local-reference-lab-v1}"
LAB_NAMESPACE="${LAB_NAMESPACE:-platform-tracing-reference-lab}"
LAB_KUBECTX="${LAB_KUBECTX:-kind-platform-tracing-reference-lab}"
LAB_DOCKER_HOST="${LAB_DOCKER_HOST:-}"
LAB_KIND_CLUSTER="${LAB_KIND_CLUSTER:-platform-tracing-reference-lab}"
LAB_ARTIFACT_DIR="${LAB_ARTIFACT_DIR:-/var/tmp/platform-tracing-reference-lab-artifacts}"
LAB_KUBECTL="${LAB_KUBECTL:-kubectl}"

log() { printf '[local-ref-lab] %s\n' "$*"; }
die() { log "ERROR: $*"; exit 1; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "Missing command: $1"
}

docker_cmd() {
  if [[ -n "${LAB_DOCKER_HOST}" ]]; then
    docker -H "${LAB_DOCKER_HOST}" "$@"
  else
    docker "$@"
  fi
}

kubectl_lab() {
  "${LAB_KUBECTL}" --context="${LAB_KUBECTX}" -n "${LAB_NAMESPACE}" "$@"
}

pod_ip() {
  local selector="$1"
  kubectl_lab get pods -l "${selector}" --field-selector=status.phase=Running \
    -o jsonpath='{.items[0].status.podIP}' 2>/dev/null || true
}

assert_not_prod_context() {
  local ctx="${LAB_KUBECTX}"
  if [[ "${ctx}" == *"prod"* ]] || [[ "${ctx}" == *"production"* ]]; then
    die "Refusing: context looks like production: ${ctx}"
  fi
  log "Kubernetes context OK (local lab): ${ctx}"
}

dry_run_or_exec() {
  local mode="$1"
  shift
  if [[ "${mode}" == "dry-run" ]]; then
    log "DRY-RUN: $*"
  else
    log "EXEC: $*"
    eval "$@"
  fi
}
