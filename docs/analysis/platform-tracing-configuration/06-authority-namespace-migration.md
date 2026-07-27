# Authority and Namespace Migration
## Corrected Repository-Grounded Architecture Decision

## Review status

| Field | Value |
|---|---|
| Report status | **PARTIAL_PASS_WITH_CRITICAL_CORRECTIONS** |
| Authority model | **Accepted with corrected semantics** |
| Namespace taxonomy | **Accepted at prefix level** |
| Complete 64-key migration map | **NOT COMPLETED by the original report** |
| Default ownership | **Corrected** |
| Implementation GO | **NOT GRANTED** |
| Legacy mutation deletion | **BLOCKED** |
| Intended handoff | Prompt 7 — DEV_ACTUATOR and JMX Local Debugging |

The original report made useful decisions—separating policy, Agent structural configuration and orchestration; dropping SDK-only production; deleting header-name configuration—but it overclaimed completion.

Critical defects in the original report:

1. It called the migration table "all 64 keys" while using wildcard groups, inferred names and an incomplete set of leaf keys.
2. It assigned Spring as the single owner of runtime-policy defaults even though Agent HELM bootstrap occurs before Spring exists.
3. It treated authority as governing all tracing configuration instead of governing the runtime-policy writer/source.
4. It used the wrong target names for propagation and diagnostics policy.
5. It placed `dev-control.enabled` under `configuration.*`, contradicting the agreed namespace taxonomy.
6. It kept ambiguous legacy `PLATFORM_TRACING_*` environment mappings unchanged after renaming property namespaces.
7. It forbade Config Server as a desired source for structural keys, contradicting the accepted structural-drift model.
8. It declared `mode=DISABLED + authority=CONFIG_SERVER` generally legal without defining whether Spring is allowed to manage an externally active Agent.
9. It classified several keys as runtime-safe or structural without sufficient per-key evidence.
10. Its proposed ArchUnit rules were not robust or, in several cases, not implementable as written.

The corrected report resolves the model-level decisions and leaves the exact leaf-key table as a required repository-generated artifact.

---

# 1. Two orthogonal control dimensions

## 1.1 Runtime integration mode

Current supported values:

```text
platform.tracing.mode=AGENT|DISABLED
```

Meaning:

- `AGENT` — Spring platform facade expects and observes the controlled Java Agent.
- `DISABLED` — Spring facade is NoOp; an externally attached Agent may still run independently.

This dimension answers:

```text
Is the Spring platform tracing integration active?
```

## 1.2 Runtime-policy authority

Target values:

```text
platform.tracing.configuration.authority=
    HELM
    CONFIG_SERVER
    DEV_ACTUATOR
```

This dimension answers:

```text
Which source is allowed to change the Agent runtime policy?
```

`DEV_ACTUATOR` is operationally a local JMX source, but it remains an authority value because it must be mutually exclusive with the production writers.

It is not merely another transport layered beside HELM or Config Server.

## 1.3 Authority scope

Authority governs:

```text
platform.tracing.policy.*
```

It does not globally govern every tracing property.

Other property families have their own owners:

- Java Agent structural/bootstrap;
- Spring application adapters;
- platform-wide imported signals;
- OTel standard configuration.

This prevents `authority` from becoming an all-purpose configuration-mode switch.

---

# 2. Valid mode/authority combinations

Recommended binding matrix:

| `mode` | `authority` | Valid? | Semantics |
|---|---|---:|---|
| `AGENT` | `HELM` | Yes, default production | Agent reads policy and structural bootstrap from env/`-D`; Spring does not reapply policy |
| `AGENT` | `CONFIG_SERVER` | Yes, explicit production | Spring bridge owns runtime-policy delivery; Agent remains runtime truth |
| `AGENT` | `DEV_ACTUATOR` | Yes, non-production only | Local dev baseline plus JMX override; no Config Server bridge |
| `DISABLED` | `HELM` | Yes | Spring facade remains NoOp; externally active Agent, if any, uses its own bootstrap |
| `DISABLED` | `CONFIG_SERVER` | **Reject by default** | "Disabled" Spring integration must not silently operate an Agent control plane |
| `DISABLED` | `DEV_ACTUATOR` | **Reject by default** | Local control contradicts disabled platform integration |

