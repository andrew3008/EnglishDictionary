# S-06 — Boot/Cloud/BOM baseline and refresh delivery (Gate E6)

| Field | Value |
| --- | --- |
| Spike | **S-06**, Gate **E6** |
| Repository | `E:\Platform_Traces`, branch `refactor/cursor-rules` |
| HEAD | `6909f44a1d9957a488c3e144b6f1e4949752aef5` |
| Toolchain | JDK 21.0.6+8-LTS-188 HotSpot, Gradle 8.14, Windows 10 amd64 |
| Production Java changed | **NO** |
| Production BOM changed | **YES — approved E6 alignment** |
| Implementation GO | `NOT_GRANTED` |
| Overall result | **PASS — dependency baseline and refresh-delivery contract approved** |

E6 selects and proves the production dependency baseline and refresh-delivery contract required before E8. This corrective evidence applies the approved BOM alignment but does not implement the Config Server bridge or start P1.

Prior gate evidence: E3 closed at commits `fdf81df4daa764e2310f10586b29285491788fd4` (S-01 spike) and `6909f44a1d9957a488c3e144b6f1e4949752aef5` (corrective evidence pass), identified by subject `test(concurrency): prove accepted policy state primitive`.

---

## 1. Executive verdict

**Verdict: `APPROVE_BOOT_CLOUD_BASELINE_AND_REFRESH_DELIVERY_CONTRACT`**

**Selected production stack:**

| Component | Exact version |
| --- | --- |
| Spring Boot | **3.5.5** |
| Spring Framework | **6.2.10** (Boot BOM-managed) |
| Spring Cloud release train | **2025.0.0** (Northfields) |
| spring-cloud-context | **4.3.0** |
| spring-cloud-config-client | **4.3.0** |
| spring-cloud-config-server | **4.3.0** |
| spring-cloud-bus | **4.3.0** |
| spring-cloud-stream / Kafka binder | **4.3.0** |
| spring-kafka / kafka-clients | **3.3.9 / 3.9.1** |
| spring-integration-core | **6.5.1** |

**Selected refresh fan-out:** Spring Cloud Config Server Monitor/webhook → **Spring Cloud Bus (Kafka binder)** → Config Client `ContextRefresher.refresh()` → **`EnvironmentChangeEvent`** → tracing bridge (complete snapshot rebuild only).

**E6 recommendation:** `READY_TO_CLOSE_E6`.

Weighted score: **Candidate A 94.6** vs Candidate B 90.2. Candidate A wins on corporate baseline alignment (`gradle.properties` / `SUPPORTED.md` validate **3.5.5**) while sharing the same official Northfields train as B.

---

## 2. Gate E6 recommendation

Repository-controlled dependency criteria are met by executed evidence:

