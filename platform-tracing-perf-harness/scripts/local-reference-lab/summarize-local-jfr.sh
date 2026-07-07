#!/usr/bin/env bash
# Parse raw local-lab .jfr into compact jfr-summary.json (read-only on raw files).
# PR-9H-E2L-JFR-summary
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY_HELPER="${SCRIPT_DIR}/lib/summarize-jfr-events.py"
RAW_DIR=""
EVIDENCE_DIR=""
SCENARIO=""
DRY_RUN=false
JFR_TIMEOUT="${JFR_PARSE_TIMEOUT_SEC:-30}"
RECORDING_NAME="${LAB_JFR_RECORDING_NAME:-local-reference}"
JFR_SETTINGS="${LAB_JFR_SETTINGS:-profile}"

usage() {
  cat <<'EOF'
Usage: summarize-local-jfr.sh --raw-dir DIR --evidence-dir DIR --scenario SC [--dry-run]

  --raw-dir       Directory containing scenario subdirs with local-reference.jfr
  --evidence-dir  Repo evidence run directory (writes <scenario>/jfr-summary.json)
  --scenario      jfr-smoke | S0 | S1 | S4
  --dry-run       Parse and print JSON to stdout; do not write evidence file
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --raw-dir) RAW_DIR="$2"; shift 2 ;;
    --evidence-dir) EVIDENCE_DIR="$2"; shift 2 ;;
    --scenario) SCENARIO="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 2 ;;
  esac
done

[[ -n "${RAW_DIR}" && -n "${EVIDENCE_DIR}" && -n "${SCENARIO}" ]] || {
  usage >&2
  exit 2
}

case "${SCENARIO}" in
  jfr-smoke|S0|S1|S4) ;;
  *) echo "Unsupported scenario: ${SCENARIO}" >&2; exit 2 ;;
esac

JFR_FILE="${RAW_DIR}/${SCENARIO}/local-reference.jfr"
OUT_FILE="${EVIDENCE_DIR}/${SCENARIO}/jfr-summary.json"

if [[ ! -f "${JFR_FILE}" ]]; then
  echo "Missing raw JFR: ${JFR_FILE}" >&2
  exit 1
fi
if [[ ! -s "${JFR_FILE}" ]]; then
  echo "Empty raw JFR: ${JFR_FILE}" >&2
  exit 1
fi

find_jfr_tool() {
  if [[ -n "${JFR_TOOL:-}" && -x "${JFR_TOOL}" ]]; then
    echo "${JFR_TOOL}"
    return 0
  fi
  if command -v jfr >/dev/null 2>&1; then
    command -v jfr
    return 0
  fi
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/jfr" ]]; then
    echo "${JAVA_HOME}/bin/jfr"
    return 0
  fi
  return 1
}

JFR_BIN="$(find_jfr_tool)" || {
  echo "BLOCKED_JFR_PARSER_UNAVAILABLE: jfr not found in PATH or JAVA_HOME" >&2
  exit 3
}

JFR_VERSION="$("${JFR_BIN}" version 2>/dev/null | head -1 || echo unknown)"
PARSER_TOOL="jfr/${JFR_VERSION}"

SHA256="$(sha256sum "${JFR_FILE}" | awk '{print $1}')"
FILE_SIZE="$(stat -c '%s' "${JFR_FILE}" 2>/dev/null || stat -f '%z' "${JFR_FILE}")"
STORAGE_REF="file://${JFR_FILE}"

TMPDIR="$(mktemp -d)"
trap 'rm -rf "${TMPDIR}"' EXIT

SUMMARY_TXT="${TMPDIR}/summary.txt"
SUMMARY_ERR="${TMPDIR}/summary.err"
EVENTS_JSON="${TMPDIR}/events.json"
PARSED_JSON="${TMPDIR}/parsed.json"

PARSER_STATUS="failed"
PARSER_ERROR=""
STATUS="collected_raw_unparsed"
MISSING_DETAILS='["jfrDetailedGcSummary","gcEvents","heapSummary","allocationSummary"]'
CAVEATS='["rawJfrNotInGit","jreNoJcmdUseRepositoryFallback","jfrLockedStreamState"]'

run_jfr() {
  timeout "${JFR_TIMEOUT}" "${JFR_BIN}" "$@" 2>"${SUMMARY_ERR}"
}

if run_jfr summary "${JFR_FILE}" >"${SUMMARY_TXT}"; then
  PARSER_STATUS="ok"
  STATUS="collected"
  CAVEATS='["rawJfrNotInGit"]'
  MISSING_DETAILS='[]'
  EVENT_FILTER="jdk.GarbageCollection,jdk.GCPhasePause,jdk.GCHeapSummary,jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB,jdk.JVMInformation,jdk.ActiveRecording"
  if run_jfr print --json --events "${EVENT_FILTER}" "${JFR_FILE}" >"${EVENTS_JSON}"; then
    if [[ -x "$(command -v python3)" && -f "${PY_HELPER}" ]]; then
      python3 "${PY_HELPER}" "${SUMMARY_TXT}" "${EVENTS_JSON}" >"${PARSED_JSON}"
    else
      PARSER_STATUS="partial"
      CAVEATS='["rawJfrNotInGit","python3UnavailableForEventAggregation"]'
      echo '{}' >"${PARSED_JSON}"
    fi
  else
    PARSER_STATUS="partial"
    MISSING_DETAILS='["gcEvents","heapSummary","allocationSummary"]'
    CAVEATS='["rawJfrNotInGit","jfrPrintEventsFailed"]'
    echo '{}' >"${PARSED_JSON}"
  fi
