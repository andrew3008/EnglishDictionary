# Refactoring Plan Template

## 1. Executive Decision

```text
Decision:
Recommended option:
Why:
Confidence:
Blocking unknowns:
```

State whether implementation should proceed.

## 2. Problem Statement

Describe:

- current architectural or behavioral problem;
- production/readiness impact;
- affected users/modules;
- why the current design is insufficient;
- why the change is non-cosmetic.

## 3. Verified Repository Facts

| Fact | Evidence | Classification |
|---|---|---|
| | file/class/task | VERIFIED / PARTIALLY_VERIFIED / INSUFFICIENT_EVIDENCE |

Do not mix facts with assumptions.

## 4. Current Architecture

### Modules and ownership

```text
API:
Core:
Spring Boot:
WebMVC:
WebFlux:
OTel/JMX:
Starters:
Tests/E2E:
```

### Current dependency graph

```text
<module> -> <module>
```

### Current public surface

```text
Types:
Methods:
Properties:
Beans:
SPIs:
Wire operations:
```

### Current runtime flow

```text
entry
    -> ...
    -> result
```

## 5. Defects and Risks

### Architecture defects

-

### Behavioral defects

-

### Security/privacy defects

-

### Operability defects

-

### Test/evidence defects

-

### Performance defects

-

Classify each as `P0`, `P1`, `P2`, or warning.

## 6. Goals

The target design must:

-

## 7. Non-Goals

The plan will not:

-

## 8. Constraints

Include:

- pre-production breaking-change policy;
- accepted ADRs;
- module/classloader boundaries;
- no compatibility aliases by default;
- no cosmetic-only refactoring;
- no unrelated build migration;
- external operational constraints;
- required rollout model.

## 9. Alternatives

### Option A — `<name>`

**Description:**
**Advantages:**
**Disadvantages:**
**API impact:**
**Runtime impact:**
**Security impact:**
**Complexity:**
**Verification:**

### Option B — `<name>`

...

### Option C — `<name>`

...

## 10. Decision Matrix

| Criterion | Weight | Option A | Option B | Option C |
|---|---:|---:|---:|---:|
| Correctness | | | | |
| Architecture | | | | |
| Security/privacy | | | | |
| API clarity | | | | |
| Runtime safety | | | | |
| Operability | | | | |
| Testability | | | | |
| Performance | | | | |
| Migration effort | | | | |

Explain scores.

## 11. Target Architecture

### Ownership

```text
API:
Core:
Spring:
Web adapters:
OTel/JMX:
Starter:
Deployment:
```

### Dependency direction

List added, removed, and forbidden edges.

### Public API/SPI target

```text
Types added:
Types removed:
Methods added:
Methods removed:
Packages:
SPIs:
Third-party types:
```

### Runtime flow

```text
entry
    -> validation
    -> policy
    -> state transition
    -> result
```

### State model

Describe:

- owner;
- immutability;
- versioning;
- LKG;
- rejection;
- rollback;
- concurrency;
- desired versus applied state.

### Classloader/wire model

Describe JDK-safe values, schemas, and adapter boundaries.

## 12. Failure Semantics

| Failure | Owner | Behavior | State Changed | Diagnostic |
|---|---|---|---|---|
| Invalid wire input | | | No | |
| Domain invalid | | | No | |
| Mutation rejected | | | No | |
| Apply failure | | | | |
| Optional dependency unavailable | | | | |

## 13. Security and Privacy

Describe:

- trust boundaries;
- input validation;
- mutation authorization assumptions;
- propagation/baggage;
- PII/scrubbing scope;
- logging;
- JMX/Actuator;
- exporter endpoints;
- external controls.

## 14. Observability

For each changed signal:

```text
Operational question:
Signal:
Owner:
Name:
Dimensions:
Cardinality:
Sensitive-data assessment:
```

Avoid duplicate telemetry.

## 15. Dependency and Build Changes

```text
Modules added/removed/renamed:
Gradle edges:
Dependencies:
Scopes:
Version owner:
Publication/POM:
Custom source sets:
Generated descriptors:
```

## 16. Migration Inventory

Update all applicable consumers:

- production sources;
- tests;
- custom source sets;
- E2E fixtures;
- samples;
- benchmarks;
- ServiceLoader descriptors;
- configuration metadata;
- Helm/environment mappings;
- docs;
- ADRs;
- warning register;
- CI/tasks.

Do not preserve old paths by default.

## 17. Implementation Slices

### Slice 0 — Preflight and evidence

**Changes:**
**Tests:**
**Exit criteria:**

### Slice 1 — Contract/API

**Changes:**
**Tests:**
**Exit criteria:**

### Slice 2 — Core/runtime

...

### Slice 3 — Spring/adapters

...

### Slice 4 — Tests/E2E/docs

...

Each slice must be independently reviewable where practical.

## 18. Do-Not-Touch

List unrelated files, APIs, modules, defaults, and accepted decisions.

## 19. Test Plan

### Unit

-

### Spring

-

### Architecture

-

### Integration

-

### E2E

-

For required E2E:

```text
tests > 0
skipped = 0
failures = 0
errors = 0
```

## 20. Verification Commands

```powershell
.\gradlew.bat :<module>:compileJava --no-daemon
.\gradlew.bat :<module>:compileTestJava --no-daemon
.\gradlew.bat :<module>:test --no-daemon
.\gradlew.bat pr1ModuleTaxonomyVerify pr4ArchitectureFitnessVerify --no-daemon
.\gradlew.bat build --warning-mode all --no-daemon
```

Add targeted Javadoc, dependency, publication, or E2E commands.

## 21. Documentation and ADRs

List documents to create/update.

State which decision becomes the source of truth.

## 22. Rollout and Rollback

Describe:

- application/agent/version alignment;
- configuration rollout;
- mixed-version behavior;
- coordinated deployment if required;
- rollback steps;
- observable success criteria.

## 23. Residual Risks

| Risk | Probability | Impact | Mitigation | Owner | Blocking |
|---|---|---|---|---|---|

## 24. Definition of Done

- [ ] Target ownership implemented
- [ ] Old path deleted
- [ ] All consumers migrated
- [ ] Public API reviewed
- [ ] Negative tests added
- [ ] Architecture gates added/updated
- [ ] Javadoc passed
- [ ] Required E2E executed
- [ ] Docs/ADR updated
- [ ] No unrelated changes
- [ ] Residual risks documented

## 25. Codex Implementation Prompt

Create a focused implementation prompt from the approved plan only after the decision is accepted.
