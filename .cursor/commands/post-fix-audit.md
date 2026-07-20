# Post-Fix Audit

Audit closure of the findings from the previous audit.

## Mandatory Templates

Before auditing, read:

- `@.cursor/templates/post-fix-audit.md`
- `@.cursor/templates/codex-remediation-prompt.md`

Use `post-fix-audit.md` as the mandatory final report structure.

Use `codex-remediation-prompt.md` only when findings remain open or partially closed.

Do not modify either template.

## Mode

Read-only.

Do not modify files, commit, push, or create a PR.

## Scope

Focus on previously reported findings.

Do not repeat a full architecture review unless:

- a fix changes the approved architecture
- a new dependency edge appears
- public API changes unexpectedly
- runtime state semantics change
- a new security/privacy risk appears
- verification reveals a broader regression

## Preflight

Verify:

```text
Branch:
Commit:
Base:
Previous audit:
Fix commits:
Working-tree status:
Remote branch state:
```

The audited fixes must be present in the reviewed branch, not only in a local working tree.

## Finding Closure

For each prior finding:

1. confirm the root cause
2. inspect the exact fix
3. verify no legacy/alternate path remains
4. inspect regression coverage
5. run or inspect the required gate
6. classify:

```text
CLOSED
PARTIALLY_CLOSED
OPEN
FALSE_POSITIVE
INSUFFICIENT_EVIDENCE
```

A code diff alone is not closure evidence.

## Regression Checks

Verify no fix introduced:

- compatibility aliases
- deprecated bridges
- dual paths
- accidental public API
- dependency cycles
- Spring/JMX/OTel leakage
- mutation enabled by default
- state changes after rejection
- PII/security regressions
- unrelated generated files
- import-only churn

## E2E

When a finding concerns a runtime boundary, verify actual E2E execution and JUnit counts.

A skipped required E2E remains `INSUFFICIENT_EVIDENCE`.

## Remaining Remediation

When findings remain, generate a focused remediation prompt using:

`@.cursor/templates/codex-remediation-prompt.md`

Do not regenerate instructions for findings already closed.

## Output

Return only the completed structure from:

`@.cursor/templates/post-fix-audit.md`
