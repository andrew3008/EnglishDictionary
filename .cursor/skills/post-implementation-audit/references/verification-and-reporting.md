# Verification and Reporting

## Verification Expectations

Use the narrowest gates first, then broader gates.

Typical sequence:

```powershell
.\gradlew.bat :<affected-module>:compileJava --no-daemon
.\gradlew.bat :<affected-module>:compileTestJava --no-daemon
.\gradlew.bat :<affected-module>:test --no-daemon
.\gradlew.bat pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify --no-daemon
.\gradlew.bat build --warning-mode all --no-daemon
```

For runtime boundaries:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :platform-tracing-e2e-tests:test `
  -PrunE2e `
  --rerun-tasks `
  --no-build-cache `
  --no-daemon
```

Do not require expensive full E2E when the change cannot affect runtime integration, but do require it when behavior crosses:

- agent/application
- JMX
- collector/exporter
- servlet/reactive
- Kafka/database
- runtime control
- sampling/scrubbing export result

## Merge Decision

Use one of:

### PASS

- no P0/P1 findings
- required gates executed and passed
- E2E executed where required
- public/architecture boundaries intact
- docs current
- branch contains the audited fixes

### PASS_WITH_WARNINGS

- no code-level P0/P1
- required compile/test/architecture gates pass
- remaining warning is documented, bounded, non-blocking
- merge recommendation is explicit

### FAIL

- P0/P1 remains
- required gate fails
- required test skipped
- fixes only exist locally but PR branch is stale
- architecture regressed
- state safety/privacy not proven

### INSUFFICIENT_EVIDENCE

- necessary environment unavailable
- runtime test did not execute
- referenced PR/branch/file unavailable
- claim cannot be verified

Do not use PASS to mean “probably fine”.

## Required Review Output

Use this structure:

```markdown
# Review Report

## 1. Executive Verdict

PASS / PASS_WITH_WARNINGS / FAIL / INSUFFICIENT_EVIDENCE

## 2. Scope

- branch
- commit
- base
- files/modules
- review mode

## 3. Problem and Intended Decision

## 4. Verified Repository Facts

| Fact | Evidence | Result |

## 5. Architecture Review

## 6. Public API / SPI Review

## 7. Runtime / State Safety Review

## 8. Security / Privacy Review

## 9. Spring / Gradle / Dependency Review

## 10. Tests and E2E

| Command | Result | Notes |

## 11. Findings

### P0
### P1
### P2
### False Positives
### Insufficient Evidence

## 12. Required Fixes

| Finding | File | Required Change | Verification |

## 13. Residual Risks

## 14. Merge Recommendation

## 15. Codex Fix Prompt

Only when fixes remain.
```

## Architect-Facing Summary

A review summary for architects should answer:

```text
What changed:
Why it is non-cosmetic:
What architecture decision is preserved:
What risks were closed:
What tests actually executed:
What remains external/operational:
Whether the PR is merge-ready:
```

Avoid long implementation narration when a concise evidence-based summary is sufficient.

## Imports and Generated Agent Prompts

When generating an implementation or remediation prompt, include:

```text
Java import policy:
- follow `.editorconfig`
- explicit imports only
- no wildcard imports
- static imports separate
- no import-only churn
- compile affected source sets
- run wildcard import scan
```

Also include:

- exact branch/base
- exact plan/audit
- do-not-touch list
- required commands
- E2E execution requirement
- no commit/push unless explicitly requested
- final report format

## Anti-Patterns

Forbidden review behavior:

- approving because code looks clean
- blocking justified breaking changes for pre-production compatibility
- demanding aliases by default
- treating all public types as supported API
- relying on implementation report without verification
- calling skipped tests passed
- broad architectural re-review after every minor fix
- findings without severity or evidence
- only reviewing main source
- ignoring custom source sets
- ignoring publication metadata
- ignoring classloader boundaries
- accepting public schema/validator implementation leakage
- accepting runtime mutation without fail-closed default
- accepting high-cardinality telemetry without budget
- approving raw PII diagnostics
- weakening architecture gates
- dismissing warnings without root-cause classification
- demanding cosmetic changes as architecture
- allowing import/format churn to hide behavior changes
- overconfident claims without evidence

## Required Final Checklist

Before final approval:

```text
[ ] Correct branch/commit reviewed
[ ] Working tree state understood
[ ] Problem and architecture decision clear
[ ] Public surface reviewed
[ ] Module dependency direction reviewed
[ ] External dependencies/scopes reviewed
[ ] Runtime state and failure paths reviewed
[ ] Security/PII/cardinality reviewed
[ ] Spring conditions/defaults reviewed
[ ] Custom source sets and descriptors reviewed
[ ] Tests compiled
[ ] Unit/integration tests passed
[ ] Required E2E executed, not skipped
[ ] Architecture fitness passed
[ ] Javadoc/build warnings classified
[ ] Docs/ADR current
[ ] No unrelated generated files
[ ] No stale compatibility bridges
[ ] No wildcard imports/import-only churn
[ ] Residual risks documented
[ ] Merge recommendation explicit
```

