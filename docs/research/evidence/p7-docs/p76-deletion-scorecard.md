# P7.6 — Deletion scorecard

**Date:** 2026-07-30  
**Basis:** working tree after P5.9 PASS + P6.10 PASS + P6.11 PASS  
**Verdict:** PASS (working-tree measurement)

Clean-checkout re-measure after commit remains for the immutable release SHA (P7.8).

## Plan §20 P7.5 targets

| Measure | Target | Measured | Evidence |
|---|---|---|---|
| Agent accepted-state owners | 1 | 1 | `AcceptedPolicyAccess` |
| supported runtime mutation paths | private writer only by authority adapters | private `applySnapshot` only | P6.10 / P6.11 |
| platform JMX semantic mutations | 0 | 0 | S07 post-P6.6 |
| private mutation operations | 1 (`applySnapshot`) | 1 | private writer MBean |
| private complete-policy reads | 1 (`readAcceptedComplete`) | 1 | PRIV-R07 |
| production/shared HTTP writes | 0 | 0 | P64 + scans |
| DEV typed leaf writes | exactly 8 | 8 | `TracingControlMvcController` |
| legacy production names | 0 | 0 | `P6LegacyNameFailFastTest` (14 banned) |
| aliases/dual readers | 0 | 0 | src/main scan |
| active Gradle modules | 16 | 16 | `pr1ModuleTaxonomyVerify` |

## Gate preconditions

| Gate | Result |
|---|---|
| P5.9 (local + Docker E2E + M10/M10c/M10d) | PASS |
| P6.10 zero-legacy | PASS |
| P6.11 coherent (local/published-consumer; E2E reused) | PASS |

## Writers remaining (intentional)

- Private `applySnapshot` / `AcceptedPolicyAccess`
- CONFIG_SERVER complete-manifest delivery
- DEV_ACTUATOR+LOCAL typed leaf commands

## Residual for release package

- Commit working tree → re-run scorecard on clean checkout SHA
- Human Architecture Committee rollout GO (P7.8) — production rollout FORBIDDEN until GO
