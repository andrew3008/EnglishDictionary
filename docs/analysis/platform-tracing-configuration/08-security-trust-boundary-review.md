# Security and Trust Boundary Review
## Corrected Repository-Grounded Adversarial Assessment

## Review status

| Field | Value |
|---|---|
| Report status | **PASS_WITH_CRITICAL_SECURITY_AMENDMENTS** |
| Production write boundary | **Private process-local MBeanServer required** |
| Platform MBeanServer | **Read-only operational projection after cutover** |
| Signed snapshots | **Not justified without a separate signing authority** |
| Config Server | **Explicitly trusted production authority, subject to TLS/auth and governance** |
| Raw secrets in snapshots/projections/digests | **Forbidden** |
| `scrubbing.rules-config` | **Startup-only Category B** |
| Implementation GO | **SECURITY NO-GO** |
| Legacy mutation deletion | **Blocked until cutover; mandatory before production GO** |
| Intended handoff | Prompt 9 — SRE Diagnostics and JMX Contract |

The original report identified many real threats but proposed several unsafe or contradictory mitigations:

- it left the future aggregate writer on the remotely exportable platform MBeanServer;
- it treated network documentation as sufficient protection from direct Agent MBean invocation;
- it made `platform.environment` the sole guard for legacy setters;
- it proposed rollback across per-domain holders even though the accepted architecture requires one aggregate CAS;
- it ordered snapshots by Config Server revision even though revision may be absent and is not a monotonic concurrency token;
- it changed local Config Server overrides from fail-closed to warning-only;
- it proposed silently disabling an externally active Agent when the Spring facade is `DISABLED`;
- it invented an unapproved production sampling ceiling;
- it used fixed debounce numbers and payload limits without evidence;
- it treated Config Server TLS as protection against a compromised Config Server;
- it described HMAC verification behavior and cross-pod requirements not established by repository evidence.

This corrected report defines an explicit trust model and closes the direct-JMX production bypass.

---

# 1. Trust model

## 1.1 Trusted components

The target architecture trusts:

- the controlled Java Agent distributive and platform extension;
- the application process and platform Spring autoconfiguration;
- the approved Spring Cloud Config Client;
- the configured Config Server as the production desired-state authority;
- the deployment platform that supplies Agent bootstrap values and secrets;
- the corporate identity/network controls protecting Config Server and management infrastructure.

## 1.2 Untrusted or potentially hostile inputs

Treat as untrusted:

- every JMX operation argument;
- every Config Server property value;
- every local YAML/system/environment value that attempts to override remote runtime policy;
- every file path supplied to `scrubbing.rules-config`;
- every collection/string decoded from `CompositeData`;
- every refresh event frequency/order;
- every unsupported Agent/starter version combination;
- every legacy mutation path while it still exists.

## 1.3 Explicitly out of scope

The library cannot provide meaningful isolation after arbitrary malicious code is already executing inside the same JVM with equivalent permissions.

A malicious in-process component can potentially:

- access JMX APIs;
- mutate Spring environment state;
- use reflection/unsafe APIs;
- read process memory;
- terminate the JVM.

The target protects against:

- remote management access;
- accidental/misconfigured local sources;
- compromised external configuration delivery;
- malformed payloads;
- stale/replayed writes;
- operational mistakes;
- unsupported artifact combinations.

It does not claim to sandbox hostile application code in the same process.

---

# 2. Binding production write-boundary decision

## 2.1 Problem

The Agent aggregate writer is needed in production for the Config Server bridge.

If it is registered as a write operation on the platform `MBeanServer`, any remote JMX connector exporting that server can invoke it directly and bypass:

- Config Server provenance checks;
- authority rules;
- Spring-side posture guards;
- developer endpoint activation;
- operational refresh controls.

Removing the Spring `@JmxEndpoint` in production does not close this bypass.

## 2.2 Required architecture

Create a dedicated process-local `MBeanServer` for application-to-Agent writes.

Conceptual topology:

