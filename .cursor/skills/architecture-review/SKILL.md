---
name: architecture-review
description: Performs decision-oriented architecture reviews for the enterprise Platform Tracing repository. Use when Codex evaluates or changes module topology, ownership, dependency direction, package taxonomy, public API/SPI, classloader or process boundaries, runtime control, Spring Boot versus OpenTelemetry responsibilities, state and failure models, deployment topology, new modules, module renames, ADRs, architecture fitness functions, or a refactoring plan with system-wide consequences.
---

# Platform Tracing Architecture Review

## Objective

Produce evidence-based architecture decisions that give Platform Tracing clear ownership, safe runtime behavior, minimal intentional public surface, enforceable boundaries, and a simple production mental model.

This is the highest-level architecture skill for the repository. Use specialized skills for implementation details inside approved boundaries.

The solution is pre-production. Breaking source, binary, package, module, bean, configuration, SPI, wire, publication, and build changes are allowed when they materially improve correctness, architecture, security, operability, testability, or adoption.

Do not preserve accidental APIs, obsolete packages, aliases, deprecated bridges, false extension points, duplicate implementations, unsafe defaults, or stale integration paths without an approved requirement.

Architects will not accept cosmetic refactoring. Every substantial decision must solve a concrete architecture or production problem, identify the correct owner, reduce coupling or risk, make invalid states harder to represent, and add executable enforcement.

## Skill coordination

Use the relevant specialized skills together with this one:

- cross-cutting implementation: `backend-engineering`
- API and SPI design: `platform-tracing-api-standards`
- Spring Boot composition: `spring-boot`
- security and privacy: `security-review`
- telemetry semantics: `observability`
- testing strategy: `testing`
- packaged/container E2E: `testcontainers`
- Gradle/modules/publications: `gradle`
- Redis/KeyDB: `redis`
- final evidence audit: `post-implementation-audit`

A specialized skill may refine implementation but must not silently override an approved module, ownership, classloader, security, or pre-production decision.

## Default behavior

Perform a read-only architecture review unless the user explicitly requests implementation or plan correction.

Do not change an authoritative plan or ADR merely to match current code. First determine whether the code or the decision is wrong.

Treat plans, ADRs, diagrams, model reports, implementation summaries, bean presence, MBean presence, and passing unit tests as evidence inputs rather than unquestionable facts.

## Priority

When concerns conflict, prefer:

1. correctness
2. security and privacy
3. runtime-state safety
4. module, process, and classloader boundaries
5. public API and SPI integrity
6. deterministic behavior
7. dependency and publication correctness
8. executable verification
9. operator diagnostics
10. bounded telemetry and resources
11. reactive and concurrency correctness
12. startup and hot-path performance
13. maintainability
14. developer ergonomics
15. compatibility with pre-production behavior

## Review workflow

1. Read repository instructions, authoritative plans, ADRs, previous analyses, and relevant specialized skills.
2. Inspect current repository facts, Git state, module graph, dependencies, packages, publications, runtime modes, and tests.
3. State the concrete architectural problem without embedding a preferred solution.
4. Separate verified facts, assumptions, constraints, preferences, and unresolved external evidence.
5. Identify actors, consumers, owners, trust boundaries, classloaders, processes, lifecycle, and operational responsibilities.
6. Load the applicable files from `references/`.
7. Enumerate materially distinct alternatives, including no-change and removal/simplification options.
8. Steelman the strongest alternative to the initially preferred design.
9. Compare alternatives using explicit criteria, weights, sensitivity analysis, and falsifiers where the decision is significant.
10. Trace success, disabled, unavailable, incompatible, invalid, degraded, failed, concurrent, shutdown, recovery, rollout, and rollback states.
11. Evaluate dependency direction, public surface, module count, adoption cost, fleet applicability, security, operability, and testability.
12. Identify decision-blocking spikes and implement executable evidence only when authorized.
13. Select a decision, conditional decision, or `INSUFFICIENT_EVIDENCE`.
14. Define module/package/API delta, migration slices, checkpoints, fitness functions, tests, and rollback.
15. Reconcile the authoritative plan and ADR only after the evidence supports the decision.
16. Produce an architect-facing report with rejected alternatives and remaining risks.

## Decision quality requirements

For every material architecture decision, state:

- problem and decision scope
- current verified topology
- constraints and non-goals
- alternatives considered
- decision criteria and weights
- trade-offs
- strongest counterargument
- decision-blocking evidence
- chosen owner and dependency direction
- public API/SPI impact
- runtime and failure-state consequences
- security and operability consequences
- rollout and rollback
- fitness functions and verification
- residual risks
- exact next checkpoint

Do not use scoring to disguise unsupported assumptions. If a high-weight criterion depends on missing evidence, run a spike or mark the decision blocked.

## Mandatory architecture invariants

