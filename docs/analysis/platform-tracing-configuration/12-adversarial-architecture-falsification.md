# Adversarial Architecture Falsification
## Corrected Repository-Grounded Report

## Review status

| Field | Value |
|---|---|
| Report status | **PASS_WITH_BLOCKERS** |
| Leading state architecture | **F1 — RETAIN_GENERIC** |
| F1 versus F2 | **F1 not falsified; E3 remains binding reversal gate** |
| Overall target architecture | **Not falsified, but not implementation-ready** |
| Critical common gate | **Private process-local MBeanServer feasibility** |
| Implementation GO | **NOT GRANTED** |
| Legacy deletion GO | **BLOCKED** |
| Intended handoff | Prompt 13 — Model Council Arbitration and Plan Rewrite |

The original adversarial report found several real risks, but it also attacked assumptions already rejected by corrected Prompts 4–11.

Critical errors in the original falsification:

1. It stated that `CONFIG_SNAPSHOT_V1` should be published only after the first successful apply. The accepted contract publishes protocol capability when the Agent handler and codec are installed; initial apply success is a separate application readiness condition.
2. It treated Agent readiness and Config Server application readiness as one state machine.
3. It claimed `EnvironmentChangeEvent` may expose a partially rebuilt Environment, despite Step 0B T10 proving that the listener observes updated state.
4. It recommended switching back to `RefreshScopeRefreshedEvent`, even though the target removes RefreshScope mutation.
5. It described `VersionedStateHolder`/CAS contention as a hot-path livelock caused by reads. Lock-free reads do not contend with CAS in that manner.
6. It treated normal CAS conflict as `FAILED` or assumed an unbounded retry loop, contrary to the corrected `STALE`/bounded-retry contract.
7. It reintroduced current direct JMX/RefreshScope writers into the target architecture, although the coherent cutover forbids activating the new writer while old writers remain supported.
8. It treated `scrubbing.rules-config` classification as unresolved, although it is fixed as Category B/startup-only.
9. It assumed the bridge can be active in `mode=DISABLED`, while the accepted authority matrix rejects `DISABLED + CONFIG_SERVER/DEV_ACTUATOR`.
10. It claimed `OriginLookup` differences could make provenance always unknown, although origin is corroborating rather than mandatory.
11. It recommended `spring.cloud.config.fail-fast=false`, contradicting the accepted non-optional authoritative initial-import contract.
12. It misstated JDK `MBeanServerFactory` behavior and did not distinguish `createMBeanServer` from `newMBeanServer`.
13. It assumed HMAC material might enter Category A despite the accepted raw-secret exclusion contract.
14. It overstated aggregate-object cache-line/GC behavior without evidence.
15. It proposed operation-level deprecation during cutover, creating compatibility behavior the pre-production cleanup rejects.
16. It proposed deleted remote keys fall back to HELM defaults, while the accepted Config Server contract requires a complete manifest and rejects missing managed keys.
17. It assumed platform read MBeans need a separately synchronized projection after private-server apply; target read MBeans should read the accepted state directly.
18. It used unverified claims about Config Client fail-fast defaults and arbitrary likelihood labels as facts.

This corrected report retains only attacks that can falsify the actual target architecture.

---

# 1. Leading architecture under attack

```text
Agent-first production topology

HELM:
    Agent bootstrap creates the initial accepted state directly.

CONFIG_SERVER:
    Standard Config Client
        -> provenance-aware complete manifest
        -> application-side Open-Type codec
        -> private process-local MBeanServer
        -> Agent aggregate handler
        -> one VersionedStateHolder<AcceptedTracingPolicyState> CAS.

DEV_ACTUATOR:
    typed Spring JMX commands
        -> full candidate snapshot
        -> the same private writer.

Platform MBeanServer:
    read-only operational projections.

State:
    one Agent-side accepted runtime-policy owner.

Diagnostics:
    structural drift, attempt evidence and bounded audit outside CAS.
```

F1 is preferred over F2 only because the existing generic primitive is expected to be sufficient with less long-lived abstraction.