```text
Platform MBeanServer
    - existing eight ObjectNames
    - approved read-only attributes/operations
    - readiness and diagnostics
    - no runtime-policy write operation after cutover

Private tracing MBeanServer
    - aggregate snapshot writer
    - accepted-state read needed by in-process adapters
    - protocol marker/runtimeInstanceId
    - not attached to the standard remote JMX connector
```

The Agent creates the private server using JDK JMX APIs.

The Spring bridge and DEV_ACTUATOR command service discover it in-process through JDK `MBeanServerFactory` and require exactly one matching internal protocol marker.

No Java DTO crosses the classloader boundary; the private server still uses `CompositeData`.

## 2.3 ObjectName compatibility

Existing platform ObjectNames remain unchanged.

The aggregate writer may use the same logical Control ObjectName on the private server because ObjectName uniqueness is scoped to an individual MBeanServer.

The platform server exposes a read-only counterpart under the preserved ObjectName.

Before implementation, capture the current JMX baseline and define the approved split explicitly.

## 2.4 Security property

A normal remote JMX connector is created for a specific `MBeanServer`. Keeping the writer on a separate server prevents the ordinary platform-server connector from exporting the write operation.

This does not attempt to hide the writer from trusted in-process code.

## 2.5 Required feasibility spike

Before implementation GO, prove:

1. Agent creates the private server during extension initialization.
2. Spring finds exactly one internal server after Agent readiness.
3. Spring and Agent codecs interoperate across classloaders.
4. Platform remote JMX sees only read operations.
5. The private writer is absent from the platform MBeanServer.
6. Local attach/management-agent activation does not expose the private server.
7. Process restart releases/recreates the server without leaks.
8. Multiple application contexts do not create duplicate writers.
9. Agent-only topology works without Spring discovery.
10. Disabled Spring facade does not discover or mutate the writer.

If this design is not feasible, production GO requires an equally enforceable alternative—not documentation alone.

---

# 3. Threat model

## T-01 — Ungated domain JMX mutation

| Field | Assessment |
|---|---|
| Current entry point | Domain write operations on sampling, scrubbing, validation, export and diagnostics MBeans |
| Target entry point | None on platform MBeanServer; one private aggregate writer |
| Boundary | Remote/local JMX to Agent runtime |
| Severity | **CRITICAL** |
| Failure | Force 100% sampling, disable export, weaken scrubbing/validation, increase log volume |
| Mandatory mitigation | Delete domain setters and old partial writes before production GO; private aggregate writer only |
| Transitional mitigation | If an unreleased transition build retains them, reject all writes unless a test-only/dev posture is explicitly installed; do not rely solely on `platform.environment` |
| Test | Platform MBeanServer contains zero write operations after cutover |
| Delete | Every direct domain write method and old write client |

Because the product is pre-production, adding a long-lived production guard to preserve old setters is inferior to deleting the setters before production release.

## T-02 — DEV_ACTUATOR accidentally active in production

| Field | Assessment |
|---|---|
| Current entry point | Gated HTTP Actuator mutation |
| Target entry point | Spring JMX-only typed endpoint |
| Boundary | Management client to application |
| Severity | **HIGH** |
| Failure | Local developer mutation becomes production control path |
| Mandatory mitigation | `mode=AGENT`, `authority=DEV_ACTUATOR`, explicit enable, trusted non-prod `platform.environment`, approved profile, JMX enabled and endpoint exposed; remote source cannot set posture |
| Test | Production process has no dev endpoint MBean and no HTTP write route |
| Delete | Existing HTTP write operations and mutation gate |

Profiles are defense in depth, not the trust anchor.

## T-03 — Remote JMX exposure

| Field | Assessment |
|---|---|
| Current entry point | Platform MBeanServer through configured JMX connector |
| Target entry point | Read-only platform MBeans; private write MBeanServer |
| Boundary | Network/attach management to JVM |
| Severity | **CRITICAL for writes; HIGH for sensitive reads** |
| Failure | Direct policy mutation or leakage of configuration/secrets |
| Mandatory mitigation | Keep production writer off the platform MBeanServer; redact reads; document remote JMX TLS/auth requirements |
| Optional hardening | Infrastructure read-only roles, connector authentication/TLS, network policy |
| Test | Remote connector cannot query/invoke aggregate writer |
| Delete | Platform-server write operations |

