# Prepare Pull Request Description

Prepare a professional pull request description from the current branch, diff, commits, and executed verification.

## Mandatory Template

Before writing, read:

`@.cursor/templates/pr-description.md`

Use that template as the mandatory output structure.

Do not modify the template. Remove no sections silently; mark non-applicable sections explicitly.

## Mode

Read-only.

Do not modify files, commit, push, create the PR, or change remote state.

## Evidence Sources

Use only:

- current Git branch and commit history
- current diff
- authoritative plan and ADR
- actual implementation
- executed command results
- JUnit reports
- current documentation

Do not rely on unverified implementation summaries.

## Required Analysis

Determine:

- problem solved
- why the change is non-cosmetic
- architecture decision
- files/modules changed
- public API/SPI changes
- breaking changes and migration
- default behavior
- runtime/state safety
- security/privacy
- observability
- dependencies/publication
- tests and E2E
- operational impact
- residual risks
- reviewer focus

## Accuracy Rules

Do not claim:

- backward compatibility when old APIs were removed
- E2E PASS when tests were skipped
- remote branch state without verification
- production behavior from unit tests alone
- zero residual risk without evidence

Clearly identify intentional pre-production breaking changes.

## Output

Return only the completed structure from:

`@.cursor/templates/pr-description.md`
