#!/usr/bin/env bash
# Apply JFR startup overlay to local perf-app (approval: --execute). PR-9H-E2L-METRICS.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

source "${SCRIPT_DIR}/lib/jfr-local.sh"

MODE="dry-run"
for arg in "$@"; do
  [[ "${arg}" == "--execute" ]] && MODE="execute"
done

require_cmd "${LAB_KUBECTL}"
assert_not_prod_context

JFR_OPTS="$(lab_jfr_jvm_opts)"

log "Mode: ${MODE}"
log "JFR opts: ${JFR_OPTS}"
log "Will patch perf-harness-app: emptyDir /tmp/jfr + append JFR opts to PERF_SCENARIO_JVM_OPTS via deploy-perf-app-lab.sh"

OVERLAY="${REPO_HARNESS}/config/local-reference-lab/jfr/perf-app-jfr-patch.yaml"
GEN="${REPO_HARNESS}/build/local-reference-lab/jfr/perf-app-jfr-patch-rendered.yaml"
mkdir -p "$(dirname "${GEN}")"

# Read current PERF_SCENARIO_JVM_OPTS from configmap
CURRENT=$("${LAB_KUBECTL}" -n "${LAB_NAMESPACE}" get cm perf-harness-app-config \
  -o jsonpath='{.data.PERF_SCENARIO_JVM_OPTS}' 2>/dev/null || echo "")

cat > "${GEN}" <<EOF
apiVersion: v1
kind: ConfigMap
metadata:
  name: perf-harness-app-config
  namespace: ${LAB_NAMESPACE}
data:
  PERF_JFR_JVM_OPTS: "${JFR_OPTS}"
  PERF_JFR_ENABLED: "true"
EOF

dry_run_or_exec "${MODE}" "${LAB_KUBECTL} --context=${LAB_KUBECTX} apply -f ${GEN}"

PATCH_JSON='[
  {"op":"add","path":"/spec/template/spec/volumes","value":[{"name":"jfr-volume","emptyDir":{}}]},
  {"op":"add","path":"/spec/template/spec/containers/0/volumeMounts","value":[{"name":"jfr-volume","mountPath":"/tmp/jfr"}]}
]'
# Simpler: apply strategic merge from overlay template
MERGE="${REPO_HARNESS}/build/local-reference-lab/jfr/deployment-jfr-merge.yaml"
mkdir -p "$(dirname "${MERGE}")"
cat > "${MERGE}" <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: perf-harness-app
  namespace: ${LAB_NAMESPACE}
spec:
  template:
    spec:
      volumes:
        - name: jfr-volume
          emptyDir: {}
      containers:
        - name: perf-app
          env:
            - name: PERF_JFR_JVM_OPTS
              value: "${JFR_OPTS}"
          volumeMounts:
            - name: jfr-volume
              mountPath: /tmp/jfr
EOF

log "NOTE: Dockerfile entrypoint uses PERF_SCENARIO_JVM_OPTS only."
log "After --execute, update deploy-perf-app-lab.sh or configmap to append:"
log "  PERF_SCENARIO_JVM_OPTS=\"\${CURRENT} \${PERF_JFR_JVM_OPTS}\""
log "Then: kubectl rollout restart deployment/perf-harness-app -n ${LAB_NAMESPACE}"

dry_run_or_exec "${MODE}" "${LAB_KUBECTL} --context=${LAB_KUBECTX} apply -f ${MERGE}"
dry_run_or_exec "${MODE}" "${LAB_KUBECTL} --context=${LAB_KUBECTX} -n ${LAB_NAMESPACE} rollout restart deployment/perf-harness-app"
dry_run_or_exec "${MODE}" "${LAB_KUBECTL} --context=${LAB_KUBECTX} -n ${LAB_NAMESPACE} rollout status deployment/perf-harness-app --timeout=180s"

log "After run, copy JFR (approval for cp):"
log "  kubectl cp ${LAB_NAMESPACE}/<pod>:${JFR_FILE} ${LAB_ARTIFACT_DIR}/local-reference.jfr"