The library should not create or configure the remote JMX connector.

## T-04 — Local/system override of Config Server policy

| Field | Assessment |
|---|---|
| Current entry point | Spring property-source precedence |
| Target entry point | Provenance-aware Config Server bridge |
| Boundary | Local deployment configuration versus remote authority |
| Severity | **HIGH** |
| Failure | Effective runtime policy differs from authoritative desired policy |
| Mandatory mitigation | Fail closed for every managed `policy.*` local/system/env override; retain current accepted state at runtime |
| Test | Higher-precedence local value causes initial readiness failure or runtime `REJECTED` |
| Delete | Duplicate local policy readers |

Warning-only behavior is not sufficient in `CONFIG_SERVER` authority.

## T-05 — Marker or revision spoofing

| Field | Assessment |
|---|---|
| Entry point | Spring `PropertySources` |
| Boundary | Config Client metadata versus arbitrary local property source |
| Severity | **MEDIUM** |
| Failure | False provenance/revision diagnostics |
| Mandatory mitigation | Direct marker-source read plus multi-signal classification; no generic winning lookup; revision remains optional metadata |
| Test | Same key in another source cannot spoof marker/version |
| Delete | Any source-name-only or `Environment.getProperty` revision logic |

A hostile component already able to mutate Spring internals in-process is outside the isolation claim.

## T-06 — Stale or replayed snapshot

| Field | Assessment |
|---|---|
| Current entry point | Sequential refresh/mutation paths |
| Target entry point | Aggregate private MBean request |
| Boundary | Application adapter to Agent accepted state |
| Severity | **HIGH** |
| Failure | Older candidate overwrites newer accepted policy |
| Mandatory mitigation | `runtimeInstanceId` + expected `acceptedSequence`; one CAS; `STALE` on base mismatch; canonical digest for idempotency |
| Test | Concurrent same-base writes produce one `APPLIED`, one `STALE` |
| Delete | Revision-ordering and per-domain version coordination |

Config Server revision is not used as a monotonic concurrency token because it may be absent or non-orderable.

## T-07 — CompositeData type confusion

| Field | Assessment |
|---|---|
| Current entry point | Partial protocol with string coercion |
| Target entry point | Versioned, exact Open-Type schema |
| Boundary | Cross-classloader wire contract |
| Severity | **HIGH** |
| Failure | Null/type confusion, silent coercion, invalid enum/range |
| Mandatory mitigation | Exact protocol/type name, exact field OpenTypes, required/optional contract, reject unknown/missing/null fields, no generic string coercion |
| Test | Negative corpus for wrong type/version/field/null/enum |
| Delete | Current string-map/string-coercion decoder |

## T-08 — Oversized payload or violation list

| Field | Assessment |
|---|---|
| Entry point | Open-Type request collections/strings |
| Boundary | Application-controlled data to Agent memory/CPU |
| Severity | **HIGH** |
| Failure | Allocation spike, validation CPU DoS, oversized diagnostics |
| Mandatory mitigation | Domain-specific entry/count/string/path limits and total bounded diagnostic output; validate before expensive normalization |
| Test | Boundary and over-limit cases for every collection/string |
| Delete | Unbounded generic maps and error lists |

Do not hard-code arbitrary limits such as `500` or `256` without deriving and documenting them from supported property contracts.

## T-09 — Secret leakage

| Field | Assessment |
|---|---|
| Entry point | Effective config, JMX reads, Actuator, audit, logs, exceptions, digests |
| Boundary | Agent secret material to operators/telemetry |
| Severity | **CRITICAL** |
| Failure | HMAC key disclosure or secret-derived offline attack material |
| Mandatory mitigation | Raw secret never enters runtime snapshot, structural desired snapshot, digest, JMX/Actuator projection, audit or log; expose only non-secret key-id and configured state when required |
| Test | Recursive secret canary tests across every projection/log/result |
| Delete | Any generic full-config projection |

