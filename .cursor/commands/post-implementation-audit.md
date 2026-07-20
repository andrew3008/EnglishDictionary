# Post-Implementation Audit

Perform an independent post-implementation audit against the authoritative approved plan.

## Mandatory Templates

Before auditing, read:

- `@.cursor/templates/post-implementation-audit.md`
- `@.cursor/templates/codex-remediation-prompt.md`

Use `post-implementation-audit.md` as the mandatory final report structure.

Use `codex-remediation-prompt.md` only when unresolved findings require a remediation prompt.

Do not modify either template.

## Mode

Read-only.

Do not modify files, commit, push, create a PR, or change remote state.

## Authority

Use:

1. current user request
2. authoritative plan
3. accepted ADRs
4. current repository facts
5. applicable rules and skills

Treat the implementation report as claims, not evidence.

## Preflight

Verify:

```text
Branch:
Commit:
Base:
Working-tree status:
Implementation commits:
Authoritative plan:
Modules/files:
Required runtime boundaries:
```

Confirm whether the audited fixes are committed and present on the reviewed branch.

## Plan Compliance

Build a requirement-by-requirement matrix.

Verify:

- all implementation slices
- all deletions
- all migrations
- tests and custom source sets
- docs and metadata
- architecture guards
- required E2E
- do-not-touch constraints

## Required Review

Audit:

- API/Core/Spring/WebMVC/WebFlux/OTel/JMX ownership
- exact public API and SPI surface
- module and dependency graph
- classloader-safe wire contracts
- control-protocol pipeline
- state atomicity, rejection, rollback, and LKG
- mutation default
- security, PII, and cardinality
- optional classpath and starter behavior
- Gradle scopes and publication metadata
- Javadoc
- architecture fitness
- E2E reports and test counts
- docs, ADRs, and warning register

## Evidence and Findings

Classify evidence and findings according to the template.

Do not accept:

- compiled but skipped E2E
- uncommitted local fixes as branch evidence
- successful unit tests as proof of classloader/JMX runtime behavior
- documentation claims without executable evidence

## Remediation Prompt

When P0/P1/P2 fixes remain, complete the remediation section using:

`@.cursor/templates/codex-remediation-prompt.md`

The prompt must be narrow, finding-specific, and include exact verification commands.

## Output

Return only the completed structure from:

`@.cursor/templates/post-implementation-audit.md`
