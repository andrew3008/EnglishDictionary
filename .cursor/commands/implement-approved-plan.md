# Implement Approved Plan

Implement the authoritative plan referenced in the current user request.

## Mandatory Template

Before editing, read:

`@.cursor/templates/implementation-report.md`

Use that template as the mandatory final report structure.

Do not modify the template. Complete every applicable section.

## Authority

Use, in order:

1. current user request
2. authoritative approved plan
3. accepted ADRs
4. current repository facts
5. applicable `.cursor/rules`
6. relevant `.cursor/skills`

If repository facts materially contradict the plan, stop only the contradictory slice, document the contradiction, and continue all safe independent work.

Do not silently redesign the approved architecture.

## Preflight

Before editing, verify:

```text
Branch:
Commit:
Base:
Working-tree status:
Unrelated user changes:
Authoritative plan:
Affected modules:
Affected public contracts:
Affected tests/custom source sets/E2E:
```

Read the complete plan, including do-not-touch constraints and verification requirements.

## Implementation Policy

The tracing solution is pre-production.

Breaking changes are allowed when required by the approved target design.

Do not add by default:

- compatibility aliases
- deprecated bridges
- forwarding types or modules
- duplicate bean names
- dual property bindings
- dual old/new runtime paths
- speculative SPIs

Migrate all repository consumers and delete obsolete paths.

## Architecture Constraints

Preserve:

- API owns contracts
- core owns runtime, policies, domain validation, and state
- Spring owns wiring, properties, and diagnostics
- servlet and WebFlux remain isolated
- OTel/JMX owns classloader-sensitive adapters
- starters remain thin

For control paths preserve:

```text
wire payload
    -> structural decode
    -> domain validation
    -> mutation policy
    -> apply or read
```

Runtime mutation must remain disabled by default.

Rejected mutation must preserve snapshot, version, source, timestamps, and last-known-good state.

## Scope Discipline

- preserve unrelated changes
- do not perform broad formatting
- do not optimize imports in unrelated files
- do not upgrade Java, Spring, Gradle, or dependencies unless the plan requires it
- do not reorganize build logic incidentally
- do not weaken architecture gates
- do not modify historical documents unless required by the plan

## Java Policy

- follow `.editorconfig`
- explicit imports only
- no wildcard imports
- no import-only churn
- constructor injection
- immutable state
- package-private implementation helpers
- no Java native serialization
- no ordinary-flow reflection
- no static mutable state
- no hidden retries or background threads

## Testing

Implement the tests required by the plan.

Where applicable cover:

- positive behavior
- invalid input
- boundary values
- disabled/no-op behavior
- state preservation
- concurrency
- rollback
- optional classpath
- public-surface guards
- architecture fitness
- real E2E runtime behavior

Do not preserve obsolete APIs only to keep stale tests compiling.

## Verification Order

Run narrow gates first:

```powershell
.\gradlew.bat :<affected-module>:compileJava --no-daemon
.\gradlew.bat :<affected-module>:compileTestJava --no-daemon
.\gradlew.bat :<affected-module>:test --no-daemon
```

Then run applicable:

```powershell
.\gradlew.bat :platform-tracing-api:javadoc --warning-mode all --no-daemon
.\gradlew.bat pr1ModuleTaxonomyVerify pr4ArchitectureFitnessVerify --no-daemon
.\gradlew.bat build --warning-mode all --no-daemon
```

Run required E2E with the configured Docker environment.

Verify JUnit reports, not only Gradle summary.

## Git Policy

Do not commit, push, create a PR, or modify remote state unless the user explicitly requests it.

Do not include unrelated working-tree changes in any commit.

## Output

Return only the completed structure from:

`@.cursor/templates/implementation-report.md`

Report unexecuted gates honestly as `INSUFFICIENT_EVIDENCE`.
