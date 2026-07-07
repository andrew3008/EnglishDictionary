#!/usr/bin/env bash
# Read-only JFR verification after pod start. PR-9H-E2L-JFR-fix.
# Usage: LAB_KUBECTL=/usr/local/bin/kubectl-v136 bash jfr-smoke-check.sh
set -euo pipefail
K="${LAB_KUBECTL:-kubectl}"
NS="${LAB_NAMESPACE:-platform-tracing-reference-lab}"
POD=$($K get pods -n "$NS" -l app=perf-harness-app --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}')
[[ -n "$POD" ]] || { echo "No perf-app pod"; exit 1; }
echo "Pod: $POD"
$K exec -n "$NS" "$POD" -- sh -c '
ps -ef | grep "java.*sut-app" | grep -v grep
echo "--- JFR files (size path) ---"
find /tmp -name "*.jfr" -type f -exec sh -c "echo \$(wc -c < \"\$1\") \$1" _ {} \; 2>/dev/null | sort -rn | head -5
ls -la /tmp/jfr 2>/dev/null || true
'