Do not expose a digest of the HMAC key. A deterministic secret digest is unnecessary and can become attack material.

## T-10 — File-path rules configuration abuse

| Field | Assessment |
|---|---|
| Entry point | Agent bootstrap `scrubbing.rules-config` |
| Boundary | Deployment configuration to filesystem/classpath |
| Severity | **HIGH** |
| Failure | Load unintended rules resource, weaken scrubbing, leak path data through diagnostics |
| Mandatory mitigation | Startup-only; explicit supported schemes; canonicalize filesystem path; require regular readable file; define symlink policy; never log file content; project only a non-sensitive rules identity/digest when required |
| Test | Traversal, symlink, unsupported scheme, non-file, unreadable and oversized rules file |
| Delete | Runtime rules-path mutation |

Allowed directories are a deployment policy and should be added only if the organization has a confirmed base-path contract.

## T-11 — HMAC key rotation inconsistency

| Field | Assessment |
|---|---|
| Entry point | Bootstrap secret/key-id during rolling deployment |
| Boundary | Secret distribution to Agent instances |
| Severity | **MEDIUM** |
| Failure | Mixed key identities across fleet or unexpected scrub output |
| Mandatory mitigation | Startup-only rotation, expose key-id/configured state, controlled rollout and fleet diagnostics |
| Optional hardening | Rotation runbook and rollout gate |
| Test | Process restart adopts new key-id; raw key absent everywhere |
| Delete | Runtime key rotation control |

Do not require all pods to use the same key-id unless the consuming security protocol explicitly requires it.

## T-12 — Sampling forced to 100%

| Field | Assessment |
|---|---|
| Entry point | Runtime policy writer |
| Boundary | Trusted authority/operator to Agent workload |
| Severity | **HIGH operational impact** |
| Failure | Collector/network/memory overload |
| Mandatory mitigation | Strict `[0,1]` validation, bounded route rules, audit/metrics, authority isolation, refresh-storm controls |
| Optional hardening | Immutable environment-specific maximum only if SRE approves a safety envelope |
| Test | High-ratio update under load and operational alert |
| Delete | Direct setter paths |

Do not invent a default production ceiling such as `0.1`; emergency full sampling may be a legitimate controlled operation.

## T-13 — Export kill-switch abuse

| Field | Assessment |
|---|---|
| Entry point | Runtime policy writer |
| Boundary | Trusted authority to observability availability |
| Severity | **HIGH operational impact** |
| Failure | Silent loss of tracing visibility |
| Mandatory mitigation | Explicit outcome/audit, current status projection, metric and alert; only aggregate authority can change it |
| Optional hardening | Environment policy may forbid disablement if SRE requires |
| Test | Change is visible and attributable |
| Delete | Direct export setter |

Whether export-disabled maps to health `DEGRADED` is an SRE contract decision, not a universal security rule.

## T-14 — Diagnostics log-level abuse

| Field | Assessment |
|---|---|
| Entry point | Runtime diagnostics policy |
| Boundary | Authority to logs and sensitive diagnostics |
| Severity | **MEDIUM–HIGH** |
| Failure | Log-volume DoS or sensitive data exposure |
| Mandatory mitigation | Authority-specific allowlist, bounded/rate-limited diagnostic logging, sensitive values never logged at any level |
| Test | TRACE/DEBUG cannot reveal policy payloads, secrets or span data |
| Delete | Direct log-level setter |

Security must not depend on suppressing `TRACE`; logging code itself must be safe.

## T-15 — Refresh-storm DoS

