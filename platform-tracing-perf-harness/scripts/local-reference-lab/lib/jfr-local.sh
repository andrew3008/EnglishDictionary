#!/usr/bin/env bash
# Shared local-lab JFR helpers (PR-9H-E2L-JFR-fix, PR-9H-E2L-JFR-finalize). Source from other scripts.
# shellcheck disable=SC2034
LAB_JFR_RECORDING_NAME="${LAB_JFR_RECORDING_NAME:-local-reference}"
LAB_JFR_FILE="${LAB_JFR_FILE:-/tmp/jfr/local-reference.jfr}"
LAB_JFR_SETTINGS="${LAB_JFR_SETTINGS:-profile}"
LAB_JFR_DURATION="${LAB_JFR_DURATION:-7m}"
LAB_JFR_FINALIZE_MODE="${LAB_JFR_FINALIZE_MODE:-durationBound}"
LAB_JFR_WAIT_BUFFER_SEC="${LAB_JFR_WAIT_BUFFER_SEC:-45}"
LAB_JFR_IN_POD_BIN="${LAB_JFR_IN_POD_BIN:-/opt/java/openjdk/bin/jfr}"

# durationBound: recording stops after duration; filename= is parseable (no jcmd).
# legacyContinuous: PR-9H-E2L-JFR-fix behavior (deprecated — produces locked streams).
lab_jfr_jvm_opts() {
  if [[ "${LAB_JFR_FINALIZE_MODE}" == "durationBound" ]]; then
    echo "-XX:FlightRecorderOptions=repository=/tmp/jfr/repository -XX:StartFlightRecording=name=${LAB_JFR_RECORDING_NAME},settings=${LAB_JFR_SETTINGS},disk=true,duration=${LAB_JFR_DURATION},filename=${LAB_JFR_FILE}"
  else
    echo "-XX:FlightRecorderOptions=repository=/tmp/jfr/repository -XX:StartFlightRecording=name=${LAB_JFR_RECORDING_NAME},settings=${LAB_JFR_SETTINGS},disk=true,maxage=10m,maxsize=128m,filename=${LAB_JFR_FILE}"
  fi
}

lab_jfr_duration_seconds() {
  python3 - <<PY
import os, re
d = os.environ.get("LAB_JFR_DURATION", "7m").strip()
m = re.fullmatch(r"(\d+)([smh])", d)
if not m:
    raise SystemExit(f"invalid LAB_JFR_DURATION: {d}")
n, u = int(m.group(1)), m.group(2)
mult = {"s": 1, "m": 60, "h": 3600}[u]
print(n * mult)
PY
}

lab_jfr_pod_start_epoch() {
  local kubectl="$1" ns="$2" pod="$3"
  local start_iso
  start_iso=$("${kubectl}" get pod -n "${ns}" "${pod}" -o jsonpath='{.status.startTime}' 2>/dev/null || true)
  [[ -n "${start_iso}" ]] || return 1
  python3 - <<PY
from datetime import datetime, timezone
iso = """${start_iso}"""
if iso.endswith("Z"):
    iso = iso[:-1] + "+00:00"
dt = datetime.fromisoformat(iso)
print(int(dt.timestamp()))
PY
}

