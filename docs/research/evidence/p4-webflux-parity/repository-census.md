# P4-WF.0 — Repository census (PRE-FLIGHT)

**Gate:** P4-WF PRE-FLIGHT  
**Status:** COMPLETED (refreshed)  
**Production code changed by this gate:** no  
**HEAD:** `00b6f54f5fdcfbbd6ed758bca868ac7a11b74ff7` (`master`)  
**Working tree:** dirty (uncommitted P4 / P4-WF work present)  
**Machine-readable twin:** [`repository-census.json`](./repository-census.json)  
**Census scope:** current repository facts for Servlet + WebFlux management control surfaces and shared control logic.

## 1. Servlet management implementation (facts)

| Role | FQCN / path |
|---|---|
| Management registration | `space.br1440.platform.tracing.autoconfigure.configuration.actuator.TracingControlManagementContextConfiguration` |
| Registration file | `platform-tracing-spring-boot-autoconfigure/.../ManagementContextConfiguration.imports` (single Servlet FQCN line) |
| HTTP write surface | `TracingControlMvcController` (`@RestController`, relative `/tracingControl`) |
| Servlet gate | `@ConditionalOnClass(WebMvcConfigurer.class)` |
| Authority/posture gate | `authority=ACTUATOR` **and** `platform.environment=LOCAL` |
| Path composition | `WebMvcConfigurer.addPathPrefix(management.endpoints.web.base-path, …)` |
| Operations | exactly 8 typed routes (7× POST + 1× PUT) |

Controller is transport-only; mutations go to `SpringActuatorTracingPolicyAdapter`.

## 2. WebFlux management implementation (current facts)

| Role | FQCN / path |
|---|---|
| Management registration | `space.br1440.platform.tracing.autoconfigure.reactive.TracingControlWebFluxManagementContextConfiguration` |
| Module | `platform-tracing-autoconfigure-webflux` |
| Registration file | `platform-tracing-autoconfigure-webflux/.../ManagementContextConfiguration.imports` (single WebFlux FQCN line) |
| HTTP write surface | `RouterFunction<ServerResponse>` bean `tracingControlWebFluxRoutes` |
| Stack gates | `@ConditionalOnClass(DispatcherHandler.class)` **and** `@ConditionalOnWebApplication(REACTIVE)` |
| Authority/posture gate | same ACTUATOR + LOCAL AND as Servlet |
| Path composition | absolute paths under `{management.endpoints.web.base-path}/tracingControl/...` |
| Operations | same 8 typed routes; same request records; same outcome body shape |

WebFlux AutoConfiguration.imports remains reactive tracing/MDC only — control registration is via **ManagementContextConfiguration.imports**, not AutoConfiguration.imports.

## 3. Shared control logic (must stay single-owned)

| Responsibility | Owner |
|---|---|
| Typed DEV mutations (8 leaves) | `SpringActuatorTracingPolicyAdapter` |
| Private JMX read/apply | `PrivateJmxTracingPolicySnapshotClient` |
| Authority/posture fail-fast | `TracingConfigurationAuthorityGuard` (+ `TracingConfigurationAuthorityAutoConfiguration`) |
| ACTUATOR readiness | `TracingActuatorAutoConfiguration.ActuatorAgentReadinessProbe` |
| Secret-safe read model | `TracingActuatorEndpoint` |
| Request bodies | eight `Set*` / `Replace*` records in `configuration.actuator` |
| Delivery outcomes | `TracingPolicyDeliveryResult` |

Main-context wiring: `TracingActuatorAutoConfiguration` in shared autoconfigure `AutoConfiguration.imports`.

## 4. Reactive classpath / management-context boundaries

- `platform-tracing-spring-boot-starter-reactive` → autoconfigure + autoconfigure-webflux + `spring-boot-starter-webflux`.
- Reactive `runtimeClasspath`: **has** `spring-webflux` / `spring-web` / `platform-tracing-autoconfigure-webflux`; **NO** `spring-webmvc` (verified).
- Servlet apps do not pull the webflux module; WebFlux control types stay out of Servlet compile/runtime path.
- Shared autoconfigure still hosts Servlet `TracingControl*` (documented historical placement); WebFlux transport is isolated in the webflux module.

## 5. Existing tests

| Proof | Test |
|---|---|
| Servlet route unit | `TracingControlMvcControllerHttpTest` |
| Servlet management child context | `TracingControlManagementChildContextIntegrationTest` |
| Servlet absence matrix | `TracingControlManagementContextAbsenceTest` |
| WebFlux route / outcome parity | `TracingControlWebFluxRouterHttpTest` |
| WebFlux management child context | `TracingControlWebFluxManagementChildContextIntegrationTest` |
| WebFlux absence matrix | `TracingControlWebFluxManagementContextAbsenceTest` |
| Adapter unit | `SpringActuatorTracingPolicyAdapterTest` |
| Authority guard context | `TracingConfigurationAuthorityGuardContextTest` |
| Reactive no-webmvc arch | `WebFluxNoServletMainDepArchTest` |

## 6. Exact Gradle tasks

```text
:platform-tracing-e2e-tests:test -PrunE2e
:platform-tracing-e2e-tests:privateMBeanServerTest
privateMBeanServerVerify
pr0StarterDependencySmoke
c6PublishedReactiveConsumerVerify
:platform-tracing-spring-boot-autoconfigure:test
:platform-tracing-autoconfigure-webflux:test
```

Focused WebFlux control:

```text
:platform-tracing-autoconfigure-webflux:test --tests "space.br1440.platform.tracing.autoconfigure.reactive.TracingControlWebFlux*"
:platform-tracing-spring-boot-autoconfigure:test --tests "*TracingControlWebFluxManagementChildContextIntegrationTest"
```

## 7. Production class counts (current)

| Scope | Count |
|---|---:|
| `configuration/` (recursive, shared autoconfigure) | 38 |
| `configuration/actuator/` | 14 |
| `platform-tracing-autoconfigure-webflux` main sources | 15 |
| WebFlux control-surface production classes | 1 (`TracingControlWebFluxManagementContextConfiguration`) |

## 8. Reuse map (summary)

| Existing | Reusable responsibility | Transport delta |
|---|---|---|
| `SpringActuatorTracingPolicyAdapter` | 8 typed mutations | none — inject into WebFlux router |
| `PrivateJmxTracingPolicySnapshotClient` | private JMX read/apply | none |
| Authority guard / readiness / read endpoint | posture + readiness + read model | none |
| Request DTOs + `TracingPolicyDeliveryResult` | bodies / outcomes | thin mapping inside WebFlux config |
| Servlet `TracingControlManagementContextConfiguration` | Servlet registration | **must not** absorb WebFlux types |
| WebFlux `TracingControlWebFluxManagementContextConfiguration` | WebFlux registration | already the transport delta |

Full reuse rows: see `repository-census.json` → `reuseMap`.