| Field | Assessment |
|---|---|
| Entry point | Repeated `EnvironmentChangeEvent`/refresh delivery |
| Boundary | Config refresh mechanism to bridge/Agent |
| Severity | **HIGH** |
| Failure | CPU/JMX/apply storms and unbounded audit |
| Mandatory mitigation | Generation/dirty single-worker coalescing, no lost latest event, bounded retry, bounded audit, apply duration/counter metrics |
| Test | Event storm converges to latest state with bounded applies |
| Delete | Parallel/per-event apply and fixed-delay debounce assumptions |

Do not use an event-dropping `AtomicBoolean` or arbitrary fixed five-second delay as the primary correctness mechanism.

## T-16 — Unsupported starter/Agent version

| Field | Assessment |
|---|---|
| Entry point | Deployment artifact mismatch |
| Boundary | Application artifact to controlled Agent distributive |
| Severity | **CRITICAL** |
| Failure | Wire mismatch, missing capability, incorrect state semantics |
| Mandatory mitigation | Controlled versioned distributive, pre-JVM integrity check, readiness descriptor/protocol capability validation, fail closed on incompatibility |
| Test | Process matrix with wrong/missing extension and protocol version |
| Delete | Best-effort WARN-only compatibility |

Reuse the existing readiness/attestation strategy rather than adding a loosely defined `UNSUPPORTED_AGENT_VERSION` capability.

## T-17 — Partial apply and LKG corruption

| Field | Assessment |
|---|---|
| Current entry point | Sequential `RuntimeConfigApplier` and domain setters |
| Target entry point | One aggregate Agent CAS |
| Boundary | Candidate validation/commit |
| Severity | **CRITICAL** |
| Failure | Mixed runtime policy and false LKG |
| Mandatory mitigation | Build/validate complete candidate, one accepted-state CAS, no per-domain rollback coordinator |
| Test | Failure before CAS leaves state unchanged; no independently mutable owner remains |
| Delete | Sequential applier, multi-holder rollback and domain writers |

Rollback across several CAS holders is not atomic and is explicitly rejected.

## T-18 — Structural drift ignored

| Field | Assessment |
|---|---|
| Entry point | Config Server structural desired state versus Agent bootstrap |
| Boundary | Remote desired state to effective pipeline |
| Severity | **HIGH** |
| Failure | Operator believes structural configuration is applied when restart is required |
| Mandatory mitigation | Initial mismatch blocks readiness; runtime mismatch retains current pipeline and emits `restartRequired` diagnostics |
| Test | Initial/runtime drift matrix |
| Delete | Spring-only "effective" projection that hides Agent truth |

Do not automatically remove every replica from readiness on runtime drift.

## T-19 — Disabled facade with active Agent

| Field | Assessment |
|---|---|
| Entry point | `mode=DISABLED` with externally attached Agent |
| Boundary | Spring facade semantics versus independent Agent lifecycle |
| Severity | **MEDIUM semantic/security risk** |
| Failure | User assumes no telemetry while Agent still exports |
| Mandatory mitigation | Explicit documentation/status warning; deployment must remove/disable Agent to guarantee no telemetry |
| Test | Disabled facade does not mutate Agent and reports Agent presence semantics correctly |
| Delete | Any best-effort auto-disable behavior |

Spring must not silently invoke `setExportEnabled(false)` on an externally active Agent. `DISABLED` disables the facade, not the Agent plane.

## T-20 — Compromised Config Server

| Field | Assessment |
|---|---|
| Entry point | Trusted Config Server runtime policy |
| Boundary | Configuration authority to application |
| Severity | **CRITICAL accepted trust-root risk** |
| Failure | Valid but malicious policy disables export/scrubbing or overloads sampling |
| Mandatory mitigation | TLS/auth, strict schema/allowlist/bounds, complete audit, optional immutable safety envelope approved by SRE |
| Test | Malicious-but-valid boundary values and audit/alert behavior |
| Delete | Assumption that transport TLS protects against server compromise |

TLS/auth protects the connection and authenticates the server. It does not protect against a genuinely compromised trusted Config Server.

---

# 4. Signed snapshot decision

## Decision

```text
Do not implement signed snapshots now.
```

Reason:

