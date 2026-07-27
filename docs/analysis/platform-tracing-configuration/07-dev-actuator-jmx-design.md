# DEV_ACTUATOR and JMX Local Debugging
## Corrected Repository-Grounded Architecture Decision

## Review status

| Field | Value |
|---|---|
| Report status | **PASS_WITH_CRITICAL_CORRECTIONS** |
| Local developer API | Spring Boot `@JmxEndpoint` with typed commands |
| Cross-classloader write API | One Agent-side aggregate `CompositeData` operation |
| HTTP mutation API | **Forbidden** |
| Production isolation | Application endpoint design accepted; Agent-writer authorization remains a security blocker |
| Implementation GO | **NOT GRANTED** |
| Legacy mutation deletion | **BLOCKED** |
| Intended handoff | Prompt 8 — Security and Trust Boundaries |

The original report selected the right technologies but mixed two distinct JMX layers:

1. the **developer-facing Spring Boot JMX endpoint**;
2. the **internal application-to-Agent aggregate MBean**.

It then exposed the same raw full-snapshot operation through both layers. That is unnecessary and unsafe.

Other critical defects in the original report:

- `@JmxEndpoint` operation parameters were modeled as raw `CompositeData`, while Spring Boot endpoint operations are intended to use simple operation parameters;
- `spring.jmx.enabled=true` was treated as sufficient exposure, but custom endpoints also require explicit JMX endpoint availability/exposure;
- typed commands were rejected even though they are appropriate on the Spring developer-facing layer;
- structural keys were included in the mutation contract instead of being absent from the endpoint;
- no-Agent behavior was incorrectly mapped to Agent apply outcome `FAILED`;
- direct invocation of the Agent aggregate MBean in production remained an unaddressed bypass;
- endpoint absence over HTTP was analyzed, but remote JMX exposure of the Agent writer was not;
- unresolved property classification was presented as a concrete wire schema;
- state and `scrubbing.rules-config` decisions from corrected Prompts 4 and 6 were incorrectly listed as pending;
- JMX baseline capture was described as blocked by implementation GO even though it is read-only evidence and should be captured before GO.

---

# 1. Binding architecture

## 1.1 Two JMX surfaces with different responsibilities

### Surface A — Spring developer-facing endpoint

```text
TracingDevControlJmxEndpoint
```

Purpose:

- ergonomic local developer commands;
- typed, narrow, Category-A-only operations;
- read current status;
- convert every command to one complete candidate snapshot;
- invoke the internal Agent aggregate writer;
- never expose raw wire payload construction to developers.

Technology:

```java
@JmxEndpoint
```

This endpoint exists only in the application/Spring classloader.

### Surface B — Agent internal aggregate MBean

Existing stable Agent control ObjectName, subject to the JMX baseline decision:

```text
type=Control,name=PlatformControlProtocol
```

Purpose:

- the only cross-classloader write boundary;
- accept one versioned full snapshot encoded as JMX Open Types;
- validate and commit through one accepted-state CAS;
- serve both Config Server and DEV_ACTUATOR application-side adapters.

Technology:

```text
standard/MXBean-style manually registered MBean
CompositeData request and response
```

This is not a developer convenience API.

## 1.2 No third write surface

After cutover there must be no:

- Actuator HTTP write;
- direct domain JMX setter;
- partial string-map control protocol;
- PerfAdminController mutation path;
- Local SDK applier;
- second raw snapshot endpoint.

---

# 2. Is `@JmxEndpoint` the correct Spring-side API?

## Decision

**Yes, conditionally.**

It is suitable because:

- it is technology-specific to JMX;
- it does not create an HTTP endpoint;
- it participates in Spring Boot endpoint availability and JMX exposure;
- it can expose simple typed operation parameters;
- it creates a separate application-side management MBean without changing any existing Agent ObjectName.

It is not suitable for:

- preserving an existing Agent MBean ObjectName;
- exposing Agent-internal DTOs;
- using a raw `CompositeData` snapshot as its developer-facing command API;
- serving as the actual Agent state owner.

## ObjectName policy

The eight existing Agent ObjectNames remain unchanged.

The new Spring Boot JMX endpoint receives its normal Spring Boot endpoint ObjectName. It is not one of the preserved Agent contracts.

