#!/usr/bin/env bash
# Run local S0/S1/S4 k6 Jobs — dry-run by default. PR-9H-E2L.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

MODE="dry-run"
SCENARIOS="S0,S1,S4"
for arg in "$@"; do
  [[ "${arg}" == "--execute" ]] && MODE="execute"
  [[ "${arg}" == --scenarios=* ]] && SCENARIOS="${arg#*=}"
done

require_cmd kubectl
assert_not_prod_context

[[ -n "${LAB_TARGET_RPS:-}" ]] || die "LAB_TARGET_RPS not set — run S0 calibration first"

IFS=',' read -ra SCEN_ARR <<< "${SCENARIOS}"
for sc in "${SCEN_ARR[@]}"; do
  log "Scenario ${sc} mode=${MODE} targetRps=${LAB_TARGET_RPS}"
  case "${sc}" in
    S0) PROFILE="s0-baseline"; EP="/perf/work" ;;
    S1) PROFILE="s1-tracing"; EP="/perf/work" ;;
    S4) PROFILE="s4-production"; EP="/perf/work" ;;
    *) die "Unsupported scenario: ${sc}" ;;
  esac
  JOB="${REPO_HARNESS}/config/k8s/k6-job.yaml"
  GEN="${REPO_HARNESS}/build/local-reference-lab/k6-job-${sc}.yaml"
  mkdir -p "$(dirname "${GEN}")"
  sed \
    -e "s/REPLACE_NAMESPACE/${LAB_NAMESPACE}/g" \
    -e "s/REPLACE_SCENARIO/${sc}/g" \
    -e "s/REPLACE_TARGET_RPS/${LAB_TARGET_RPS}/g" \
    -e "s/REPLACE_WARMUP_DURATION/2m/g" \
    -e "s/REPLACE_STEADY_DURATION/10m/g" \
    -e "s|REPLACE_ENDPOINT_PATH|${EP}|g" \
    "${JOB}" > "${GEN}"
  dry_run_or_exec "${MODE}" "kubectl --context='${LAB_KUBECTX}' delete job perf-harness-k6-${sc} -n ${LAB_NAMESPACE} --ignore-not-found"
  dry_run_or_exec "${MODE}" "kubectl --context='${LAB_KUBECTX}' apply -f '${GEN}'"
done

log "After jobs complete: collect-local-reference-summary.sh per scenario"