A signature is valuable only when the signer/trust root is separate from the Config Server and the Agent can validate an independent organizational authorization decision.

Signing inside the same compromised Config Server does not help.

A correct signed-snapshot system would require:

- independent signing authority;
- key distribution to the Agent distributive;
- key rotation/revocation;
- canonical payload definition;
- replay policy;
- operational signer consumer;
- incident recovery.

No such confirmed platform capability or consumer is present.

## Reversal condition

Reconsider signing only when the organization requires Config Server to be treated as an untrusted distribution channel and provides a separate signer/key-management contract.

---

# 5. Config Server TLS and authentication decision

## Decision

The library does not implement Config Server transport security.

Production support requires:

- TLS;
- approved server authentication;
- approved client identity/token or mutual authentication;
- certificate/credential rotation owned by platform infrastructure;
- no plaintext production endpoint.

The tracing platform must document and test required client configuration where feasible.

The security model explicitly treats the authenticated Config Server as trusted policy authority.

---

# 6. JMX transport security decision

## Decision

The library does not create or configure a remote JMX connector, password file, TLS socket factory or RMI registry.

Infrastructure owns remote connector security.

The library owns:

- keeping production writes off the platform MBeanServer;
- redacting read models;
- no HTTP mutation;
- exact Open-Type validation;
- dev endpoint posture;
- JMX contract tests.

Remote platform JMX, when enabled, must use approved authentication/TLS/read-only roles. Documentation alone is not accepted as protection for the aggregate writer; the private server provides the enforceable control.


---

# 7. Secret representation contract

## 7.1 Raw HMAC key

Raw key material:

- remains Agent bootstrap-only;
- is never copied into Spring;
- is never represented in Config Server desired state by this tracing contract;
- is never included in runtime accepted policy;
- is never included in canonical policy digest;
- is never returned through JMX/Actuator;
- is never included in audit, logs or exception messages.

## 7.2 Key identifier

A non-secret key identifier may be exposed when required for rollout diagnostics.

Expose only:

- key-id;
- configured/not-configured;
- optional load status.

Do not expose raw key length, digest or source value unless a security review explicitly approves it.

## 7.3 Scrubbing rules

Do not project inline rules or file content.

A rules identity/content digest may be exposed only after confirming:

- it cannot reveal secret material;
- SRE has a concrete comparison/attestation consumer;
- canonicalization is defined.

Full filesystem paths should not be exposed by default.

---

# 8. Fail-closed and availability semantics

## Initial CONFIG_SERVER activation

Fail closed and block readiness on:

- Config import failure;
- invalid/missing marker;
- local policy override;
- unsupported protocol;
- malformed/incomplete manifest;
- rejected/failed/stale initial apply after bounded retry;
- initial structural mismatch.

## Runtime failure

Fail to LKG:

- retain current accepted policy;
- do not reset to defaults;
- remain ready by default;
- expose staleness/failure diagnostics and alerts;
- structural mismatch adds `restartRequired`.

A custom `DEGRADED` status may be used only if already part of the corporate health contract and approved by SRE.

---

# 9. Mandatory security controls

| ID | Control | Owner |
|---|---|---|
| SC-01 | Private process-local write MBeanServer | Agent extension |
| SC-02 | Platform MBeanServer read-only after cutover | Agent extension |
| SC-03 | Delete all legacy writers before production GO | Agent/Spring |
| SC-04 | Exact versioned Open-Type schema; no string coercion | Agent + Spring codecs |
| SC-05 | One aggregate CAS and optimistic base | Agent state |
| SC-06 | Fail-closed remote policy provenance | Spring bridge |
| SC-07 | Complete runtime-policy manifest | Config Server contract |
| SC-08 | Generation/dirty refresh coalescing | Spring bridge |
| SC-09 | Domain-specific payload and diagnostics limits | Codecs/validators |
| SC-10 | Raw-secret exclusion contract | All projections/codecs/logging |
| SC-11 | Startup-only rules/key lifecycle | Agent bootstrap |
| SC-12 | Versioned controlled Agent distributive/attestation | Distribution/readiness |
| SC-13 | DEV_ACTUATOR multi-gate and no HTTP route | Spring autoconfigure |
| SC-14 | Initial structural mismatch readiness gate | Spring readiness |
| SC-15 | Runtime fail-LKG behavior | Bridge + Agent |
| SC-16 | Security-focused process E2E suite | E2E modules |

