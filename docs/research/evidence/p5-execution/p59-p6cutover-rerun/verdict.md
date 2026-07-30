# P5.9 Verification Package — Post-P6 Cutover Rerun

## Verdict

**P5.9: PASS**

Verified working tree at commit `dca9f946918ef81ec4b9eb82b2b3844a3648d6e6` (plus uncommitted P6 cutover / perf-harness fixes in this train).

All mandatory local gates and Docker-backed gates executed and passed. No mandatory gate was skipped or reported as PASS without execution.

## Local Verification

| Gate | Result |
|---|---|
| Agent extension tests | PASS |
| Spring Boot autoconfigure tests | PASS |
| Configuration provenance | PASS |
| Private MBeanServer | PASS |
| Configuration inventory | PASS (regenerated) |
| Root `test` | PASS (`e2e:test` SKIPPED by design without `-PrunE2e`) |
| Full `build` | PASS |
| Architecture fitness + module taxonomy | PASS |
| Formal P5.7 JMH (`-f 3` / `-prof gc`) | PASS |
| Legacy perf mutation scan | PASS |

## Docker Verification

Endpoint: `DOCKER_HOST=tcp://192.168.100.70:2375` (Gentoo lab Engine 28.0.4).

| Gate | Result | Evidence |
|---|---|---|
| Docker opt-in E2E (`-PrunE2e`) | PASS | `e2e-runE2e.log` — BUILD SUCCESSFUL, 55 tests |
| M10 (2+10 @ 500 rps) | PASS | `m10/2026-07-30_131557/m10/summary.json` — runValid=true, k6=0, privateControlFlowValid=true, 6× APPLIED |
| M10c valid storm | PASS | `m10c/2026-07-30_132818/m10c/summary.json` — runValid=true, storm rows=297 |
| M10d invalid storm | PASS | `m10d/2026-07-30_140735/m10d/summary.json` — runValid=true, drops=0, seqDrift=0, rejected 1→299 |

## Cutover-driven fixes applied during this gate

- `PrivateControlBoundaryTest` counts only private-domain ready writers.
- `PlatformExtensionReadinessJmxTest` releases leaked `MBeanServer` instances.
- S-04 inventory / ABI / otel public-surface allowlist regenerated after public control-protocol deletion.
- S07 JMX read-only delta evidence/tests aligned to post-P6.6 contract.
- Perf SUT `application.yaml`: `platform.environment=PRODUCTION`, `platform.tracing.configuration.authority=HELM`.
- Perf harness: host-side workers via `Start-Process` (avoid Start-Job starvation under docker/k6 I/O); scenario env cleared before each load (prevent `PERF_RELOAD_SEQUENCE` leak into m10c/m10d); stray worker cleanup; UTF-8 BOM retained on `run-perf-scenario.ps1`.

## Formal JMH (apply path)

| Path | Score | Contract |
|---|---:|---|
| APPLIED | 154.239 ns/op | <= 600 ns/op |
| STALE | 1.859 ns/op | exact STALE |
| UNCHANGED | 7.744 ns/op | <= 250 ns/op |

Raw JSON + SHA-256 hashes: `jmh/summary.json`.

## Next

P5.9 no longer blocks P6 coherent cutover / P6.10 completion / P7.6–P7.8.