else
  if grep -qi "locked stream state" "${SUMMARY_ERR}"; then
    PARSER_ERROR="locked_stream_state"
    CAVEATS='["rawJfrNotInGit","jreNoJcmdUseRepositoryFallback","jfrLockedStreamState"]'
  elif grep -qi "empty" "${SUMMARY_ERR}"; then
    PARSER_ERROR="empty_recording"
  else
    PARSER_ERROR="summary_failed"
  fi
  : >"${SUMMARY_TXT}"
  echo '{}' >"${PARSED_JSON}"
fi

PARSER_MESSAGE=""
if [[ -s "${SUMMARY_ERR}" ]]; then
  PARSER_MESSAGE="$(tr '\n' ' ' < "${SUMMARY_ERR}" | sed 's/  */ /g' | cut -c1-240)"
fi

export STATUS PARSER_STATUS PARSER_ERROR PARSER_MESSAGE SCENARIO RECORDING_NAME JFR_SETTINGS
export STORAGE_REF SHA256 FILE_SIZE PARSER_TOOL MISSING_DETAILS CAVEATS PARSED_JSON
export LAB_JFR_FINALIZE_MODE="${LAB_JFR_FINALIZE_MODE:-durationBound}"
export LAB_JFR_DURATION="${LAB_JFR_DURATION:-}"
python3 - <<'PY' >"${TMPDIR}/out.json"
import json, os, pathlib

base = {
    "status": os.environ["STATUS"],
    "evidenceTier": "REFERENCE",
    "labTier": "LOCAL_REFERENCE_LAB",
    "w004Eligible": False,
    "nonAuthoritative": True,
    "nonAuthoritativeReasons": ["localEnvironment", "singleRunOnly", "provisionalBudgetOnly"],
    "scenario": os.environ["SCENARIO"],
    "jfrStartMode": "startup",
    "finalizationMode": os.environ.get("LAB_JFR_FINALIZE_MODE", "durationBound"),
    "recordingName": os.environ["RECORDING_NAME"],
    "settings": os.environ["JFR_SETTINGS"],
    "storageRef": os.environ["STORAGE_REF"],
    "sha256": os.environ["SHA256"],
    "fileSizeBytes": int(os.environ["FILE_SIZE"]),
    "parserTool": os.environ["PARSER_TOOL"],
    "parserStatus": os.environ["PARSER_STATUS"],
}
if os.environ.get("LAB_JFR_DURATION"):
    base["jfrDuration"] = os.environ["LAB_JFR_DURATION"]
if os.environ.get("PARSER_ERROR"):
    base["parserError"] = os.environ["PARSER_ERROR"]
if os.environ.get("PARSER_MESSAGE"):
    base["parserMessage"] = os.environ["PARSER_MESSAGE"]

parsed = json.loads(pathlib.Path(os.environ["PARSED_JSON"]).read_text() or "{}")
meta = parsed.get("recordingMetadata") or {}
for key in ("recordingName", "jdkVersion", "jfrFormatVersion", "commandLine", "startTime", "durationSeconds", "hostName", "osDescription"):
    if meta.get(key) is not None:
        base[key] = meta[key]

missing = json.loads(os.environ["MISSING_DETAILS"])
caveats = json.loads(os.environ["CAVEATS"])
gs = parsed.get("gcSummary") or {}
if gs:
    base["gcSummary"] = {k: v for k, v in gs.items() if k != "eventsChecked"}
if parsed.get("allocationSummary"):
    base["allocationSummary"] = parsed["allocationSummary"]

if base["parserStatus"] == "ok" and gs.get("gcPauseCount"):
    caveats = [c for c in caveats if c not in ("jfrLockedStreamState", "detailedGcSummaryUnavailable")]
    missing = [m for m in missing if m not in ("jfrDetailedGcSummary", "gcEvents")]
elif base["parserStatus"] == "ok" and gs.get("eventPresent"):
    missing = [m for m in missing if m != "gcEvents"]

if missing:
    base["missingDetails"] = missing
base["caveats"] = caveats
print(json.dumps(base, separators=(",", ":")))
PY

if [[ "${DRY_RUN}" == true ]]; then
  cat "${TMPDIR}/out.json"
  exit 0
fi

mkdir -p "$(dirname "${OUT_FILE}")"
cp "${TMPDIR}/out.json" "${OUT_FILE}"
echo "Wrote ${OUT_FILE} status=${STATUS} parserStatus=${PARSER_STATUS} parserError=${PARSER_ERROR:-none}"
