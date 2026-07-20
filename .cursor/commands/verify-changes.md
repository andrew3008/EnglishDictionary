# Verify Changes

Determine and execute the narrowest sufficient verification matrix for the current change.

## Mandatory Template

Before verification, read:

`@.cursor/templates/verification-report.md`

Use that template as the mandatory final output structure.

Do not modify the template.

## Mode

Verification-first.

Do not change production behavior merely to make a gate pass.

Do not commit, push, or create a PR.

When verification exposes a defect, report it. Do not fix it unless the user explicitly requests remediation.

## Preflight

Inspect:

- branch and commit
- working-tree status
- current diff
- affected modules
- public API changes
- dependency changes
- runtime boundaries
- tests and custom source sets
- required architecture gates
- E2E requirements

## Build the Verification Matrix

Determine whether each is required:

- production compile
- test compile
- unit tests
- Spring tests
- architecture fitness
- module taxonomy
- Javadoc
- dependency reports
- publication metadata
- full build
- static scans
- Docker-backed E2E

Explain why a gate is required or not applicable.

## Execution Order

Run narrow tasks before broad tasks.

Use exact commands and record:

- executed/not executed
- exit result
- tests
- skipped
- failures
- errors
- warnings
- evidence produced

Do not rely only on console `BUILD SUCCESSFUL`.

## Required Semantics

A required skipped test is `INSUFFICIENT_EVIDENCE`.

A warning must be classified by root cause and blocking impact.

A false-positive static scan must be documented and the rule/pattern identified.

Do not claim publication, configuration-cache, or E2E correctness without executing the relevant verification.

## Output

Return only the completed structure from:

`@.cursor/templates/verification-report.md`