- exact Boot and Cloud versions selected;
- official compatibility confirmed ([spring.io/projects/spring-cloud](https://spring.io/projects/spring-cloud) — 2025.0.x ↔ Boot 3.5.x);
- C1–C7 **PASS** on approved-stack verification;
- servlet and reactive published consumers **PASS**;
- no Config Client leakage via standard starters;
- no Agent extension leakage;
- S-02 **PASS** on approved stack (Boot 3.5.5 + 2025.0.0);
- exact Config Server and Config Client Bus dependency graphs pass;
- no custom polling;
- migration and rollback units defined;
- production Java unchanged.

The Platform Tracing Architecture Committee approved the complete corporate Kafka and Config
Server `/monitor` hardening profile in `corporate-bus-readiness.json` on 2026-07-28. This closes
the E6 design blocker. Approval does not replace the mandatory pre-production verification
listed in that contract.

---

## 3. Current repository dependency state

Corporate baseline is declared in `gradle.properties`:

```properties
springBootVersion=3.5.5
springCloudVersion=2025.0.0
```

`platform-tracing-bom` imports both `spring-boot-dependencies:3.5.5` and
`spring-cloud-dependencies:2025.0.0`. The former direct `spring-cloud-context:4.1.5`
constraint is removed.

Published starter consumers receive the `spring-cloud-dependencies` platform for version
alignment, but resolve **no Spring Cloud runtime libraries**. Autoconfigure declares
`spring-cloud-context` as **`compileOnly`** only.

Full inventory: `docs/research/evidence/boot-cloud-bom-refresh/current-dependency-inventory.json`.

---

## 4. Current publication

`s06CandidateBomPublicationVerify` checks the generated Maven POM and Gradle module metadata:
Cloud BOM `2025.0.0` is an imported platform in every variant and `4.1.5` is absent.
Defect **GOV-1 is resolved**.

---

## 5. Official compatibility evidence

- [Spring Cloud release train table](https://spring.io/projects/spring-cloud): **2025.0.x (Northfields) → Spring Boot 3.5.x**
- [Supported Versions wiki](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions): config/context/commons **4.3.x** under 2025.0

Executable: `gradle/s06-approved-stack-resolution verifyApprovedStackResolution` — Boot
**3.5.5**, Config/Context/Commons **4.3.0**, exact Bus/Stream/Kafka graph, no **4.1.5**.

---

## 6. Candidate baselines

| ID | Boot | Cloud train | Score | Notes |
| --- | --- | --- | ---: | --- |
| **A (selected)** | 3.5.5 | 2025.0.0 | **94.6** | Corporate baseline |
| B | 3.5.6 | 2025.0.0 | 90.2 | Prior S-02 stack; not corporate baseline |

---

## 7. C1–C7 results

| Gate | Status |
| --- | --- |
| C1 Root resolution | **PASS** |
| C2 BOM publication | **PASS** (generated POM and Gradle metadata) |
| C3 Servlet consumer | **PASS** |
| C4 Reactive consumer | **PASS** |
| C5 Config Client leakage | **PASS** |
| C6 Agent extension isolation | **PASS** |
| C7 S-02 approved stack | **PASS** |

Task `bootCloudBomRefreshVerify` — **BUILD SUCCESSFUL**.

---

## 8–9. Published consumers

Servlet: `published-servlet-consumer-dependencies.txt` — no spring-cloud, no agent extension.

Reactive: `published-reactive-consumer-dependencies.txt` — same proofs.

---

## 10–11. Leakage and isolation

No Config Client on starter runtime classpaths. Agent extension absent from published consumers (C3/C6 negative probes).

---

## 12. S-02 approved-stack reproduction

`configProvenance` source set realigned to Boot **3.5.5** + Cloud **2025.0.0**. `configProvenanceVerify --rerun-tasks` PASS. Q6 closed.

---

## 13–14. Refresh delivery

**Selected:** Config Server Monitor → Spring Cloud Bus (Kafka) → Config Client refresh → `EnvironmentChangeEvent`.

Rejected: `/actuator/refresh`, custom polling, restart-only.

Repository feasibility is proven by exact dependency resolution. Corporate deployment readiness is
not inferred from dependency resolution; see `corporate-bus-readiness.json`.

---

## 15–18. Ownership, contract, rollback

See `refresh-delivery-decision.json`, `resolved-refresh-delivery-graph.json`,
`corporate-bus-readiness.json` and `published-metadata-summary.json`. Rollback unit = entire
unreleased train.

---

## 20. Evidence index

| Artefact | Path |
| --- | --- |
| This report | `docs/research/boot-cloud-bom-refresh-s06.md` |
| Evidence directory | `docs/research/evidence/boot-cloud-bom-refresh/` |

```powershell
.\gradlew.bat bootCloudBomRefreshVerify --no-daemon --rerun-tasks
.\gradlew.bat configProvenanceVerify --no-daemon --rerun-tasks
```

---

## 21. Remaining blockers

**E6:** `READY_TO_CLOSE_E6`. Corporate Kafka and Config Server control-plane contract approved.
All checks in `verificationRequiredBeforeProduction` remain mandatory before production rollout.

**E8:** E7, wire schema, Implementation GO and subsequent P4/P6 implementation gates.