Do not customize the global `EndpointObjectNameFactory` merely to imitate an Agent ObjectName.

---

# 3. Target developer-facing command API

## 3.1 Expose typed commands, not a raw full snapshot

The original report chose one full-snapshot operation for the Spring endpoint. Corrected decision:

```text
Spring @JmxEndpoint:
    typed Category-A commands

Agent MBean:
    one full aggregate snapshot operation
```

Each developer command performs:

```text
read current accepted state
    -> check expected runtimeInstanceId/acceptedSequence
    -> copy current immutable policy
    -> modify one approved field/domain
    -> validate complete candidate
    -> encode complete Agent snapshot
    -> invoke aggregate Agent MBean
```

This preserves atomicity because only the final full candidate crosses the Agent boundary.

## 3.2 Representative operations

The exact operation set depends on the final Category A allowlist.

Representative minimal surface:

```java
@ReadOperation
DevControlStatus status();

@WriteOperation
DevCommandResult setSamplingEnabled(
    String expectedRuntimeInstanceId,
    long expectedAcceptedSequence,
    boolean enabled);

@WriteOperation
DevCommandResult setSamplingRatio(
    String expectedRuntimeInstanceId,
    long expectedAcceptedSequence,
    double ratio);

@WriteOperation
DevCommandResult setExporterEnabled(
    String expectedRuntimeInstanceId,
    long expectedAcceptedSequence,
    boolean enabled);

@WriteOperation
DevCommandResult setDiagnosticsLogLevel(
    String expectedRuntimeInstanceId,
    long expectedAcceptedSequence,
    String level);
```

Additional commands may be added only for final approved Category A values.

Do not expose:

- arbitrary property names;
- raw maps;
- a generic `set(String key, String value)`;
- arbitrary JSON/YAML;
- file paths;
- HMAC secrets;
- structural Agent keys;
- a raw `CompositeData` snapshot operation.

## 3.3 Spring endpoint type constraints

Operation inputs should be simple types supported by Spring Boot endpoint invocation:

- `String`;
- `boolean`;
- numeric primitives;
- carefully validated simple arrays where necessary.

Do not make the application endpoint signature:

```java
applyConfigurationSnapshot(CompositeData payload)
```

The Open-Type payload belongs to the Agent MBean adapter, not the developer-facing endpoint.

## 3.4 Result representation

Keep the developer endpoint result small and stable.

Representative fields:

```text
commandOutcome
attemptId
runtimeInstanceId
acceptedSequence
payloadDigest
restartRequired
messageCode
```

Do not expose:

- raw rejected payload;
- secrets;
- stack traces;
- unbounded validation output.

The exact Spring Boot JMX response representation must be verified by an integration test. Do not assume it is the same `CompositeData` contract as the Agent MBean.

---

# 4. Agent-side Open-Type contract

## 4.1 Aggregate operation

Reuse the existing stable control ObjectName only after baseline review.

Target operation:

```java
CompositeData applyConfigurationSnapshot(CompositeData request);
```

Read operation:

```java
CompositeData readAcceptedState();
```

Old partial operations are deleted after cutover.

## 4.2 Request envelope

The binding schema cannot freeze all policy leaf fields until the exact property disposition and Category A list are complete.

The envelope should contain:

```text
protocolVersion
expectedRuntimeInstanceId
expectedAcceptedSequence
sourceAuthority
applyTrigger
sourceRevision
payloadDigest
runtimePolicy
structuralDesiredState
```

Where:

- `runtimePolicy` is nested `CompositeData` containing the complete Category A candidate;
- `structuralDesiredState` contains only values/fingerprints used for drift comparison;
- `sourceRevision` is optional;
- `payloadDigest` is computed from canonical normalized runtime policy.

## 4.3 Response envelope

```text
protocolVersion
outcome
attemptId
runtimeInstanceId
acceptedSequence
payloadDigest
restartRequired
restartRequiredKeys
messageCode
```

Possible outcomes:

```text
APPLIED
UNCHANGED
REJECTED
STALE
FAILED
```

Transport/unavailability errors remain distinct from apply outcomes.

## 4.4 Optimistic concurrency

