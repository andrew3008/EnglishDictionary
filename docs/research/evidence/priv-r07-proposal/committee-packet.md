# PROPOSED_PRIV_R07 — Architecture Committee Packet

**Proposal:** `PROPOSED_PRIV_R07`  
**Subject:** private read-only operation `readAcceptedComplete`  
**Packet assembled:** 2026-07-29 (P4-WF.8 / P4 DECISION)  
**Decision owner:** Human Architecture Committee  
**LLM status authority:** none — this packet does **not** approve or reject  

Machine-readable twin: [`decision-summary.json`](./decision-summary.json)  
Verifier: `priv07ProposalVerify` → **PASS** (executed 2026-07-29)

---

## 1. Decision requested

Record exactly one of:

| Decision | Meaning |
|---|---|
| **APPROVE** | PRIV-R07 is a permanent private read-only operation; formal P4 may proceed to P4-WF.9 |
| **REJECT** | Operation must change or be removed; P4 formal closure stays blocked; state remediation required |

Optional companion decisions (see §6):

1. Whether to add an explicit maximum logical payload bound (like `applySnapshot`)  
2. When to reconcile this addendum into the S-07/E7 read-only-delta package  

---

## 2. Exact MBean contract

| Field | Value |
|---|---|
| ObjectName | `space.br1440.platform.tracing:name=PlatformControlProtocol,type=Control` |
| Operation | `readAcceptedComplete` |
| Kind | `READ_OPERATION` |
| Parameters | none (`MBeanParameterInfo[0]`) |
| Return type | `javax.management.openmbean.CompositeData` |
| CompositeType | `AcceptedCompleteSnapshot/v1` (10 fields) |
| Impact | `INFO` (`MBeanOperationInfo.INFO`) |
| Mutation | **false** |
| Exposure | **PRIVATE_MBEAN_SERVER_ONLY** |
| Agent source | `space.br1440.platform.tracing.otel.javaagent.control.state.PrivateControlWriter` |

**Invocation signature (canonical):**

```text
MBeanServer.invoke(objectName, "readAcceptedComplete", new Object[0], new String[0])
```

Also accepted: `(null, null)` for params/signature.  
Rejected: non-empty params and/or non-empty signature.

**Arithmetic delta vs S-07 baseline (addendum, not in-place edit of S-07):**

| Metric | Before | After |
|---|---:|---:|
| private members | 7 | 8 |
| private read members | 6 | 7 |
| private mutations | 1 | 1 |
| complete-policy read ops | 0 | 1 |
| platform members | 56 | 56 (unaffected) |
| platform mutations | 0 | 0 |

`applySnapshot` remains the sole private mutation.

---

## 3. Wire schema (summary)

Root: `AcceptedCompleteSnapshot/v1` — 10 fields, unknown fields **REJECT**, null fields **REJECT**.

| Field | OpenType |
|---|---|
| acceptedSequence | LONG (≥ 0) |
| runtimeInstanceId | STRING (non-blank) |
| samplingEnabled | BOOLEAN |
| samplingRatio | DOUBLE [0.0, 1.0] |
| samplingRouteRatios | TabularType RouteRatios.v1 (≤100 entries; key ≤255 UTF-16) |
| validationEnabled | BOOLEAN |
| validationStrict | BOOLEAN |
| exporterEnabled | BOOLEAN |
| traceControlHeadersEnabled | BOOLEAN |
| diagnosticsLogLevel | STRING ∈ {OFF,ERROR,WARN,INFO,DEBUG,TRACE} |

**Open item for committee:** no explicit maximum logical payload bound (unlike `applySnapshot`’s 32768-byte guard). Implicitly bounded by route-table limits; worst case low tens of KB.

Full field table: `decision-summary.json` → `wireSchema`.

---

## 4. Why it is required (hard P4 dependencies)

| Consumer | Role | Failure without PRIV-R07 |
|---|---|---|
| `PrivateJmxTracingPolicySnapshotClient` | Spring private JMX client; `invoke(..., "readAcceptedComplete", …)` | no authoritative complete snapshot / sequence |
| `SpringActuatorTracingPolicyAdapter` | DEV read-copy-change-validate-apply | cannot read complete accepted state before leaf mutation |
| `TracingActuatorAutoConfiguration.ActuatorAgentReadinessProbe` | ACTUATOR readiness | startup blocked / FATAL if op missing or malformed |
| `TracingPolicyRefreshCoalescer` / Config Server path | initial delivery + sequence refresh via `snapshotReader.currentComplete()` | cannot establish authoritative sequence / runtime instance |
| Servlet + WebFlux DEV control | both transports share the adapter | same dependency on both stacks |

Without APPROVE, formal P4 closure remains blocked (`p4ClosureImpact.classification = BLOCKER`).

---

## 5. Executable evidence (already green)

| Proof | Location / task | Result |
|---|---|---|
| Proposal consistency | `Priv07ProposalEvidenceTest` / `priv07ProposalVerify` | PASS |
| Agent→Spring wire codec parity | `AgentToSpringWireCodecParityTest` | PASS |
| DynamicMBean exact invoke + MBeanInfo | `PrivateControlWriterInvokeContractTest` | PASS |
| Private-only registration (absent from platform) | `PrivateControlBoundaryTest` | PASS |
| Config Server / readiness missing-op / malformed | `TracingPolicyRefreshCoalescerTest`, `TracingControlManagementChildContextIntegrationTest` | PASS |
| Private isolation E2E | `:platform-tracing-e2e-tests:privateMBeanServerTest` | PASS (P4-WF.7) |
| Aggregate Docker E2E | `:platform-tracing-e2e-tests:test -PrunE2e` | PASS 28/65 (P4-WF.7) |

Production code **already implements** the operation (commit `00b987a…`); this packet is governance approval of an existing private read, not a greenfield design.

---

## 6. Security / isolation constraints

- Registered only on process-local private `MBeanServer` (`PrivateControlMBeanServerOwner`)  
- Never on platform `MBeanServer`; never remote-JMX reachable  
- Exact bounded Open Types; unknown/null fields rejected  
- Read-only (INFO); does not change accepted sequence / state  
- Platform surface arithmetic unchanged (56 / 0 mutations)

---

## 7. Committee decision record (recorded)

```text
proposalId: PROPOSED_PRIV_R07
decision:   APPROVE
decidedBy:  Architecture Committee
decidedAt:  2026-07-29
rationale:
  The committee approves readAcceptedComplete as the permanent private,
  read-only authoritative accepted-policy operation. The operation is required
  for Config Server initial/runtime delivery, DEV_ACTUATOR read-copy-change,
  application readiness, runtime-instance detection, and Servlet/WebFlux
  transport parity. It preserves Agent-side state ownership, introduces no
  additional mutation path, remains inaccessible through the platform
  MBeanServer and remote JMX, and is supported by executable contract,
  codec-parity, isolation and Docker E2E evidence.
payloadBoundDecision: REQUIRE_EXPLICIT_BOUND
s07Reconcile: RECONCILE_NOW
```

Status after recording:

```text
APPROVED
```
