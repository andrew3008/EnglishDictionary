# P4-WF.1 — Independent minimality audit

**Gate:** P4-WF DESIGN (reviewer artifact)  
**Author design:** [`minimal-design.md`](./minimal-design.md)  
**Census:** [`repository-census.md`](./repository-census.md)  
**Reviewer:** Explore subagent `[design minimality audit](af92f9e4-54aa-4c54-a536-f4adb8c7488a)`  
**Opus attempt:** timed out (`requestId` `285eba2e-6533-4895-ba15-fb77b7a49f90`) — not used as evidence  
**Verdict:** `MINIMAL_DESIGN_ACCEPTED`

## 1. Checklist

| # | Criterion | Result |
|---|---|---|
| 1 | Reuses adapter + DTOs + private JMX + authority/readiness; no second command/state owner | **PASS** |
| 2 | WebFlux code is transport-only (`RouterFunction` registration/mapping) | **PASS** |
| 3 | No new Gradle module / public API-DTO / generic transport framework | **PASS** |
| 4 | Agent / private JMX / wire contracts unchanged | **PASS** |
| 5 | Exact create/modify list minimal and correct vs census | **PASS** |
| 6 | Negative list rejects forbidden abstractions | **PASS** |
| 7 | Module placement in autoconfigure-webflux justified by isolation | **PASS** |
| 8 | Exactly 1 new production class; Servlet class cannot be safely extended | **PASS** |
| 9 | Design does not ratify Servlet-only limitation | **PASS** |

## 2. Strongest counterargument (rejected)

Place WebFlux transport in shared autoconfigure with `compileOnly spring-webflux`.

Fails: Servlet/WebFlux isolation rules; expands documented shared-module Servlet `TracingControl*` defect; webflux module exists precisely to keep WebFlux types off Servlet classpaths; Boot can discover a second `ManagementContextConfiguration.imports` from the webflux jar.

## 3. Residual risks (non-blocking for DESIGN)

- Path/`outcome` duplication drift → mitigated by parity tests  
- Blocking adapter work uses `boundedElastic` → cover in WebFlux HTTP tests  
- Servlet placement asymmetry remains a pre-existing defect (not expanded)

## 4. Authorisation

```text
MINIMAL_DESIGN_ACCEPTED
production code changed by this gate = false
P4-WF.2 class-budget remains a separate gate (ledger already CLASS_BUDGET_ACCEPTED historically)
```