Every developer write requires:

- `expectedRuntimeInstanceId`;
- `expectedAcceptedSequence`.

For an interactive developer command:

```text
STALE -> return to caller; do not silently retry
```

The developer rereads status and decides whether to repeat the command.

This differs from the Config Server bridge, which may perform one bounded rebuild/retry.

## 4.5 Capability

The Agent advertises:

```text
CONFIG_SNAPSHOT_V1
```

when the codec, operation and handler are installed.

The Spring endpoint does not publish the capability.

---

# 5. Structural configuration behavior

## Decision

Structural keys are not writable through the developer endpoint.

Do not expose commands for:

- `javaagent.*`;
- `scrubbing.rules-config`;
- queue/BSP settings;
- resource/service identity;
- processor/propagator composition;
- HMAC secrets/key identifiers;
- extension loading.

Therefore there is no normal operation that "requests a structural key."

The endpoint may expose read diagnostics showing:

```text
restartRequired
restartRequiredKeys
effectiveStructuralFingerprint
desiredStructuralFingerprint
```

A raw Agent request containing an illegal structural mutation is rejected by the Agent protocol validator, but the Spring developer API must prevent creating such a request.

DEV_ACTUATOR must not have a wider runtime allowlist than CONFIG_SERVER.

---

# 6. DEV_ACTUATOR activation model

## 6.1 Required conditions

All conditions are required:

| Condition | Required value |
|---|---|
| `platform.tracing.mode` | `AGENT` |
| `platform.tracing.configuration.authority` | `DEV_ACTUATOR` |
| `platform.tracing.dev-control.enabled` | `true` |
| `platform.environment` | trusted non-production tier |
| active Spring profile | approved local/dev profile |
| `spring.jmx.enabled` | `true` |
| JMX endpoint exposure | explicitly includes the dev endpoint |
| Config Server bridge | absent |
| Agent protocol capability | `CONFIG_SNAPSHOT_V1` present before usable control |
| Local SDK topology | absent/unsupported |

## 6.2 Endpoint exposure is an explicit gate

`spring.jmx.enabled=true` enables Spring management beans, but it does not by itself prove that the custom endpoint is exposed.

Require explicit endpoint availability/exposure, using the repository's selected Spring Boot 3.5 endpoint-access conventions, for example:

```properties
management.endpoints.jmx.exposure.include=tracingdevcontrol
```

The exact endpoint ID and access property must be verified against the project's Spring Boot version.

## 6.3 Trusted environment posture

Do not rely solely on an active profile.

Profiles and local properties are developer-controlled.

Use at least:

- imported `platform.environment`;
- explicit authority;
- explicit dev-control enablement;
- profile as defense in depth.

The final trust classification of `platform.environment` and its sources belongs to Prompt 8.

## 6.4 Source constraints

The following must not be sourced from Config Server:

```text
platform.tracing.mode
platform.tracing.configuration.authority
platform.tracing.dev-control.enabled
```

A remote source cannot enable the local control surface.

---

# 7. Activation truth table

| Mode | Authority | Dev enabled | Trusted non-prod | JMX enabled/exposed | Result |
|---|---|---:|---:|---:|---|
| `AGENT` | `DEV_ACTUATOR` | true | true | true | Endpoint created and usable after Agent readiness |
| `AGENT` | `DEV_ACTUATOR` | false | true | true | Endpoint absent |
| `AGENT` | `DEV_ACTUATOR` | true | false | true | Startup fail-fast |
| `AGENT` | `DEV_ACTUATOR` | true | true | false | Endpoint unavailable; startup posture error or explicit diagnostic |
| `AGENT` | `HELM` | any | any | any | Endpoint absent |
| `AGENT` | `CONFIG_SERVER` | any | any | any | Endpoint absent |
| `DISABLED` | `DEV_ACTUATOR` | true | any | any | Startup fail-fast |
| any | authority sourced remotely | any | any | any | Startup fail-fast |

Do not merely register the endpoint and make each write return an error when posture is invalid. In invalid postures the endpoint bean should be absent or startup should fail.

---

# 8. No-Agent behavior

`mode=AGENT` without the required controlled Agent is already an invalid supported startup posture.

Therefore:

```text
No Agent is not an ordinary DEV_ACTUATOR runtime state.
```

Correct behavior:

- application does not become ready or startup fails through the existing Agent readiness contract;
- endpoint is not considered usable before `CONFIG_SNAPSHOT_V1`;
- transport unavailability is reported as endpoint/control-plane `UNAVAILABLE`, not Agent apply outcome `FAILED`.

Do not fabricate an Agent `FAILED` outcome when no Agent MBean exists.

---

# 9. Restart/reset semantics

DEV_ACTUATOR commands are in-memory and process-local.

Default reset contract:

```text
JVM restart
    -> new Agent bootstrap state
    -> no persisted dev overrides
```

Do not add:

- database persistence;
- local file persistence;
- replay log;
- restore-on-start;
- hidden Spring cache of overrides.

Recommended baseline:

- Agent bootstrap policy from env/`-D`;
- developer commands mutate only the current process.

A `resetToBootstrap` command would require retaining a separate bootstrap baseline and is not justified by the confirmed requirement. Restart is the reset mechanism.

---

# 10. HTTP absence proof

## 10.1 Design invariant

Use `@JmxEndpoint`, not:

- `@Endpoint`;
- `@WebEndpoint`;
- `@RestControllerEndpoint`;
- `@ControllerEndpoint`;
- `@EndpointWebExtension`.

A JMX-specific endpoint is not exposed as an Actuator HTTP endpoint.

## 10.2 Tests

Required:

1. endpoint present in `JmxEndpointsSupplier` for valid dev posture;
2. endpoint absent from `WebEndpointsSupplier`;
3. no HTTP route under `/actuator` for the endpoint ID;
4. no `@WriteOperation` remains on the existing web-capable tracing endpoint;
5. production-context process test confirms no dev endpoint MBean.

Absence from the web endpoint supplier is necessary but not sufficient for security; the JMX connector exposure must also be assessed.

---

# 11. Critical unresolved production bypass

## 11.1 Problem

Even when the Spring `@JmxEndpoint` is absent in production, the Agent aggregate MBean must exist for the Config Server bridge.

If that MBean is registered in a remotely reachable `MBeanServer`, an operator or attacker may invoke:

```text
applyConfigurationSnapshot
```

directly and bypass:

- Config Server provenance;
- Spring authority guards;
- the developer endpoint gates.

This is the most important unresolved security issue in the Prompt 7 design.

## 11.2 What Prompt 7 can establish

The Agent handler must at least:

- know its bootstrap authority posture;
- reject writes in `HELM`;
- accept only the aggregate protocol;
- reject obsolete domain writes;
- validate protocol version, bounds and expected base;
- record caller/transport metadata when available.

This does not yet prove caller authorization in `CONFIG_SERVER`.

## 11.3 Required Prompt 8 decision

Prompt 8 must choose and prove a production control-boundary mechanism, such as:

- authenticated/authorized JMX connector subjects and roles;
- a private/internal MBeanServer or non-exported internal registration path;
- a per-process application-to-Agent capability inaccessible to generic JMX clients;
- a platform-level prohibition and executable verification of remote JMX in production;
- another demonstrably enforceable mechanism.

Do not call the target production-safe until this bypass is closed.

---

# 12. Exact component changes

## 12.1 `platform-tracing-spring-boot-autoconfigure`

### New internal components

```text
TracingDevControlAutoConfiguration
TracingDevControlJmxEndpoint
TracingDevPolicyCommandService
DevControlPostureGuard
application-side aggregate JMX client/codec
```

Responsibilities:

- activation;
- typed commands;
- read-modify-validate of complete candidate;
- aggregate Agent invocation;
- endpoint-level status mapping.

### Existing components

| Component | Target action |
|---|---|
| existing read-capable tracing Actuator endpoint | retain/read-only rewrite |
| all Actuator write operations | DELETE after cutover |
| `assertMutationAllowed()` | DELETE with web writes |
| `platform.tracing.actuator.mutation-enabled` | DELETE |
| `RuntimeConfigApplier` | DELETE after Config Server/dev cutover |
| `TracingRefreshScopeAutoConfiguration` | DELETE after Config Server cutover |
| per-domain JMX client write methods | DELETE after aggregate client cutover |

