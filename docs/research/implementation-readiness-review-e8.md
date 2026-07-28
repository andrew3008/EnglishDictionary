# P0/E8 - Implementation Readiness Review

| Field | Value |
| --- | --- |
| Gate | **E8** |
| Review type | Final technical readiness package before committee authorization |
| Repository baseline | `E:\Platform_Traces`, branch `refactor/cursor-rules`, reviewed HEAD `0f81052557c685b9988d438a6fbd4a5a031aab7a` |
| Production code changed | **NO** |
| Cursor plan changed | **NO** |
| P1 started | **NO** |
| Technical package | **`COMPLETE`** |
| Implementation GO | **`NOT_GRANTED`** |
| Verdict | **`READY_FOR_COMMITTEE_APPROVAL`** |

This review closes the technical blockers previously listed as A1-A3. It does not
self-approve committee-owned decisions, close E7, authorize P1, authorize legacy
deletion, or permit production rollout.

## 1. Executive Verdict

**`READY_FOR_COMMITTEE_APPROVAL`**

The evidence chain is internally consistent and the previously missing bounded,
versioned Open-Type contract is now explicit:

- exact `applySnapshot(CompositeData)` request and response type names;
- exactly eight Category A leaves;
- closed enums and exact Open-Type fields;
- strict unknown, missing, null and type rejection;
- deterministic SHA-256 canonical digest;
- exact route-map, string, collection and logical-payload bounds;
- independent Agent/App codec ownership with JDK Open Types as the only boundary;
- scenario, rollback and pre-production security-debt decision records.

No technical P0 artifact remains to be designed. The remaining actions are decisions:
the committee must approve the proposed contract, record E7 closure, accept the
section 28 handoff records, and explicitly record E8 closure and Implementation GO.

Until then:

```text
Implementation GO: NOT_GRANTED
P1: BLOCKED
Legacy deletion: BLOCKED
Production rollout: FORBIDDEN
```

## 2. Gate Status

| Gate | Status | Evidence |
| --- | --- | --- |
| E1 | **CLOSED** | JMX baseline and consumer inventory |
| E2 | **CLOSED** | Private process-local MBeanServer proof |
| E3 | **CLOSED** | One Agent accepted-state owner and concurrency evidence |
| E4 | **CLOSED** | Configuration and environment inventory |
| E5 | **CLOSED** | Exactly eight Category A leaves |
| E6 | **CLOSED** | Boot/Cloud/BOM and refresh-delivery contract |
| E7 | **READY_TO_CLOSE** | Exact R3 JMX delta and private `applySnapshot` role |
| E8 | **READY_FOR_COMMITTEE_APPROVAL** | This package |
| E9 | **CLOSED** | Config Server provenance |

E7 is not relabelled `CLOSED` by this report. Its corrected evidence is complete, but
formal closure remains a committee/plan-owner action.

## 3. Resolved Blockers

| Previous blocker | Resolution |
| --- | --- |
| A1: no versioned wire schema | `p0-wire-schema-contract.json` freezes version 1 request, policy, route table and response |
| A2: no numeric/collection/payload bounds | `p0-wire-schema-bounds.json` freezes all decoder limits and fail-closed outcomes |
| A3: Agent/App Open Types not fixed | Contract names both codec owners, exact JDK boundary types and shared golden-vector rule |
| A5: security-debt handoff absent | `security-debt-acceptance.json` defines the exact, pre-production-only acceptance offered to committee |
| A6: rollback/scenario handoff absent | `rollback-acceptance.json` and `scenario-acceptance.json` are complete |

A4 (formal E7 close) and A7 (Implementation GO) are not technical defects and cannot
be granted by a coding agent.

## 4. Exact Wire Contract

Private operation:

```text
ObjectName:
  space.br1440.platform.tracing:name=PlatformControlProtocol,type=Control

Operation:
  applySnapshot(CompositeData) -> CompositeData

Request type:
  space.br1440.platform.tracing.control.ApplySnapshotRequest.v1

Policy type:
  space.br1440.platform.tracing.control.RuntimePolicy.v1

Route table:
  space.br1440.platform.tracing.control.RouteRatios.v1

Response type:
  space.br1440.platform.tracing.control.ApplySnapshotResponse.v1
```

The runtime policy contains only:

1. `samplingEnabled`
2. `samplingRatio`
3. `samplingRouteRatios`
4. `validationEnabled`
5. `validationStrict`
6. `exporterEnabled`
7. `traceControlHeadersEnabled`
8. `diagnosticsLogLevel`

The request is a complete snapshot, not a patch. Unknown or missing items, nulls,
unapproved coercion, oversized data and Category B mutation attempts are rejected
before domain validation and before CAS.

## 5. Bound Decisions

