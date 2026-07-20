# Prepare Refactoring Plan

Prepare an implementation-ready refactoring plan for the component or problem identified in the current user request.

## Mandatory Template

Before analysis, read:

`@.cursor/templates/refactoring-plan.md`

Use that template as the mandatory final output structure.

Do not modify the template. Complete every applicable section. Mark non-applicable sections explicitly.

## Mode

Read-only.

Do not modify production code, tests, build files, documentation, Git state, or remote state.

## Required Context

Read:

- `@.cursor/project-context.md`
- applicable `.cursor/rules`
- relevant `.cursor/skills`
- current ADRs
- current architecture documentation
- referenced research, audit, or decision files

Treat historical plans as evidence of previous thinking, not automatically as current truth.

## Repository Investigation

Before planning:

1. inspect branch, commit, base, and working tree
2. locate every production caller and implementation
3. locate Spring wiring and properties
4. inspect module dependencies and publication scopes
5. inspect tests, custom source sets, samples, benchmarks, and E2E
6. inspect JMX/agent/classloader paths
7. inspect architecture gates and static scans
8. distinguish current documentation from superseded material

Do not plan against guessed call sites or assumed module ownership.

## Plan Quality

The plan must be:

- non-cosmetic
- implementation-ready
- sliced into reviewable steps
- explicit about ownership
- explicit about public API changes
- explicit about failure and state semantics
- explicit about migration and deletion
- explicit about tests and E2E
- explicit about residual risks

Do not use vague actions such as:

- improve architecture
- refactor for clarity
- add tests
- clean up code
- make robust

Name exact types, packages, modules, contracts, and gates.

## Pre-Production Policy

Breaking changes are allowed when materially justified.

Default approach:

1. define the intended final contract
2. migrate all repository consumers
3. delete the old path
4. update tests, custom source sets, samples, docs, metadata, and CI
5. add negative and architecture guards
6. do not add compatibility aliases by default

Do not use pre-production status to justify unrelated redesign.

## Required Decisions

The plan must define:

- target module ownership
- target package taxonomy
- public API/SPI diff
- dependency edges added and removed
- third-party type exposure
- runtime/classloader boundary
- control-protocol flow when relevant
- state ownership and LKG behavior
- failure semantics
- security and PII implications
- observability/cardinality implications
- no-op/disabled behavior
- rollout and rollback

## Alternatives

Evaluate materially different alternatives before selecting the target.

Do not score cosmetic variations as separate architectures.

## Verification

Include exact commands for:

- production compilation
- test compilation
- focused tests
- Javadoc
- architecture fitness
- module taxonomy
- dependency reports
- publication verification
- full build
- targeted E2E

Required E2E must satisfy:

```text
tests > 0
skipped = 0
failures = 0
errors = 0
```

## Output

Return only the completed structure from:

`@.cursor/templates/refactoring-plan.md`

Do not start implementation.
