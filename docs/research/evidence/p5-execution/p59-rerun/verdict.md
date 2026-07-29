# P5.9 Corrective Rerun Verdict

## Verdict

**P5.9: BLOCKED_INFRASTRUCTURE**

Verified code commit: `d170ef36d925217d0843b52cda811084154408e3`.

The repository defects that caused the previous local gate failure are corrected:

- both MDC integration tests receive their explicit test-only JMX client dependency;
- configuration inventory is regenerated and reproducible;
- the production sampling `UNCHANGED` path avoids candidate construction and CAS;
- formal JMH verifies the real APPLIED, STALE, and UNCHANGED outcomes;
- `PerfAdminController` and the legacy platform-JMX mutation path are removed from M10;
- M10 fixtures now mutate a complete Category A snapshot through the private aggregate writer;
- the PowerShell runner validates sequence, digest, rejection, and LKG invariants.

P5 cannot be declared PASS because the mandatory external gates were not executed. The Docker
daemon at `tcp://192.168.100.70:2375` timed out during both `build` diagnostics and the explicit
`docker version` probe. Therefore:

- opt-in aggregate E2E is `NOT_RUN`;
- M10, M10c, and M10d are `NOT_RUN`;
- the `dynconf-no-stall` hard budget remains without new measured evidence.

This is an infrastructure blocker, not a remaining known code blocker. A skipped or not-run
mandatory gate is not reported as PASS.

## Local Verification

| Gate | Result |
|---|---|
| Agent extension tests | PASS |
| Spring Boot autoconfigure tests | PASS |
| Configuration provenance | PASS |
| Private MBeanServer | PASS |
| Configuration inventory | PASS |
| Root tests | PASS |
| Full build | PASS |
| Architecture fitness and module taxonomy | PASS |
| Formal P5.7 JMH | PASS |
| Legacy perf mutation scan | PASS |
| Perf PowerShell UTF-8 parse | PASS |
| Docker opt-in E2E | NOT_RUN |
| M10/M10c/M10d | NOT_RUN |

## JMH Correction

The prior `applyUnchanged` result allocated approximately 280 B/op because it constructed the
candidate before detecting equality. The corrected production fast path compares the witnessed
immutable sampling state first.

Formal rerun:

| Path | Score | Allocation | Contract |
|---|---:|---:|---|
| APPLIED | 153.336 ns/op | 168 B/op | <= 600 ns/op |
| STALE | 1.851 ns/op | below measurement floor | exact STALE, no retry |
| UNCHANGED | 7.680 ns/op | below measurement floor | <= 250 ns/op |

All codec, digest, and read budgets pass. Raw results and SHA-256 hashes are recorded in
`jmh/summary.json`.

## Exact Remaining Action

When Docker is reachable, execute in order:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
docker version
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --no-daemon --rerun-tasks
.\platform-tracing-perf-tests\scripts\run-perf-scenario.ps1 -Scenario m10 -DockerHost $env:DOCKER_HOST
.\platform-tracing-perf-tests\scripts\run-perf-scenario.ps1 -Scenario m10c -DockerHost $env:DOCKER_HOST
.\platform-tracing-perf-tests\scripts\run-perf-scenario.ps1 -Scenario m10d -DockerHost $env:DOCKER_HOST
```

Only successful, non-skipped execution with sequence/digest assertions and the measured M10 hard
budget can change this verdict to `P5.9 PASS`.
