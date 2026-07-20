# Dependencies, Startup, and Failure Behavior

## Dependency Hygiene

Use Gradle dependency scopes intentionally.

Guidelines:

- `api` only for dependencies visible in public signatures
- `implementation` for internal runtime dependencies
- `compileOnly` for genuine provided-at-runtime contracts
- `annotationProcessor` only for processors
- avoid adding a broad dependency when a narrow artifact is enough

Examples:

- use `jackson-annotations`, not `jackson-databind`, when only annotation metadata is required
- do not expose Spring/JMX/OTel implementation types from API contracts
- starters should not make unrelated optional integrations transitive

Every new transitive dependency in a starter should be treated as an architecture decision.

## Direct Environment Access

Ordinary components must not call:

- `System.getenv`
- `System.getProperty`
- global Spring `Environment`
- static configuration holders

Use typed configuration properties or explicit runtime contracts.

Narrow bootstrap/integration code may read process-level settings only when the setting belongs to the underlying platform runtime and cannot be represented through normal Spring binding. Such exceptions require documentation and tests.

## Startup Side Effects

Forbidden during auto-configuration unless explicitly required and tested:

- network calls
- Docker access
- file writes
- mutation of global OpenTelemetry state
- JMX mutations
- background threads without lifecycle ownership
- modification of application system properties
- silent fallback after critical configuration failure

Registration side effects must be:

- idempotent
- reversible when practical
- covered by rollback tests
- tied to Spring lifecycle

## Failure Behavior

Choose failure semantics deliberately.

Fail startup when:

- a mandatory safety invariant is violated
- configuration cannot be interpreted safely
- mutually exclusive critical settings are enabled
- a required platform runtime is missing

Back off or degrade when:

- an optional integration is absent
- tracing is explicitly disabled
- a non-critical diagnostics feature cannot initialize

Never silently enable a risky capability after configuration failure.

Error messages must include:

- property or capability name
- invalid value category, without leaking secrets
- expected action
- whether startup is blocked or capability is disabled