Fail-fast on the rejected combinations.

Reversal condition:

If the committee explicitly requires Spring to manage an externally attached Agent while the facade is disabled, introduce and document a separate explicit product posture. Do not infer this behavior from `DISABLED`.

---

# 3. Final namespace taxonomy

## 3.1 Runtime desired/accepted policy

```text
platform.tracing.policy.*
```

Properties that are approved as runtime mutable and participate in the one aggregate accepted state.

Candidate groups, subject to later security/SRE confirmation:

- `policy.sampling.*`;
- `policy.exporter.enabled`;
- runtime-safe scrubbing subset;
- runtime-safe validation subset;
- `policy.propagation.trace-control-headers.enabled`;
- `policy.diagnostics.log-level`.

Correct precise names:

```text
platform.tracing.policy.propagation.trace-control-headers.enabled
platform.tracing.policy.diagnostics.log-level
```

Do not use ambiguous:

```text
platform.tracing.policy.propagation.enabled
platform.tracing.policy.log-level
```

## 3.2 Java Agent structural/bootstrap

```text
platform.tracing.javaagent.*
```

Agent-owned values required before or during Agent pipeline construction.

Confirmed examples:

- queue/BSP configuration;
- resource/service identity used at SDK construction;
- Agent processor composition;
- propagator composition;
- `scrubbing.rules-config`;
- HMAC key material;
- hash key identifier/rotation metadata;
- scrubbing rule extensions;
- extension loading hooks;
- Agent instrumentation/watchdog settings proven startup-only.

These values:

- are applied from env/`-D` at bootstrap;
- may appear in Config Server as desired state;
- are not applied at runtime;
- produce `restartRequired` when desired differs from effective.

## 3.3 Spring application-owned configuration

Remain under existing non-reserved:

```text
platform.tracing.*
```

Examples may include:

- servlet/WebFlux response behavior;
- outbound adapter settings;
- Kafka adapter settings;
- semantic mapping;
- suppression;
- executor integration;
- other Spring-only adapter behavior.

Do not move a key into `javaagent.*` merely because it concerns tracing. Ownership follows the final consumer and lifecycle.

In `CONFIG_SERVER` deployments these Spring-owned settings may be supplied remotely at startup. Runtime refresh support is independent and must be explicitly designed per key; otherwise changes are restart-required.

## 3.4 Orchestration

```text
platform.tracing.configuration.*
```

Contains only control-plane posture, for example:

```text
platform.tracing.configuration.authority
```

Potential future bridge settings must be narrowly justified.

Authority itself is bootstrap configuration. It must not be sourced from the Config Server whose authority it enables.

## 3.5 Local dev control

```text
platform.tracing.dev-control.*
```

Correct key:

```text
platform.tracing.dev-control.enabled
```

Not:

```text
platform.tracing.configuration.dev-control.enabled
```

This namespace contains only local JMX control activation/settings.

## 3.6 Unsupported SDK namespace

Do not create:

```text
platform.tracing.sdk.*
```

unless SDK-only production becomes an approved topology.

## 3.7 Imported and standard signals

Keep unchanged:

- `platform.environment`;
- `otel.*`;
- `OTEL_*`;
- `otel.javaagent.extensions`.

`platform.tracing.policy.version` is an OTel resource attribute, not a configuration key. It must be documented to prevent confusion with the new `policy.*` namespace.

---

# 4. Authority source truth table

## 4.1 Runtime policy sources

| Authority | Allowed initial policy source | Runtime writer | Forbidden |
|---|---|---|---|
| `HELM` | Agent system properties/environment materialized by Helm | None after Agent bootstrap | application YAML policy keys; Config Server policy; JMX runtime writes |
| `CONFIG_SERVER` | Complete trusted Config Server policy manifest | Config Server bridge only | local YAML, command-line, system/env override of policy keys; direct JMX; HTTP mutation |
| `DEV_ACTUATOR` | `application-dev.yml`/approved local-dev source as baseline | JMX-only DEV endpoint | production posture; Config Server bridge; HTTP mutation |

