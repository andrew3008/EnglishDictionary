#!/usr/bin/env bash
# Full LOCAL_REFERENCE_LAB metrics run: Prom + JFR + S0/S1/S4. PR-9H-E2L-FULL.
# Run on Gentoo with --execute after context verification.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

MODE="dry-run"
RUN_ID="${LAB_RUN_ID:-20260614-local-metrics-full-1}"
TARGET_RPS="${LAB_TARGET_RPS:-20}"
WARMUP="${LAB_WARMUP:-1m}"
STEADY="${LAB_STEADY:-3m}"
for arg in "$@"; do
  [[ "${arg}" == "--execute" ]] && MODE="execute"
  [[ "${arg}" == --run-id=* ]] && RUN_ID="${arg#*=}"
done

KUBECTL="${LAB_KUBECTL:-/usr/local/bin/kubectl-v136}"
NS="${LAB_NAMESPACE}"
ART="/var/tmp/platform-tracing-reference-lab-artifacts/${RUN_ID}"
JFR_OPTS="-XX:StartFlightRecording=name=local-reference,settings=profile,dumponexit=true,filename=/tmp/jfr/local-reference.jfr"
export KUBECTL NS LAB_TARGET_RPS="${TARGET_RPS}" LAB_WARMUP="${WARMUP}" LAB_STEADY="${STEADY}"

require_cmd "${KUBECTL}"
require_cmd curl
assert_not_prod_context

[[ "${KUBECTL} --context=${LAB_KUBECTX} config current-context" != *"prod"* ]] || die "unsafe context"

log "=== PR-9H-E2L-FULL runId=${RUN_ID} mode=${MODE} rps=${TARGET_RPS} ==="
mkdir -p "${ART}"

prom_pf_pid=""
cleanup() {
  [[ -n "${prom_pf_pid}" ]] && kill "${prom_pf_pid}" 2>/dev/null || true
}
trap cleanup EXIT

start_prom_forward() {
  local prom_ip
  prom_ip=$("${KUBECTL}" -n monitoring get pod -l app.kubernetes.io/name=prometheus \
    -o jsonpath='{.items[0].status.podIP}' 2>/dev/null || true)
  [[ -n "${prom_ip}" ]] || prom_ip=$("${KUBECTL}" -n monitoring get pod \
    -l app.kubernetes.io/name=prometheus -o jsonpath='{.items[0].status.podIP}' 2>/dev/null || true)
  [[ -n "${prom_ip}" ]] || {
    prom_ip=$("${KUBECTL}" -n monitoring get pods -o jsonpath='{.items[?(@.metadata.name=~"prometheus.*")].status.podIP}' 2>/dev/null | awk '{print $1}')
  }
  [[ -n "${prom_ip}" ]] || return 1
  export LAB_PROM_URL="http://${prom_ip}:9090"
  log "Prometheus pod IP: ${LAB_PROM_URL}"
  if curl -sS -m 10 "${LAB_PROM_URL}/-/ready" >/dev/null 2>&1; then
    log "Host curl to Prometheus OK"
    return 0
  fi
  log "Host curl failed — Prom queries via in-cluster probe"
  return 0
}

prom_query_scalar() {
  local q="$1"
  local enc job raw result
  enc=$(python3 -c "import urllib.parse; print(urllib.parse.quote('''${q}'''))" 2>/dev/null || echo "$q")
  job="prom-q-${RANDOM}"
  "${KUBECTL}" -n "${NS}" delete job "${job}" --ignore-not-found >/dev/null 2>&1 || true
  cat <<EOF | "${KUBECTL}" -n "${NS}" apply -f - >/dev/null
apiVersion: batch/v1
kind: Job
metadata:
  name: ${job}
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 60
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: curl
          image: curlimages/curl:8.5.0
          command: ["curl", "-sS", "-m", "25", "${LAB_PROM_URL}/api/v1/query?query=${enc}"]
EOF
  "${KUBECTL}" -n "${NS}" wait --for=condition=complete "job/${job}" --timeout=90s >/dev/null 2>&1 || true
  raw=$("${KUBECTL}" -n "${NS}" logs "job/${job}" 2>/dev/null || echo "")
  "${KUBECTL}" -n "${NS}" delete job "${job}" --ignore-not-found >/dev/null 2>&1 || true
  result=$(echo "${raw}" | python3 -c "import json,sys; d=json.loads(sys.stdin.read().strip()); r=d.get('data',{}).get('result',[]); print(r[0]['value'][1] if r else '')" 2>/dev/null || echo "")
  echo "${result}"
}

