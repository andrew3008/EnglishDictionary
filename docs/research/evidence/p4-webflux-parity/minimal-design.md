# P4-WF.1 — Minimal WebFlux management-control parity design

**Gate:** P4-WF DESIGN  
**Status:** COMPLETED  
**Production code changed by this gate:** no  
**Grounded in:** refreshed [`repository-census.md`](./repository-census.md) (P4-WF.0)  
**Design verdict:** `MINIMAL_DESIGN_ACCEPTED`  
**Independent minimality audit:** [`minimality-audit.md`](./minimality-audit.md)  
**Class-budget ledger (P4-WF.2, separate gate):** [`class-justification-ledger.md`](./class-justification-ledger.md)  
**Machine-readable twin:** [`minimal-design.json`](./minimal-design.json)

## 1. Goal

Identical supported ACTUATOR + LOCAL management mutating control contract on Servlet and WebFlux, by a transport-only WebFlux registration path over existing shared control logic.

```text
Servlet transport (shared autoconfigure) ─┐
                                          ├─→ SpringActuatorTracingPolicyAdapter
WebFlux transport (webflux module)      ─┘     + PrivateJmx + authority guard + readiness + DTOs
```

Plan §14.7 forbids Servlet-only as the product end-state.

## 2. Exact create / modify list

| Action | Path |
|---|---|
| **CREATE** (1 production class) | `platform-tracing-autoconfigure-webflux/src/main/java/space/br1440/platform/tracing/autoconfigure/reactive/TracingControlWebFluxManagementContextConfiguration.java` |
| **CREATE** | `platform-tracing-autoconfigure-webflux/src/main/resources/META-INF/spring/org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration.imports` |

**Do not modify** in this design scope:

| Path | Why untouched |
|---|---|
| Shared autoconfigure `ManagementContextConfiguration.imports` | Servlet registration stays as-is |
| Shared autoconfigure `build.gradle` | no `compileOnly spring-webflux` on shared module |
| `TracingControlManagementContextConfiguration` / `TracingControlMvcController` | Servlet transport unchanged |
| `SpringActuatorTracingPolicyAdapter` and request DTOs | shared command/DTO ownership |
| Agent / private JMX / wire contracts | out of transport scope |
| webflux `AutoConfiguration.imports` | control uses ManagementContextConfiguration.imports, not auto-config |

## 3. Production class (budget target = 1)

**FQCN:** `space.br1440.platform.tracing.autoconfigure.reactive.TracingControlWebFluxManagementContextConfiguration`  
**Module:** `platform-tracing-autoconfigure-webflux`

| Concern | Choice |
|---|---|
| Discovery | `@ManagementContextConfiguration` via **webflux-module** `ManagementContextConfiguration.imports` |
| Stack gates | `@ConditionalOnClass(DispatcherHandler.class)` **and** `@ConditionalOnWebApplication(REACTIVE)` |
| Authority/posture | same AND: `authority=ACTUATOR` + `platform.environment=LOCAL` (intentional mirror of Servlet; no shared meta-annotation) |
| Transport | `@Bean RouterFunction<ServerResponse> tracingControlWebFluxRoutes` |
| Paths | absolute `{managementBasePath}/tracingControl/...` for exactly 8 ops (7 POST + 1 PUT) |
| Command | inject existing `SpringActuatorTracingPolicyAdapter` only |
| Bodies | existing eight `Set*` / `Replace*` records |
| Outcomes | private helpers → 200 / 422 / 409 / 503 + `{outcome,leaf,value}` |
| Blocking I/O | adapter calls on `Schedulers.boundedElastic()` (no event-loop block) |
| Path prefix | `@Value("${management.endpoints.web.base-path:/actuator}")` + same `normalise()` rules as Servlet |

**Class-count projection (from census):**

| Scope | Before → After |
|---|---|
| webflux main sources | 14 → 15 |
| `configuration/actuator/` | 14 → 14 |
| `configuration/` recursive | 38 → 38 |
| WebFlux control-surface classes | 0 → 1 |

## 4. Why a new class (existence)

`TracingControlManagementContextConfiguration` is `@ConditionalOnClass(WebMvcConfigurer)` and imports Servlet types. On reactive-only classpaths it never loads. Absorbing WebFlux types would either keep zero reactive surface or weaken the gate into a classloading hazard. Parallel `@ManagementContextConfiguration` is required.

## 5. Why webflux module (placement)

Not ownership convenience:

1. Global/architecture rules: Servlet and WebFlux adapters remain isolated; shared autoconfigure only for framework-neutral code.
2. Shared module already documents Servlet `TracingControl*` as a post-merge defect — do not add a second stack-specific type there.
3. `platform-tracing-autoconfigure-webflux` already has `compileOnly spring-webflux` and `api` → shared autoconfigure (adapter/DTOs reachable).
4. Boot merges `ManagementContextConfiguration.imports` from every jar — a second imports file in the webflux jar is the correct discovery path.
5. Plan §18 allows “minimal management transport adapter” in the webflux module when census proves module-level placement.

Rejected placement in shared autoconfigure (+ `compileOnly spring-webflux`) fails isolation and doubles the documented defect.

## 6. Negative list (rejected)

| Rejected | Why |
|---|---|
| `TracingControlTransport` / registry / factory | §14.7 forbidden framework |
| Abstract handler hierarchy / route descriptor | speculative abstraction |
| `ReactiveTracingPolicyAdapter` / second JMX client / second readiness | second state/command owner |
| WebFlux-specific request DTOs | parallel DTO model |
| New Gradle module / starter | census: existing webflux module is sufficient |
| Separate handler class + config | second production class without boundary necessity |
| Shared meta-annotation for ACTUATOR+LOCAL | premature abstraction at N=2 |
| Placing WebFlux types in shared autoconfigure | Servlet/WebFlux isolation violation |
| Servlet-only permanent product limitation | §14.7 forbids |

## 7. Verification sequencing (not this gate)

| Gate | Proof |
|---|---|
| P4-WF.2 | class-budget ledger acceptance |
| P4-WF.3 | production implementation of §2 |
| P4-WF.4–P4-WF.6 | parity / child-context / absence tests |
| P4-WF.7 | Docker aggregate E2E + privateMBeanServerTest |

Exact Gradle tasks: see census §6 / `repository-census.json` → `exactGradleTasks`.

## 8. Verdict

**`MINIMAL_DESIGN_ACCEPTED`**

No `BLOCKED_REPOSITORY_CONSTRAINT`. Design is transport-only, single new production class in the webflux module, full reuse of shared control logic, negative list complete.
