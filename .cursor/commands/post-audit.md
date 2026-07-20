# Post Audit

Perform an independent evidence-based audit of the artifact, branch, implementation, plan execution, or change identified in the current user request.

## Mandatory Template

Before auditing, read:

`@.cursor/templates/post-audit-report.md`

Use that template as the mandatory final output structure.

Do not modify the template. Mark non-applicable sections explicitly.

## Mode

Read-only.

Do not modify files, commit, push, or create a PR.

## Audit Standard

Treat:

- implementation summaries
- previous assistant reports
- PR descriptions
- plan completion claims
- comments in code

as claims, not evidence.

Verify the repository directly.

## Preflight

Record:

```text
Repository:
Branch:
Commit:
Base:
Working-tree state:
Audit subject:
Authoritative references:
```

Inspect Git status, current diff, module graph, public surface, tests, custom source sets, E2E, docs, and architecture gates.

## Audit Areas

Review as applicable:

- scope and plan compliance
- architecture ownership
- dependency direction
- public API/SPI
- classloader boundaries
- runtime and state safety
- control protocol
- Spring and properties
- security and privacy
- observability/cardinality
- Gradle/publication
- tests and E2E
- documentation/ADR
- Git and remote state

## Evidence Classification

Use:

- `VERIFIED`
- `PARTIALLY_VERIFIED`
- `INSUFFICIENT_EVIDENCE`
- `FALSE_POSITIVE`
- `HISTORICAL_ONLY`

## Finding Severity

Use:

- `P0`
- `P1`
- `P2`
- false positive
- insufficient evidence

Every finding must identify:

- exact evidence
- impact
- required change
- verification command

## Verification

Run only read-only verification tasks unless the user explicitly requests fixes.

A skipped required E2E is `INSUFFICIENT_EVIDENCE`.

Do not report `PASS` when fixes exist only in an uncommitted working tree but the reviewed branch does not contain them.

## Output

Return only the completed structure from:

`@.cursor/templates/post-audit-report.md`

When required fixes remain, reference `.cursor/templates/codex-remediation-prompt.md` for the remediation prompt section.