---

# 2. Falsification standard

The architecture is falsified when repository or executable evidence proves one of:

1. the private writer boundary cannot be made process-local and isolated from platform remote JMX;
2. the generic holder cannot express the required atomic state contract without tracing-specific pollution;
3. one accepted state cannot satisfy hot-path or lifecycle requirements within approved budgets;
4. Config Server cannot provide a reliable production desired-state flow on the approved dependency stack;
5. the exact property/default/source model cannot prevent silent divergence;
6. required SRE/security contracts force multiple writers or a different lifecycle owner;
7. the coherent cutover cannot eliminate all legacy writers.

A missing implementation alone is not falsification. It is an implementation-GO blocker.

---

# 3. Attack disposition summary

| Attack | Correct disposition |
|---|---|
| Initial Config Server apply | Valid blocker, but original capability/readiness sequence was wrong |
| Refresh during traffic | Valid cutover invariant; not a permanent architecture flaw |
| Refresh storm | Valid proof gate; original CAS contention explanation was wrong |
| Partial Environment refresh | **Rejected attack**; Step 0B T10 disproves it |
| Partial domain failure | Valid aggregate-validation test; multi-writer example is legacy-only |
| Structural drift | Valid E4/E5 gate; `rules-config` itself already resolved |
| Agent absent | Already handled by topology; bridge must be absent |
| Facade disabled + active Agent | Bridge absent by accepted authority matrix |
| Version/BOM mismatch | Valid E6 blocker |
| Config Server outage | Valid lifecycle test; `fail-fast=false` is not the correction |
| Private JMX leak | Critical E2 attack |
| Secret leakage | Critical E7/E5 attack |
| Hot-path regression | Valid benchmark gate; original mechanism overstated |
| Old/new path overlap | Valid cutover gate; no deprecation compatibility layer |
| Invalid property migration | Critical E4/release-train gate |
| Missing defaults | Valid concern; complete manifest rejects missing keys |
| Property shadowing | Valid source-of-truth invariant |
| MBean read/write divergence | Read MBeans must read common accepted state directly |

---

# 4. Corrected attack catalogue

## A-01 — Initial Config Server authority is not established

### Invariant

In `mode=AGENT`, `authority=CONFIG_SERVER`, application readiness must not be satisfied until:

- approved Config Client import succeeds;
- controlled Agent is ready;
- Agent advertises aggregate protocol support;
- provenance and complete policy manifest are valid;
- initial aggregate apply returns `APPLIED` or `UNCHANGED`;
- initial structural drift is absent.

### Failure sequence

1. Production currently lacks Config Client.
2. The target bridge is not implemented.
3. The Agent can reach its own bootstrap `READY` independently of Spring Config Server.
4. If application health uses only Agent readiness, the application may serve with HELM/bootstrap policy while claiming Config Server authority.

### Likelihood

High before E6 and application-readiness implementation; low after executable gates.

### Impact

Critical: false authority and incorrect initial runtime policy.

### Evidence that would disprove the attack

A production-stack process test proving that Config Server authority cannot become application-ready without all initial conditions above.

### Minimal correction

Keep two distinct contracts:

```text
Agent protocol capability:
    published when handler/codec installed.

Application tracing-configuration readiness:
    satisfied after successful initial evaluation/apply.
```

The Spring bridge never publishes Agent capability.

### Architecture cost

Application-side readiness coordinator and E2/E6 process tests. No redesign of F1.

---

## A-02 — Two writers become active during cutover

### Invariant

The new private aggregate writer must never be activated in a supported artifact while any old production writer remains callable.

### Failure sequence

1. Current repository has RefreshScope, HTTP, partial protocol and direct domain JMX writers.
2. New Config Server bridge is enabled before old paths are removed.
3. A legacy write changes one domain.
4. Aggregate apply observes a different expected base or overwrites the manual change.
5. Diagnostics and SRE authority become unreliable.

### Likelihood

High if cutover is performed incrementally in released artifacts.

### Impact

Critical: mixed authority and non-coherent policy.

### Evidence that would disprove the attack

