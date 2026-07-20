# Dependencies, Platforms, Repositories, and Plugins

## Dependency Scopes

Use Gradle scopes intentionally.

### `api`

Use only when a dependency's types are intentionally exposed in supported public signatures or required by consumers to compile against the published API.

`api` is an architectural decision, not a convenience fix.

### `implementation`

Use for internal implementation dependencies that must not leak onto consumer compile classpaths.

This is the default for runtime implementation details.

### `compileOnly`

Use only for a genuine provided-runtime contract.

Document who provides the runtime dependency:

- OTel agent
- Spring Boot starter
- consuming application
- JDK/runtime
- another platform artifact

A `compileOnly` dependency used by executable main code is still a runtime requirement. Do not describe the module as runtime-independent if consumers must provide the artifact.

### `runtimeOnly`

Use when code does not compile against the dependency but the runtime requires it.

### `annotationProcessor`

Use only for annotation processors.

Do not confuse annotation availability with processors.

### Test scopes

Use the narrowest test scope:

- `testImplementation`
- `testRuntimeOnly`
- test fixtures configurations if already adopted
- custom source-set configurations for agent/e2e fixtures

Do not leak test libraries into production configurations.

## Public API Dependency Governance

For `platform-tracing-api`, every non-JDK dependency requires review.

Verify:

- whether types appear in public signatures
- whether the artifact is compile-only, transitive API, or internal implementation
- runtime provider ownership
- classloader implications
- Javadoc classpath
- license
- version management
- consumer impact

Do not add a broad runtime merely to use one annotation or utility.

Example:

```gradle
compileOnly "com.fasterxml.jackson.core:jackson-annotations"
```

may be appropriate when Javadoc/class metadata needs annotation types and Jackson is not a runtime/public contract.

Do not add `jackson-databind` when only annotations are required.

## Dependency Versions

Use the repository's existing version-management model:

- Spring Boot dependency management/BOM
- explicit BOM/platform
- version catalog
- central dependency constants
- approved root/convention policy

Do not hard-code a version in a feature module when the repository already manages it.

Forbidden:

```gradle
implementation "org.example:library:+"
implementation "org.example:library:latest.release"
```

Avoid duplicate version ownership.

When adding a dependency, record:

```text
Artifact:
Version owner:
Gradle scope:
Public API exposure:
Runtime provider:
Transitive impact:
License/governance:
Reason:
```

## BOM and Platform Usage

Use BOMs/platforms to align coherent dependency families.

Do not import multiple competing BOMs without verifying precedence.

Spring Boot-managed versions should not be overridden locally without a documented incompatibility or security reason.

When overriding:

- explain why
- verify dependency insight
- test the affected runtime
- document removal criteria

Use `enforcedPlatform` only when strict enforcement is intentionally required. It can constrain consumers and should not be applied casually in published libraries.

## Repositories

Repositories should be centrally governed.

Prefer repository configuration in:

- settings dependency resolution management
- approved convention plugin
- enterprise init/configuration

Feature modules should not add:

```gradle
repositories {
    mavenCentral()
}
```

without a specific approved need.

Do not add:

- arbitrary HTTP repositories
- unauthenticated internal repositories
- JitPack for production dependencies without governance
- repositories that shadow approved coordinates
- dynamic repository selection based on developer machine

Credentials must use approved Gradle/CI secret mechanisms.

## Dependency Verification and Locking

Use repository-approved supply-chain controls where configured:

- dependency verification metadata
- checksums/signatures
- dependency locking
- repository content filters
- vulnerability scanning
- SBOM generation

Do not bypass dependency verification to make a generated change pass.

A new repository or artifact that cannot be verified requires review.

## Plugins

Plugin versions and repositories belong in plugin management or the approved build-logic mechanism.

Do not hard-code plugin versions inconsistently across modules.

Do not apply unrelated plugins from a convention plugin.

Apply plugins only when the module uses their lifecycle or artifact model.

Examples:

- `java-library` for published libraries with API/implementation separation
- Spring Boot plugin only where Boot packaging/task behavior is needed
- `maven-publish` only for published artifacts
- JMH plugin only for benchmark modules
- test fixtures plugin only where fixtures are shared intentionally

Do not apply the Spring Boot executable-jar model to ordinary library modules.

