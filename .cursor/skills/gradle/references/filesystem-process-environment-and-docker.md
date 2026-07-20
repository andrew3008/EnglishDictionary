# Filesystem, Processes, Environment, and Docker

## Filesystem Access

Avoid broad recursive filesystem scans during configuration.

Use:

- `fileTree` as declared task input
- source sets
- Gradle file collections
- artifact views
- providers
- task outputs

A scan used as a quality gate should run in a task with explicit scope and deterministic result.

Examples:

- forbidden API scan
- BOM scan
- wildcard import scan
- legacy package scan
- generated metadata consistency

Do not make normal IDE import/sync perform expensive repository-wide scans unnecessarily.

## Process and External Service Access

Do not access:

- Docker
- GitHub
- remote HTTP services
- databases
- collectors
- cloud APIs

during Gradle configuration.

External access belongs in task execution or tests.

Use explicit opt-in properties for environment-dependent tasks.

Failure/skip semantics must be visible.

## Environment Variables and Properties

Read environment and Gradle properties lazily through providers when possible.

Do not hard-code:

- developer paths
- Windows drive letters
- Docker host
- CI vendor paths
- credentials
- local Maven repositories

Project-specific E2E may use:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
```

but build logic must read it from the environment and tests must resolve service endpoints through Testcontainers.

Do not bake this address into published or ordinary production code.

## OS Portability

Build logic must account for supported developer/CI platforms.

Avoid assuming:

- Bash exists on Windows
- PowerShell exists in Linux CI
- `/tmp` semantics
- drive-letter paths on remote Linux Docker
- executable bit behavior without Git/tooling support
- platform-specific path separators in generated config

Prefer JDK/Gradle APIs.

When separate scripts are necessary, provide the supported variants or constrain them to CI.

## Docker and Remote Docker

Docker-backed validation must run during task execution.

Known development environment:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
```

The daemon runs on Gentoo Linux.

Rules:

- do not mount Windows host paths into the remote Linux daemon
- prefer `withCopyToContainer` / classpath resources
- do not treat a Docker warning as harmless if a required test did not execute
- distinguish `SKIPPED` from `PASS`
- verify XML test results for mandatory opt-in E2E
- avoid shelling out to Docker from normal configuration

A task that validates collector configuration against Docker must have explicit environment behavior and should not print alarming non-fatal errors as if they were success-neutral without explanation.