A coherent unreleased release-train test proving:

- old writer count becomes zero before target activation;
- platform MBeanServer is read-only;
- only one private writer is registered;
- no HTTP write route exists.

### Minimal correction

Use the accepted release-train invariant:

```text
new writer tested while unreachable from supported posture;
then one coherent activation/deletion cutover;
no released overlap.
```

### Architecture cost

Strict build/release gates, not compatibility adapters or deprecation warnings.

---

## A-03 — Refresh storm overwhelms bridge/diagnostics

### Invariant

Repeated refresh events must converge to the latest Environment state without unbounded concurrency, unbounded retry or lost final event.

### Failure sequence

1. Many events arrive while one apply is running.
2. A worker is spawned per event or events are simply dropped.
3. Apply/JMX/audit work grows without bound or the latest state is never processed.
4. Runtime remains stale or management threads are exhausted.

### Likelihood

Medium in fleet-wide refresh and incident scenarios.

### Impact

High operational and performance impact.

### Evidence that would disprove the attack

Generation/dirty single-worker tests showing:

- at most one active worker;
- latest generation is eventually processed;
- intermediate events may coalesce;
- audit remains capped;
- stale retry is bounded;
- M10c/M10d and P5 pass.

### Minimal correction

Implement the accepted generation-counter/dirty-worker algorithm.

A CAS miss returns `STALE`; Config Server bridge may rebuild/retry once. It is not an unbounded holder-level loop.

### Architecture cost

One serialized worker plus bounded metrics/tests.

---

## A-04 — EnvironmentChangeEvent delivers partial state

### Disposition

**ATTACK REJECTED.**

Step 0B T10 is executable evidence that the listener sees the updated Environment. T9 confirms deleted keys appear in the event.

The target requires a complete manifest, so a missing required policy key is rejected rather than converted to a partial apply.

### Residual risk

The production-approved Spring stack must rerun the same characterization tests.

### Minimal correction

Retain `EnvironmentChangeEvent`, full rebuild and production-stack characterization. Do not restore RefreshScope mutation.

### Architecture cost

E6 integration tests only.

---

## A-05 — Aggregate validation throws or omits a domain

### Invariant

All approved Category A fields are decoded, normalized and validated before the single CAS. Any validation/internal failure leaves accepted state unchanged.

### Failure sequence

1. One approved domain lacks target aggregate validation.
2. Candidate construction throws or silently ignores fields.
3. Handler incorrectly commits a partially interpreted candidate or reports the wrong outcome.

### Likelihood

Medium during first implementation.

### Impact

High to critical depending on scrubbing/export behavior.

### Evidence that would disprove the attack

Per-domain and cross-domain negative tests proving:

- decode failure occurs before CAS;
- no field is silently ignored;
- unchecked validator failure -> `FAILED`, no commit;
- one CAS occurs only after full successful validation;
- no legacy writer can mutate concurrently.

### Minimal correction

Define exact E5 allowlist and exact versioned Open-Type schema before handler implementation.

`scrubbing.rules-config` is excluded as Category B and cannot trigger runtime file validation.

### Architecture cost

Large but necessary validation test matrix; no architectural redesign.

---

## A-06 — Structural value is falsely reported as applied

### Invariant

Structural desired state is never included in runtime accepted policy. A mismatch produces drift evidence, not runtime mutation.

### Failure sequence

1. Property classification is incomplete.
2. A structural key is accidentally included in Category A or omitted from drift comparison.
3. Handler reports `APPLIED` while actual Agent pipeline remains unchanged.

### Likelihood

High until E4/E5 close.

### Impact

Critical for scrubbing, exporter topology or resource identity.

### Evidence that would disprove the attack

Exact leaf-key/env disposition and tests proving:

- every key has one lifecycle owner;
- Category B never enters runtime policy payload;
- runtime outcome and `restartRequired` are orthogonal;
- initial drift blocks readiness;
- runtime drift keeps LKG and publishes diagnostics.

### Minimal correction

Close E4/E5 before snapshot schema and bridge implementation.

