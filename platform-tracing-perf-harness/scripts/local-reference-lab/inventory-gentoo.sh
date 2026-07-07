#!/usr/bin/env bash
# Read-only Gentoo / local lab inventory (PR-9H-E2L). No installs. No mutations.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

OUT_DIR="${REPO_HARNESS}/build/local-reference-lab/inventory"
mkdir -p "${OUT_DIR}"
STAMP="$(date +%Y%m%d-%H%M%S)"
REPORT="${OUT_DIR}/inventory-${STAMP}.txt"

{
  echo "=== PR-9H-E2L read-only inventory ${STAMP} ==="
  echo "profileId=${LAB_PROFILE_ID} namespace=${LAB_NAMESPACE}"
  echo ""

  if [[ "$(uname -s 2>/dev/null || echo unknown)" != "Linux" ]]; then
    echo "NOTE: Running on non-Linux host; host-local sections may be N/A."
    echo "Use SSH to run this script on Gentoo, or rely on Docker remote section below."
    echo ""
  fi

  echo "--- Host (local shell) ---"
  uname -a 2>/dev/null || echo "uname: N/A"
  [[ -f /etc/os-release ]] && cat /etc/os-release || echo "no /etc/os-release"
  echo "CPU cores: $(nproc 2>/dev/null || echo N/A)"
  free -h 2>/dev/null || echo "free: N/A"
  df -h / /var/tmp /home 2>/dev/null || df -h / 2>/dev/null || echo "df: N/A"
  if [[ -d /sys/fs/cgroup ]]; then
    stat -fc '%T %m' /sys/fs/cgroup 2>/dev/null || echo "cgroup: unknown"
  fi
  echo "init: $(ps -p 1 -o comm= 2>/dev/null || echo unknown)"
  echo ""

  echo "--- Tools (local shell) ---"
  for c in docker podman kubectl helm k3s kind minikube java gradle; do
    printf '%-10s ' "${c}:"
    command -v "${c}" 2>/dev/null || echo "missing"
  done
  echo ""

  echo "--- kubectl contexts (local shell) ---"
  kubectl config get-contexts 2>/dev/null || echo "kubectl: N/A"
  echo ""

  echo "--- Docker remote (${LAB_DOCKER_HOST:-local}) ---"
  if command -v docker >/dev/null 2>&1; then
    docker_cmd info 2>/dev/null | grep -E '^(Name|Operating System|Kernel|CPUs|Total Memory|Cgroup|Storage Driver|Server Version)' || \
      echo "docker info failed (check LAB_DOCKER_HOST / connectivity)"
    echo ""
    echo "Images (k6/otel/kind/k3s):"
    docker_cmd images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | \
      grep -E 'k6|otel|kind|k3s|kubernetes' | head -20 || true
  else
    echo "docker CLI missing"
  fi
  echo ""

  echo "--- Backend recommendation ---"
  echo "Prefer kind if Docker remote/local is healthy and kind binary available on Gentoo."
  echo "Prefer k3s if single-node cgroup fidelity needed AND install approved via SSH."
  echo "Blocked if: no container runtime, SSH+install not approved, or context ambiguous."
} | tee "${REPORT}"

log "Inventory report: ${REPORT}"