## 12.2 `platform-tracing-otel-javaagent-extension`

| Component | Target action |
|---|---|
| existing stable Control ObjectName | preserve |
| aggregate `applyConfigurationSnapshot` | ADD/REPLACE protocol operation |
| `readAcceptedState` | ADD/REPLACE protocol read |
| old `applyPolicy`/partial operations | DELETE after baseline and cutover |
| domain MBean setters | DELETE after cutover |
| aggregate handler/state | use corrected Prompt 4 design |
| capability publication | add with installed protocol |
| Agent-side authority/security gate | required; final design deferred to Prompt 8 |

Do not describe the existing old codec as automatically reusable. It uses the partial protocol semantics and must be replaced or rigorously rewritten for the versioned aggregate schema.

## 12.3 Test/perf modules

- replace direct setter scenarios with the aggregate boundary;
- do not use DEV_ACTUATOR to prove production Config Server performance;
- keep local-dev process tests separate from M10/M10c/M10d production tests.

---

# 13. JMX baseline and contract evolution

The JMX baseline is read-only evidence collection and should be captured **before implementation GO**, not blocked by it.

Capture now:

- all ObjectNames;
- attributes and Open Types;
- operations/signatures;
- notifications;
- endpoint domains.

Then define approved delta:

- preserve all eight ObjectNames;
- add/replace the one aggregate operation;
- preserve approved read contracts;
- remove old partial writes;
- remove domain setters;
- add a separate Spring Boot dev endpoint ObjectName only in valid dev posture.

Legacy deletion remains blocked until:

- aggregate writer is proven;
- application adapters are proven;
- baseline contract test approves the delta;
- explicit deletion GO is granted.

---

# 14. Test matrix

## 14.1 Activation

- valid DEV_ACTUATOR posture creates the endpoint;
- `HELM` does not;
- `CONFIG_SERVER` does not;
- `DISABLED + DEV_ACTUATOR` fails;
- production `platform.environment` fails;
- missing explicit dev enablement keeps endpoint absent;
- authority/dev-control sourced remotely fails;
- JMX disabled or endpoint not exposed makes endpoint unavailable;
- Config Server bridge and dev endpoint are never active together.

## 14.2 Endpoint technology

- present in JMX endpoint supplier;
- absent in web endpoint supplier;
- no MVC/WebFlux route;
- no generic `@Endpoint`/web extension annotation.

## 14.3 Commands

- every command builds a full candidate;
- current accepted state is reread before command construction;
- expected base required;
- successful command returns `APPLIED`;
- same value returns `UNCHANGED`;
- concurrent command returns `STALE`;
- invalid ratio/rule/level returns `REJECTED`;
- no hidden retry in developer command;
- no structural command exists;
- restart removes override.

## 14.4 Agent boundary

- one aggregate operation only;
- symmetric Open-Type codec;
- protocol version mismatch rejected;
- oversized lists/maps rejected;
- no raw secrets in request/result/audit;
- no domain setter remains after cutover;
- capability present before endpoint becomes usable.

## 14.5 No-Agent and readiness

- no Agent prevents readiness;
- endpoint invocation before Agent capability reports `UNAVAILABLE`;
- no false `FAILED` apply record is produced for transport absence.

## 14.6 Production isolation

- production process contains no Spring dev endpoint MBean;
- remote Config Server cannot enable dev posture;
- direct Agent MBean production invocation is tested according to the security mechanism selected by Prompt 8.

---

# 15. Legacy APIs to delete

After proven cutover and explicit deletion GO:

1. Actuator HTTP mutation methods.
2. `platform.tracing.actuator.mutation-enabled`.
3. Current partial protocol operations.
4. `platform.tracing.control.runtime-mutation.enabled`.
5. Direct sampling MBean setters.
6. Direct scrubbing MBean setters.
7. Direct validation MBean setters.
8. Direct exporter/propagation/log-level setters when included in aggregate state.
9. `RuntimeConfigApplier`.
10. `TracingRefreshScopeAutoConfiguration`.
11. PerfAdminController mutation path after production perf migration.
12. Old application-side per-domain JMX write clients.
13. Tests and docs for all removed paths.

