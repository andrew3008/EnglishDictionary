# Testcontainers Standards

## Context

This repository contains an enterprise platform tracing solution with Docker-backed integration and end-to-end tests.

The solution is currently **pre-production**. Breaking test-harness changes are allowed when they materially improve reliability, isolation, diagnostics, or production confidence.

Do not preserve flaky helpers, fixed-host assumptions, accidental container topology, or compatibility wrappers merely because existing tests use them.

## Primary Goals

Testcontainers-based tests must provide:

- reproducible integration evidence
- classloader and process-boundary coverage
- deterministic container startup and readiness
- network correctness for local, CI, and remote Docker
- clear distinction between compiled, skipped, executed, and passed tests
- useful diagnostics when a container or exporter path fails
- minimal dependence on a developer workstation
- no reliance on real cloud services

## When to Use Testcontainers

Use Testcontainers when behavior depends on a real external boundary, for example:

- OpenTelemetry Collector
- Jaeger or another tracing backend used by the test harness
- PostgreSQL
- Kafka
- KeyDB / Redis
- MinIO
- another protocol-compatible service required by an integration test

Do not use a container when a focused unit test or in-memory implementation proves the same behavior more clearly.

Do not mock a protocol boundary when the purpose of the test is to prove real serialization, networking, exporter, agent, JMX, or backend behavior.

## Container Images

Rules:

- pin explicit image versions
- do not use `latest`
- prefer images already approved by the repository
- keep image upgrades in focused commits with verification evidence
- use `DockerImageName.asCompatibleSubstituteFor(...)` only when compatibility is deliberate and tested
- do not pull unrelated heavyweight images for a narrow test

For critical infrastructure, document the image version in the test support class or runbook.

## Ports

Never use fixed host ports.

Always use:

- container internal service ports
- `getMappedPort(...)` for host-to-container access
- `container.getHost()` or the Testcontainers-resolved host
- dynamic properties for Spring Boot tests

Forbidden:

```java
"http://localhost:4318"
"127.0.0.1:9092"
"192.168.100.70:4318" // hard-coded in test production code
```

Preferred:

```java
String endpoint = "http://%s:%d".formatted(
        container.getHost(),
        container.getMappedPort(4318)
);
```

The documented local execution command may set `DOCKER_HOST`, but test code should resolve the host through Testcontainers rather than duplicating the remote Docker address.

## Network Addressing Matrix

Choose the endpoint according to where the caller process runs.

### Host JVM or child JVM -> container

Use:

```text
container.getHost() + mapped port
```

Examples:

- Gradle/JUnit process on Windows -> Jaeger OTLP HTTP
- Java agent smoke-test child JVM on Windows -> Jaeger mapped `4318`
- application process outside Docker -> container service via mapped port

Do not use Docker container IPs from a host JVM. Container IPs may not be routable from the Windows host or CI runner.

### Container -> container on the same Docker daemon

Normally prefer a dedicated Testcontainers network and network aliases.

However, this project has a known Gentoo remote-Docker environment where Docker-internal DNS may be unreliable for the Collector-to-Jaeger path. In that environment:

- use the existing project support helper to obtain the target container network IP
- inject `<container-ip>:<internal-port>` into the source container
- use the internal container port, not the mapped host port
- do not duplicate low-level Docker inspection logic across tests

Project-specific example:

```text
Collector container -> Jaeger container:
JAEGER_OTLP_GRPC_ENDPOINT=<jaeger-network-ip>:4317
```

Do not generalize the container-IP workaround to host-to-container calls.

### Direct agent export

For a Java agent or child JVM running on the host, use the host-mapped Jaeger/Collector endpoint:

```text
http://<testcontainers-host>:<mapped-4318>/v1/traces
```

This path must not depend on Docker-internal DNS.

## Remote Docker