Already binding:

```text
scrubbing.rules-config = Category B / startup-only.
```

### Architecture cost

Repository-wide property inventory and tests.


---

## A-07 — Agent is absent but bridge reports success

### Invariant

The Config Server bridge exists only for:

```text
mode=AGENT
authority=CONFIG_SERVER
```

and requires controlled Agent readiness/protocol support.

### Failure sequence

A bridge is accidentally registered without the Agent and maps transport absence to a normal apply outcome.

### Likelihood

Low after accepted conditional configuration; medium before implementation.

### Impact

High diagnostic-integrity failure.

### Evidence that would disprove the attack

Context/process tests proving:

- `mode=AGENT` without Agent fails readiness/startup;
- `mode=DISABLED` never creates the bridge;
- missing private server is `UNAVAILABLE`, not `APPLIED` or Agent `FAILED`;
- no Config Server apply metric is emitted without a real handler call.

### Minimal correction

Enforce mode/authority/class/readiness conditions and transport-error separation.

### Architecture cost

Conditional auto-configuration and process tests.

---

## A-08 — Disabled facade still discovers the private writer

### Invariant

`mode=DISABLED` disables Spring platform integration and therefore does not create Config Server or DEV control adapters.

### Original failure claim

The original report assumed the bridge may be present and fail to find the private server.

### Disposition

The scenario is invalid under the accepted authority matrix.

The remaining test is important:

- `mode=DISABLED` must not locate or mutate an externally active Agent;
- it may report/document that the independent Agent can still run.

### Evidence that would disprove the residual risk

Process test proving no locator/client/bridge/dev endpoint bean is created.

### Minimal correction

No correction beyond accepted guards.

### Architecture cost

One topology test.

---

## A-09 — Production Spring stack differs from Step 0B

### Invariant

The production bridge must run only on a formally supported and tested Boot/Cloud/Config Client stack.

### Failure sequence

1. Implementation compiles against the Step 0B stack.
2. Production resolves a different Cloud Context/Config Client set.
3. Event, marker or classpath behavior differs.
4. Bridge fails or provenance becomes ambiguous.

### Likelihood

High until E6.

### Impact

Critical for rollout across services.

### Evidence that would disprove the attack

Approved dependency matrix and T1–T10 plus bridge E2E rerun on the exact published BOM/starter consumer classpath.

### Minimal correction

Close E6 before production bridge implementation/activation.

`OriginLookup` returning `null` alone is not failure because it is corroborating evidence only.

### Architecture cost

Platform dependency governance and consumer smoke tests.

---

## A-10 — Config Server outage breaks availability or authority

### Invariant

Initial authoritative startup fails closed; post-start failure retains LKG.

### Failure sequence

1. Initial import is optional or silently falls back.
2. Service starts under bootstrap state while declaring Config Server authority.
3. Or runtime failure clears/resets policy instead of retaining accepted state.

### Likelihood

Medium without explicit configuration.

### Impact

Critical at startup; high at runtime.

### Evidence that would disprove the attack

Tests proving:

- CONFIG_SERVER uses non-optional authoritative initial import;
- initial outage/missing marker prevents readiness;
- runtime delivery failure does not produce an empty candidate;
- current accepted state remains;
- failure/staleness diagnostics are emitted;
- no fallback to HELM authority occurs.

### Minimal correction

Keep the accepted lifecycle contract.

Do **not** prescribe `fail-fast=false` as a fix. Exact Config Client retry/fail-fast properties belong to E6 and must preserve authoritative startup semantics.

### Architecture cost

Startup retry/readiness policy and runbooks.

---

## A-11 — Private writer leaks onto platform JMX

### Invariant

The aggregate writer exists only on the private process-local MBeanServer.

### Failure sequence

1. Agent registers writer using `ManagementFactory.getPlatformMBeanServer()`, or the private server is attached to the remote connector.
2. Remote JMX sees and invokes the write operation.
3. Config Server provenance and application authority guards are bypassed.

### Likelihood

Unknown before E2; critical implementation risk.

### Impact