Do not mark old operations `@Deprecated` as a migration strategy. The product is pre-production; retain them only inside the unreleased controlled cutover, then remove them.

---

# 16. Updated blocker status

| Item | Correct status |
|---|---|
| `RETAIN_GENERIC` state direction | Resolved conditionally; focused API review remains |
| `scrubbing.rules-config` Category B | Resolved |
| DEV_ACTUATOR posture | Accepted |
| Developer-facing API shape | Accepted: typed Spring JMX commands |
| Agent write shape | Accepted: one aggregate Open-Type operation |
| Exact Category A command list | Pending full property/security/SRE decisions |
| Complete 64-key/env disposition | Blocking |
| JMX baseline | Must be captured pre-GO |
| Production Agent MBean authorization | **Critical security blocker** |
| Config Server dependency/refresh delivery | Governance/operational blockers |
| Diagnostics retention matrix | Pending |
| Implementation GO | Not granted |
| Legacy deletion | Blocked |

---

# 17. Handoff contract for Prompt 8

Prompt 8 must treat these findings as accepted:

```text
1. DEV_ACTUATOR is a mutually exclusive non-production authority posture.
2. The Spring developer-facing API is a JMX-only @JmxEndpoint.
3. The Spring endpoint exposes typed Category-A commands, not a raw snapshot.
4. Every typed command is converted to a complete candidate before crossing JMX.
5. The Agent exposes the only full aggregate CompositeData write operation.
6. Existing Agent ObjectNames remain unchanged.
7. The Spring dev endpoint uses a separate Spring Boot-generated ObjectName.
8. No HTTP mutation endpoint exists in the target.
9. Structural keys are not exposed as developer write commands.
10. DEV_ACTUATOR cannot widen the production Category A allowlist.
11. Every write requires expected runtimeInstanceId and acceptedSequence.
12. Interactive stale commands return STALE without hidden retry.
13. No-Agent is transport/readiness UNAVAILABLE, not apply FAILED.
14. Dev overrides are in-memory and reset only on process restart.
15. Explicit JMX endpoint exposure is required in addition to spring.jmx.enabled.
16. Profile alone is not a sufficient production guard.
17. The Agent aggregate writer remains a possible direct-JMX bypass in production.
18. Production safety is not proven until Agent-side writer authorization/reachability is resolved.
19. JMX baseline should be captured before implementation GO.
20. Implementation GO remains not granted.
```

Prompt 8 must not inherit these claims from the original Prompt 7 output:

```text
1. That both the Spring endpoint and Agent MBean should expose raw full-snapshot CompositeData.
2. That @JmxEndpoint should accept CompositeData as its developer-facing operation input.
3. That typed domain commands recreate non-atomicity when they are converted to a full candidate.
4. That spring.jmx.enabled alone exposes the endpoint.
5. That structural mutation should be offered and return restartRequired.
6. That no-Agent should be represented as Agent apply FAILED.
7. That proving HTTP absence proves production control isolation.
8. That direct invocation of the Agent aggregate MBean is harmless.
9. That the exact Category A wire fields are already approved.
10. That the old partial protocol codec can be reused without replacement review.
11. That state review and rules-config classification are still unresolved.
12. That JMX baseline capture must wait for implementation GO.
```

---

# 18. Final status

```text
Prompt 7 result: PASS_WITH_CRITICAL_CORRECTIONS
Developer API: typed @JmxEndpoint commands
Agent boundary: one aggregate CompositeData writer
HTTP mutation: forbidden
Production Agent-writer authorization: unresolved security blocker
Implementation GO: NOT GRANTED
Legacy mutation deletion: BLOCKED
Next prompt: Security and Trust Boundaries
```

---

## External framework references

- Spring Boot 3.5 — Monitoring and Management over JMX:
  https://docs.spring.io/spring-boot/3.5/reference/actuator/jmx.html
- Spring Boot — Implementing Custom Endpoints:
  https://docs.spring.io/spring-boot/reference/actuator/endpoints.html
- Spring Boot 3.5 API — `@JmxEndpoint`:
  https://docs.spring.io/spring-boot/3.5/api/java/org/springframework/boot/actuate/endpoint/jmx/annotation/JmxEndpoint.html