---

# 10. Optional hardening

Implement only with a named consumer/requirement:

- immutable environment-specific maximum sampling ratio;
- prohibition of export disablement in selected environments;
- MBean notifications for critical state changes;
- rules file allowlisted directories;
- symlink prohibition;
- rules content digest;
- Config Server staleness health threshold;
- separate JMX read-only role verification;
- external snapshot signing with independent signer.

These are not substitutes for the mandatory write-boundary architecture.

---

# 11. Overengineering and unsafe mitigations rejected

Reject:

1. Generic security-policy framework.
2. Library-created remote JMX server/TLS stack.
3. Snapshot signing without independent signer infrastructure.
4. Runtime HMAC key rotation.
5. Runtime `rules-config` mutation.
6. Multi-holder rollback coordinator.
7. Silent best-effort Agent export disable in `mode=DISABLED`.
8. Hard-coded `0.1` production sampling ceiling.
9. Fixed five-second refresh debounce as correctness mechanism.
10. Treating Config revision as a monotonic sequence.
11. Warning-only local override in CONFIG_SERVER authority.
12. Exposing raw policy snapshots through developer-facing JMX.
13. Relying solely on profiles or `platform.environment` for production safety.
14. Treating network documentation as sufficient authorization for the Agent writer.

---

# 12. Repository amendments

## `platform-tracing-otel-javaagent-extension`

Add after GO:

```text
InternalTracingMBeanServer
InternalControlProtocolMarker
aggregate write MBean registered only on internal server
read-only platform Control MBean projection
strict aggregate Open-Type codec
security/bounds validator
```

Modify:

- readiness/attestation to identify supported internal protocol;
- aggregate state and diagnostics according to corrected Prompt 4;
- scrubbing loader path validation;
- logging/redaction.

Delete after cutover:

- domain write operations;
- old partial protocol decoder/operations;
- direct export/propagation/log-level setters;
- independently mutable domain state owners.

## `platform-tracing-spring-boot-autoconfigure`

Add after GO:

```text
InternalTracingMBeanServerLocator
aggregate in-process JMX client
Config Server provenance/guard
DEV_ACTUATOR typed endpoint
security-safe diagnostics projections
generation/dirty refresh worker
```

Delete after cutover:

- HTTP mutation;
- per-domain JMX writers;
- sequential RuntimeConfigApplier;
- RefreshScope mutation path;
- legacy mutation gates.

## Test/E2E modules

Add:

- private-server isolation process test;
- remote platform JMX negative write test;
- secret canary suite;
- malformed/oversized Open-Type corpus;
- authority/provenance attacks;
- refresh-storm tests;
- Agent/starter incompatibility matrix;
- disabled-facade semantics test.

---

# 13. Security blockers before implementation GO

| Blocker | Status |
|---|---|
| Private MBeanServer feasibility and isolation spike | **OPEN / CRITICAL** |
| JMX baseline and approved read/write split | **OPEN** |
| Complete property/env disposition | **OPEN** |
| Final Category A allowlist and safety limits | **OPEN** |
| Secret inventory and projection contract | **OPEN** |
| Exact aggregate Open-Type schema | **OPEN** |
| Controlled Agent/starter protocol compatibility | Existing foundation; target extension required |
| Config Server dependency and refresh delivery governance | **OPEN** |
| Diagnostics retention/redaction matrix | **OPEN** |
| Removal plan for all legacy writers | Designed, not executed |
| Security process E2E | **OPEN** |
| Explicit implementation GO | **NOT GRANTED** |

---

# 14. Handoff contract for Prompt 9

Prompt 9 must treat these findings as accepted:

