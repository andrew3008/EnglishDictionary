# P4-WF.9 — Formal P4 closure gate

**Gate:** P4-WF.9  
**As of:** 2026-07-29  
**Independent audit:** [closure audit](eda6581a-b85a-4443-98d3-b56aa1c6c25c)  
**Verdict:** `FORMALLY_CLOSED`

## Checklist

| Criterion | Result | Evidence |
|---|---|---|
| Servlet parity | **PASS** | `TracingControlMvc*` + focused Servlet tests PASS (re-run 2026-07-29) |
| WebFlux parity | **PASS** | `TracingControlWebFlux*` + child-context/absence/route tests PASS (re-run) |
| new production classes ≤ budget | **PASS** | 1 WebFlux control class (`CLASS_BUDGET_ACCEPTED`) |
| class justifications complete | **PASS** | `class-justification-ledger.md` |
| focused/module tests | **PASS** | WebFlux + Servlet control + wire parity + `priv07ProposalVerify` |
| reactive/aggregate Docker E2E | **PASS** | `DOCKER_HOST=tcp://192.168.100.70:2375` Server=28.0.4; aggregate 28 suites / 65 tests, failures=0, skipped=0 |
| private isolation E2E | **PASS** | `privateMBeanServerTest` 1/1, failures=0, skipped=0 |
| aggregate E2E | **PASS** | `:platform-tracing-e2e-tests:test -PrunE2e` BUILD SUCCESSFUL in 9m 32s (post-PRIV-R07 payload-bound) |
| PROPOSED_PRIV_R07 APPROVED | **PASS** | Architecture Committee 2026-07-29 |
| evidence/code/plan consistent | **PASS** | independent audit 12/12; companion PRIV-R07 follow-ups landed |
| mandatory skipped gates = 0 | **PASS** | aggregate skipped=0; privateMBean skipped=0 |

## Closure record

Post-PRIV-R07 Docker re-run on Gentoo host completed green:

```text
DOCKER_HOST=tcp://192.168.100.70:2375
Server=28.0.4 OS=linux
:platform-tracing-e2e-tests:test -PrunE2e → 65 tests, 0 failures, 0 skipped
:platform-tracing-e2e-tests:privateMBeanServerTest → 1 test, 0 failures, 0 skipped
BUILD SUCCESSFUL in 9m 32s
```

Prior P4-WF.9 `BLOCKED` (Docker unreachable) is superseded by this run.

## Non-blocking

- Working tree dirty (expected pre-commit)
- Servlet `TracingControl*` still in shared autoconfigure (documented pre-existing placement defect)

## P5 unblock

Verdict `FORMALLY_CLOSED` completes `p4-formal-closure-gate` and formally unblocks P5.
