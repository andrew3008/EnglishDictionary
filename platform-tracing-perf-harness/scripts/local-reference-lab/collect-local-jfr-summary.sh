#!/usr/bin/env bash
# Collect finalized JFR from perf-app pod + compact summary. PR-9H-E2L-JFR-finalize.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"
source "${SCRIPT_DIR}/lib/jfr-local.sh"

RUN_ID="${1:-$(date +%Y%m%d-%H%M%S)}"
SCENARIO="${2:-smoke}"
JFR_PATH="${3:-${LAB_ARTIFACT_DIR}/${RUN_ID}/${SCENARIO}/local-reference.jfr}"
OUT="${4:-${REPO_HARNESS}/evidence/reference-local/${LAB_PROFILE_ID}/${RUN_ID}/${SCENARIO}}"

export LAB_JFR_FINALIZE_MODE="${LAB_JFR_FINALIZE_MODE:-durationBound}"
export LAB_JFR_DURATION="${LAB_JFR_DURATION:-7m}"

SUMMARY="${OUT}/jfr-summary.json"
SUMMARIZE="${SCRIPT_DIR}/summarize-local-jfr.sh"
require_cmd "${LAB_KUBECTL}"
assert_not_prod_context

APP_POD=$("${LAB_KUBECTL}" -n "${LAB_NAMESPACE}" get pods -l app=perf-harness-app \
  --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)

if [[ -n "${APP_POD}" ]]; then
  log "Wait finalized JFR on pod ${APP_POD} (duration=${LAB_JFR_DURATION})..."
  if lab_jfr_wait_for_finalized "${LAB_KUBECTL}" "${LAB_NAMESPACE}" "${APP_POD}"; then
    log "Copy finalized JFR from pod ${APP_POD} ..."
    lab_jfr_copy_finalized_from_pod "${LAB_KUBECTL}" "${LAB_NAMESPACE}" "${APP_POD}" "${JFR_PATH}" || true
  else
    log "WARN: JFR not finalized/parseable in pod"
  fi
fi

if [[ -s "${JFR_PATH}" ]] && lab_jfr_verify_parseable_file "${JFR_PATH}"; then
  mkdir -p "${OUT}"
  EVIDENCE_RUN="$(dirname "${OUT}")"
  bash "${SUMMARIZE}" --raw-dir "${LAB_ARTIFACT_DIR}/${RUN_ID}" --evidence-dir "${EVIDENCE_RUN}" --scenario "${SCENARIO}"
  log "JFR collected: ${JFR_PATH} ($(wc -c < "${JFR_PATH}") bytes)"
  log "Summary: ${EVIDENCE_RUN}/${SCENARIO}/jfr-summary.json"
else
  mkdir -p "${OUT}"
  lab_jfr_write_summary "${SUMMARY}" "${JFR_PATH}" "${SCENARIO}" || true
  log "JFR missing or unparseable — wrote ${SUMMARY}"
  exit 1
fi
