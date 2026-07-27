# S-06 — Boot/Cloud/BOM baseline and refresh delivery (Gate E6)

| Field | Value |
| --- | --- |
| Spike | **S-06**, Gate **E6** |
| Repository | `E:\Platform_Traces`, branch `refactor/cursor-rules` |
| HEAD | `6909f44a1d9957a488c3e144b6f1e4949752aef5` |
| Toolchain | JDK 21.0.6+8-LTS-188 HotSpot, Gradle 8.14, Windows 10 amd64 |
| Production code changed | **NO** |
| Implementation GO | `NOT_GRANTED` |
| Overall result | **PASS** |

E6 selects and proves the production dependency and refresh-delivery contract required before E8. It does not implement the Config Server bridge, change the published BOM, or start P1.

Prior gate evidence: E3 closed at commits `fdf81df4daa764e2310f10586b29285491788fd4` (S-01 spike) and `6909f44a1d9957a488c3e144b6f1e4949752aef5` (corrective evidence pass), identified by subject `test(concurrency): prove accepted policy state primitive`.

---

## 1. Executive verdict

**Verdict: `APPROVE_BOOT_CLOUD_BASELINE`**

**Selected production stack:**

| Component | Exact version |
| --- | --- |
| Spring Boot | **3.5.5** |
| Spring Framework | **6.2.10** (Boot BOM-managed) |
| Spring Cloud release train | **2025.0.0** (Northfields) |
| spring-cloud-context | **4.3.0** |
| spring-cloud-config-client | **4.3.0** |
| spring-cloud-config-server | **4.3.0** |

**Selected refresh fan-out:** Spring Cloud Config Server Monitor/webhook → **Spring Cloud Bus (Kafka binder)** → Config Client `ContextRefresher.refresh()` → **`EnvironmentChangeEvent`** → tracing bridge (complete snapshot rebuild only).

**E6 recommendation:** `READY_TO_CLOSE_E6` (committee/plan owner closes; this spike does not close E6 autonomously).

Weighted score: **Candidate A 94.6** vs Candidate B 90.2. Candidate A wins on corporate baseline alignment (`gradle.properties` / `SUPPORTED.md` validate **3.5.5**) while sharing the same official Northfields train as B.

---

## 2. Gate E6 recommendation

All E6 closure criteria are met by executed evidence:

- exact Boot and Cloud versions selected;
- official compatibility confirmed ([spring.io/projects/spring-cloud](https://spring.io/projects/spring-cloud) — 2025.0.x ↔ Boot 3.5.x);
- C1–C7 **PASS** on approved-stack verification;
- servlet and reactive published consumers **PASS**;
- no Config Client leakage via standard starters;
- no Agent extension leakage;
- S-02 **PASS** on approved stack (Boot 3.5.5 + 2025.0.0);
- refresh fan-out selected with explicit owners;
- no custom polling; no application HTTP mutation;
- migration and rollback units defined;
- production code unchanged.

---

## 3. Current repository dependency state

Corporate baseline is declared in `gradle.properties`:

```properties
springBootVersion=3.5.5
springCloudContextVersion=4.1.5
```

`platform-tracing-bom` imports `spring-boot-dependencies:3.5.5` but also publishes a direct **`api` constraint** on `spring-cloud-context:4.1.5`. That version belongs to the EOL **2023.0.x (Leyton)** train (Boot 3.2/3.3), not Northfields.

Starters resolve **no** `spring-cloud-*` artifacts on `runtimeClasspath`. Autoconfigure declares `spring-cloud-context` as **`compileOnly`** only.

Full inventory: `docs/research/evidence/boot-cloud-bom-refresh/current-dependency-inventory.json`.

---

## 4. Current publication defects

Generated POM contains `spring-cloud-context:4.1.5` in `dependencyManagement` while importing Boot **3.5.5**. Defect **GOV-1**: misaligned EOL constraint without `spring-cloud-dependencies` import.

---

## 5. Official compatibility evidence

- [Spring Cloud release train table](https://spring.io/projects/spring-cloud): **2025.0.x (Northfields) → Spring Boot 3.5.x**
- [Supported Versions wiki](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions): config/context/commons **4.3.x** under 2025.0

Executable: `gradle/s06-approved-stack-resolution verifyApprovedStackResolution` — Boot **3.5.5**, context **4.3.0**, no **4.1.5**.

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
| C2 BOM publication | **PASS** (approved contract; current POM defect documented for P4) |
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

---

## 15–18. Ownership, contract, rollback

See `refresh-delivery-decision.json` and `published-metadata-summary.json`. Rollback unit = entire unreleased train.

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

**E6:** none — recommend `READY_TO_CLOSE_E6`.

**E8:** E7, wire schema, Implementation GO, P4 BOM apply, Bus ops confirmation.
