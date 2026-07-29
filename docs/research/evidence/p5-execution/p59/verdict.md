# P5.9 Independent Verification Verdict

> Date: 2026-07-29
> HEAD: `84f448800db5851cc6ab9bbfc3f7a8e3d6d643e7`
> Verdict: **P5 FAIL**

## 1. Executive verdict

P5 cannot be closed. This is a deterministic `FAIL`, not merely an infrastructure
`BLOCKED` result:

1. the mandatory Spring module suite, root test suite and full build fail;
2. `tracingConfigurationInventoryVerify` fails on committed-inventory drift;
3. formal JMH violates the committed `applyUnchanged = 0 B/op` contract;
4. the APPLIED and STALE benchmarks do not prove that each measured invocation
   executes the outcome named by the benchmark;
5. macro M10 still uses the P5.8-forbidden PerfAdmin/`PlatformTracingControl`
   mutation path.

The M10 execution additionally became infrastructure-blocked when the Gentoo
Docker endpoint stopped responding. That does not soften the overall verdict,
because repository-local failures already make P5 fail.

## 2. Green evidence

| Gate | Result |
|---|---|
| Agent focused module | PASS, 866 tests, 0 skipped |
| Focused P5 Spring tests | PASS, 200 tests, 0 skipped |
| `configProvenanceVerify` | PASS |
| `privateMBeanServerVerify` | PASS |
| Opt-in Docker E2E | PASS, 28 classes / 65 tests / 0 failures / 0 skipped |
| P57 budget-contract test | PASS |
| JMH read paths | PASS, allocation below measurement floor |
| JMH digest and codec latency budgets | PASS |

## 3. Blocking findings

### P5-B1: mandatory Spring/full suites are red

The Spring module reports 595 tests and three failures:

- inventory drift;
- missing `PlatformTracingJmxClient` in `MdcPropagationAsyncIntegrationTest`;
- missing `PlatformTracingJmxClient` in `MdcPropagationCompletableFutureTest`.

The same failures reproduce in root `test` and full `build`.

### P5-B2: inventory gate is red

`tracingConfigurationInventoryVerify` reports that
`docs/research/evidence/tracing-configuration-inventory/inventory.json` differs
from the deterministic generated inventory. Regeneration was intentionally not
performed in this independent, no-code-generation run.

### P5-B3: formal allocation budget fails

The formal standalone JMH run used three measurement forks, two warmup forks,
three 5-second warmup iterations and five 5-second measurement iterations.

`applyUnchanged` result:

```text
21.515 +/- 0.200 ns/op
280.000006 B/op
```

The committed contract requires `0 B/op`; therefore P5.7 fails.

The source also creates one store per iteration, not per invocation.
`applyApplied` applies only on the first invocation and then measures a different
branch. `applyStale` performs two high-level updates but never supplies a captured
stale expected base or asserts a `STALE` outcome. These measurements cannot be
accepted as APPLIED/STALE proof.

### P5-B4: M10 production-flow proof is absent

The macro harness still contains `/perf/admin/*` calls and
`PerfAdminController`, contrary to the explicit P5.8 prohibition. The attempted
M10 run then failed before compose startup because Docker became unreachable.
M10c/M10d were not run. Required sequence/digest/collector outputs do not exist.

### P5-B5: no compatible before/after reference-lab evidence

The committed plan requires raw before/after evidence on the same approved
reference lab. This run produced raw local JMH JSON, but no compatible pre-change
P57 baseline exists. Local measurements therefore cannot close the comparative
acceptance requirement.

## 4. Evidence files

- `command-results.json` - exact command/result matrix;
- `environment.json` - execution environment and source HEAD;
- `docker-e2e-summary.json` - XML-derived E2E counts;
- `m10-execution.json` and `m10-failure.log` - macro attempt;
- `jmh/*.json` - raw formal JMH results and normalized summary.

SHA-256:

```text
p57-apply-jmh.json  1B18F9CD219378F6C0611BFF1D02312F88E719E65ECC907007ED527BB9882B96
p57-codec-jmh.json  F3E4B8E42A6FC02E6B16355FA192E81116AF8C17AAFE4AB658588CFFC1846A8C
p57-digest-jmh.json CA8368AA670EFD523D4DD8B6ABF4D9C90D40AED09AE9641BD1967CD661FF9D7E
p57-read-jmh.json   A99CE56BE8FDC84999EF33A6679FFF5B83E266C17DC3A1B8EF3E447EC59A93B6
```

## 5. Gate status

```text
P5 IMPLEMENTATION FIXTURES: PARTIAL
P5 FUNCTIONAL VERIFICATION: FAIL
P5 PERFORMANCE VERIFICATION: FAIL
P5 MACRO M10: BLOCKED_INFRASTRUCTURE + CONTRACT FAIL
P5.9: FAIL
P6 PRODUCTION CUTOVER: BLOCKED
```

P5 may be re-run only after the exact blockers above are corrected. A skipped
or unit-only replacement for macro M10 must not be counted as closure evidence.