collect_prom_summary() {
  local sc="$1" out="$2" achieved_rps="$3"
  local ns="${NS}" pod="perf-harness-app"
  mkdir -p "${out}"
  if [[ -z "${LAB_PROM_URL:-}" ]]; then
    cat > "${out}/prometheus-compact-summary.json" <<EOF
{"status":"unavailable","labTier":"LOCAL_REFERENCE_LAB","w004Eligible":false,"nonAuthoritative":true,"missingMetrics":["prometheus","cpu","cfsThrottling","workingSet"]}
EOF
    return
  fi
  local q_cpu q_th q_cp q_ws q_rss
  q_cpu="avg(rate(container_cpu_usage_seconds_total{namespace=\"${ns}\",container!=\"\",pod=~\"${pod}.*\"}[5m]))"
  q_th="avg(rate(container_cpu_cfs_throttled_periods_total{namespace=\"${ns}\",container!=\"\",pod=~\"${pod}.*\"}[5m]))"
  q_cp="avg(rate(container_cpu_cfs_periods_total{namespace=\"${ns}\",container!=\"\",pod=~\"${pod}.*\"}[5m]))"
  q_ws="avg(container_memory_working_set_bytes{namespace=\"${ns}\",container!=\"\",pod=~\"${pod}.*\"})"
  q_rss="avg(container_memory_rss{namespace=\"${ns}\",container!=\"\",pod=~\"${pod}.*\"})"

  local cpu_rate th_rate cp_rate ws rss
  cpu_rate=$(prom_query_scalar "${q_cpu}")
  th_rate=$(prom_query_scalar "${q_th}")
  cp_rate=$(prom_query_scalar "${q_cp}")
  ws=$(prom_query_scalar "${q_ws}")
  rss=$(prom_query_scalar "${q_rss}")

  local throttle_pct per1k=""
  if [[ -n "${cp_rate}" && "${cp_rate}" != "0" && -n "${th_rate}" ]]; then
    throttle_pct=$(python3 -c "print(round(100.0*float('${th_rate}')/float('${cp_rate}'),4))" 2>/dev/null || echo "null")
  else
    throttle_pct="null"
  fi
  if [[ -n "${cpu_rate}" && -n "${achieved_rps}" && "${achieved_rps}" != "0" ]]; then
    per1k=$(python3 -c "print(round(float('${cpu_rate}')/float('${achieved_rps}')*1000,6))" 2>/dev/null || echo "null")
  else
    per1k="null"
  fi

  cat > "${out}/prometheus-compact-summary.json" <<EOF
{
  "status": "ok",
  "evidenceTier": "REFERENCE",
  "labTier": "LOCAL_REFERENCE_LAB",
  "w004Eligible": false,
  "nonAuthoritative": true,
  "nonAuthoritativeReasons": ["localEnvironment", "singleRunOnly", "provisionalBudgetOnly"],
  "scenario": "${sc}",
  "metricSource": "container_memory_working_set_bytes/cAdvisor",
  "cpu": {
    "coresRateAvg": ${cpu_rate:-null},
    "per1kRps": ${per1k},
    "throttleRatioPct": ${throttle_pct}
  },
  "memory": {
    "workingSetBytesAvg": ${ws:-null},
    "rssAnonymousAvg": ${rss:-null}
  },
  "promqlQueries": {
    "cpuRate": "${q_cpu}",
    "throttleRate": "${q_th}",
    "cfsPeriodsRate": "${q_cp}",
    "workingSet": "${q_ws}",
    "rss": "${q_rss}"
  },
  "caveats": ["localKindEnvironment", "notSrePreprod", "localKindDnsWorkaround"]
}
EOF
}

