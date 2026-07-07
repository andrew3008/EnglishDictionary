#!/usr/bin/env bash
# Assemble local reference-summary skeleton — no fake metrics. PR-9H-E2L.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

SCENARIO=""
RUN_ID=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenario) SCENARIO="$2"; shift 2 ;;
    --run-id) RUN_ID="$2"; shift 2 ;;
    *) die "Unknown arg: $1" ;;
  esac
done

[[ -n "${SCENARIO}" ]] || die "Usage: $0 --scenario S0|S1|S4 --run-id <id>"
[[ -n "${RUN_ID}" ]] || RUN_ID="$(date +%Y%m%d-%H%M%S)"

OUT="${REPO_HARNESS}/build/perf-results/local-reference-lab/${LAB_PROFILE_ID}/${RUN_ID}"
mkdir -p "${OUT}"

log "Creating local lab summary skeleton (no fabricated metrics)"
log "Output: ${OUT}/reference-summary.json"

# Delegate to Gradle skeleton with local paths — or write minimal JSON here
if command -v ./gradlew >/dev/null 2>&1 && [[ -f "${REPO_HARNESS}/../../gradlew" ]]; then
  (cd "${REPO_HARNESS}/../.." && ./gradlew :platform-tracing-perf-harness:perfReferenceCreateLocalLabSummarySkeleton \
    -PperfProfileId="${LAB_PROFILE_ID}" \
    -PperfScenario="${SCENARIO}" \
    -PperfRunId="${RUN_ID}" \
    -PperfNamespace="${LAB_NAMESPACE}" \
    -PperfTargetRps="${LAB_TARGET_RPS:-0}" \
    -PperfOutputDir="${OUT}" \
    -PperfAllowUnknownGitCommit=true) || true
fi

log "Fill metrics from k6/Prometheus/JFR captures manually."
log "Validate: ./gradlew :platform-tracing-perf-harness:test"
log "Compact commit path: evidence/reference-local/${LAB_PROFILE_ID}/${RUN_ID}/"
