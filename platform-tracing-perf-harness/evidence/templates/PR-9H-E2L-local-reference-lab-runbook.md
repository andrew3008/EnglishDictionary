# PR-9H-E2L — Local Kubernetes REFERENCE Lab Runbook (Gentoo)

Rehearses PR-9H-E2 mechanics on the Gentoo reference laptop. **Not** equivalent to SRE/pre-prod REFERENCE evidence.

## Classification

| Field | Value |
|-------|-------|
| evidenceTier | REFERENCE |
| labTier | LOCAL_REFERENCE_LAB |
| w004Eligible | **false** |
| nonAuthoritative | **true** |
| nonAuthoritativeReasons | localEnvironment, singleRunOnly, provisionalBudgetOnly (+ missingMetrics if needed) |

W-004 stays **OPEN**. W-011/W-012 stay **DIAGNOSTIC/OPEN** unless reproducible macro issue found (unlikely from local lab alone).

## Phase 1 — Read-only inventory

From Gentoo (SSH):

```bash
cd platform-tracing-perf-harness/scripts/local-reference-lab
cp local-reference-lab.env.example local-reference-lab.env  # edit LAB_SSH_USER etc.
chmod +x *.sh lib/common.sh
./inventory-gentoo.sh
```

From Windows (partial, Docker remote only):

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
bash scripts/local-reference-lab/inventory-gentoo.sh
```

**2026-06-14 Docker remote snapshot (read-only):**

| Item | Value |
|------|-------|
| Host | T480 |
| OS | Gentoo Linux |
| Kernel | 6.12.31-gentoo |
| CPUs | 8 |
| Memory | 31.22 GiB |
| Cgroup | v2 |
| Docker | 28.0.4 |
| SSH | Permission denied from dev workstation (key not configured) |

**Backend decision:** **kind** (Docker healthy; avoids k3s install/sudo). k3s documented as manual optional path.

## Phase 2 — Setup (requires approval)

```bash
./setup-kind-lab.sh --dry-run
./setup-kind-lab.sh --execute   # APPROVAL REQUIRED
```

k3s: see `setup-k3s-lab.sh` — manual only.

## Phase 3 — Build images

```bash
./build-local-images.sh --dry-run
./build-local-images.sh --execute   # on Gentoo / Docker host
kind load docker-image local-reference-sink:lab --name platform-tracing-reference-lab
```

Perf app image: build `bootJar` + agent extension per lab README (manual).

## Phase 4 — Deploy

```bash
./gradlew :platform-tracing-perf-harness:prepareK6ReferenceConfigMap
./deploy-local-reference-lab.sh --dry-run
./deploy-local-reference-lab.sh --execute
```

## Phase 5 — Calibrate local TARGET_RPS

Short S0 calibration (not official evidence). Pin `LAB_TARGET_RPS` at ~60–70% stable throughput. Same value for S0/S1/S4.

## Phase 6 — Run scenarios

```bash
export LAB_TARGET_RPS=300   # example — use calibrated value
./run-local-reference-scenarios.sh --dry-run --scenarios=S0,S1,S4
./run-local-reference-scenarios.sh --execute --scenarios=S0,S1,S4
```

## Phase 7 — Collect

```bash
./collect-local-reference-summary.sh --scenario S4 --run-id 20260614-local-1
```

Fill metrics from k6/Prometheus/JFR — **never invent values**.

Copy compact files to `evidence/reference-local/gentoo-local-reference-lab-v1/<runId>/`.

## Phase 8 — Validate

```bash
./gradlew :platform-tracing-perf-harness:test
./gradlew :platform-tracing-perf-harness:perfReferenceValidateLocalLabConfig
```

## Phase 9 — Cleanup (approval)

```bash
./cleanup-local-reference-lab.sh --execute --confirm-delete
```

## Human approval required for

- kind cluster create/delete
- namespace delete
- k3s install (not automated)
- long-running k6 jobs
- sudo / emerge / systemctl / firewall changes

## What this lab cannot claim

- W-004 resolution
- Production readiness
- Equivalence to SRE/pre-prod cluster
- `w004Eligible=true`
- W-011/W-012 reclassification (default)

## Metrics diagnostics (PR-9H-E2L-METRICS)

After k6-only run (`20260614-local-1`), CPU/RSS/JFR/Prometheus were missing:

| Gap | Root cause | Local fix |
|-----|------------|-----------|
| cAdvisor `container_*` | Prometheus not installed | Helm kube-prometheus-stack (approval) |
| Collector `otelcol_*` | Not scraped; DNS broken | `collect-local-collector-summary.sh` (pod IP) |
| JFR | Not in JVM opts | `patch-local-jfr-startup.sh --execute` |

Runbook: `evidence/templates/PR-9H-E2L-metrics-debug-runbook.md`