## 4.2 Structural Agent sources

| Authority | Effective bootstrap source | Config Server desired state |
|---|---|---|
| `HELM` | env/`-D` | Not authoritative |
| `CONFIG_SERVER` | env/`-D` | Allowed for comparison and rollout diagnostics |
| `DEV_ACTUATOR` | env/`-D` | Bridge disabled; structural JMX attempts return `restartRequired` |

## 4.3 Spring-owned configuration sources

Spring-owned adapter properties may come from normal Spring configuration sources.

In `CONFIG_SERVER` mode, Config Server may be their startup source. Runtime refresh is allowed only for keys with a separately proven runtime contract.

Authority rules for Agent runtime policy must not accidentally reject unrelated Spring-owned tracing properties.

---

# 5. Canonical default ownership

The original report incorrectly declared Spring the owner of runtime-policy defaults.

## 5.1 HELM authority

The Agent must own canonical policy parsing, validation and defaults because it runs before Spring.

```text
HELM:
    Agent bootstrap policy/defaults are canonical.
    Spring is read-only for Agent policy.
```

## 5.2 CONFIG_SERVER authority

Use a complete authoritative runtime-policy manifest.

```text
CONFIG_SERVER:
    missing managed policy key -> reject;
    no fallback to Spring field defaults;
    no silent inheritance from previous accepted state.
```

This avoids duplicating Agent defaults in Spring.

## 5.3 DEV_ACTUATOR authority

Recommended:

- require a complete local dev policy baseline; or
- initialize from the current Agent accepted policy read projection.

Do not create an independent Spring default catalog.

## 5.4 Structural and application defaults

- Agent structural defaults are owned by Agent bootstrap configuration.
- Spring application-adapter defaults remain owned by their Spring configuration types.
- OTel standard defaults remain owned by OTel.

---

# 6. Property and environment-variable migration

## 6.1 The original "64-key map" is incomplete

The original table:

- uses wildcard rows;
- omits leaf keys;
- invents or normalizes names without source proof;
- does not enumerate every Agent environment variable;
- does not prove final consumers for every key.

Therefore:

```text
Full 64-key migration map: STILL BLOCKING
```

It must be generated from:

- `TracingProperties`;
- `ExtensionPropertyNames`;
- `PropagationDefaults`;
- `ExtensionEnvironmentVariables`;
- resource/service providers;
- YAML/docs/scripts/perf fixtures.

Required artifact:

```text
docs/research/tracing-configuration-key-disposition.md
```

Required row shape:

| Current property/env key | Declaration | Current readers | Final consumer | Current default owner | Target key/DELETE | Target owner | A/B | Authority/source rules | Tests |
|---|---|---|---|---|---|---|---|---|---|

No wildcard rows in the binding appendix.

## 6.2 Approved dispositions already supported by evidence

| Current item | Target disposition |
|---|---|
| `platform.tracing.sdk.mode` | DELETE |
| `platform.tracing.propagation.platform-headers.*` | DELETE; use `PlatformHeaders` |
| `platform.tracing.actuator.mutation-enabled` | DELETE after cutover |
| `platform.tracing.control.runtime-mutation.enabled` | DELETE after cutover |
| `platform.tracing.scrubbing.rules-config` | `platform.tracing.javaagent.scrubbing.rules-config`, Category B |
| `platform.environment` | KEEP |
| OTel standard keys/env | KEEP |
| runtime propagation gate | rename to `platform.tracing.policy.propagation.trace-control-headers.enabled` if retained in Category A |
| Agent diagnostic level | rename to `platform.tracing.policy.diagnostics.log-level` if retained in Category A |

## 6.3 Environment variable migration

Do not keep every current `PLATFORM_TRACING_*` mapping unchanged.

Renamed properties require corresponding canonical environment names, for example:

```text
PLATFORM_TRACING_POLICY_...
PLATFORM_TRACING_JAVAAGENT_...
PLATFORM_TRACING_CONFIGURATION_AUTHORITY
PLATFORM_TRACING_DEV_CONTROL_ENABLED
```

The exact names must be emitted by the full disposition artifact.

No aliases for old environment names after cutover.

