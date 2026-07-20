# Tracing, Spring Boot, and Opt-In E2E

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

