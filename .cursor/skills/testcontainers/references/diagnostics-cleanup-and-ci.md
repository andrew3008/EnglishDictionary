# Diagnostics, Cleanup, CI, and Reporting

## Failure Diagnostics

On failure, include:

- Docker host resolved by Testcontainers
- image name and version
- container ID/name if safe
- internal and mapped ports
- effective endpoint used by the caller
- wait strategy and timeout
- relevant container logs
- application/child-process stdout and stderr
- trace/span lookup criteria
- whether the test executed or was skipped

Do not print:

- credentials
- authorization headers
- raw sensitive attributes
- private keys
- full environment dumps

Prefer project support helpers that gather diagnostics consistently.

## Cleanup

Testcontainers resources must be closed through JUnit/Testcontainers lifecycle.

Do not:

- manually leave containers running as part of test success
- depend on previous test containers
- disable cleanup globally without a documented reason
- use `docker rm` shell calls from individual tests

If Ryuk or automatic cleanup must be changed for a remote environment, document it centrally and verify CI cleanup separately.

## CI Requirements

Containers must run correctly in:

- GitHub Actions
- GitLab CI
- Kubernetes runners
- documented remote Docker environments

CI must make opt-in semantics explicit.

A mandatory CI job must fail when:

- Docker is unavailable
- required tests are skipped
- container startup fails
- no tests were executed
- expected artifacts/reports are missing

Do not treat infrastructure skip as a green production gate.

## Performance

Container startup cost should be managed, but correctness has priority.

Allowed optimizations:

- shared immutable infrastructure per suite
- image pre-pulling in CI
- parallel independent suites within resource limits
- targeted test selection

Forbidden optimizations:

- skipping readiness checks
- reusing dirty mutable state
- removing isolation
- replacing real protocol tests with mocks
- hiding skipped tests

## Generated Code and Agent Instructions

For Cursor, Codex, and Perplexity-generated test changes:

- inspect existing Testcontainers support classes first
- reuse project endpoint/network helpers
- do not introduce fixed ports or localhost assumptions
- do not duplicate the Gentoo DNS workaround
- do not add `Thread.sleep`
- do not report skipped E2E as passed
- run the narrowest affected compile/test task
- run the opt-in E2E profile when runtime behavior changed

## Anti-Patterns

Forbidden:

- fixed host ports
- fixed `localhost`
- hard-coded remote Docker IP in Java test code
- Docker-internal DNS assumptions in the known Gentoo failure path
- Windows bind mounts against a remote Linux daemon
- `Thread.sleep`
- unbounded polling
- mutable shared test state
- `latest` image tags
- real cloud dependencies
- tests that pass only when containers are reused
- tests that silently skip after `-PrunE2E`
- reporting `BUILD SUCCESSFUL` as E2E evidence when tests were skipped

## Required Reporting

For each Docker-backed verification, report:

```text
Docker endpoint:
Testcontainers host:
Targeted test task:
Opt-in property:
Tests executed:
Tests skipped:
Failures:
Errors:
Result:
```

Use:

- `PASS` only when required tests executed and passed
- `PASS_WITH_WARNINGS` when code is green but non-blocking environment warnings remain
- `INSUFFICIENT_EVIDENCE` when runtime tests did not execute
- `FAIL` when the requested Docker-backed gate did not run successfully