Both:

- Agent bootstrap mapper;
- Spring relaxed binding/guard;

must reject or stop recognizing legacy names consistently.

---

# 7. Header-name configuration removal

Binding decision:

```text
PlatformHeaders is the sole supported header-name contract.
```

Delete all configuration allowing the following names to vary:

- `X-Trace-On`;
- `X-QA-Trace`;
- `X-Request-Id`;
- other platform header names currently represented by `PlatformHeaders`.

Required migration:

1. inventory all override keys and environment aliases;
2. update Agent, Spring web, Kafka, tests, samples and docs;
3. add contract tests against `PlatformHeaders`;
4. add fail-fast diagnostics for removed configuration keys;
5. perform one coherent supported cutover.

Do not keep mutable aliases.

---

# 8. Guard model

The guard classifies by:

- integration mode;
- authority;
- key ownership;
- runtime/structural category;
- property-source provenance;
- environment posture.

## 8.1 Bootstrap-control keys

The winning source for:

```text
platform.tracing.mode
platform.tracing.configuration.authority
platform.tracing.dev-control.enabled
```

must be an approved bootstrap/local source.

Config Server must not determine which authority controls Config Server.

## 8.2 Legacy keys

All removed/renamed legacy property and environment names are rejected in every source after cutover.

Before cutover, do not activate the new guard partially.

## 8.3 HELM

Fail fast if Agent runtime-policy keys are found only in:

- `application.yml`;
- profile YAML;
- Config Server sources.

Reason: the Agent cannot see them during premain.

## 8.4 CONFIG_SERVER

Require:

- Config Client present;
- valid `configClient` marker;
- complete remote runtime-policy manifest;
- every runtime-policy effective value won by the trusted remote source;
- no local/system/env policy override;
- modern Config Data path;
- Agent protocol capability.

Structural Agent desired keys may be remote even though effective values are env/`-D`.

## 8.5 DEV_ACTUATOR

Require all:

- `mode=AGENT`;
- `authority=DEV_ACTUATOR`;
- `platform.tracing.dev-control.enabled=true`;
- dev/local profile;
- non-production `platform.environment`;
- `spring.jmx.enabled=true`;
- JMX endpoint exposure;
- Config Server bridge absent.

Runtime-policy baseline may come only from approved local-dev sources.

## 8.6 Removed keys

Removed keys cause aggregated fail-fast diagnostics with:

- old key;
- target key or deletion reason;
- valid source channel;
- authority/mode context.

No WARN-only compatibility phase is required.

---

# 9. Architecture and contract tests

Do not rely only on brittle field-name ArchUnit samples.

Use a combination of source/architecture, binding and process-level tests.

## 9.1 Architecture rules

- no production binding or constants for `platform.tracing.sdk.*`;
- Spring Config Server bridge depends only on aggregate applier client;
- no Spring call to domain JMX setters;
- no `@WriteOperation` on the read Actuator endpoint;
- no public control DTOs in `platform-tracing-api`;
- no legacy control protocol use after cutover;
- exactly one Agent JMX write operation;
- no independently mutable policy holder after state cutover;
- no platform header literals outside `PlatformHeaders`;
- no compatibility alias registry.

## 9.2 Property-contract tests

- complete key disposition table has one row per discovered key/env name;
- every target runtime-policy key has one semantic owner;
- every target structural key has one bootstrap owner;
- old keys do not bind;
- old environment variables do not map;
- `policy.version` resource attribute is not treated as configuration.

## 9.3 Authority matrix tests

Test every mode/authority combination, including rejected combinations.

## 9.4 Provenance tests

Retain T1–T10 and add:

- authority cannot be sourced remotely;
- structural remote desired value allowed;
- HELM YAML-only policy rejected;
- DEV baseline accepted only in dev posture.

---

# 10. Migration/cutover strategy

The namespace cutover should be coherent at the supported-release level.

Implementation may be split into internal PRs, but do not publish or document a mixed supported contract.

Required order:

