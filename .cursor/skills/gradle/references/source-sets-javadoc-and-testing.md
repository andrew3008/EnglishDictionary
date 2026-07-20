# Source Sets, Javadoc, and Testing

## Source Sets

Custom source sets are allowed for genuine isolation, such as:

- Java agent smoke child applications
- JMX wire extensions
- custom ServiceLoader provider jars
- classloader probes
- performance fixtures

Every custom source set must define:

- purpose
- compile/runtime classpath
- producing artifact/task
- consumer task
- lifecycle integration
- whether it is published
- verification

Do not create custom source sets merely to avoid normal module ownership.

Ensure stale source-set consumers are migrated when APIs change.

## Generated Sources and Resources

Generated files must have one owner.

Rules:

- write under `build/`, not source directories, unless the artifact is intentionally committed
- declare generating task
- wire task output to source set/resource processing lazily
- make generation deterministic
- do not regenerate committed docs silently
- do not mix generated and handwritten files without a clear convention

For ServiceLoader descriptors:

- verify exact descriptor path
- verify provider FQN
- remove obsolete descriptors
- test generated `@AutoService` output when used
- inspect built artifacts, not only source tree

## Annotation Processing

Configure annotation processors intentionally.

Separate:

- compile-time annotation artifact
- annotation processor artifact
- test annotation processor

Do not add processors to runtime classpath.

For Lombok or generated accessors, remember:

- Javadoc may not resolve generated methods as source links
- public API should not depend on undocumented generated shape
- removing Lombok from sensitive public protocol packages may be justified
- generated code must not hide public surface changes

## Javadoc

Published API modules should generate Javadoc and Javadoc JARs when applicable.

Javadoc tasks must:

- use the correct classpath
- resolve annotation types used by sources or referenced class metadata
- fail or report actionable warnings according to repository policy
- not link API docs to unavailable core implementation types
- use deterministic encoding

Do not globally suppress doclint or warnings to hide defects.

Example root cause/fix:

```text
warning:
unknown enum constant JsonInclude.Include.NON_EMPTY

correct fix:
add the narrow `jackson-annotations` artifact to the affected compile/Javadoc classpath
when that accurately represents the metadata contract
```

Do not add `jackson-databind` for an annotation-only need.

## Testing

Gradle must expose clear test tasks for module and integration scopes.

Test configuration should define:

- JUnit Platform
- parallelism policy
- logging/reporting
- system properties
- opt-in behavior
- max heap/forks only when justified
- test result locations

Do not make test success depend on execution order.

Do not hide failing tests through broad filters or `ignoreFailures`.

## Opt-In Tests

Opt-in tests may be appropriate for Docker-backed E2E or expensive performance verification.

Rules:

- opt-in property is documented
- normal build skip is visible
- explicit opt-in must execute or fail, not silently skip
- reports distinguish executed/skipped
- CI has a required job for production-critical opt-in tests
- final evidence includes test count

Example:

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test `
  -PrunE2e `
  --rerun-tasks `
  --no-build-cache `
  --no-daemon
```

`BUILD SUCCESSFUL` with the test task `SKIPPED` is not E2E evidence.

## Test Reports

For mandatory E2E evidence, verify:

```text
tests > 0
skipped = 0
failures = 0
errors = 0
```

Do not rely only on console summary.

Archive useful XML/HTML reports in CI.

Do not publish sensitive environment data or credentials in reports.

