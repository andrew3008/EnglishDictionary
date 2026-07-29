# P4-WF class-justification ledger (§14.7)

**Status:** `CLASS_BUDGET_ACCEPTED` (corrected by P4-WF.2)  
**Evidence:** [`class-budget-gate.md`](./class-budget-gate.md)  
**Budget target:** ≤1 new production class  
**Budget hard max without committee:** 2  
**Proposed new production classes:** 1

---

## Class 1 of 1

| Field | Content |
|---|---|
| **Proposed class** | `space.br1440.platform.tracing.autoconfigure.reactive.TracingControlWebFluxManagementContextConfiguration` |
| **Module** | `platform-tracing-autoconfigure-webflux` |
| **Responsibility** | Single transport-specific responsibility: register WebFlux `RouterFunction` management routes for the eight typed DEV_ACTUATOR writes under `{management.endpoints.web.base-path}/tracingControl/...`, inject existing `SpringActuatorTracingPolicyAdapter`, map `TracingPolicyDeliveryResult` to HTTP status/body. |
| **Existing reuse attempted** | `TracingControlManagementContextConfiguration` (Servlet — unsafe to extend); `TracingControlMvcController`; `SpringActuatorTracingPolicyAdapter`; request DTOs; private JMX client; authority guard; readiness; `TracingActuatorEndpoint`. |
| **Why modification of Servlet config is unsafe** | `@ConditionalOnClass(WebMvcConfigurer)` + Servlet imports — never loads on reactive-only classpath; weakening gate is a classloading hazard. |
| **Why module is webflux (not shared autoconfigure)** | WebFlux `RouterFunction`/`DispatcherHandler` types are not framework-neutral. Global/architecture rules isolate Servlet and WebFlux adapters. Shared autoconfigure already documents Servlet `TracingControl*` placement as a post-merge defect. Plan §18 allows minimal management transport in the webflux module. Boot merges `ManagementContextConfiguration.imports` from every jar — a second imports file in the webflux jar is the correct discovery path. |
| **Rejected alternatives** | (1) Extend Servlet config — unsafe. (2) Place WebFlux class in shared autoconfigure + `compileOnly spring-webflux` — violates isolation; doubles the documented monolith defect. (3) Config + separate handler — second class without boundary necessity. (4) Transport framework — §14.7 forbidden. |
| **Class count** | webflux main sources: 14 → 15; `configuration/actuator/`: 14 (unchanged); `configuration/` recursive: 38 (unchanged); WebFlux control-surface production classes: 0 → 1 |

### Explicit non-decisions

- Servlet `TracingControl*` left in shared autoconfigure (pre-existing defect; migration remains separate committee item — not expanded here).
- No `@Order` vs Servlet config (mutually exclusive via stack `@ConditionalOnClass` + module classpath).
- No shared meta-annotation for ACTUATOR+LOCAL guards.
- No new `compileOnly spring-webflux` on the shared autoconfigure module.

---

## Budget check

```text
new production classes = 1  (<= target 1)
committee exception required = no
CLASS_BUDGET_ACCEPTED
```
