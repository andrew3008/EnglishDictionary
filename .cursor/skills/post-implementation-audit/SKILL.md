---
name: post-implementation-audit
description: Performs rigorous evidence-based post-implementation audits for the enterprise Platform Tracing repository. Use when Codex must review generated or large-scale changes, verify implementation claims against actual code and executed tests, audit closure of prior findings, assess architecture, API, runtime, security, Spring, Gradle, dependency, concurrency, performance, or E2E risks, assign P0/P1/P2 severity, and produce an explicit merge verdict and remediation prompt.
---

# Post-Implementation Audit

## Objective

Audit implemented Platform Tracing changes against repository evidence, approved architecture, runtime safety, and production-readiness requirements.

Treat implementation reports, plans, comments, and model summaries as claims rather than proof.

The solution is pre-production. Do not demand compatibility shims, aliases, deprecated bridges, or obsolete execution paths unless an approved requirement needs them. Breaking changes are acceptable when they materially improve correctness, architecture, security, operability, or public API quality.

Do not accept cosmetic refactoring as completion.

## Default behavior

Perform a read-only audit unless the user explicitly authorizes fixes.

If fixes are authorized:

1. complete and report the audit first
2. preserve the evidence and finding identifiers
3. implement only approved or clearly in-scope fixes
4. rerun the affected verification
5. perform a post-fix audit focused on finding closure
6. report any new regression separately

Preserve unrelated user changes. Do not commit or push unless explicitly requested.

## Review priority

Review concerns in this order:

1. correctness
2. security and privacy
3. runtime-state safety
4. module and classloader boundaries
5. public API and SPI integrity
6. control-plane safety
7. dependency and publication correctness
8. executed test and E2E evidence
9. operator diagnostics
10. reactive and concurrency correctness
11. performance and allocation behavior
12. maintainability
13. developer ergonomics
14. compatibility with pre-production behavior
15. formatting and cosmetic style

## Core workflow

1. Read applicable repository instructions, authoritative plans, ADRs, and prior audits.
2. Select the review mode explicitly.
3. Inspect branch, commit, working tree, untracked files, and the complete relevant diff.
4. Establish the intended decision and do-not-touch scope.
5. Inventory affected modules, source sets, public contracts, runtime paths, build logic, publications, and documentation.
6. Load the applicable reference files.
7. Verify every material implementation claim against source or executable evidence.
8. Trace success, disabled, degraded, invalid, failure, shutdown, and rollback paths.
9. Inspect all callers and consumers of changed contracts.
10. Compile main, test, custom, generated, benchmark, and E2E source sets where affected.
11. Run narrow verification first, then the required broader gates.
12. Confirm that opt-in E2E actually executed with skipped=0.
13. Classify every material claim and finding.
14. Assign a merge verdict without converting missing evidence into PASS.
15. Produce an architect-facing summary and a remediation prompt when fixes remain.

## Review modes

Select one or combine only when the change requires it:

- **Post-implementation audit:** default for generated or large-scale implementation; verify actual files, callers, public surface, Gradle results, E2E execution, Git state, and docs.
- **Post-fix audit:** verify closure of known findings; do not reopen the full architecture without evidence of a regression.
- **Architecture review:** use for module ownership, public API/SPI, package taxonomy, classloader boundaries, protocols, starter responsibilities, or production defaults.
- **Implementation review:** use when architecture is approved; focus on correctness, plan compliance, tests, diagnostics, and regression risk.
- **Security review:** use for trust boundaries, PII, mutation, exporters, propagation, credentials, or privileged control.
- **Performance review:** use for hot paths, startup, allocation, queues, sampling, scrubbing, or build performance.

## Evidence classification

Classify material claims as exactly one of:

- `VERIFIED`
- `PARTIALLY_VERIFIED`
- `INSUFFICIENT_EVIDENCE`
- `FALSE_POSITIVE`
- `HISTORICAL_ONLY`

Do not claim:

- passed tests when they were skipped
- runtime behavior from unit tests alone
- absence of callers without repository-wide search
- compatibility without consumer evidence
- mitigated threats solely from documentation
- a merge-ready branch when fixes exist only in an uncommitted working tree

## Finding severity

Use:

