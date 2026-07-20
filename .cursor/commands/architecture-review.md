# Architecture Review

Perform an evidence-based architecture review of the component, plan, branch, or change identified in the current user request.

## Mandatory Template

Before analysis, read:

`@.cursor/templates/architecture-review-report.md`

Use that template as the mandatory final output structure.

Do not modify the template. Do not omit sections silently. Mark non-applicable sections as `Not applicable` and explain why.

## Mode

Read-only.

Do not modify files, commit, push, create a PR, or change remote state.

## Required Context

Read before drawing conclusions:

- `@.cursor/project-context.md`
- applicable `.cursor/rules`
- relevant `.cursor/skills`
- current ADRs and architecture documentation
- the authoritative plan or decision referenced by the user

Inspect the current repository state rather than relying on previous agent reports.

## Repository Preflight

Verify:

```text
Repository:
Branch:
Commit:
Base:
Working-tree state:
Untracked files:
Review scope:
Authoritative inputs:
```

Inspect:

- `git status --short --branch`
- recent commits
- current diff or branch diff
- `settings.gradle` and relevant module build files
- production call sites
- tests and custom source sets
- architecture fitness rules
- current documentation

## Review Priorities

Review in this order:

1. correctness
2. security and privacy
3. runtime-state safety
4. module and classloader boundaries
5. public API and SPI integrity
6. dependency and publication correctness
7. executable verification
8. operability
9. performance
10. compatibility with current pre-production behavior

## Required Architecture Analysis

Verify:

- API/Core/Spring/WebMVC/WebFlux/OTel/JMX ownership
- allowed and forbidden dependency directions
- public-surface classification
- classloader-neutral wire boundaries
- control-protocol ownership
- runtime mutation defaults and state invariants
- servlet/reactive isolation
- starter thinness
- external type leakage
- hidden side effects
- false SPI, ServiceLoader, holders, or registries
- no-op and disabled behavior
- security, PII, and cardinality
- testability and architecture fitness

## Pre-Production Policy

The tracing solution is pre-production.

Breaking changes are allowed when materially justified.

Do not recommend compatibility aliases, deprecated bridges, forwarding modules, duplicate bean names, dual property bindings, or dual old/new execution paths by default.

Do not approve arbitrary churn merely because breaking changes are allowed.

## Alternatives

When an architecture decision is still open:

- provide at least two materially different alternatives
- identify trade-offs
- score them against explicit criteria
- recommend one option
- state what evidence could falsify the recommendation

Do not present one preselected design as an exhaustive alternatives analysis.

## Evidence Standard

Classify material claims as:

- `VERIFIED`
- `PARTIALLY_VERIFIED`
- `INSUFFICIENT_EVIDENCE`
- `FALSE_POSITIVE`
- `HISTORICAL_ONLY`

Cite exact files, classes, methods, Gradle edges, tests, commands, and results.

## Findings

Classify findings as:

- `P0` — merge/release blocker
- `P1` — must fix before merge
- `P2` — should fix before release
- false positive
- insufficient evidence

Every finding must include evidence, impact, required change, and verification.

## Output

Return only the completed structure from:

`@.cursor/templates/architecture-review-report.md`

Do not invent a different report format.