lab_jfr_verify_parseable_in_pod() {
  local kubectl="$1" ns="$2" pod="$3"
  "${kubectl}" exec -n "${ns}" "${pod}" -- sh -c "
    F='${LAB_JFR_FILE}'
    [ -s \"\$F\" ] || exit 1
    JFR='${LAB_JFR_IN_POD_BIN}'
    [ -x \"\$JFR\" ] || JFR=\$(command -v jfr || true)
    [ -n \"\$JFR\" ] || exit 2
    \"\$JFR\" summary \"\$F\" >/dev/null 2>&1
  " 2>/dev/null
}

lab_jfr_verify_parseable_file() {
  local jfr_path="$1"
  [[ -s "${jfr_path}" ]] || return 1
  if command -v jfr >/dev/null 2>&1; then
    timeout 30 jfr summary "${jfr_path}" >/dev/null 2>&1
    return $?
  fi
  return 2
}

lab_jfr_wait_for_finalized() {
  local kubectl="$1" ns="$2" pod="$3"
  local dur_sec start_epoch deadline now
  dur_sec="$(lab_jfr_duration_seconds)"
  start_epoch="$(lab_jfr_pod_start_epoch "${kubectl}" "${ns}" "${pod}")" || {
    echo "lab_jfr_wait_for_finalized: cannot read pod startTime" >&2
    return 1
  }
  deadline=$((start_epoch + dur_sec + LAB_JFR_WAIT_BUFFER_SEC))
  echo "JFR wait: duration=${LAB_JFR_DURATION} (${dur_sec}s) buffer=${LAB_JFR_WAIT_BUFFER_SEC}s deadline_epoch=${deadline}" >&2
  while true; do
    now=$(date +%s)
    if lab_jfr_verify_parseable_in_pod "${kubectl}" "${ns}" "${pod}"; then
      echo "JFR finalized and parseable in pod ${pod}" >&2
      return 0
    fi
    if [[ "${now}" -ge "${deadline}" ]]; then
      break
    fi
    sleep 10
  done
  if lab_jfr_verify_parseable_in_pod "${kubectl}" "${ns}" "${pod}"; then
    return 0
  fi
  echo "lab_jfr_wait_for_finalized: timeout or locked stream after duration" >&2
  return 1
}

lab_jfr_copy_finalized_from_pod() {
  local kubectl="$1" ns="$2" pod="$3" dest="$4"
  lab_jfr_verify_parseable_in_pod "${kubectl}" "${ns}" "${pod}" || return 1
  mkdir -p "$(dirname "${dest}")"
  "${kubectl}" cp "${ns}/${pod}:${LAB_JFR_FILE}" "${dest}" 2>/dev/null || return 1
  [[ -s "${dest}" ]] || return 1
  lab_jfr_verify_parseable_file "${dest}" || return 1
}

# Legacy — copy largest .jfr (produces locked streams if recording still open).
lab_jfr_find_in_pod() {
  local kubectl="$1" ns="$2" pod="$3"
  "${kubectl}" exec -n "${ns}" "${pod}" -- sh -c "
    F='${LAB_JFR_FILE}'
    if [ -s \"\$F\" ]; then echo \"\$F\"; exit 0; fi
    best=''
    bestsz=0
    for f in \$(find /tmp -name '*.jfr' -type f 2>/dev/null); do
      sz=\$(wc -c < \"\$f\" 2>/dev/null || echo 0)
      if [ \"\$sz\" -gt \"\$bestsz\" ]; then bestsz=\$sz; best=\$f; fi
    done
    if [ \"\$bestsz\" -gt 0 ]; then echo \"\$best\"; fi
  " 2>/dev/null || true
}

lab_jfr_copy_from_pod() {
  local kubectl="$1" ns="$2" pod="$3" dest="$4"
  if [[ "${LAB_JFR_FINALIZE_MODE}" == "durationBound" ]]; then
    lab_jfr_copy_finalized_from_pod "${kubectl}" "${ns}" "${pod}" "${dest}"
    return $?
  fi
  local in_pod
  in_pod=$(lab_jfr_find_in_pod "${kubectl}" "${ns}" "${pod}")
  [[ -n "${in_pod}" ]] || return 1
  mkdir -p "$(dirname "${dest}")"
  "${kubectl}" cp "${ns}/${pod}:${in_pod}" "${dest}" 2>/dev/null || return 1
  [[ -s "${dest}" ]] || return 1
  return 0
}

lab_jfr_write_summary() {
  local summary="$1" jfr_path="$2" scenario="${3:-smoke}"
  local sha size
  if [[ ! -s "${jfr_path}" ]]; then
    cat > "${summary}" <<EOF
{"status":"missing","evidenceTier":"REFERENCE","labTier":"LOCAL_REFERENCE_LAB","w004Eligible":false,"nonAuthoritative":true,"scenario":"${scenario}","missingMetrics":["jfrGc"],"reason":"JFR file missing or empty"}
EOF
    return 1
  fi
  sha=$(sha256sum "${jfr_path}" | awk '{print $1}')
  size=$(stat -c '%s' "${jfr_path}" 2>/dev/null || stat -f '%z' "${jfr_path}")
  cat > "${summary}" <<EOF
{
  "status": "collected",
  "evidenceTier": "REFERENCE",
  "labTier": "LOCAL_REFERENCE_LAB",
  "w004Eligible": false,
  "nonAuthoritative": true,
  "nonAuthoritativeReasons": ["localEnvironment", "singleRunOnly", "provisionalBudgetOnly"],
  "scenario": "${scenario}",
  "jfrStartMode": "startup",
  "finalizationMode": "${LAB_JFR_FINALIZE_MODE}",
  "recordingName": "${LAB_JFR_RECORDING_NAME}",
  "settings": "${LAB_JFR_SETTINGS}",
  "jfrDuration": "${LAB_JFR_DURATION}",
  "storageRef": "file://${jfr_path}",
  "sha256": "${sha}",
  "fileSizeBytes": ${size},
  "caveats": ["detailedGcSummaryUnavailable", "rawJfrNotInGit"]
}
EOF
  return 0
}