- **P0:** release or merge blocker, including compilation failure, required gate failure, secret/PII leak, state corruption, unsafe classloader crossing, invalid publication, or silently skipped mandatory E2E.
- **P1:** must fix before merge, including missing invariants, unsafe defaults, stale live consumers, duplicate implementations, runtime traps, or missing negative tests for privileged behavior.
- **P2:** should fix before release, including documentation drift, weak diagnostics, missing golden tests, classified non-blocking warnings, or bounded operational gaps.
- **False positive:** scan/review result that does not represent the intended behavior.
- **Insufficient evidence:** required environment, artifact, branch, or runtime execution is unavailable.

## Mandatory review invariants

- Review all affected source sets, not only `main`.
- Verify actual dependency direction and publication metadata.
- Treat classloader, process, wire, and JMX boundaries as explicit protocols.
- Review both automatic and manual instrumentation paths.
- Trace accepted and rejected control mutations.
- Review privacy, cardinality, diagnostics, and exporter behavior.
- Verify Spring conditions, defaults, bean ownership, and disabled/no-op semantics.
- Verify reactive context propagation and cross-request isolation.
- Inspect lifecycle, shutdown, ownership, and cleanup.
- Require runtime E2E when behavior crosses Agent/application, JMX, collector, servlet/reactive, Kafka/database, control, sampling, or scrubbing boundaries.
- Do not weaken architecture gates to make an implementation pass.
- Keep findings evidence-backed, scoped, prioritized, and actionable.

## Reference selection

Read only references relevant to the audit, except for the required combinations below.

### Foundations, scope, evidence, and severity

Read [review-foundations.md](references/review-foundations.md).

Use it to select review mode, establish evidence standards, classify severity, control scope, and inspect Git state.

### Architecture and public API

Read [architecture-and-api-review.md](references/architecture-and-api-review.md).

Use it for modules, packages, dependency direction, public API/SPI, naming, compatibility, surface diffs, and ServiceLoader.

### Tracing runtime, control, propagation, and security

Read [tracing-runtime-and-security.md](references/tracing-runtime-and-security.md).

Use it for control protocols, runtime mutation, tracing correctness, automatic/manual instrumentation, propagation, sampling, scrubbing, metrics, logging, and security.

### Spring, Gradle, dependencies, and Javadoc

Read [spring-build-and-dependencies.md](references/spring-build-and-dependencies.md).

Use it for auto-configuration, conditions, properties, dependency scopes, publication behavior, Gradle source sets, and Javadoc classpaths.

### Concurrency, reactive, performance, tests, and E2E

Read [concurrency-performance-and-testing.md](references/concurrency-performance-and-testing.md).

Use it for threading, Reactor context, hot paths, startup, build performance, tests, Testcontainers, Docker-backed E2E, and skip detection.

### Implementation quality and migration completeness

Read [implementation-quality.md](references/implementation-quality.md).

Use it for ADR/docs, warning registers, generated code, imports, scans, error handling, reflection, abstractions, side effects, defaults, disabled/no-op behavior, and migration completeness.

### Verification, verdict, and report

Read [verification-and-reporting.md](references/verification-and-reporting.md).

Use it for verification commands, PASS/FAIL criteria, required report structure, architect summary, remediation prompts, anti-patterns, and the final checklist.

## Required reference combinations

For every post-implementation audit, read:

1. `review-foundations.md`
2. `verification-and-reporting.md`
3. every domain reference touched by the diff

For an architecture-affecting implementation, also read:

1. `architecture-and-api-review.md`
2. `implementation-quality.md`

For runtime tracing behavior, also read:

1. `tracing-runtime-and-security.md`
2. `concurrency-performance-and-testing.md`
3. `spring-build-and-dependencies.md` when Spring participates

## Completion standard

Do not report completion until:

- branch, commit, base, and working-tree state are explicit
- review mode and scope are explicit
- affected public surface and runtime paths are reviewed
- implementation claims are classified
- required commands actually execute
- mandatory E2E is not skipped
- findings include severity, evidence, consequence, and required fix
- residual risks and external dependencies are explicit
- the merge verdict is one of `PASS`, `PASS_WITH_WARNINGS`, `FAIL`, or `INSUFFICIENT_EVIDENCE`
- the final report follows the required structure
- a focused remediation prompt is included when fixes remain