```text
1. The platform MBeanServer becomes read-only for tracing policy after cutover.
2. The aggregate writer must live on a separate process-local MBeanServer.
3. Config Server and DEV_ACTUATOR Spring adapters invoke that private server in-process.
4. Existing platform ObjectNames remain unchanged as operational read contracts.
5. Remote JMX connector security remains infrastructure-owned.
6. Documentation alone is not sufficient to protect a platform-server writer.
7. All legacy domain/partial writers must be deleted before production GO.
8. The aggregate wire schema is exact, versioned and bounded; generic string coercion is deleted.
9. runtimeInstanceId + acceptedSequence provide concurrency; Config revision is metadata only.
10. Local/system policy override in CONFIG_SERVER is fail-closed, not warning-only.
11. Raw HMAC key never appears in snapshots, digests, projections, audit, logs or exceptions.
12. Key-id may be exposed for a concrete rollout consumer.
13. scrubbing.rules-config remains startup-only Category B.
14. Rules contents and full filesystem paths are not exposed by default.
15. Initial CONFIG_SERVER failure/mismatch blocks readiness.
16. Runtime failure retains LKG and normally keeps readiness.
17. mode=DISABLED does not disable or mutate an externally active Agent.
18. Signed snapshots are not justified without an independent signer/trust root.
19. TLS/auth protects Config Server transport but not a compromised trusted server.
20. Sampling/export/log-level abuse requires authority, validation, audit and alerting; no invented hard-coded policy ceiling.
21. Refresh storms use generation/dirty coalescing, not fixed debounce.
22. Unsupported Agent/starter protocol mismatch fails closed.
23. One aggregate CAS replaces rollback across multiple holders.
24. Diagnostics must be bounded and secret-safe.
25. Implementation GO remains security-blocked.
```

Prompt 9 must not inherit these claims from the original Prompt 8 output:

```text
1. That an environment guard on platform-server setters is an adequate production design.
2. That remote JMX documentation alone protects the aggregate writer.
3. That local override outranks Config Server or should only warn.
4. That Config Server revision should order/reject snapshots.
5. That a normal CAS conflict is FAILED.
6. That arbitrary 500-entry/256-character limits are approved.
7. That an HMAC key digest is safe or required.
8. That all pods must share a key-id without a confirmed protocol requirement.
9. That production sampling must default to a 0.1 ceiling.
10. That export-disabled universally means DEGRADED.
11. That TRACE rejection alone prevents sensitive logging.
12. That five-second debounce is the correct storm control.
13. That unsupported Agent versions should merely WARN.
14. That partial apply should be repaired with rollback.
15. That Spring should auto-disable an active Agent when mode=DISABLED.
16. That TLS/auth protects against a compromised Config Server.
17. That signed snapshots can be rejected without stating the trusted-authority assumption.
18. That rules file base-path policy is known.
19. That HMAC key rotation semantics are already known.
20. That security GO can be granted before the private writer boundary is proven.
```

---

# 15. Final status

```text
Prompt 8 result: PASS_WITH_CRITICAL_SECURITY_AMENDMENTS
Production write boundary: PRIVATE PROCESS-LOCAL MBeanServer
Platform JMX contract: READ-ONLY after cutover
Signed snapshots: NOT JUSTIFIED under current trust model
Secrets: NEVER in snapshot/digest/projection/audit/log
rules-config: STARTUP-ONLY
Security implementation GO: NOT GRANTED
Legacy deletion: BLOCKED but mandatory before production GO
Next prompt: SRE Diagnostics and JMX Contract
```

---

## Primary framework references

- Java SE 21 `MBeanServerFactory`:
  https://docs.oracle.com/en/java/javase/21/docs/api/java.management/javax/management/MBeanServerFactory.html
- Java JMX Remote security:
  https://docs.oracle.com/en/java/javase/23/jmx/security.html
- Spring Boot 3.5 JMX management:
  https://docs.spring.io/spring-boot/3.5/reference/actuator/jmx.html
