# PR-9H-E2L-FULL — Local reference lab metrics run report

**Status:** `COMPLETED_PARTIAL_LOCAL_METRICS_RERUN_NON_AUTHORITATIVE`  
**runId:** `20260614-local-metrics-full-1`  
**Date:** 2026-06-15 (Gentoo T480)

## Classification

| Field | Value |
|-------|-------|
| evidenceTier | REFERENCE |
| labTier | LOCAL_REFERENCE_LAB |
| w004Eligible | **false** |
| nonAuthoritative | **true** |
| nonAuthoritativeReasons | localEnvironment, singleRunOnly, provisionalBudgetOnly, missingMetrics |

## Executive summary

Full local Gentoo rerun of S0/S1/S4 at TARGET_RPS=20 with Prometheus/cAdvisor compact metrics and collector self-metrics **completed**. JFR startup was configured but **0-byte dumps** — `jfrGc` in missingMetrics. kind DNS/ClusterIP workaround remains.

---

## 1. What changed

- Prometheus (`local-prom`) operational in `monitoring` namespace
- S0/S1/S4 k6 + prometheus-compact-summary + collector-compact-summary committed under this runId
- JFR overlay in deploy script; JFR capture failed (empty files)
- Scripts: `install-prometheus-kind.sh`, `run-full-local-metrics-gentoo.sh`, CRLF fixes for Gentoo

## 2. What did not change

- Production/runtime code, W-004 (**OPEN**), W-011/W-012 classification
- Official `evidence/reference/`

## 3. Local context

- Host: Gentoo T480, context `kind-platform-tracing-reference-lab`
- Namespace: `platform-tracing-reference-lab`

## 4–7. Metrics stack status

| Component | Status |
|-----------|--------|
| Prometheus | **INSTALLED** |
| cAdvisor container_* | **COLLECTED** (compact summaries per scenario) |
| Collector otelcol_* | **COLLECTED** (queue_size=0, capacity=1000) |
| JFR | **MISSING** (0-byte files after jcmd dump) |

## 8. kind DNS/ClusterIP

- **Broken** — pod IP workarounds for k6 and OTel (`localKindDnsWorkaround`)

## 9–10. Scenarios and k6

| Scenario | achieved RPS | p95 ms | dropped | errors |
|----------|--------------|--------|---------|--------|
| S0 | 20.008 | 1.52 | 0 | 0% |
| S1 | 20.004 | 1.97 | 0 | 0% |
| S4 | 20.004 | 1.99 | 0 | 0% |

TARGET_RPS=20, warmup=1m, steady=3m.

## 11. CPU/RSS/CFS (Prometheus compact)

| Scenario | coresRateAvg | per1kRps | throttleRatioPct | workingSetBytesAvg |
|----------|--------------|----------|------------------|---------------------|
| S0 | 0.027 | 1.37 | 0.0 | 323121152 |
| S1 | 0.052 | 2.58 | 0.0 | 430546944 |
| S4 | 0.044 | 2.20 | 0.0 | 418279424 |

metricSource: `container_memory_working_set_bytes/cAdvisor`

## 12. Collector metrics

- exporterQueueSize: 0, exporterQueueCapacity: 1000
- backpressureEvidenceValid: **false** (debug sink)
- localCollectorPodIpWorkaround documented

## 13. JFR/GC

- **Not collected** — JFR files 0 bytes; jfr-summary.json status=missing

## 14. Missing metrics

- jfrGc (all scenarios)

## 15. Evidence produced

`evidence/reference-local/gentoo-local-reference-lab-v1/20260614-local-metrics-full-1/{S0,S1,S4}/`

Per scenario: reference-summary.json, k6-summary.json, prometheus-compact-summary.json, collector-compact-summary.json, jfr-summary.json, command.txt

## 16–18. Validation / hygiene / W-004

- No raw .jfr, collector-metrics-raw, or Prom dumps committed
- W-004 remains **OPEN**

## 19–21. Tests / guardrails / limitations

- Gradle validation required post-merge
- JFR requires follow-up (jcmd in container may lack permissions or recording name mismatch)

## 22. Recommended next PR

- **PR-9H-E2L-JFR-fix** — local JFR startup capture fix
- **PR-9H-E2-run** — official SRE/pre-prod REFERENCE

## Warning Register close-out

- Warnings added: none
- Warnings resolved: none
- Warnings reclassified: none
- Warnings intentionally unchanged: W-003 … W-013
- Warning register updated: yes (W-004 note)
