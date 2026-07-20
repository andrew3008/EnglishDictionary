# Environment, Images, Ports, and Networking

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

