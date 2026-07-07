#!/usr/bin/env bash
# Read-only local lab metrics diagnostics (PR-9H-E2L-METRICS). No mutations.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

OUT_DIR="${REPO_HARNESS}/build/local-reference-lab/inventory"
mkdir -p "${OUT_DIR}"
STAMP="$(date +%Y%m%d-%H%M%S)"
REPORT="${OUT_DIR}/metrics-diagnostics-${STAMP}.txt"

require_cmd "${LAB_KUBECTL}"

{
  echo "=== PR-9H-E2L-METRICS read-only diagnostics ${STAMP} ==="
  echo "context=${LAB_KUBECTX} namespace=${LAB_NAMESPACE}"
  echo ""

  assert_not_prod_context

  echo "--- Host (if on Gentoo) ---"
  uname -a 2>/dev/null || true
  free -h 2>/dev/null || true
  df -h / 2>/dev/null || true
  kind version 2>/dev/null || echo "kind: N/A"
  echo ""

  echo "--- Kubernetes context ---"
  "${LAB_KUBECTL}" config current-context 2>/dev/null || true
  kind get clusters 2>/dev/null || true
  echo ""

  echo "--- Lab namespace pods ---"
  kubectl_lab get pods -o wide 2>/dev/null || true
  echo ""

  echo "--- Lab services ---"
  kubectl_lab get svc -o wide 2>/dev/null || true
  echo ""

  echo "--- Prometheus / operator (cluster-wide) ---"
  if "${LAB_KUBECTL}" get pods -A 2>/dev/null | grep -iE 'prometheus|grafana|kube-prometheus' | head -10; then
    echo "Prometheus-like pods found (see above)"
  else
    echo "NO Prometheus pods found cluster-wide"
  fi
  if "${LAB_KUBECTL}" get crd servicemonitors.monitoring.coreos.com 2>/dev/null; then
    "${LAB_KUBECTL}" get servicemonitor,podmonitor -A 2>/dev/null || true
  else
    echo "NO ServiceMonitor CRD (Prometheus Operator not installed)"
  fi
  echo ""

  echo "--- perf-app JFR / JVM env ---"
  APP_POD=$("${LAB_KUBECTL}" -n "${LAB_NAMESPACE}" get pods -l app=perf-harness-app \
    --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
  if [[ -n "${APP_POD}" ]]; then
    echo "perf-app pod: ${APP_POD}"
    kubectl_lab exec "${APP_POD}" -- printenv 2>/dev/null | grep -iE 'JAVA|JFR|PERF_|SPRING' || echo "(env probe failed or empty)"
    kubectl_lab exec "${APP_POD}" -- ls -la /tmp/jfr 2>/dev/null || echo "NO /tmp/jfr directory"
    kubectl_lab exec "${APP_POD}" -- sh -c 'echo $PERF_SCENARIO_JVM_OPTS' 2>/dev/null | grep -i StartFlightRecording \
      && echo "JFR startup: present" || echo "JFR startup: NOT configured"
  else
    echo "perf-app pod: not running"
  fi
  echo ""

  echo "--- Collector self-metrics (:8888 via pod IP) ---"
  COL_IP="$(pod_ip 'app=perf-harness-collector')"
  echo "collector pod IP: ${COL_IP:-unknown}"
  if [[ -n "${COL_IP}" ]] && command -v curl >/dev/null 2>&1; then
    curl -sS -m 10 "http://${COL_IP}:8888/metrics" 2>/dev/null | grep -E '^otelcol_' | head -20 \
      || echo "curl to collector metrics failed (try from in-cluster probe pod)"
  else
    echo "Skip curl (no pod IP or curl missing on host)"
  fi
  echo ""

  echo "--- Root cause summary ---"
  echo "cAdvisor/container_* metrics: require Prometheus + kubelet/cAdvisor scrape (NOT installed)"
  echo "Collector otelcol_* metrics: available at collector pod IP :8888 if pod reachable"
  echo "JFR: requires patch-local-jfr-startup.sh --execute (approval) + pod restart"
  echo "kind ClusterIP/DNS: known broken on Gentoo lab — use pod IP for probes"
} | tee "${REPORT}"

log "Report: ${REPORT}"