| Value | Bound |
| --- | ---: |
| Logical request, canonical UTF-8 | 32,768 bytes |
| Route-ratio entries | 100 |
| Route prefix | 255 UTF-16 code units |
| Source revision | 256 UTF-16 code units |
| Restart-required keys | 32 |
| Violation codes | 16 |
| Ratios | finite `[0.0, 1.0]` |
| Runtime/attempt identifiers | canonical UUID, 36 ASCII characters |
| Digest | SHA-256, 64 lowercase hexadecimal characters |

The repository already enforced the ratio range, but had no route-map cardinality or
route-prefix bound. The latter two are therefore identified honestly as new P0
security limits, aligned with existing sampling-control ceilings rather than
misrepresented as historical validator behavior.

## 6. Concurrency And Digest

The contract preserves E3:

- `APPLIED`: exactly one successful expected-base CAS and sequence `+1`;
- `UNCHANGED`: no CAS;
- `REJECTED`: no CAS;
- `STALE`: no hidden retry or rebase in the Agent store;
- `FAILED`: internal invariant failure, never an ordinary conflict.

The candidate digest excludes source revision and uses SHA-256 over a fixed field
order and binary framing. Route entries are key-sorted. `Map.toString`,
locale-sensitive numeric formatting and unordered iteration are forbidden.

## 7. Classloader Contract

The application and Agent implement independent codecs:

| Plane | Owner |
| --- | --- |
| Application codec | `platform-tracing-spring-boot-autoconfigure` |
| Agent codec | `platform-tracing-otel-javaagent-extension` |

Only `CompositeData`, `TabularData`, primitive wrappers, strings and Open-Type arrays
cross the boundary. No public control DTO, arbitrary map, second SDK or shared
application object is introduced.

## 8. Section 28 Handoff

The committee packet now contains:

- `committee-readiness-checklist.json`;
- `scenario-acceptance.json`;
- `rollback-acceptance.json`;
- `security-debt-acceptance.json`.

The security record accepts current mutation debt only in pre-production and only
until coherent P6 cutover. It continues to forbid production rollout and remote
write access. It explicitly does not self-approve the risk.

## 9. Remaining Risks

There are no open technical architecture blockers in this package. Residual risks
are implementation and rollout risks:

- independent codec drift, controlled by one contract and golden vectors;
- accidental cross-classloader DTO leakage, controlled by architecture tests;
- malformed/oversized input, controlled before normalization and CAS;
- incomplete legacy-writer deletion, controlled by S-07 and P6 zero-write gates;
- corporate Bus production-like execution and external JMX consumer migration.

These risks are assigned to P1-P7 and release readiness. They do not require another
architecture-alternatives study.

## 10. Committee Decision Required

The exact next decision is:

1. approve or return concrete corrections for the two P0 wire artifacts;
2. record E7 `CLOSED`;
3. accept the scenario, rollback and security-debt handoff;
4. record E8 `CLOSED` and `Implementation GO`.

P1 may start only after item 4. A partial or implied GO is not sufficient.

## 11. Artifacts

All files are under:

```text
docs/research/evidence/implementation-readiness/
```

Normative candidate artifacts:

- `p0-wire-schema-contract.json`
- `p0-wire-schema-bounds.json`
- `committee-readiness-checklist.json`
- `scenario-acceptance.json`
- `rollback-acceptance.json`
- `security-debt-acceptance.json`

Executable gate:

```powershell
.\gradlew.bat implementationReadinessVerify --no-daemon --rerun-tasks
```

## 12. Verification Results

| Command | Result |
| --- | --- |
| `:platform-tracing-bench:compileTestJava implementationReadinessVerify --rerun-tasks` | **PASS** |
| `jmxReadOnlyDeltaVerify acceptedStatePrimitiveVerify bootCloudBomRefreshVerify privateMBeanServerVerify --rerun-tasks` | **PASS** |
| `:platform-tracing-bench:test --tests "*E8ImplementationReadinessEvidenceTest" --rerun-tasks` | **PASS**, 9 tests |
| `test --no-daemon` | **PASS** |
| JSON parse for all E8 evidence | **PASS**, 11 files |
| unresolved placeholder scan in E8/S-07 evidence | **PASS**, 0 matches |
| `git diff --check` | **PASS** |

The aggregate `test` task reports the existing opt-in
`platform-tracing-e2e-tests:test` and sample tests as `SKIPPED`. They are not
represented as executed or green runtime E2E evidence by this review.

## 13. Final Status

```text
Overall technical verification: READY
E8 package: READY_FOR_COMMITTEE_APPROVAL
Technical blockers: NONE
E7 formal status: READY_TO_CLOSE
Implementation GO: NOT_GRANTED
P1 may start: NO
Legacy deletion GO: BLOCKED
Production rollout: FORBIDDEN
```
