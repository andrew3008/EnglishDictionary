# Review Foundations

## Context

This repository contains an enterprise platform tracing solution for Spring Boot servlet and reactive microservices.

The solution includes:

- `platform-tracing-api`
- `platform-tracing-otel`
- Spring Boot auto-configuration
- servlet and WebFlux adapters
- starters
- OpenTelemetry agent/SDK integration
- sampling and scrubbing policies
- runtime control
- JMX/OpenMBean integration
- collector configuration
- architecture fitness rules
- benchmarks and Docker-backed E2E tests

The solution is currently **pre-production**.

Breaking source, binary, package, bean, configuration, wire, SPI, and module changes are allowed when they materially improve:

- correctness
- architecture
- runtime safety
- public API quality
- dependency governance
- privacy
- operability
- testability
- production readiness

Backward compatibility with the current pre-production implementation is **not** a primary review goal.

Do not preserve accidental APIs, aliases, deprecated bridges, dual execution paths, obsolete package structures, unsafe defaults, or stale tests merely because they already exist.

Architects will not accept cosmetic refactoring. A review must distinguish:

- material architectural improvement
- production hardening
- necessary cleanup
- cosmetic churn
- compatibility-only preservation

## Review Mission

The purpose of code review is to verify that a change:

- solves the stated problem
- preserves or improves architecture
- does not create hidden production risk
- has a clear owner and boundary
- provides sufficient executable evidence
- keeps public surface intentional
- remains operable by platform and service teams
- does not hide uncertainty

A review is not complete when the code merely compiles.

A reviewer must be able to answer:

```text
What problem is solved?
Why is this change needed?
Why does this layer own it?
What failure mode is introduced?
What proves the behavior?
What remains unverified?
What is the merge recommendation?
```

## Review Priority

When concerns conflict, review in this order:

1. correctness
2. security and privacy
3. runtime-state safety
4. module and classloader boundaries
5. public API integrity
6. control-plane safety
7. dependency and publication correctness
8. test and E2E evidence
9. operator diagnostics
10. reactive/concurrency correctness
11. performance and allocation behavior
12. maintainability
13. developer ergonomics
14. compatibility with pre-production behavior
15. formatting and cosmetic style

Do not block an architecturally justified breaking change merely because migration requires updating repository callers.

Do block a compatibility shim that preserves an accidental or unsafe API without an approved migration requirement.

## Review Modes

Choose the review mode explicitly.

### Architecture review

Use when a change affects:

- module ownership
- public API/SPI
- package taxonomy
- runtime control
- JMX/classloader boundaries
- dependency contracts
- wire protocols
- Spring starter responsibilities
- production defaults

Output must include:

- decision
- alternatives
- trade-offs
- module boundary
- public surface impact
- ADR requirements
- fitness rules

### Implementation review

Use when architecture is already approved.

Do not reopen accepted decisions unless implementation evidence reveals a contradiction or serious production risk.

Focus on:

- plan compliance
- correctness
- missing cases
- tests
- diagnostics
- regression risk
- code quality

### Post-implementation audit

Use after generated or large-scale implementation.

Treat implementation reports as claims, not evidence.

Verify:

- actual files
- actual call sites
- actual public surface
- actual Gradle results
- actual E2E execution
- actual Git state
- actual docs

### Post-fix audit

Review only closure of known findings unless a fix created an architectural regression.

Do not repeat a full architecture review unnecessarily.

### Security review

Use when trust boundaries, PII, mutation, exporter endpoints, propagation, or credentials are affected.

### Performance review

Use when hot paths, startup, allocation, exporter queues, sampling, or scrubbing performance changes.

Do not use one noisy benchmark as the only acceptance evidence.

## Evidence Standard

Every material claim must be classified as one of:

- `VERIFIED`
- `PARTIALLY_VERIFIED`
- `INSUFFICIENT_EVIDENCE`
- `FALSE_POSITIVE`
- `HISTORICAL_ONLY`

Examples:

```text
VERIFIED:
The test executed with skipped=0 and passed.

INSUFFICIENT_EVIDENCE:
The test source compiled, but the opt-in E2E task was skipped.

FALSE_POSITIVE:
The regex matched `network.protocol.version`, not the removed
`api.control.protocol.version` package.
```

Do not claim:

- tests passed when they were skipped
- PR metadata was verified when GitHub access was unavailable
- runtime behavior was verified from unit tests only
- no callers exist without a repository-wide search
- compatibility is preserved without consumer evidence
- a threat is mitigated solely because documentation says so

Use exact evidence where practical:

- file path
- class/method
- source line
- command
- result
- test count
- commit
- branch
- artifact metadata

## Finding Severity

Use the following severity model.

### P0 — Release / merge blocker

Examples:

- production code does not compile
- test source sets do not compile
- required build or architecture gate fails
- public API exposes an unsafe implementation detail
- invalid control payload can reach apply
- rejected mutation changes state
- PII or secret leak
- required E2E fails or is silently skipped
- incompatible classloader type crosses the boundary
- publication metadata is wrong
- data corruption or partial apply is possible

### P1 — Must fix before merge

Examples:

- missing domain invariant
- unsafe default
- missing negative test for a privileged path
- stale consumer still uses removed API
- duplicate live implementation path
- false SPI / ServiceLoader holder
- missing rollback
- warning register contradicts implementation
- public helper lacks governance
- dependency scope creates runtime trap

### P2 — Should fix before release

Examples:

- documentation drift
- missing golden test
- weak diagnostics
- javadoc warning
- known non-blocking operational gap
- test naming causing scan false positives
- missing bounded metric/documentation detail

### False positive

A scan or review finding that does not represent the intended architecture or code behavior.

Narrow the rule or document the allowed context.

Do not rename unrelated domain concepts solely to satisfy a bad regex unless the rename also improves clarity.

### Insufficient evidence

Use when environment/tooling/runtime execution is unavailable.

Do not convert insufficient evidence into PASS.

## Scope Discipline

A review must protect scope without defending architectural debt.

Reject unrelated changes when they:

- enlarge risk
- make review harder
- mix independent decisions
- change unrelated public contracts
- hide generated files
- add broad formatting/import churn
- modify unrelated baselines
- combine architecture and rollout docs without reason

Allow adjacent fixes when they are necessary to:

- compile the affected module
- remove stale consumers of the changed API
- close a discovered production blocker
- make required verification executable
- repair a dependency/Javadoc classpath contract
- remove a false-positive scan that blocks architecture gates

Any adjacent change must be called out explicitly.

## Git and Change-Scope Review

Before reviewing implementation, inspect:

```powershell
git status --short --branch
git log --oneline -10
git diff --stat
git diff --check
```

For a branch/PR review, inspect:

```powershell
git diff <base>...HEAD --stat
git diff <base>...HEAD
```

Verify:

- correct branch
- clean or intentionally dirty working tree
- untracked artifacts
- unrelated user changes
- generated baseline files
- commit cohesion
- whether remote tip contains the audited fixes

Do not declare a PR merge-ready if fixes exist only in an uncommitted local working tree.

