# Testing, Build, and Code Quality

## Testing

Tests must prove intended behavior.

Use:

- JUnit 5
- AssertJ
- Mockito only when necessary
- `ApplicationContextRunner`
- Testcontainers for real boundaries
- Awaitility for asynchronous behavior
- ArchUnit/fitness tasks
- E2E for cross-module/runtime behavior

Avoid:

- `Thread.sleep`
- fixed ports
- fixed localhost
- shared mutable state
- broad `@SpringBootTest`
- copying production logic
- compatibility shims added only for old tests
- probability where deterministic behavior is possible
- skipped E2E reported as PASS

Follow `testing.md` and `testcontainers.md`.

## E2E Evidence

A required E2E gate must actually execute.

For runtime evidence, verify:

```text
tests > 0
skipped = 0
failures = 0
errors = 0
```

A compiled harness is not runtime evidence.

A skipped test is `INSUFFICIENT_EVIDENCE`.

Known remote Docker environment:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
```

Test code must still resolve endpoints through Testcontainers/project helpers.

## Gradle and Build

Before changing Gradle:

- inspect wrapper
- inspect settings/root/module builds
- preserve current DSL
- inspect dependency management
- inspect custom source sets/tasks
- run dependency reports for scope changes

Do not migrate build DSL or build-logic architecture during an unrelated backend task.

Follow `gradle.md`.

## Javadoc

Public Javadoc must compile without actionable source/classpath warnings.

Do not:

- link API docs to core implementation
- link Lombok-generated methods that Javadoc cannot resolve
- suppress doclint globally
- add broad dependencies to silence one warning

Fix the actual source or classpath contract.

## Imports and Formatting

Follow `.editorconfig`.

Use:

- explicit imports
- separate explicit static imports
- deterministic layout

Do not use:

- wildcard imports
- static wildcard imports
- import-only churn
- broad unrelated formatting changes
- locally invented ordering

Generated code must follow the same standard.

## Generated Code and LLM Agents

Cursor, Codex, and Perplexity output must meet the same standard as handwritten code.

Agents must:

- read relevant skills first
- inspect actual repository facts
- avoid invented call sites
- preserve approved architecture
- avoid compatibility aliases by default
- avoid fake SPIs
- avoid duplicate logic/signals
- add negative tests
- run narrow verification before broad verification
- report skipped tests honestly
- distinguish facts from assumptions
- not commit/push unless explicitly requested
- include import policy in downstream prompts

Do not accept confident unsupported claims.

