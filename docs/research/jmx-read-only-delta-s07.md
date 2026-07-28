# S-07 — Exact read-only JMX contract delta (Gate E7)

| Field | Value |
| --- | --- |
| Spike | **S-07**, Gate **E7** |
| Repository | `E:\Platform_Traces`, branch `refactor/cursor-rules` |
| Toolchain | JDK 21.0.6+8-LTS-188, Gradle 8.14 |
| Production code changed | **NO** |
| Implementation GO | `NOT_GRANTED` |
| Overall result | **PASS** |
| Verdict | **`APPROVE_EXACT_JMX_DELTA`** |
| E7 recommendation | **`READY_TO_CLOSE_E7`** |

Prior evidence: E1 `939e95c`, E2 `2e3f68f`, E3 `fdf81df`/`6909f44`, E6 `e385e88`.

---

## 1. Executive verdict

**Selected read-contract option: R3** (domain reads + aggregate accepted-state/readiness projection), weighted score **87.4** vs R1 64.6 / R2 67.0.

After P6:

| Surface | ObjectNames | Members | Mutations |
| --- | ---: | ---: | ---: |
| Platform MBeanServer | **8** | **56** read-only | **0** |
| Private MBeanServer | **2** | **4** read + **1** write | **1** (`applyPolicy`) |

All **16** semantic mutations leave the platform server. `applyPolicy` moves private-only. `resetSamplerCounters` is deleted. Category B `DropPathPrefixes` / `ForceRecordValues` leave platform JMX. Per-domain `*ConfigVersion` / `*LastUpdatedSource` are replaced by Control aggregate accepted-state attributes.

---

## 2. Gate E7 recommendation

`READY_TO_CLOSE_E7` — live baseline matches committed fixture; all 8 ObjectNames and 63 members have exact dispositions; platform after-state has 0 mutations; external-consumer risk does not block; production code unchanged.

Committee/plan owner closes E7. This spike does not close it autonomously.

---

## 3. Reproduced baseline

```powershell
.\gradlew.bat jmxManagementContractVerify --no-daemon --rerun-tasks
```

**BUILD SUCCESSFUL.** Live `MBeanInfo` equals committed
`jmx-management-contract-baseline.json`.

| Metric | Count |
| --- | ---: |
| ObjectNames | 8 |
| Members | 63 |
| Read-only | 47 |
| Mutations | 16 (8 writable attrs + 8 mutating ops) |

Drift: **none**.

---

## 4. ObjectName inventory

| ObjectName | Decision |
| --- | --- |
| Control / PlatformControlProtocol | **RETAIN_PRIVATE_AND_PLATFORM_SPLIT** |
| Diagnostics / PlatformDiagnosticsControl | **RETAIN_EXACT** (read-only) |
| Export / PlatformExportControl | **RETAIN_EXACT** (read-only) |
| Readiness / PlatformExtension | **RETAIN_EXACT** (+ ConfigDeliveryHealth) |
| Metrics / PlatformProcessorMetricsControl | **RETAIN_EXACT** |
| Sampling / PlatformSamplingControl | **RETAIN_EXACT** (trimmed) |
| Scrubbing / PlatformScrubbingControl | **RETAIN_EXACT** (trimmed) |
| Validation / PlatformValidationControl | **RETAIN_EXACT** (trimmed) |

Private discovery marker (S-03 → productionize):
`PROPOSED …:type=PrivateMarker,name=PlatformPrivateControlPlane` — not on platform server.

No mechanical Control→Status rename.

---

## 5. Exact 63-member inventory

Authoritative machine-readable list:
`docs/research/evidence/jmx-read-only-delta/member-inventory.json`.

Disposition histogram:

| Disposition | Count |
| --- | ---: |
| RETAIN_EXACT | 38 |
| RETAIN_SEMANTICS_CLARIFIED | 8 |
| REPLACE_WITH_AGGREGATE_READ | 4 |
| DELETE_OBSOLETE_READ | 5 |
| DELETE_MUTATION | 7 |
| MOVE_TO_PRIVATE_ONLY | 1 |
| **Total** | **63** |

---

## 6–7. Consumer inventory and external risk

Named repository consumers: `PlatformTracingJmxClient` (CLIENT), Actuator, `AgentExtensionObserver` (HEALTH), tests/docs.

Members with `NO_NAMED_CONSUMER_FOUND` (Dropped* metrics) are **RETAIN_EXACT** — not auto-deleted.

`resetSamplerCounters` has no named consumer but is **DELETE_MUTATION** by binding (S-03 N7 proved remote mutation risk).

`e7BlockedExternalOwnerReview`: **false**.

---

## 8. Mutation deletion decision

All 16 semantic mutations:

| # | Member | Disposition |
| --- | --- | --- |
| 1–2 | Diagnostics PlatformLogLevel, PropagationEnabled (writable) | RETAIN as read-only (write deleted) |
| 3 | Export ExportEnabled (writable) | RETAIN as read-only |
| 4–5 | Sampling DropPathPrefixes, ForceRecordValues | DELETE_OBSOLETE_READ |
| 6–8 | Sampling RouteRatios, SamplerEnabled, SamplingRatio | RETAIN as read-only |
| 9 | Control applyPolicy | **MOVE_TO_PRIVATE_ONLY** |
| 10–11 | Sampling updateSamplingPolicy ×2 | DELETE_MUTATION |
| 12 | Sampling resetSamplerCounters | DELETE_MUTATION |
| 13–14 | Scrubbing updateScrubbingPolicy ×2 | DELETE_MUTATION |
| 15–16 | Validation updateValidationPolicy ×2 | DELETE_MUTATION |

Platform after P6: **writable attributes = 0**, **mutating operations = 0**.
Private: **exactly one** aggregate writer. No domain setters on private server.

---

## 9–10. Read-contract candidates and scoring

| Option | Score | Verdict |
| --- | ---: | --- |
| R1 retain-most-reads | 64.6 | Rejected — duplicate version owners |
| R2 minimal | 67.0 | Rejected — deletes required named-consumer reads |
| **R3 domain + aggregate** | **87.4** | **Selected** |

---

## 11–14. Selected contract, accepted-state visibility, readiness, counters

- Category A values: selected non-sensitive read projections on domain MBeans + digest/sequence on Control.
- Route-ratios: TabularData, deterministic key order, bounded; internal path vocabulary sensitivity noted.
- Secrets forbidden; Category B drop-paths/force-record removed from platform JMX.
- Readiness: retain 13 Extension attrs; add `ConfigDeliveryHealth` ∈ {AVAILABLE, UNAVAILABLE, RECOVERING}; **no** Agent lifecycle `DEGRADED`.
- `CONFIG_SNAPSHOT_V1` capability ≠ successful initial apply (`LastApplyOutcome`).
- Counters monotonic; `resetSamplerCounters` deleted.

Added reads (9): RuntimeInstanceId, AcceptedSequence, AcceptedPolicyDigest, LastApplyOutcome, LastAttemptId, LastFailureCode, StructuralDrift, RestartRequired, ConfigDeliveryHealth.

---

## 15. Exact before/after delta

```text
Before: ObjectNames 8 | Members 63 | Read-only 47 | Mutations 16
After:  Platform ON 8 | Platform members 56 | Platform mutations 0
        Private ON 2 | Private reads 4 | Private mutations 1
        Added reads 9 | Deleted reads 8 | Renamed reads 0
        Deleted mutations from platform 16
```

Arithmetic identity verified in `before-after-delta.json` (`check: true`).

---

## 16–17. Consumer migration and P6 map

| Phase | Action |
| --- | --- |
| P2 | Private boundary + test-only target projections; **do not** delete platform reads |
| P4 | Adapters to private writer; Bus bridge; still no supported dual contract switch |
| P6 | Cut over supported writer/read contract; delete legacy platform mutations and obsolete names in one train |

Zero-write platform verification: extend S-03 M12 permanently — assert every platform MBean has `writable=false` and no mutating operations after P6.

---

## 18. Test strategy

- `jmxManagementContractVerify` — live baseline = fixture
- `jmxReadOnlyDeltaVerify` — inventory completeness, 16 mutations, arithmetic, R3 decision
- `privateMBeanServerVerify` — private/platform split evidence (S-03)
- P6: zero-write scan + consumer migration tests

---

## 19. Risks and rollback

| Risk | Mitigation |
| --- | --- |
| External JMX consumers of deleted reads | Pre-production; deleted reads have replacements or Category B ownership; survey before rollout |
| Route-ratios path vocabulary | Document sensitivity; digest available without full map |
| Dual Control ObjectName confusion | Scoped per MBeanServer; platform projection has zero writes |

Rollback unit: entire unreleased train.

---

## 20. Evidence index

| Artefact | Path |
| --- | --- |
| Report | `docs/research/jmx-read-only-delta-s07.md` |
| Evidence dir | `docs/research/evidence/jmx-read-only-delta/` |
| Baseline fixture | `…/jmx-management-contract-baseline.json` |
| Consumer inventory | `docs/research/evidence/jmx-contract/jmx-consumer-inventory.md` |

```powershell
.\gradlew.bat jmxReadOnlyDeltaVerify --no-daemon --rerun-tasks
.\gradlew.bat privateMBeanServerVerify --no-daemon --rerun-tasks
```

---

## 21. Remaining blockers

**For E7:** none from evidence — recommend `READY_TO_CLOSE_E7`.

**For E8 / later:** P0 wire schema + bounds; Implementation GO; P6 zero-write enforcement; optional external JMX consumer survey before production rollout (not an E7 blocker).
