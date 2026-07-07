# PR-9H-C Macro/Load/RSS Evidence Harness Architecture Decision

## Context

W-003 (validation JMH baseline) is RESOLVED. W-004 (macro/soak/RSS/release perf gates) remains OPEN.
The architecture committee requires professional, extensible macro evidence that complements JMH without
manufacturing false confidence from local-only or noisy workstation runs.

Opus 4.8 architecture options review compared six approaches (local k6, dedicated module,
Testcontainers, Kubernetes-only, hybrid, external perf-lab).

## Decision

Implement **Option E — Hybrid local smoke + Kubernetes reference run**, packaged as a dedicated
Gradle module **`platform-tracing-perf-harness`**.

## Chosen option

**Hybrid (E)** with **dedicated module (B)** structure.

- **SMOKE tier**: local/dev/CI-friendly, functional-only, short k6, non-authoritative
- **REFERENCE tier**: Kubernetes/pre-prod, open-model load, Prometheus/cAdvisor, JFR, soak — sole W-004 evidence source

## Rejected options

| Option | Reason |
|--------|--------|
| A — Local app + k6 only | False confidence; SRE will not accept for W-004 |
| C — Testcontainers-only | Dev-host kernel; not reference-grade |
| D — Kubernetes-only | CI-hostile; blocks scenario iteration |
| F — External perf-lab repo | Cross-repo drift; no credibility gain over hybrid |

**Note:** `platform-tracing-perf-tests` (M0–M10 docker-compose) remains for legacy matrix runs;
this harness adds explicit SMOKE/REFERENCE separation aligned with W-004 closure criteria.

## Consequences

- PR-9H-C delivers skeleton + SMOKE only; W-004 stays OPEN
- PR-9H-E prepares REFERENCE tier (K8s templates, evidence contract, guarded tasks); execution deferred to PR-9H-E2
- Reference runs require cluster access and SRE/architecture approval of evidence contract
- Developers get fast functional feedback without claiming pre-prod readiness
- Clear artifact tier stamping prevents smoke/reference confusion

## Follow-up PRs

PR-9H-D → PR-9H-E (contract/templates) → PR-9H-E1 (contract hardening) → PR-9H-E2 (first official K8s reference run) → PR-9H-F (soak) → PR-9H-G (W-004 decision)

See `PR-9H-macro-load-rss-harness.md`.

## Warning Register impact

- **W-004**: OPEN — PR-9H-E1 hardened schema, collector backpressure template, budgets/variance; official execution pending PR-9H-E2/F
- **W-011, W-012**: unchanged DIAGNOSTIC/OPEN — no reference macro evidence yet
- **W-003**: unchanged RESOLVED
- **W-007, W-013**: unchanged (not addressed under perf work)
