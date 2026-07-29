# P4-WF.2 — Class-budget and justification gate

**Gate:** P4-WF.2  
**As of:** 2026-07-29  
**Production code authorised by this gate alone:** no (authorises budget + corrected placement for subsequent P4-WF.3)  
**Initial ledger status:** REJECTED_AS_SUBMITTED (module placement)  
**Corrected ledger status:** `CLASS_BUDGET_ACCEPTED`  
**Committee exception (>2 classes):** not required

## 1. Independent reviews

| Reviewer | Artifact | Outcome on original monolith placement |
|---|---|---|
| Opus `review_architecture_with_opus` | requestId `dd1c59c8-e7e8-40e7-af0f-459e392912e4` | Class count OK; did not challenge module placement deeply |
| Explore subagent `[class-budget audit](6029bfe5-f0b3-40da-be34-bcce0a6fad60)` | independent memo | **BLOCKED** on module placement — WebFlux transport in shared autoconfigure contradicts Servlet/WebFlux isolation and the module’s own documented purpose |

Gate rule: a skeptical independent finding that cites repository architecture boundaries takes precedence over ownership convenience.

## 2. Acceptance checklist (corrected design)

| Criterion | Result | Evidence |
|---|---|---|
| new production classes ≤ 1 (target) | **PASS** | Exactly one new class |
| new production classes ≤ 2 without committee | **PASS** | 1 |
| exact boundary justification | **PASS** | Servlet `@ConditionalOnClass(WebMvcConfigurer)` cannot host WebFlux types; parallel `@ManagementContextConfiguration` required; **module** placement justified by Servlet/WebFlux isolation (not ownership of imports file) |
| no interface-with-one-impl / wrapper-only / registry/factory/abstract hierarchy | **PASS** | Single concrete management configuration with `RouterFunction` beans |
| existing class cannot be safely extended | **PASS** | Confirmed against `TracingControlManagementContextConfiguration` |
| `compileOnly` deps not counted as classes | **PASS** | Corrected design needs **no** new `compileOnly spring-webflux` on the shared module |

## 3. Correction forced by this gate (P4-WF.1a)

**Rejected placement:** new class + `compileOnly spring-webflux` inside `platform-tracing-spring-boot-autoconfigure`.

Reasons:

1. Shared autoconfigure already documents Servlet `TracingControl*` as a post-merge defect pending committee migration.
2. Global / architecture rules: Servlet and WebFlux adapters remain isolated; shared module only for framework-neutral code.
3. `platform-tracing-autoconfigure-webflux` exists so Servlet apps do not pull WebFlux types into the compile story of the shared module.
4. Plan §18 explicitly allows “minimal management transport adapter” in the webflux module when census proves module-level placement — isolation rules prove it.

**Accepted placement:**

| Item | Value |
|---|---|
| Module | `platform-tracing-autoconfigure-webflux` |
| FQCN | `space.br1440.platform.tracing.autoconfigure.reactive.TracingControlWebFluxManagementContextConfiguration` |
| Discovery | **new** `META-INF/spring/org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration.imports` in the webflux jar (Boot merges imports from all jars) |
| Shared command reuse | inject `SpringActuatorTracingPolicyAdapter` + existing request records from `api` dependency on `platform-tracing-spring-boot-autoconfigure` |
| Build delta on shared autoconfigure | **none** |
| New production classes | **1** |
| Class counts after | webflux main sources 14→15; `configuration/actuator/` stays 14; `configuration/` recursive stays 38 |

## 4. Steelman against any new class

“Put WebFlux routes into the existing Servlet management configuration.”

**Fails:** that class is hard-gated on `WebMvcConfigurer` and imports Servlet types; reactive-only classpaths never load it. Weakening the gate is a classloading hazard.

## 5. Authorisation

```text
CLASS_BUDGET_ACCEPTED (corrected placement)
new production classes = 1
module = platform-tracing-autoconfigure-webflux
P4-WF.3 production implementation may proceed against the corrected ledger only
```

Does **not** close P4-WF.4–P4-WF.9. Does **not** approve PRIV-R07.
