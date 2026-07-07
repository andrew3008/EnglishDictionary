#!/usr/bin/env bash
# Build/load local lab images — dry-run by default. PR-9H-E2L.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

MODE="dry-run"
for arg in "$@"; do
  [[ "${arg}" == "--execute" ]] && MODE="execute"
done

require_cmd docker

SINK_CTX="${REPO_HARNESS}/docker/local-reference-sink"
log "Mode: ${MODE}"
log "Build reference sink: ${SINK_CTX}"
dry_run_or_exec "${MODE}" "docker_cmd build -t '${LAB_REFERENCE_SINK_IMAGE}' '${SINK_CTX}'"

log "Perf harness bootJar + agent extension image build is manual/lab-specific."
log "See config/local-reference-lab/README.md for image tags."
log "After build on Gentoo: kind load docker-image ${LAB_REFERENCE_SINK_IMAGE} --name ${LAB_KIND_CLUSTER}"