The known development environment uses:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
```

The remote daemon runs on Gentoo.

Rules:

- read Docker connection settings from the environment
- do not hard-code the remote address into Java test code
- do not assume Docker runs on the same machine as Gradle
- do not mount arbitrary Windows host paths into the remote Linux daemon
- avoid bind mounts unless the remote path is explicitly provisioned
- prefer classpath resources copied into containers
- use Testcontainers-managed temporary files or `withCopyToContainer(...)`

A Windows path such as `E:\...` is not a valid Linux path on the remote daemon. Treat remote-Docker volume-path warnings as test-infrastructure defects when they affect a required gate.

## Container Lifecycle

Prefer static shared containers only when:

- startup cost is material
- state is isolated per test
- tests do not depend on execution order
- parallel execution is safe
- container state is reset or uniquely namespaced

Do not use a singleton/shared container merely to make tests faster.

For mutable backends:

- use unique trace IDs, topics, database names, buckets, or namespaces
- clean state between tests when necessary
- never assert against unfiltered global backend state
- do not reuse an old trace/span name as the only lookup discriminator

Container reuse:

- may be enabled intentionally for local development
- must not be required for correctness
- must not hide initialization defects
- should normally be disabled in CI
- must not be assumed by tests

Do not use fixed container names.

## Networks

Prefer one explicit Testcontainers network per integration suite when container-to-container communication is required.

Rules:

- network aliases must be unique and intentional
- do not assume Docker's default bridge behavior
- do not depend on host DNS for container-to-container calls
- close custom networks with the test lifecycle
- avoid sharing a mutable network across unrelated parallel suites

If using the Gentoo DNS workaround, document it in the support helper and test runbook.

## Readiness

Container started does not mean service ready.

Use the strongest practical readiness signal.

Preferred:

- `Wait.forHttp(...)`
- `Wait.forHealthcheck()`
- `Wait.forLogMessage(...)` with a stable, version-pinned message
- service-specific probe
- functional API readiness check

Use `Wait.forListeningPort()` only when a listening socket is sufficient evidence.

Examples:

- Collector: configuration loaded and receivers started
- Jaeger: query/OTLP endpoint is responsive
- Kafka: broker is ready for metadata/topic operations
- PostgreSQL: connection and simple query succeed

Never use:

```java
Thread.sleep(...)
```

Use bounded Awaitility for eventual backend visibility after export.

Separate these waits:

1. container process readiness
2. application/exporter readiness
3. eventual trace visibility in the backend

## Timeouts

Every wait must be bounded.

Rules:

- use explicit startup timeouts for slow images
- use explicit Awaitility timeouts for exported trace visibility
- include useful failure messages
- avoid excessively long global timeouts that hide hangs
- do not retry forever

Timeout values must be justified by the service and environment, not copied blindly.

## Test Isolation

Tests must be safe under parallel execution or explicitly serialized.

Avoid:

- shared mutable static state
- global backend queries without unique correlation
- common Kafka topic names across tests
- fixed database schemas
- fixed bucket names
- fixed trace names as the only identifier

Prefer:

- UUID/correlation-based identifiers
- per-test resource names
- unique request IDs
- query filters using trace ID plus expected span data
- resource cleanup in `finally` / lifecycle hooks

If a test cannot run in parallel, document and enforce that constraint.

## Tracing-Specific Rules

Tracing E2E tests must distinguish paths:

- application -> Collector -> Jaeger
- application/agent -> Jaeger directly
- application -> agent extension -> exporter
- JMX control operation -> decode -> domain validate -> apply/read

For exported spans, assert more than presence when relevant:

- span name
- trace/span relationship
- attributes
- resource identity
- status
- events
- sampling result
- scrubbing result
- propagation headers

Avoid nondeterministic sampling in tests that require export. Explicitly set the platform sampling configuration to deterministic values such as ratio `1.0` unless the test is specifically testing sampling.

A test that expects no export should use an explicit deterministic drop configuration, not probability.

## Collector and Jaeger Configuration

Prefer building active endpoints from running container state rather than static YAML DNS names.

For remote Gentoo Docker:

- Collector-to-Jaeger active endpoint should use the Jaeger network IP when the known DNS defect applies
- host-based child JVMs should use mapped Jaeger HTTP ports
- fallback values in YAML must not be confused with the actual injected active endpoint

Tests must log the effective endpoint configuration on failure without exposing secrets.

## Spring Boot Integration

Use dynamic properties:

```java
@DynamicPropertySource
static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("some.endpoint", () -> endpointFrom(container));
}
```

Do not hard-code container endpoints in `application-test.yml` unless they are placeholders overridden by dynamic properties.

For `ApplicationContextRunner`, use containers only when the test genuinely exercises an external integration. Keep ordinary condition/property tests container-free.

## Opt-In E2E Gates

Project E2E tests may be opt-in through:

```text
-PrunE2e
```

Rules:

- without the opt-in property, skip is allowed only for intentionally optional local execution
- with `-PrunE2e`, unavailable Docker or unmet prerequisites should fail the requested gate rather than silently pass
- final reports must say whether tests executed or were skipped
- `BUILD SUCCESSFUL` with `test SKIPPED` is not runtime evidence

Recommended command:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :platform-tracing-e2e-tests:test `
  -PrunE2e `
  --rerun-tasks `
  --no-build-cache `
  --no-daemon
```

Targeted example:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :platform-tracing-e2e-tests:test `
  --tests "*WireRoundTrip*" `
  -PrunE2e `
  --rerun-tasks `
  --no-build-cache `
  --no-daemon
```

For a release or merge gate, verify the XML/test report:

```text
tests > 0
skipped = 0
failures = 0
errors = 0
```

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
