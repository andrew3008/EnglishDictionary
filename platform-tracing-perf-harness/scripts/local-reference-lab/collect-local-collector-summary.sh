#!/usr/bin/env bash
# Collect collector self-metrics via pod IP (no Prometheus required). PR-9H-E2L-METRICS.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

RUN_ID="${1:-$(date +%Y%m%d-%H%M%S)}"
OUT="${REPO_HARNESS}/build/perf-results/local-reference-lab/${LAB_PROFILE_ID}/${RUN_ID}"
mkdir -p "${OUT}"

require_cmd "${LAB_KUBECTL}"
assert_not_prod_context

COL_IP="$(pod_ip 'app=perf-harness-collector')"
[[ -n "${COL_IP}" ]] || die "collector pod IP not found"

METRICS_RAW="${OUT}/collector-metrics-raw.txt"
SUMMARY="${OUT}/collector-compact-summary.json"

fetch_metrics() {
  if command -v curl >/dev/null 2>&1; then
    if curl -sS -m 5 "http://${COL_IP}:8888/metrics" > "${METRICS_RAW}" 2>/dev/null \
        && grep -q '^otelcol_' "${METRICS_RAW}"; then
      return 0
    fi
  fi
  log "Host curl failed or unreachable — using in-cluster probe pod"
  kubectl_lab run collector-metrics-probe --rm -i --restart=Never \
    --image=curlimages/curl:8.5.0 --command -- \
    curl -sS -m 15 "http://${COL_IP}:8888/metrics" > "${METRICS_RAW}"
}

fetch_metrics || die "collector metrics fetch failed"

parse_metric() {
  local name="$1"
  grep -E "^${name}" "${METRICS_RAW}" | head -1 | awk '{print $2}' || echo "null"
}

QUEUE_SIZE=$(parse_metric 'otelcol_exporter_queue_size')
QUEUE_CAP=$(parse_metric 'otelcol_exporter_queue_capacity')
SEND_FAILED=$(parse_metric 'otelcol_exporter_send_failed_spans')
ENQUEUE_FAILED=$(parse_metric 'otelcol_exporter_enqueue_failed_spans')
DROPPED=$(parse_metric 'otelcol_processor_dropped_spans')
ACCEPTED=$(parse_metric 'otelcol_receiver_accepted_spans')
REFUSED=$(parse_metric 'otelcol_receiver_refused_spans')

cat > "${SUMMARY}" <<EOF
{
  "status": "ok",
  "labTier": "LOCAL_REFERENCE_LAB",
  "w004Eligible": false,
  "nonAuthoritative": true,
  "source": "direct-pod-ip",
  "collectorPodIp": "${COL_IP}",
  "metricsEndpoint": "http://${COL_IP}:8888/metrics",
  "collector": {
    "exporterQueueSize": ${QUEUE_SIZE:-null},
    "exporterQueueCapacity": ${QUEUE_CAP:-null},
    "exporterSendFailedSpans": ${SEND_FAILED:-null},
    "exporterEnqueueFailedSpans": ${ENQUEUE_FAILED:-null},
    "processorDroppedSpans": ${DROPPED:-null},
    "receiverAcceptedSpans": ${ACCEPTED:-null},
    "receiverRefusedSpans": ${REFUSED:-null},
    "backpressureEvidenceValid": false
  },
  "caveats": [
    "Point-in-time scrape; not tied to steady-state load window",
    "Local lab only; backpressureEvidenceValid remains false (debug sink)"
  ]
}
EOF

log "Raw metrics: ${METRICS_RAW}"
log "Compact summary: ${SUMMARY}"
