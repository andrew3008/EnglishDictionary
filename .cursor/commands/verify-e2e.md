# Verify E2E

Verify the Docker-backed E2E behavior affected by the current change.

## Mandatory Template

Before verification, read:

`@.cursor/templates/e2e-report.md`

Use that template as the mandatory final output structure.

Do not modify the template.

## Mode

Read-only verification.

Do not modify production/test code, commit, push, or create a PR.

Do not change configuration merely to force a green result.

## Determine the Runtime Boundary

Inspect the current diff and identify affected boundaries:

- application ↔ Java agent
- application ↔ Collector/backend
- JMX/OpenMBean ↔ runtime control
- servlet or WebFlux
- Kafka or database
- sampling or force sampling
- scrubbing/PII
- context propagation
- classloader-sensitive extension behavior

Select the narrowest test set proving the change.

## Harness Review

Verify:

- Gradle E2E task and opt-in property exist
- selected tests are enabled
- images are pinned
- dynamic ports are used
- remote-Docker-safe resource copying is used
- no Windows bind mounts target remote Linux Docker
- readiness is bounded
- sampling is deterministic
- unique test identifiers are used
- no `Thread.sleep(...)` synchronization

## Docker Environment

Use the configured environment.

For the known local environment, use when applicable:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
```

Do not hard-code it into repository files.

Distinguish:

- host JVM → mapped host/port
- container → network alias/internal port
- explicitly verified remote-Docker workaround

## Execute

Prefer exact test patterns first:

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test `
  --tests "<fully-qualified-test-pattern>" `
  -PrunE2e `
  --rerun-tasks `
  --no-build-cache `
  --no-daemon
```

Expand only when the scope requires it.

## Evidence

Inspect JUnit XML.

Required:

```text
tests > 0
skipped = 0
failures = 0
errors = 0
```

Verify applicable runtime assertions, not merely telemetry existence.

Collect bounded diagnostics on failure without exposing secrets or PII.

## Output

Return only the completed structure from:

`@.cursor/templates/e2e-report.md`

Use `PASS`, `PASS_WITH_WARNINGS`, `INSUFFICIENT_EVIDENCE`, or `FAIL` according to executed evidence.