Critical security failure.

### Evidence that would disprove the attack

E2 process tests proving:

- writer absent from platform server;
- platform server has zero tracing write operations;
- remote connector cannot query/invoke writer;
- Spring locates exactly one private protocol marker;
- attach/management-agent behavior does not export the private server.

### Minimal correction

Complete E2 before GO and add permanent architectural verification.

JDK detail:

- `createMBeanServer` creates a server retained by `MBeanServerFactory`;
- `newMBeanServer` creates one not retained by that factory.

The implementation must deliberately choose a discovery strategy and prove lifecycle/security properties.

### Architecture cost

Feasibility spike, locator/marker and process-level security tests. Failure triggers write-boundary redesign for both F1 and F2.

---

## A-12 — Secret material enters management or wire data

### Invariant

Raw HMAC key and other secret material never cross Agent bootstrap secret boundaries.

### Failure sequence

1. Property inventory misses a secret field.
2. Generic snapshot/projection builder includes it.
3. JMX, Actuator, digest, audit, logs or exception text exposes it.

### Likelihood

Medium until E4/E7 secret inventory and canary tests.

### Impact

Critical.

### Evidence that would disprove the attack

Recursive canary tests and schema review proving:

- raw key is absent from runtime snapshot;
- raw key is absent from structural desired snapshot;
- no key digest is exposed;
- only approved non-secret key-id/configured status may appear;
- rules content and full paths are absent;
- audit and metrics contain bounded codes only.

### Minimal correction

Close secret inventory and explicit schema allowlists before codecs/projections.

HMAC material is not a candidate Category A value under the accepted security contract.

### Architecture cost

Schema discipline and security tests, not a signing framework.

---

## A-13 — Aggregate state harms the hot path

### Invariant

One accepted aggregate owner must meet existing sampling/processor/export performance budgets.

### Failure sequence

1. Runtime hook dereferences accepted state and domain sub-policy.
2. Extra indirection/object layout or changed algorithms regress throughput/latency.
3. Refresh/apply allocation causes GC or synchronization effects under load.

### Likelihood

Unknown before benchmark; not inherently medium-high.

### Impact

Critical if P5 fails.

### Evidence that would disprove the attack

Before/after benchmarks and process tests for:

- sampler read;
- concurrent apply under sampling load;
- scrubbing/validation/export hooks;
- allocation;
- refresh storm.

### Minimal correction

Keep deeply immutable domain sub-records/views inside one accepted envelope and benchmark actual code.

Do not require cache-line placement or mutable projections. Reading a reference to a larger aggregate object does not copy the object, and independent hook invocations already read separate lifecycle stages.

### Architecture cost

Benchmarking and possible internal layout optimization. If approved budgets still fail, F1/F2 aggregate design must be reconsidered.

---

## A-14 — Operational overlap and runbook conflict

### Invariant

Runbooks and supported artifacts must switch authority together.

### Failure sequence

1. New bridge is released while legacy domain JMX runbooks remain valid.
2. Operators make manual changes that Config Server later overwrites.
3. Incident response becomes nondeterministic.

### Likelihood

High if documentation and code cutover are decoupled.

### Impact

High.

### Evidence that would disprove the attack

One release-train proof that:

- runbooks are updated before artifact release;
- old setters are absent;
- new DEV endpoint is absent in production;
- Config Server is the only production writer.

### Minimal correction

Treat code, Agent distributive, Config Server manifest, Helm values and runbooks as one cutover unit.

Do not add deprecation warnings or preserve callable setters. They create the forbidden overlap.

### Architecture cost

Release governance and deployment validation.

---

## A-15 — Namespace cutover silently uses wrong values

### Invariant

No service can start with a partially migrated tracing property/environment contract.

### Failure sequence

1. New code expects new names.
2. Deployment/config repository still supplies old names or omits required manifest keys.
3. Values silently default or diverge between Agent and Spring.

### Likelihood

High without E4 and deployment checks.

### Impact

Critical.

### Evidence that would disprove the attack

Exact property/env disposition plus startup guard proving:

- all legacy names are rejected with actionable errors;
- complete Config Server manifest is present;
- Agent structural values are supplied under final env/`-D` names;
- Helm/Config Server examples and consumer fixtures use only target names;
- no dual readers/aliases exist.

### Minimal correction

Close E4 and add pre-deployment/startup validation.

Do not invent a specific missing default such as sampling ratio `1.0` unless confirmed by the key inventory.

### Architecture cost

Repository/deployment inventory and coherent release train.

---

## A-16 — Missing Config Server key weakens policy

### Invariant

CONFIG_SERVER authority uses a complete managed runtime-policy manifest.

### Original failure claim

The original report proposed restoring a deleted key to HELM bootstrap default.

### Disposition

That correction is rejected.

Accepted semantics:

- missing required key at initial load -> reject and block readiness;
- missing required key after refresh -> reject candidate and retain LKG;
- returning to default requires publishing the explicit documented value;
- no fallback to current Spring field defaults or HELM state.

### Evidence that would disprove the attack

Complete-manifest and key-deletion tests.

### Architecture cost

Stricter Config Server governance, simpler default ownership.

---

## A-17 — Agent bootstrap remains a competing runtime source

### Invariant

Agent bootstrap is an immutable baseline. After startup, all runtime hooks read the accepted aggregate state, not `ExtensionConfig` for Category A values.

### Failure sequence

1. A migrated runtime reader still consults bootstrap config or an old holder.
2. Aggregate apply changes accepted state.
3. Different consumers observe different owners.

### Likelihood

High unless target reader migration is exhaustive.

### Impact

Critical for ownership coherence.

### Evidence that would disprove the attack

Architecture/source/process tests proving:

- every Category A consumer reads the one accepted state;
- no target runtime consumer rereads `ExtensionConfig`;
- no independently mutable holder remains;
- HELM initializes the same state once at Agent bootstrap.

### Minimal correction

Make this a hard architecture-fitness and reference-scan gate.

### Architecture cost

Reader rewrites and exhaustive symbol inventory; already part of the implementation train.

---

## A-18 — Platform read JMX becomes stale after private write

### Invariant

Platform read MBeans expose the same accepted-state and diagnostics owners used by the private handler.

### Original failure claim

The report assumed a separate projection must be asynchronously updated after private CAS.

### Disposition

A separately synchronized projection would create another source of truth and is rejected.

Correct design:

```text
Private writer commits accepted state.
Platform read MBeans read accepted state/diagnostics directly.
```

### Residual failure

A platform MBean may accidentally cache an old DTO or retain current per-domain holders.

### Evidence that would disprove the residual risk

Contract/process tests proving platform reads reflect the accepted sequence immediately and no cached/mutable projection owner exists.

### Minimal correction

Use read-through projections from common state; delete per-domain state/version sources.

### Architecture cost

Read-adapter rewrite only.


---

# 5. F1-specific falsification

Most attacks apply equally to F1 and F2. Only one gate determines whether F1 remains the state winner.

## E3 — Exact generic-holder suitability

F1 is falsified in favor of F2 if executable repository analysis proves:

1. expected-base compare-and-commit cannot be expressed through the current generic contract;
2. normal CAS conflict cannot be represented as stale without contaminating the generic API;
3. aggregate metadata requires duplicate sequence/version truth;
4. the generic holder must learn authority, Config Server, digest, diagnostics or tracing-domain validation;
5. composition is materially less understandable/testable than a dedicated state wrapper.

If E3 passes, none of the attacks above provides evidence that F2 is safer or more correct.

If E3 fails, F2 becomes the state winner, while the private writer, codecs, adapters, diagnostics, namespace and cleanup designs remain valid.

---

# 6. True blockers before implementation GO

The corrected blocker set is the previously defined evidence program, not an ad hoc list.