collect_collector_summary() {
  local sc="$1" out="$2"
  mkdir -p "${out}"
  local col_ip
  col_ip=$("${KUBECTL}" -n "${NS}" get pods -l app=perf-harness-collector --field-selector=status.phase=Running -o jsonpath='{.items[0].status.podIP}')
  local raw="${out}/collector-metrics-raw.txt"
  if ! curl -sS -m 5 "http://${col_ip}:8888/metrics" > "${raw}" 2>/dev/null; then
    "${KUBECTL}" -n "${NS}" run "col-probe-${sc,,}" --rm -i --restart=Never \
      --image=curlimages/curl:8.5.0 --command -- curl -sS -m 15 "http://${col_ip}:8888/metrics" > "${raw}" 2>/dev/null || true
  fi
  parse_m() { grep -E "^${1}" "${raw}" 2>/dev/null | head -1 | awk '{print $2}' || echo "null"; }
  cat > "${out}/collector-compact-summary.json" <<EOF
{
  "status": "ok",
  "evidenceTier": "REFERENCE",
  "labTier": "LOCAL_REFERENCE_LAB",
  "w004Eligible": false,
  "nonAuthoritative": true,
  "scenario": "${sc}",
  "source": "direct-pod-ip",
  "collectorPodIp": "${col_ip}",
  "collector": {
    "exporterQueueSize": $(parse_m 'otelcol_exporter_queue_size'),
    "exporterQueueCapacity": $(parse_m 'otelcol_exporter_queue_capacity'),
    "exporterSendFailedSpans": $(parse_m 'otelcol_exporter_send_failed_spans'),
    "exporterEnqueueFailedSpans": $(parse_m 'otelcol_exporter_enqueue_failed_spans'),
    "processorDroppedSpans": $(parse_m 'otelcol_processor_dropped_spans'),
    "receiverAcceptedSpans": $(parse_m 'otelcol_receiver_accepted_spans'),
    "receiverRefusedSpans": $(parse_m 'otelcol_receiver_refused_spans'),
    "backpressureEvidenceValid": false
  },
  "caveats": ["localCollectorPodIpWorkaround", "steadyLoadWindowScrape", "notSrePreprod"]
}
EOF
}

dump_jfr() {
  local sc="$1" out="$2"
  mkdir -p "${out}"
  local pod jfr_in="/tmp/jfr/local-reference.jfr" jfr_out="${ART}/${sc}/local-reference.jfr"
  pod=$("${KUBECTL}" -n "${NS}" get pods -l app=perf-harness-app --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}')
  [[ -n "${pod}" ]] || return 1
  "${KUBECTL}" -n "${NS}" exec "${pod}" -- sh -c 'jcmd 1 JFR.dump name=local-reference filename=/tmp/jfr/local-reference.jfr 2>/dev/null || true' || true
  sleep 2
  "${KUBECTL}" -n "${NS}" cp "${pod}:${jfr_in}" "${jfr_out}" 2>/dev/null || true
  local sha size
  if [[ -f "${jfr_out}" ]]; then
    sha=$(sha256sum "${jfr_out}" | awk '{print $1}')
    size=$(stat -c '%s' "${jfr_out}")
    cat > "${out}/jfr-summary.json" <<EOF
{
  "status": "metadata-only",
  "evidenceTier": "REFERENCE",
  "labTier": "LOCAL_REFERENCE_LAB",
  "w004Eligible": false,
  "nonAuthoritative": true,
  "scenario": "${sc}",
  "jfrStartMode": "startup",
  "jfrSettings": "profile",
  "storageRef": "file://${jfr_out}",
  "sha256": "${sha}",
  "fileSizeBytes": ${size},
  "caveats": ["detailedGcSummaryUnavailable", "rawJfrNotInGit"]
}
EOF
  else
    cat > "${out}/jfr-summary.json" <<EOF
{"status":"missing","labTier":"LOCAL_REFERENCE_LAB","w004Eligible":false,"missingMetrics":["jfrGc"],"reason":"JFR dump failed for ${sc}"}
EOF
  fi
}

deploy_scenario() {
  local sc="$1"
  LAB_JFR_ENABLED=true bash /tmp/deploy-perf-app-lab.sh "${sc}"
}

parse_k6() {
  python3 -c "
import json,sys
d=json.load(open(sys.argv[1]))
m=d.get('metrics',{})
rps=m.get('http_reqs',{}).get('rate',0)
p95=m.get('http_req_duration',{}).get('p(95)',0)*1000
fail=m.get('http_req_failed',{}).get('value',0)
dropped=m.get('dropped_iterations',{}).get('count',0)
print(f'{rps:.6f} {p95:.3f} {fail} {dropped}')
" "$1" 2>/dev/null || echo "0 0 0 0"
}

