---
name: Hybrid config bridge
overview: "Step 0 PASS/COMPLETED. 0A and executable 0B PASS. General implementation remains NOT GO pending the remaining review gates."
status: pass_step0_completed
todos:
  - id: provenance-source-analysis
    content: "Step 0A: source/API design analysis → docs/research/config-server-property-provenance-spike.md §1–8"
    status: completed
  - id: provenance-runtime-verification
    content: "Step 0B: executable T1–T10 plus real Git-backed Config Server process verification; §10 evidence complete"
    status: completed
  - id: jmx-baseline
    content: "BLOCKED until implementation GO: capture JMX management contract baseline BEFORE domain setter removal"
    status: pending
  - id: versioned-state-review
    content: "RETAIN_GENERIC | REFACTOR_DOMAIN_SPECIFIC + placement (parallel with 0B OK)"
    status: pending
  - id: diagnostics-inventory
    content: "Diagnostics matrix + component split (parallel with 0B OK)"
    status: pending
  - id: pre-review
    content: "Explicit implementation GO after Step 0B PASS + state review + diagnostics + rules-config"
    status: pending
  - id: rules-config-classify
    content: "scrubbing.rules-config classification (parallel with 0B OK)"
    status: pending
  - id: replace-legacy
    content: "BLOCKED; domain setters only after JMX baseline captured"
    status: pending
isProject: false
---

# Hybrid configuration plan

> **Review status: PASS (plan scope); Step 0 PASS/COMPLETED**
>
> | Scope | Status |
> |-------|--------|
> | **Plan revision** | **PASS** |
> | **Step 0A — source/API design analysis** | **PASS**, **COMPLETED** |
> | **Step 0B — executable provenance characterization** | **PASS**, **COMPLETED** |
> | **Step 0 overall** | **PASS**, **COMPLETED** |
> | **General implementation** | **NOT GO** |
> | **Legacy mutation deletion** | **BLOCKED** |
>
> **Evidence:** T1–T10 и отдельный child-process Config Server с временным Git backend
> выполнены на Java 21 / Boot 3.5.6 / Cloud 2025.0.0 / Config 4.3.0. См.
> `docs/research/config-server-property-provenance-spike.md` §10.
>
> **Path to implementation GO:** Step 0B PASS → state review ∥ diagnostics ∥ rules-config → explicit implementation GO.

---

## Apply outcome contract (authoritative)

### Identifiers (separate concerns)

| Identifier | Role |
|------------|------|
| **`acceptedSequence`** | Version of **accepted** runtime policy state; optimistic concurrency base (with `runtimeInstanceId`) |
| **`attemptId`** | Unique id for **each** apply attempt, **any** outcome; audit/diagnostics correlation |

Optimistic concurrency: `expectedBaseRuntimeInstanceId` + **`expectedBaseAcceptedSequence`**.

Audit and diagnostics correlate by **`attemptId`**, not by accepted sequence alone.

---

### `APPLIED` vs `UNCHANGED` (mandatory — resolves prior contradiction)

| Outcome | Policy state changed? | CAS? | `acceptedSequence` | Attempt counter / audit |
|---------|----------------------|------|--------------------|-------------------------|
| **`APPLIED`** | Yes — new accepted policy | **Yes** — CAS new accepted state | **`+1`** | attempt counter++; audit record with new `attemptId` |
| **`UNCHANGED`** | No — effective policy identical | **No** — CAS not required | **No change** | attempt counter++; audit record with new `attemptId` |
| **`REJECTED`** | No | No | No change | attempt counter++; audit |
| **`FAILED`** | No | No | No change | attempt counter++; audit |
| **`STALE`** | No | No | No change | attempt counter++; audit |

**Rule:** Identical payload re-delivered from Config Server (same effective category A + same structural desired comparison) **must not** create a new effective policy version — `runtimeOutcome=UNCHANGED`, no CAS, no sequence increment.

Repeat apply of identical digest → **UNCHANGED**, not APPLIED.

---

### Revision-only refresh (`sourceRevision` changes, policy unchanged)

When **`sourceRevision`** changes but effective category A policy and structural desired comparison are unchanged:

| Field | Behavior |
|-------|----------|
| `runtimeOutcome` | **`UNCHANGED`** |
| `acceptedSequence` | **Does not increase** |
| Observed `sourceRevision` | **May update in diagnostics** (not in CAS state unless explicitly justified) |
| Policy commit | **Not** a new commit — revision-only refresh |

`sourceRevision` **optional**; read from `configClient` marker source directly (not `environment.getProperty`); never synthesize from PropertySource name. See spike §4.4.

---

### Structural drift combined with category A

`restartRequired` is **orthogonal** to `runtimeOutcome`.

**Structural drift only** (category A unchanged, category B mismatch detected):

```text
runtimeOutcome = UNCHANGED
restartRequired  = true
restartRequiredKeys = [...]
acceptedSequence — no increment
```

**Category A applied + structural drift**:

```text
runtimeOutcome = APPLIED
restartRequired  = true
restartRequiredKeys = [...]
acceptedSequence — increments (+1)   ← only this case advances accepted sequence
```

Initial CONFIG_SERVER load with structural mismatch → readiness blocked (unchanged from drift policy).

Runtime refresh structural mismatch → category A applied if changed; service stays READY/DEGRADED with restart diagnostic.

Per-key remote desired vs effective for structural keys: spike T4/T5.

---

## Minimal atomic state (`VersionedPolicyState`)

CAS payload stays **minimal**. Contains **only** runtime truth + accepted version:

- `EffectiveTracingPolicy` (accepted)
- `runtimeInstanceId`
- **`acceptedSequence`**
- `payloadDigest` (of accepted policy)
- Fields required for CAS compare (expected-base check inputs)