| Gate | Required evidence | Why it blocks |
|---|---|---|
| **E1** | JMX baseline and named consumer inventory | Exact read/write deletion contract |
| **E2** | Private process-local MBeanServer feasibility/isolation | Production writer security |
| **E3** | Exact `VersionedStateHolder` API/prototype | F1 binding decision |
| **E4** | Complete exact property and env disposition | Ownership, migration, defaults |
| **E5** | Final Category A and bounds/secret classification | Wire schema and runtime safety |
| **E6** | Production Boot/Cloud/Config Client plus refresh delivery | Config Server lifecycle feasibility |
| **E7** | Exact JMX/diagnostics/secret-safe projection delta | SRE/security contract |
| **E8** | Architecture committee implementation GO | Permission to implement target |

Additional proof under these gates:

- target hot-path and refresh-storm performance;
- complete-manifest deletion semantics;
- no dual writer at release;
- all runtime readers use one accepted owner;
- controlled Agent/starter protocol compatibility.

These are not separate architecture alternatives; they are evidence required to close E1–E7 and the implementation train.

---

# 7. Falsification verdict

## Verdict

```text
PASS_WITH_BLOCKERS
```

The leading architecture survives falsification because:

- no repository fact proves multiple accepted-state owners are necessary;
- no lifecycle fact requires Spring-owned SDK state;
- no classloader fact invalidates an Agent-side aggregate state;
- no attack demonstrates that F2 inherently solves a problem F1 cannot solve;
- the private writer, exact property model and production Config Server path remain testable hypotheses rather than disproven mechanisms.

The architecture is not implementation-ready because E1–E7 remain open.

## Redesign triggers

Change verdict to `REDESIGN` when:

- E2 proves the private writer cannot be isolated/discovered safely;
- one accepted state cannot meet approved hot-path/lifecycle requirements;
- production Config Server cannot provide a reliable complete desired-state flow;
- SRE/security requirements require a second production writer;
- exact property ownership cannot eliminate silent dual-source behavior.

Change F1 to F2, without redesigning the rest, when E3 fails.

## Rejection triggers

Change verdict to `REJECT` when the business/SRE requirement for runtime Config Server control is withdrawn and the remaining Category A scope does not justify the bridge, or when the organization refuses the required controlled Agent/Config Server trust model.

---

# 8. Mandatory amendments before Prompt 13

The Model Council must not arbitrate from the original false conclusions.

It must use these corrected propositions:

```text
1. CONFIG_SNAPSHOT_V1 is protocol-support capability, published before first apply.
2. Application configuration readiness is separate from Agent readiness.
3. EnvironmentChangeEvent remains the accepted post-refresh trigger; Step 0B T10 supports it.
4. CONFIG_SERVER uses a complete manifest; missing managed keys reject rather than fall back.
5. Runtime Config Server failure retains LKG; initial authoritative failure blocks readiness.
6. mode=DISABLED never creates bridge/dev control and never mutates an external Agent.
7. Private MBeanServer feasibility is the dominant common architecture gate.
8. createMBeanServer/newMBeanServer discovery semantics must be chosen deliberately and tested.
9. Platform read MBeans read the common accepted state directly; no asynchronously synchronized projection state.
10. CAS conflicts are STALE or one bounded bridge retry, not FAILED/unbounded spin.
11. No supported old/new writer overlap or deprecation compatibility layer.
12. rules-config and raw secrets are startup-only structural inputs.
13. No arbitrary sampling ceiling, payload limit, debounce period or latency threshold is approved.
14. Broad/Narrow Category A remains a separate product-scope decision.
15. F1 is falsified only by E3-specific evidence; most blockers affect F1 and F2 equally.
16. PASS_WITH_BLOCKERS does not grant implementation GO.
```

---

# 9. Final status

```text
Prompt 12 result: PASS_WITH_BLOCKERS
Leading state architecture: F1 RETAIN_GENERIC survives
F1-specific reversal gate: E3
Common critical gate: E2 private writer feasibility
Initial authority/readiness: E6 + application readiness proof
Property/default/source contract: E4 + E5
JMX/SRE/security contract: E1 + E7
Implementation GO: NOT GRANTED
Legacy deletion GO: BLOCKED
Next prompt: Model Council Arbitration and Plan Rewrite
```
