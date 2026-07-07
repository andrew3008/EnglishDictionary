#!/usr/bin/env bash
# JFR smoke — prove finalized parseable capture before S0/S1/S4 rerun. PR-9H-E2L-JFR-finalize.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"
source "${SCRIPT_DIR}/lib/jfr-local.sh"

MODE="dry-run"
RUN_ID="${LAB_RUN_ID:-20260614-local-jfr-finalize-1}"
SMOKE_SEC="${LAB_JFR_SMOKE_SEC:-45}"
for arg in "$@"; do
  [[ "${arg}" == "--execute" ]] && MODE="execute"
done

export LAB_JFR_FINALIZE_MODE="${LAB_JFR_FINALIZE_MODE:-durationBound}"
export LAB_JFR_DURATION="${LAB_JFR_SMOKE_DURATION:-2m}"

require_cmd "${LAB_KUBECTL}"
assert_not_prod_context

DEPLOY="${REPO_HARNESS}/build/local-reference-lab/deploy-perf-app-lab.sh"
[[ -f "${DEPLOY}" ]] || DEPLOY="/tmp/deploy-perf-app-lab.sh"
SUMMARIZE="${SCRIPT_DIR}/summarize-local-jfr.sh"

ART="${LAB_ARTIFACT_DIR}/${RUN_ID}/jfr-smoke"
RAW_JFR="${ART}/local-reference.jfr"
EVIDENCE="${REPO_HARNESS}/evidence/reference-local/${LAB_PROFILE_ID}/${RUN_ID}"

log "Mode=${MODE} runId=${RUN_ID} finalize=${LAB_JFR_FINALIZE_MODE} duration=${LAB_JFR_DURATION} smokeLoadSec=${SMOKE_SEC}"
log "JFR opts: $(lab_jfr_jvm_opts)"

if [[ "${MODE}" != "execute" ]]; then
  log "DRY-RUN: deploy S0 + optional ${SMOKE_SEC}s load + wait ${LAB_JFR_DURATION} + copy finalized JFR"
  exit 0
fi

export LAB_JFR_ENABLED=true KUBECTL="${LAB_KUBECTL}"
bash "${DEPLOY}" S0

POD=$("${LAB_KUBECTL}" -n "${LAB_NAMESPACE}" get pods -l app=perf-harness-app \
  --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}')
[[ -n "${POD}" ]] || die "perf-app pod not found"

APP_IP=$("${LAB_KUBECTL}" -n "${LAB_NAMESPACE}" get pod "${POD}" -o jsonpath='{.status.podIP}')
log "Pod ${POD} ip=${APP_IP} — optional smoke load ${SMOKE_SEC}s"
"${LAB_KUBECTL}" -n "${LAB_NAMESPACE}" run "jfr-smoke-${RANDOM}" --rm -i --restart=Never \
  --image=curlimages/curl:8.5.0 --command -- sh -c "
    i=0; while [ \$i -lt ${SMOKE_SEC} ]; do curl -sS -m 2 http://${APP_IP}:8080/perf/health >/dev/null || true; i=\$((i+1)); sleep 1; done; echo SMOKE_DONE
  " >/dev/null 2>&1 || log "WARN: smoke curl job failed — continuing"

mkdir -p "${ART}"
log "Waiting for duration-bound JFR finalization (${LAB_JFR_DURATION})..."
if ! lab_jfr_wait_for_finalized "${LAB_KUBECTL}" "${LAB_NAMESPACE}" "${POD}"; then
  die "BLOCKED_JFR_LOCKED_STREAM or finalize timeout — smoke JFR not parseable"
fi

if ! lab_jfr_copy_finalized_from_pod "${LAB_KUBECTL}" "${LAB_NAMESPACE}" "${POD}" "${RAW_JFR}"; then
  die "BLOCKED_JFR_FINAL_FILE_MISSING_OR_EMPTY — copy failed after finalize wait"
fi

log "JFR smoke raw OK: $(wc -c < "${RAW_JFR}") bytes -> ${RAW_JFR}"
if ! command -v jfr >/dev/null 2>&1; then
  die "BLOCKED_JFR_PARSER_UNAVAILABLE: host jfr not found"
fi
if ! timeout 30 jfr summary "${RAW_JFR}" >/dev/null 2>&1; then
  die "BLOCKED_JFR_LOCKED_STREAM — jfr summary failed on host after copy"
fi

mkdir -p "${EVIDENCE}/jfr-smoke"
bash "${SUMMARIZE}" --raw-dir "${LAB_ARTIFACT_DIR}/${RUN_ID}" --evidence-dir "${EVIDENCE}" --scenario jfr-smoke
log "Smoke JFR parseable — wrote ${EVIDENCE}/jfr-smoke/jfr-summary.json"
exit 0