1. Generate and approve exact key/env disposition.
2. Approve final Category A/B list.
3. Implement new readers/codecs behind unreleased branch.
4. Update Helm templates, Config Server examples, tests and docs.
5. Add complete guard.
6. Switch Agent and Spring adapters together.
7. Remove old readers/defaults/aliases.
8. Verify no legacy key remains outside migration documentation.
9. Release only the coherent new contract.

Because the product is pre-production, no dual-read compatibility phase is needed.

---

# 11. Remaining unresolved decisions

| Decision | Status |
|---|---|
| Authority separate from mode | ACCEPTED |
| Three authority values | ACCEPTED |
| DEV_ACTUATOR as mutually exclusive local authority posture | ACCEPTED |
| `sdk.*` namespace | REJECTED |
| Prefix taxonomy | ACCEPTED |
| Exact 64-key/ENV migration | **UNRESOLVED / BLOCKING** |
| Complete Category A allowlist | **UNRESOLVED** |
| Config Server refresh-delivery mechanism | **UNRESOLVED** |
| `mode=DISABLED` with Config Server/DEV control | Rejected by default |
| Runtime policy default owner | Corrected: Agent/complete manifest |
| Header-name config removal | ACCEPTED |
| Exact guard implementation | Design accepted; depends on final key map |
| Implementation GO | NOT GRANTED |

---

# 12. Handoff contract for Prompt 7

Prompt 7 must treat these findings as accepted:

```text
1. mode and authority are orthogonal.
2. mode=AGENT|DISABLED controls Spring integration; authority controls runtime-policy writer/source.
3. HELM, CONFIG_SERVER and DEV_ACTUATOR remain mutually exclusive authority postures.
4. DEV_ACTUATOR is a local authority posture, not an additional writer beside production authority.
5. SDK-only production parity and platform.tracing.sdk.* are excluded.
6. Runtime policy lives under platform.tracing.policy.*.
7. Agent structural/bootstrap lives under platform.tracing.javaagent.*.
8. Spring application-owned configuration remains outside policy/javaagent reserved prefixes.
9. Orchestration lives under platform.tracing.configuration.*.
10. Dev JMX settings live under platform.tracing.dev-control.*.
11. Correct names include policy.propagation.trace-control-headers.enabled and policy.diagnostics.log-level.
12. platform.environment and OTel standard configuration remain unchanged.
13. Runtime-policy defaults are not owned by Spring:
    - HELM uses Agent defaults;
    - CONFIG_SERVER uses a complete manifest;
    - DEV uses complete baseline/current accepted state.
14. Structural Agent keys may exist remotely as desired state in CONFIG_SERVER mode.
15. authority/mode/dev-control bootstrap keys must not be sourced from Config Server.
16. mode=DISABLED with CONFIG_SERVER or DEV_ACTUATOR is rejected by default.
17. PlatformHeaders is the fixed header-name contract; header-name config is deleted.
18. No property or environment aliases survive cutover.
19. The original report did not complete the required 64-key map; that remains a blocker.
20. Implementation GO is not granted.
```

Prompt 7 must not inherit these claims from the original Prompt 6 output:

```text
1. That DEV_ACTUATOR is not an authority while still using it as an authority enum value.
2. That mode=DISABLED + CONFIG_SERVER is generally legal.
3. That dev-control.enabled belongs under platform.tracing.configuration.*.
4. That policy.propagation.enabled and policy.log-level are sufficiently precise names.
5. That Spring owns canonical runtime-policy defaults.
6. That all PLATFORM_TRACING_* environment names remain unchanged.
7. That Config Server cannot carry structural desired state.
8. That the displayed wildcard table is a complete 64-key migration map.
9. That every key classified as Category A/B in the table has sufficient evidence.
10. That the sample ArchUnit rules are implementation-ready.
11. That all authority/namespace blockers are resolved.
```

---

# 13. Final status

```text
Prompt 6 result: PARTIAL_PASS_WITH_CRITICAL_CORRECTIONS
Authority model: ACCEPTED
Namespace prefix taxonomy: ACCEPTED
Complete property/env migration: BLOCKING / INCOMPLETE
Implementation GO: NOT GRANTED
Legacy mutation deletion: BLOCKED
Next prompt: DEV_ACTUATOR and JMX Local Debugging
```
