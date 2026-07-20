---
name: testcontainers
description: Defines enterprise Testcontainers and Docker-backed integration-test standards for the Platform Tracing repository. Use when Codex designs, reviews, refactors, implements, runs, or diagnoses tests involving OpenTelemetry Collector, Jaeger, Kafka, PostgreSQL, Redis/KeyDB, MinIO, remote Docker, child JVMs, Java Agent packaging, real protocol boundaries, fault injection, container networking, readiness, lifecycle, or opt-in E2E execution.
---

# Testcontainers Standards

## Objective

Produce reproducible, isolated, diagnosable integration and end-to-end evidence across real process, network, protocol, classloader, Agent, exporter, and infrastructure boundaries.

The solution is pre-production. Breaking test-harness changes are allowed when they materially improve reliability, isolation, diagnostics, portability, or production confidence.

Do not preserve flaky helpers, fixed-host assumptions, accidental topology, workstation-specific behavior, or compatibility wrappers merely because existing tests use them.

## Applicability

Use Testcontainers when correctness depends on a real external boundary, such as:

- OpenTelemetry Collector or Jaeger
- Kafka
- PostgreSQL
- Redis or KeyDB
- MinIO
- another protocol-compatible infrastructure service
- a child JVM or Java Agent interacting with containerized infrastructure

Prefer a focused unit test when it proves the same invariant without losing important runtime evidence.

Do not mock the boundary when the purpose is to prove serialization, networking, exporter behavior, Agent behavior, backend compatibility, failover, or container lifecycle.

## Priority

When requirements conflict, prefer:

1. evidence that the intended runtime path actually executed
2. deterministic isolation and cleanup
3. correct host/container network addressing
4. explicit readiness instead of startup sleeps
5. pinned and governed images
6. bounded timeouts and useful failure diagnostics
7. portability across local, CI, and remote Docker
8. minimal test cost
9. implementation convenience
10. compatibility with the previous test harness

## Core workflow

1. Read applicable repository instructions, E2E runbooks, Gradle gates, and infrastructure assumptions.
2. Identify which process runs on the host, in a child JVM, or in each container.
3. Draw the network-addressing matrix before creating endpoints.
4. Inventory images, versions, ports, networks, aliases, files, wait strategies, timeouts, and cleanup ownership.
5. Load the applicable files from `references/`.
6. Pin images and use mapped ports or Docker-network aliases according to caller location.
7. Start infrastructure in dependency order and wait for semantic readiness.
8. Generate configuration that uses addresses reachable from the consuming process.
9. Run the actual opt-in E2E path with required properties.
10. Verify test count, failures, errors, skips, child-process exits, and backend observations.
11. Capture bounded diagnostics on failure.
12. Clean up child processes, temporary files, networks, and containers.
13. Report compiled, discovered, skipped, executed, passed, and failed states separately.

## Mandatory invariants

- Never use `latest` image tags.
- Never hard-code host ports, localhost assumptions, or a developer Docker host in test production code.
- Use `container.getHost()` and mapped ports for host or child-JVM access.
- Use Docker network aliases and internal ports for container-to-container access.
- Do not pass Windows bind-mount paths to a remote Linux Docker daemon unless a proven shared filesystem exists.
- Do not use fixed sleeps as the primary readiness mechanism.
- Bound startup, request, exporter, polling, and shutdown timeouts.
- Give every container, network, child JVM, and temporary artifact explicit lifecycle ownership.
- Isolate test state, topics, databases, keys, service names, and output directories.
- Do not let parallel tests collide through static global infrastructure without proven synchronization.
- Do not call an opt-in E2E test passed when the task was skipped.
- Verify backend-observed spans/messages/data when the boundary is material.
- Do not substitute source compilation for packaged-artifact execution.
- Test actual published/distribution artifacts when validating packaging.
- Sanitize logs and diagnostics; never print credentials or sensitive telemetry.
- Preserve primary failure evidence while handling cleanup failures separately.
- Do not leave Gradle daemons, child JVMs, containers, or networks running after a test.

## Evidence classification

Report each relevant suite as one of:

- `COMPILED_ONLY`
- `DISCOVERED_NOT_EXECUTED`
- `SKIPPED`
- `EXECUTED_PASSED`
- `EXECUTED_FAILED`
- `ENVIRONMENT_BLOCKED`

Include:

- exact command
- opt-in flags
- test count
- failures
- errors
- skipped count
- child-process exit codes
- container image/version
- relevant backend observation
- environmental limitations

## Reference selection

Read only the references relevant to the task, except for required combinations.

### Environment, images, ports, and networking

Read [environment-images-and-networking.md](references/environment-images-and-networking.md).

Use it for image governance, mapped ports, host/child-JVM access, container-to-container access, direct Agent export, and remote Docker.

### Lifecycle, networks, readiness, timeouts, and isolation

Read [lifecycle-readiness-and-isolation.md](references/lifecycle-readiness-and-isolation.md).

Use it for container ownership, shared networks, wait strategies, deadlines, parallel execution, and test-data isolation.

### Tracing, Collector/Jaeger, Spring Boot, and opt-in E2E

Read [tracing-spring-and-e2e.md](references/tracing-spring-and-e2e.md).

Use it for Agent/application classloaders, Collector configuration, exporter endpoints, dynamic Spring properties, and Gradle E2E gates.

### Diagnostics, cleanup, CI, performance, and reporting

Read [diagnostics-cleanup-and-ci.md](references/diagnostics-cleanup-and-ci.md).

Use it for failure bundles, cleanup, CI requirements, performance, generated prompts, anti-patterns, and required result reporting.

## Required reference combinations

For every Docker-backed integration or E2E change, read:

1. `environment-images-and-networking.md`
2. `lifecycle-readiness-and-isolation.md`
3. `diagnostics-cleanup-and-ci.md`

Also read `tracing-spring-and-e2e.md` whenever the test involves Platform Tracing, Spring Boot, OpenTelemetry Agent, Collector, Jaeger, propagation, or packaged applications.

## Completion standard

Do not report completion until:

- the process and network topology are explicit
- images are pinned
- endpoints are reachable from their actual callers
- readiness and timeouts are deterministic
- test state and lifecycle are isolated
- opt-in E2E actually executes
- skipped count is inspected
- packaged artifacts are used where packaging is under test
- backend observations prove the expected behavior
- cleanup completes without hiding the primary failure
- CI and remote-Docker limitations are explicit
- the final report distinguishes passed, skipped, compiled-only, and environment-blocked evidence

