# Validation, CI, Performance, and Security

## Collector Configuration Validation

Collector config validation should:

- use pinned collector image/version
- avoid invalid host path mounts under remote Docker
- copy config into container when needed
- return clear task status
- distinguish environment warning from validation failure
- be reproducible in CI

Do not swallow a real validation error and return success.

Do not emit repeated alarming stderr for a known non-fatal path without improving the task.

## Static Scans

Static scans should be narrow and intentional.

Potential scans:

- removed API symbols
- legacy package imports
- wildcard imports
- BOM
- forbidden dependencies
- stale Javadoc links
- hard-coded credentials
- trust-all TLS
- deprecated bridges
- generated descriptor paths

Avoid fragile regexes that match unrelated semantic-convention keys or test method names.

When a false positive is known:

- narrow the scope/pattern
- exclude exact allowed context
- avoid renaming unrelated domain vocabulary solely to satisfy a poor regex unless the rename also improves clarity

A scan should encode architecture, not incidental text.

## Task Naming

Task names should describe action and scope.

Prefer:

```text
verifyArchitecture
verifyModuleTaxonomy
validateCollectorConfigs
generatePublicApiReport
verifyStarterDependencies
```

Avoid:

```text
checkStuff
doValidation
tempTask
fixAll
```

Task group and description should be set for discoverability.

Do not rename established CI tasks casually; before production, a direct rename is allowed when the new taxonomy is materially better, but update CI/docs in the same change.

## Failure Messages

Build failures must be actionable.

Include:

- task/capability
- offending file/module/dependency
- expected invariant
- suggested next action
- whether the failure is environment or code

Avoid:

- swallowed exceptions
- stack traces without context
- success after required verification was skipped
- error-level stderr for expected optional behavior without explanation

## CI

CI should run:

- compile/tests
- Javadoc for published APIs
- architecture fitness
- dependency/starter smoke
- publication verification where relevant
- mandatory opt-in E2E
- security/dependency scans
- docs/golden consistency where relevant

A required CI job must fail when:

- tests were requested but skipped
- no tests executed
- artifact/report missing
- publication metadata invalid
- architecture task failed
- Docker unavailable for a mandatory Docker gate

Do not detect CI vendor inside feature modules.

CI-specific orchestration belongs in CI configuration or approved build logic.

## Local and CI Parity

Local commands and CI should use the same Gradle tasks.

Avoid CI-only shell logic that reimplements Gradle verification.

Document required local environment variables.

Use `--no-daemon`, `--rerun-tasks`, or `--no-build-cache` only when the verification purpose requires them. Do not make all development tasks maximally expensive.

## Gradle Daemon

The daemon is appropriate for normal local development.

For reproducibility/audit commands, `--no-daemon` may be used.

Do not treat `--no-daemon` as a universal correctness requirement.

## Parallel Execution

Tasks and tests should be safe for parallel execution where enabled.

Do not introduce:

- shared mutable static build state
- fixed ports
- common output directories
- common temp files
- task outputs outside project/build directory
- cross-project task mutation

If a task is not parallel-safe, declare/document the constraint.

## Configuration-on-Demand and Isolated Projects

Do not claim compatibility with configuration-on-demand or isolated projects unless verified.

Avoid patterns that make future support impossible:

- cross-project model access
- mutation of another project's tasks/configurations
- eager traversal of all projects

Use explicit project dependencies and convention plugins.

## Security

Never:

- hard-code credentials
- log secrets
- print full environment
- embed tokens in generated metadata
- add untrusted repositories
- disable TLS verification
- bypass dependency verification
- publish secret-bearing resources

Use approved credentials/providers/CI secrets.

Build scans and logs must not expose sensitive system properties.