**Not in CAS state** (belong to `TracingPolicyApplyDiagnostics` / `TracingPolicyAuditBuffer` unless proven to require atomic co-switch with policy):

- attempt counters
- rejected/stale/failed metadata
- failure summaries
- observed `sourceRevision` (diagnostics-only)
- audit entries

`TracingPolicyApplyDiagnostics` holds: counters, last result, last `attemptId`, observed revision, failure summaries.

---

## Commit flow (updated)

```text
assign attemptId (always)
read current VersionedPolicyState (instanceId, acceptedSequence, policy, digest)
build candidate EffectiveTracingPolicy
validate full candidate + structural diff
compare expected base (optimistic concurrency)
if effective policy unchanged:
    runtimeOutcome = UNCHANGED (possibly restartRequired=true)
    no CAS
else if valid:
    runtimeOutcome = APPLIED
    CAS VersionedPolicyState { policy, acceptedSequence+1, digest, ... }
else:
    runtimeOutcome = REJECTED | FAILED | STALE
    no CAS
publish diagnostics + audit (always, including UNCHANGED)
```

---

## JMX management contract — baseline before refactor

**Before removing domain setters**, capture **baseline** from current codebase (todo `jmx-baseline` — blocked until implementation GO, but **must precede** setter removal in implementation order):

| Baseline artifact | Content |
|-------------------|---------|
| ObjectName | Exact values per MBean |
| Read attributes | Names + Open Types |
| Operations | Names + signatures (including domain setters — marked for removal) |
| Notifications | Metadata if present |

Store baseline in repo (e.g. `platform-tracing-otel-javaagent-extension/src/test/resources/jmx-management-contract-baseline.json` or test fixture class generated from live introspection).

**Contract tests** (post-refactor) may **only** assert:

- Approved removal of domain-specific **write** operations
- Addition of **exactly one** aggregate `applyConfigurationSnapshot` writer
- **Preservation** of read contracts and ObjectName

Baseline captured **before** refactoring — not inferred after.

---

## Runtime component separation (unchanged intent)

| Component | Responsibility |
|-----------|----------------|
| `VersionedPolicyState` | Runtime truth + accepted version; CAS only |
| `TracingPolicyApplyDiagnostics` | Counters, attemptId, last result, observed revision |
| `TracingPolicyAuditBuffer` | Bounded in-memory audit |
| JMX projection | Aggregated read-only |

Single runtime state owner; bridge/dev → `TracingPolicyApplier` only.

---

## State owner, LKG, audit, namespace (unchanged)

- Review: `RETAIN_GENERIC` | `REFACTOR_DOMAIN_SPECIFIC`; placement prefer `platform-tracing-otel`
- LKG logical: current accepted = LKG when no failed apply changed state
- Audit: fixed capacity, in-memory, no secrets, no recovery, bounded strings
- Namespace: `platform.tracing.policy.*` runtime; `javaagent.*` / `sdk.*` structural; default authority `HELM`

---

## Step 0 — Provenance spike (PASS/COMPLETED)

**Артефакт:** [docs/research/config-server-property-provenance-spike.md](docs/research/config-server-property-provenance-spike.md)

| Sub-step | Status |
|----------|--------|
| **0A** source/API design analysis | **PASS**, **COMPLETED** |
| **0B** executable T1–T10 characterization | **PASS**, **COMPLETED** |
| **Overall** | **PASS**, **COMPLETED** |

Key 0A outcomes: multi-signal classifier; per-key provenance model; `ConfigServiceOrigin` excluded; revision from marker source; Config Data path only (legacy `configService` → fail-fast); T1–T10 defined.

0B deliverable completed: T1–T10 and real Git-backed Config Server process verification in
`platform-tracing-e2e-tests`; §10 evidence filled.

---

## Implementation order (NOT GO)

```text
0A. [DONE] Source/API design analysis — PASS
0B. [DONE] Executable provenance characterization T1–T10 + real Config Server — PASS
--- parallel with 0B (OK): state review, diagnostics inventory, rules-config classify ---
--- implementation GO required below ---
1. Capture JMX management contract baseline     ← before any setter removal
2. State review + placement
3. Diagnostics inventory + rules-config classify
4. Explicit implementation GO (requires 0B PASS)
5. VersionedPolicyState + apply handler (contract above)
6. Codec + applier port + bridge + dev endpoint
7. JMX contract tests vs baseline
8. Domain setter removal + aggregate snapshot writer
9. Guard + namespace migration
10. Perf/E2E + ADR
```

Legacy mutation deletion **blocked** until step 4; domain setters **blocked** until step 1 baseline exists.

---

## Re-review checklist (implementation GO)

- [x] **Step 0B PASS** (T1–T10 + real Config Server executed, §10 evidence complete)
- [ ] Apply contract: APPLIED vs UNCHANGED vs structural-only UNCHANGED+restartRequired
- [ ] `acceptedSequence` vs `attemptId` documented
- [ ] Revision-only refresh semantics documented
- [ ] Minimal CAS state vs diagnostics/audit split
- [ ] Per-key provenance model (effective vs remote desired)
- [ ] Revision read from `configClient` marker (not `environment.getProperty`)
- [ ] Legacy bootstrap fail-fast documented
- [ ] JMX baseline capture plan approved (before setter removal)
- [ ] State review + diagnostics + rules-config
- [ ] Explicit implementation GO

---

## Preserved decisions

- Fixed `PlatformHeaders`; `platform.environment` imported signal
- No new Gradle module; no generic control protocol
- Production apply: CONFIG_SERVER; local: DEV_ACTUATOR JMX
- JMX ObjectName unchanged; no mechanical Control→Status rename
- Perf M10: Config Server flow
