# Prepare Codex Remediation Prompt

Create a precise Codex remediation prompt for the unresolved findings identified in the current user request or referenced audit.

## Mandatory Template

Before writing, read:

`@.cursor/templates/codex-remediation-prompt.md`

Use that template as the mandatory output structure.

Do not modify the template.

## Mode

Read-only.

Do not implement fixes, modify files, commit, push, or create a PR.

## Required Inputs

Read:

- the authoritative audit
- the approved plan
- relevant ADRs
- current repository state
- affected files and call sites
- applicable rules and skills
- previous verification results

Treat the audit finding as a hypothesis until verified against the current branch.

## Prompt Requirements

The resulting Codex prompt must:

- identify exact branch/base
- identify authoritative plan/audit
- list findings by ID and severity
- state root causes
- define exact required changes
- identify files/modules
- define do-not-touch constraints
- forbid compatibility aliases by default
- preserve approved architecture
- require negative/regression tests
- require exact verification commands
- require actual E2E when runtime boundaries are affected
- forbid commit/push unless explicitly requested
- require a structured final report

Do not include already closed findings.

Do not ask Codex to redesign accepted architecture unless the audit proves it unsafe or contradictory.

## Evidence

Separate:

- verified facts
- assumptions
- missing evidence
- external environment constraints

## Output

Return only the completed structure from:

`@.cursor/templates/codex-remediation-prompt.md`
