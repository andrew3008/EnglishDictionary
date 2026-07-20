# Codex Remediation Prompt

## Role

Act as a senior Java platform engineer performing a narrowly scoped remediation in an enterprise platform tracing repository.

## Objective

Fix the findings listed in the authoritative audit or review referenced by the user.

Do not redesign the approved architecture unless a finding proves that the approved design is internally inconsistent or unsafe.

## Authoritative Inputs

Use, in this order:

1. the current user request;
2. the referenced audit or review report;
3. the approved implementation or refactoring plan;
4. accepted ADRs and current architecture documentation;
5. current repository facts;
6. `.cursor/rules` and relevant `.cursor/skills`.

Treat implementation summaries and previous agent reports as claims, not evidence.

## Project Policy

The tracing solution is pre-production.

Breaking source, binary, package, bean, property, SPI, wire, module, and build changes are allowed when they materially improve the final architecture or close a verified defect.

Do not add by default:

- compatibility aliases;
- deprecated bridges;
- forwarding types or modules;
- dual old/new paths;
- legacy property bindings;
- speculative extension points.

Do not use pre-production status to justify unrelated churn.

## Before Editing

Verify:

```text
Current branch:
Current commit:
Base branch:
Working-tree status:
Untracked files:
Authoritative plan/audit:
Affected modules:
Affected production call sites:
Affected tests/custom source sets/E2E fixtures:
Unrelated user changes:
```

Inspect:

- `git status --short --branch`;
- recent commits;
- the full current diff;
- exact files referenced by every finding;
- module dependencies;
- public API and SPI surface;
- relevant Gradle tasks;
- existing architecture fitness rules.

Do not overwrite or include unrelated user changes.

## Finding Closure

For every finding, create an internal checklist:

```text
Finding ID:
Severity:
Root cause:
Required change:
Files affected:
Public API impact:
Runtime/state impact:
Security/privacy impact:
Tests required:
Verification command:
Closure evidence:
```

Do not mark a finding closed merely because code compiles.

## Implementation Constraints

- Preserve the approved API/Core/Spring/WebMVC/WebFlux/OTel/JMX ownership.
- Keep implementation helpers package-private whenever possible.
- Do not expose Spring, JMX/OpenMBean, or OTel SDK implementation types through application-facing API.
- Keep servlet and WebFlux adapters isolated.
- Keep starters thin.
- Preserve the approved control pipeline:

```text
wire payload
    -> structural decode
    -> domain validation
    -> mutation policy
    -> apply or read
```

- Keep runtime mutation disabled by default.
- Ensure rejected mutations preserve snapshot, version, source, timestamps, and last-known-good state.
- Do not reintroduce removed schema/validator APIs, legacy packages, or dual paths.
- Do not add ServiceLoader or SPI unless an external-provider requirement is verified.
- Do not add hidden retries, global mutable state, service locators, or startup side effects.

## Java Code Policy

- Follow `.editorconfig`.
- Use explicit imports only.
- Do not use wildcard or static wildcard imports.
- Keep static imports separate.
- Do not create import-only churn.
- Use constructor injection.
- Prefer immutable state and records for bounded value/result models.
- Do not use Java native serialization.
- Do not use reflection in ordinary core flows.
- Do not suppress warnings broadly.

## Test Requirements

Add the narrowest tests proving each finding is closed.

Where applicable, include:

- positive path;
- negative path;
- boundary values;
- disabled/no-op behavior;
- state preservation after rejection;
- concurrency or rollback;
- optional classpath;
- architecture/public-surface guard;
- custom source-set migration;
- real E2E coverage.

Do not add compatibility shims only to keep stale tests compiling.

Delete or rewrite tests that protect removed pre-production behavior.

## Verification Order

Run the narrowest affected gates first:

```powershell
.\gradlew.bat :<affected-module>:compileJava --no-daemon
.\gradlew.bat :<affected-module>:compileTestJava --no-daemon
.\gradlew.bat :<affected-module>:test --no-daemon
```

Then, when applicable:

```powershell
.\gradlew.bat :platform-tracing-api:javadoc --warning-mode all --no-daemon
.\gradlew.bat pr1ModuleTaxonomyVerify pr4ArchitectureFitnessVerify --no-daemon
.\gradlew.bat build --warning-mode all --no-daemon
```

For required Docker-backed runtime evidence:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"

.\gradlew.bat :platform-tracing-e2e-tests:test `
  --tests "<required-pattern>" `
  -PrunE2e `
  --rerun-tasks `
  --no-build-cache `
  --no-daemon
```

Verify JUnit reports contain:

```text
tests > 0
skipped = 0
failures = 0
errors = 0
```

A skipped required E2E is `INSUFFICIENT_EVIDENCE`, not `PASS`.

## Static Checks

Run checks relevant to the findings:

- removed or legacy symbols;
- forbidden dependency directions;
- public implementation helpers;
- wildcard imports;
- BOM;
- stale Javadoc links;
- obsolete ServiceLoader descriptors;
- trust-all TLS;
- Java native serialization;
- hard-coded credentials/endpoints;
- stale current documentation.

Avoid broad regexes that generate known false positives.

## Git Policy

Do not commit, push, create a PR, or modify remote state unless the user explicitly requests it.

Before finishing, report:

- branch;
- commit;
- working-tree status;
- modified and untracked files;
- whether all fixes are committed;
- whether the remote branch contains them.

## Required Final Report

```markdown
# Remediation Report

## 1. Verdict

PASS / PASS_WITH_WARNINGS / INSUFFICIENT_EVIDENCE / FAIL

## 2. Scope

- branch:
- commit:
- authoritative audit:
- findings addressed:
- modules:

## 3. Finding Closure

| Finding | Status | Root Cause | Change | Evidence |
|---|---|---|---|---|

Allowed statuses:

- CLOSED
- PARTIALLY_CLOSED
- OPEN
- FALSE_POSITIVE
- INSUFFICIENT_EVIDENCE

## 4. Files Changed

## 5. Public API / SPI Impact

## 6. Runtime and State Safety

## 7. Security and Privacy

## 8. Tests and Verification

| Command | Result | Tests | Skipped | Notes |
|---|---|---:|---:|---|

## 9. Architecture Fitness

## 10. Git State

## 11. Residual Risks

## 12. Merge Readiness
```

Do not claim completion when a required finding or gate remains unverified.
