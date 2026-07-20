# Post-Implementation Audit Template

## 1. Executive Verdict

**Verdict:** `PASS | PASS_WITH_WARNINGS | INSUFFICIENT_EVIDENCE | FAIL`

**Merge recommendation:** `MERGE | MERGE AFTER REQUIRED FIXES | DO NOT MERGE | CANNOT DETERMINE`

Provide a concise evidence-based summary.

## 2. Scope

```text
Repository:
Branch:
Commit:
Base:
Authoritative plan:
Implementation report:
Working-tree status:
Modules:
Files:
Review mode: Post-implementation audit
```

Treat implementation summaries as claims, not evidence.

## 3. Problem and Intended Decision

### Problem

Describe the verified problem the implementation was intended to solve.

### Approved decision

Summarize the approved target architecture and non-negotiable constraints.

### Non-goals

List behavior or modules intentionally outside scope.

## 4. Verified Repository Facts

| Fact | Evidence | Classification |
|---|---|---|
| | file/class/command | VERIFIED / PARTIALLY_VERIFIED / INSUFFICIENT_EVIDENCE |

Distinguish current code from historical plans and documents.

## 5. Plan Compliance

| Plan Requirement | Implementation Evidence | Status |
|---|---|---|
| | | SATISFIED / PARTIAL / MISSING / CONTRADICTED |

Check all implementation slices, migrations, deletions, tests, documentation, and gates.

## 6. Architecture Review

### Module ownership

Verify:

- API owns contracts;
- core owns runtime/domain/state;
- Spring owns wiring/properties/diagnostics;
- servlet and WebFlux remain isolated;
- OTel/JMX owns classloader-sensitive integration;
- starters remain thin.

### Dependency direction

List edges added and removed.

Identify forbidden edges or cycles.

### Classloader boundary

Verify JDK-safe wire types, explicit schemas, no implementation casts, and no Java native serialization.

## 7. Public API and SPI Review

```text
Public types added:
Public types removed:
Public methods added:
Public methods removed:
Packages changed:
Public-for-compilation internal bridges:
Third-party types exposed:
SPIs added/removed:
ServiceLoader descriptors:
Compatibility aliases/bridges:
Javadoc:
```

Flag accidental public surface.

## 8. Runtime and State Safety

Review:

- state ownership;
- immutability/publication;
- atomic apply;
- LKG behavior;
- rejection behavior;
- repeated apply;
- rollback;
- concurrency;
- no-op behavior;
- startup desired state versus live applied state.

For runtime control, verify:

```text
wire decode
    -> domain validation
    -> mutation policy
    -> apply/read
```

## 9. Security and Privacy

Review:

- untrusted input validation;
- mutation default;
- propagation;
- baggage;
- force sampling;
- PII scrubbing scope;
- logging;
- Actuator/JMX exposure;
- exporter endpoint safety;
- TLS;
- secrets;
- custom providers;
- resource limits.

State which controls remain external: JVM, network, RBAC, Kubernetes, secret management.

## 10. Observability Review

Verify:

- operator use case;
- no duplicate spans/metrics;
- span naming/cardinality;
- attribute safety;
- sampling behavior;
- scrubbing behavior;
- diagnostics;
- desired versus applied state;
- exporter queue/drop visibility;
- bounded warning deduplication.

## 11. Spring and Auto-Configuration Review

Verify:

- `@AutoConfiguration`;
- correct conditions;
- typed properties;
- defaults;
- optional classpath;
- bean override policy;
- no eager side effects;
- servlet/WebFlux isolation;
- Actuator/health semantics;
- configuration metadata.

## 12. Gradle and Dependency Review

Verify:

- project dependencies;
- `api`/`implementation`/`compileOnly` semantics;
- runtime provider ownership;
- BOM/version ownership;
- custom source sets;
- generated descriptors;
- Javadoc classpath;
- POM/module metadata;
- starter dependency graph.

## 13. Test Review

Review:

- unit tests;
- negative tests;
- boundary tests;
- no-op/disabled tests;
- concurrency/rollback tests;
- optional-classpath tests;
- architecture tests;
- custom source sets;
- samples/benchmarks;
- E2E fixtures.

Do not accept tests that only reproduce production logic or preserve removed pre-production APIs.

## 14. Commands and Results

| Command | Executed | Result | Tests | Skipped | Notes |
|---|---|---|---:|---:|---|

A required skipped test is `INSUFFICIENT_EVIDENCE`.

## 15. E2E Evidence

```text
Docker environment:
Containers/images:
Network paths:
Selected test patterns:
Tests:
Skipped:
Failures:
Errors:
Runtime assertions:
```

Required evidence:

```text
tests > 0
skipped = 0
failures = 0
errors = 0
```

## 16. Findings

### P0 — Merge Blockers

| ID | Finding | Evidence | Required Fix | Verification |
|---|---|---|---|---|

### P1 — Must Fix Before Merge

| ID | Finding | Evidence | Required Fix | Verification |
|---|---|---|---|---|

### P2 — Should Fix Before Release

| ID | Finding | Evidence | Required Fix | Verification |
|---|---|---|---|---|

### False Positives

| Finding | Why False | Rule/Scan Correction |
|---|---|---|

### Insufficient Evidence

| Claim | Missing Evidence | Required Action |
|---|---|---|

## 17. Documentation and ADRs

Verify:

- ADR updated;
- current architecture docs updated;
- historical docs clearly marked;
- property and Helm mappings updated;
- warning register accurate;
- samples current;
- rollout guidance current.

## 18. Git and Publication State

```text
Branch:
Commit:
Working tree:
Untracked files:
Fixes committed:
Remote branch contains commit:
Publication impact verified:
Unrelated changes:
```

## 19. Residual Risks

| Risk | Owner | Impact | Mitigation | Blocking |
|---|---|---|---|---|

## 20. Merge Recommendation

State exactly:

- whether P0/P1 findings remain;
- whether required gates executed;
- whether runtime behavior is verified;
- whether the audited fixes are on the branch;
- whether merge is recommended.

## 21. Codex Remediation Prompt

Include only when required fixes remain.

Use `.cursor/templates/codex-remediation-prompt.md` as the structure.
