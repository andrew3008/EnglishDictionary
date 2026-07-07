# PR-9H-E2L local reference lab pre-flight checklist

**LOCAL_REFERENCE_LAB** — not official SRE/pre-prod. W-004 remains OPEN.

## Access

- [ ] SSH to Gentoo works (or operator runs scripts on-host)
- [ ] Remote host identified as Gentoo Linux
- [ ] Docker remote reachable (`LAB_DOCKER_HOST`) OR docker on-host
- [ ] **Not** connected to official/production Kubernetes context

## Resources

- [ ] CPU ≥ 4 (lab has 8 verified via Docker remote)
- [ ] RAM ≥ 16 GiB (lab has ~32 GiB)
- [ ] Disk free ≥ 20 GiB under `/var/lib/docker` and artifact dir
- [ ] cgroup v2 recorded (lab: v2 verified)

## Backend

- [ ] Backend selected: kind (recommended) / k3s (manual) / blocked
- [ ] kind or k3s binary available on execution host
- [ ] Human approval for cluster create/delete

## Lab identity

- [ ] namespace: `platform-tracing-reference-lab`
- [ ] profileId: `gentoo-local-reference-lab-v1`
- [ ] kubectl context: `kind-platform-tracing-reference-lab` (or approved local name)
- [ ] `labTier=LOCAL_REFERENCE_LAB` on all summaries

## Components

- [ ] Local reference sink image built/loaded (or missingMetrics documented)
- [ ] k6 ConfigMap generated (`prepareK6ReferenceConfigMap`)
- [ ] Prometheus/cAdvisor path available OR missingMetrics documented
- [ ] JFR artifact dir writable (`/var/tmp/...` or home fallback)
- [ ] gitCommit resolved (or `perfAllowUnknownGitCommit` with caveat)

## Governance

- [ ] Budgets acknowledged provisional
- [ ] All summaries: `w004Eligible=false`, `nonAuthoritative=true`
- [ ] Reasons include: `localEnvironment`, `singleRunOnly`, `provisionalBudgetOnly`
- [ ] No raw dumps committed under `evidence/reference-local/`
- [ ] No artifacts under official `evidence/reference/`
- [ ] E2 does not resolve W-004; W-011/W-012 unchanged by default