- Give every capability one explicit owner.
- Keep dependency direction acyclic and enforceable.
- Keep application-facing API independent of implementation, Spring, OpenTelemetry SDK/Agent, JMX, transport, and deployment types.
- Create a module only for a real dependency, publication, lifecycle, classloader, security, or ownership boundary.
- Do not retain a module or package solely because teams are familiar with its name.
- Keep Java package taxonomy aligned with the approved module architecture.
- Classify every public type as API, SPI, wire contract, internal bridge, or implementation.
- Keep cross-process and cross-classloader protocols neutral, versioned, validated, and testable.
- Do not inject or cast Agent-side objects across the application classloader boundary.
- Separate application integration from Agent/SDK ownership according to the approved composition model.
- Separate structural decoding, domain validation, policy, and state application.
- Make runtime state transitions atomic and observable.
- Distinguish disabled, unavailable, incompatible, failed, degraded, and insecure states.
- Do not treat application NoOp as proof that Agent/export behavior stopped.
- Preserve context safely across servlet, reactive, messaging, and async paths.
- Keep security/compliance enforcement stronger than availability preferences.
- Avoid distributed infrastructure unless the capability truly requires distributed state.
- Keep starters simple for service teams without hiding runtime ownership.
- Encode important boundaries with architecture fitness functions.
- Require packaged evidence for Agent, classloader, publication, and deployment claims.
- Do not weaken gates or regenerate baselines to hide violations.

## Reference selection

Read only references relevant to the decision, except for required combinations.

### Foundations, decision quality, facts, and ADRs

Read [foundations-decisions-and-adrs.md](references/foundations-decisions-and-adrs.md).

Use it for repository discovery, mission, principles, decision records, evidence, and alternative analysis.

### Modules, dependency direction, creation, removal, and renaming

Read [modules-dependencies-and-renaming.md](references/modules-dependencies-and-renaming.md).

Use it for ownership, graph topology, criteria for modules, module naming, migration, and publication impact.

### API, public surface, external types, dependencies, and packages

Read [api-public-surface-and-packages.md](references/api-public-surface-and-packages.md).

Use it for API/core separation, root facades, visibility, SPIs, package taxonomy, and third-party type leakage.

### Classloaders, control protocols, and runtime control

Read [classloaders-control-and-runtime.md](references/classloaders-control-and-runtime.md).

Use it for Agent/application planes, JMX/wire boundaries, decoding, validation, mutation, runtime ownership, and state reconciliation.

### Spring, OpenTelemetry, observability, security, sampling, and scrubbing

Read [spring-otel-observability-and-security.md](references/spring-otel-observability-and-security.md).

Use it for starter versus SDK ownership, automatic/manual instrumentation, signal ownership, privacy, sampling, and policy pipeline placement.

### State, concurrency, side effects, failure, and no-op

Read [state-concurrency-effects-and-failure.md](references/state-concurrency-effects-and-failure.md).

Use it for state machines, lifecycle, atomicity, initialization, failure semantics, degradation, and disabled behavior.

### Distributed systems, Kubernetes, and build architecture

Read [distributed-kubernetes-and-build.md](references/distributed-kubernetes-and-build.md).

Use it for distributed-state applicability, deployment/runtime ownership, platform operations, Gradle modules, and artifacts.

### Testing, fitness functions, docs, risks, and audits

Read [testing-fitness-docs-and-risks.md](references/testing-fitness-docs-and-risks.md).

Use it for test ownership, packaged E2E, architecture enforcement, documentation hierarchy, warning registers, and evidence audits.

### Compatibility, LLM changes, anti-patterns, verification, and reports

Read [compatibility-agents-antipatterns-and-reporting.md](references/compatibility-agents-antipatterns-and-reporting.md).

Use it for pre/post-production policy, generated changes, import discipline, forbidden designs, required commands, and architecture reports.

## Required reference combinations

For every material architecture review, read:

1. `foundations-decisions-and-adrs.md`
2. `testing-fitness-docs-and-risks.md`
3. `compatibility-agents-antipatterns-and-reporting.md`
4. every domain reference touched by the decision

For module topology, also read:

1. `modules-dependencies-and-renaming.md`
2. `api-public-surface-and-packages.md`
3. `distributed-kubernetes-and-build.md`

For runtime ownership, also read:

1. `classloaders-control-and-runtime.md`
2. `spring-otel-observability-and-security.md`
3. `state-concurrency-effects-and-failure.md`

## Completion standard

Do not report completion until:

- repository facts and assumptions are separated
- the problem is stated independently of the preferred solution
- materially distinct alternatives are evaluated
- the strongest counterargument is addressed
- blocking evidence and spikes are explicit
- owner, boundaries, dependencies, public surface, and runtime states are defined
- security, operations, fleet adoption, rollout, and rollback are considered
- architecture decisions have executable fitness functions and tests
- the authoritative plan/ADR status is explicit
- residual risks and external dependencies are recorded
- the exact next checkpoint or implementation slice is identified

