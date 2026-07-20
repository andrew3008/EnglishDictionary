# Verification Report Template

## 1. Verdict

**Verdict:** `PASS | PASS_WITH_WARNINGS | INSUFFICIENT_EVIDENCE | FAIL`

**Scope verified:**
**Merge readiness:**

Do not use `PASS` when a required gate was skipped or unavailable.

## 2. Repository State

```text
Repository:
Branch:
Commit:
Base:
Working-tree status:
Untracked files:
Affected modules:
Changed runtime boundaries:
```

## 3. Verification Matrix

| Area | Required | Command / Evidence | Result | Notes |
|---|---|---|---|---|
| Production compile | Yes/No | | | |
| Test compile | Yes/No | | | |
| Unit tests | Yes/No | | | |
| Spring tests | Yes/No | | | |
| Architecture fitness | Yes/No | | | |
| Module taxonomy | Yes/No | | | |
| Javadoc | Yes/No | | | |
| Dependency reports | Yes/No | | | |
| Publication metadata | Yes/No | | | |
| Full build | Yes/No | | | |
| E2E | Yes/No | | | |
| Static scans | Yes/No | | | |

## 4. Commands Executed

| # | Command | Exit Result | Tests | Skipped | Failures | Errors | Duration/Notes |
|---:|---|---|---:|---:|---:|---:|---|

Use exact commands.

## 5. Unit and Integration Results

```text
Modules:
Test classes:
Tests:
Skipped:
Failures:
Errors:
```

Summarize the behavior proven, not only the task status.

## 6. Spring Boot Verification

Where applicable:

```text
Default context:
Enabled context:
Disabled context:
Invalid properties:
Missing optional class:
Bean override:
No eager initialization:
Diagnostics:
Configuration metadata:
Servlet/WebFlux isolation:
```

## 7. Architecture Verification

```text
API -> core prohibition:
Core -> Spring prohibition:
WebMVC/WebFlux isolation:
JMX/OpenMBean absent from API:
OTel SDK implementation absent from API:
Starters thin:
Legacy symbols absent:
Raw wire apply absent:
Mutation disabled by default:
Custom source sets migrated:
```

List exact gate names and results.

## 8. Public API and Javadoc

```text
Public types added:
Public types removed:
Methods added:
Methods removed:
Packages changed:
Aliases/bridges:
Third-party types exposed:
Javadoc task:
Warnings:
```

## 9. Dependency and Publication Verification

```text
Dependencies added/removed:
Scopes:
Runtime provider:
Version owner:
Compile classpath:
Runtime classpath:
POM:
Gradle module metadata:
Sources JAR:
Javadoc JAR:
Artifact contents:
```

## 10. Static Scans

| Scan | Scope | Result | False Positives | Notes |
|---|---|---|---|---|
| Wildcard imports | | | | |
| BOM | | | | |
| Legacy symbols | | | | |
| Forbidden dependencies | | | | |
| ServiceLoader descriptors | | | | |
| Trust-all TLS | | | | |
| Java serialization | | | | |
| Hard-coded credentials/endpoints | | | | |
| Stale Javadoc/docs | | | | |

Do not treat an imprecise regex as architecture evidence.

## 11. E2E Verification

```text
Docker environment:
Docker host:
Containers/images:
Network paths:
Selected tests:
JUnit report location:
Tests:
Skipped:
Failures:
Errors:
```

Required E2E evidence:

```text
tests > 0
skipped = 0
failures = 0
errors = 0
```

### Runtime assertions

- [ ] exact span count
- [ ] span names
- [ ] trace continuity
- [ ] parent/child relationships
- [ ] links
- [ ] resource identity
- [ ] sampling
- [ ] scrubbing
- [ ] no duplicate spans
- [ ] runtime-control state behavior
- [ ] no context/MDC leakage

Mark only applicable assertions.

## 12. Security and Privacy Verification

```text
Mutation default:
Rejected mutation state:
PII/scrubbing:
Sensitive logs:
JMX/Actuator exposure:
Exporter endpoint/TLS:
Secrets:
Classloader serialization:
External controls:
```

## 13. Warnings

| Warning | Source | Blocking | Owner | Required Follow-Up |
|---|---|---|---|---|

Classify warnings by root cause.

## 14. Failures

| Failure | Gate/Test | Evidence | Required Fix |
|---|---|---|---|

## 15. Insufficient Evidence

| Claim | Why Unverified | Required Environment or Action |
|---|---|---|

Typical examples:

- Docker unavailable;
- required E2E skipped;
- PR metadata inaccessible;
- publication not generated;
- configuration cache not executed.

## 16. Git State

```text
Working tree clean:
Untracked files:
All fixes committed:
Remote branch updated:
Unrelated changes:
Generated artifacts:
```

## 17. Residual Risks

| Risk | Impact | Mitigation | Owner | Blocking |
|---|---|---|---|---|

## 18. Final Assessment

State:

- what is proven;
- what is not proven;
- whether required gates executed;
- whether warnings are blocking;
- whether the branch is ready for audit or merge.