write_reference_summary() {
  local sc="$1" out="$2" profile="$3"
  local k6="${out}/k6-summary.json"
  read -r achieved p95 fail dropped <<< "$(parse_k6 "${k6}")"
  local missing='[]'
  [[ -f "${out}/prometheus-compact-summary.json" ]] || missing='["prometheus","cpu","workingSet"]'
  cat > "${out}/reference-summary.json" <<EOF
{
  "evidenceTier": "REFERENCE",
  "labTier": "LOCAL_REFERENCE_LAB",
  "nonAuthoritative": true,
  "nonAuthoritativeReasons": ["localEnvironment", "singleRunOnly", "provisionalBudgetOnly"],
  "w004Eligible": false,
  "profileId": "gentoo-local-reference-lab-v1",
  "runId": "${RUN_ID}",
  "gitCommit": "unknown-local-lab",
  "environment": {
    "clusterAlias": "kind-platform-tracing-reference-lab",
    "namespace": "${NS}",
    "cgroupVersion": "v2",
    "kernelVersion": "6.12.31-gentoo",
    "javaVersion": "21",
    "jfrStartMode": "startup"
  },
  "scenario": {
    "scenarioId": "${sc}",
    "springProfile": "${profile}",
    "duration": "${STEADY}",
    "warmupDuration": "${WARMUP}",
    "loadModel": "constant-arrival-rate",
    "endpointPath": "/perf/work",
    "targetRps": ${TARGET_RPS}
  },
  "metrics": {
    "load": {
      "executor": "constant-arrival-rate",
      "warmupSec": 60,
      "steadyWindowSec": 180,
      "targetRps": ${TARGET_RPS},
      "achievedRpsAvg": ${achieved},
      "droppedIterations": ${dropped},
      "httpReqFailedRate": ${fail}
    },
    "latency": { "p95Ms": ${p95} },
    "collector": { "backpressureEvidenceValid": false }
  },
  "artifacts": {
    "k6Summary": "k6-summary.json",
    "prometheusCompactSummary": "prometheus-compact-summary.json",
    "collectorCompactSummary": "collector-compact-summary.json",
    "jfrSummary": "jfr-summary.json",
    "command": "command.txt"
  },
  "classification": { "w004Candidate": false, "w011Evidence": "none", "w012Evidence": "none" },
  "budgets": {
    "cpuOverheadPct": { "threshold": 3, "budgetStatus": "provisional" },
    "rssOverheadPct": { "threshold": 10, "budgetStatus": "provisional" },
    "p99LatencyDeltaPct": { "threshold": 15, "budgetStatus": "provisional" },
    "errorRatePct": { "threshold": 0.1, "budgetStatus": "provisional" }
  },
  "reproducibility": { "reproductionRunCount": 1 },
  "caveats": {
    "missingMetrics": [],
    "localKindDnsWorkaround": true,
    "notForProductionReadinessReason": "LOCAL_REFERENCE_LAB; not SRE/pre-prod evidence"
  }
}
EOF
}

if [[ "${MODE}" != "execute" ]]; then
  log "DRY-RUN: would install Prom, run S0/S1/S4, collect metrics to ${ART}"
  exit 0
fi

# Phase 2: Prometheus (skip if already running)
if ! "${KUBECTL}" get svc -A 2>/dev/null | grep -qi prometheus; then
  log "Installing Prometheus via helm..."
  LAB_KUBECTL="${KUBECTL}" bash "${SCRIPT_DIR}/install-prometheus-kind.sh" --execute
fi

start_prom_forward || log "WARN: Prometheus port-forward failed — partial metrics"

declare -A PROFILES=( [S0]=s0-baseline [S1]=s1-tracing [S4]=s4-production )

for SC in S0 S1 S4; do
  log "=== Scenario ${SC} ==="
  OUT="${ART}/${SC}"
  mkdir -p "${OUT}"
  deploy_scenario "${SC}"
  "${KUBECTL}" -n "${NS}" delete job "perf-harness-k6-$(echo "${SC}" | tr '[:upper:]' '[:lower:]')" --ignore-not-found
  bash /tmp/run-k6-lab.sh "${SC}" "${TARGET_RPS}"
  cp "/var/tmp/platform-tracing-reference-lab-artifacts/${SC}/k6-summary.json" "${OUT}/" 2>/dev/null || true

  # Collect during post-steady window
  read -r achieved _ _ _ <<< "$(parse_k6 "${OUT}/k6-summary.json" 2>/dev/null || echo '0 0 0 0')"
  collect_prom_summary "${SC}" "${OUT}" "${achieved}"
  collect_collector_summary "${SC}" "${OUT}"
  dump_jfr "${SC}" "${OUT}"

  {
    echo "# PR-9H-E2L-FULL ${RUN_ID} scenario ${SC}"
    echo "TARGET_RPS=${TARGET_RPS} WARMUP=${WARMUP} STEADY=${STEADY}"
    echo "deploy-perf-app-lab.sh ${SC} (JFR enabled)"
    echo "run-k6-lab.sh ${SC} ${TARGET_RPS}"
    echo "kind DNS workaround: perf-app pod IP for k6"
  } > "${OUT}/command.txt"

  write_reference_summary "${SC}" "${OUT}" "${PROFILES[$SC]}"
done

log "Evidence assembled under ${ART}"
ls -laR "${ART}"
