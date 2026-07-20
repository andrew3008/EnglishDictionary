# Modules, Dependencies, Creation, and Renaming

## Module Architecture

### `platform-tracing-api`

Owns contracts.

It may contain:

- public tracing facades
- capability interfaces
- immutable value objects
- specifications and builders
- public annotations
- intentional SPIs
- classloader-neutral wire/control vocabulary
- public result and violation types

It must not own:

- runtime implementation
- Spring Boot wiring
- JMX/OpenMBean implementation
- OpenTelemetry SDK implementation
- exporters or span processors
- domain validation
- sampling algorithms
- scrubbing implementation
- mutable live runtime state
- infrastructure clients
- application context access

The API module must not depend on core.

### `platform-tracing-core`

Owns implementation and domain/runtime behavior.

It may contain:

- implementations of public contracts
- tracing lifecycle
- span specification conversion
- context interpretation
- sampling and scrubbing policies
- domain validation
- runtime-control handlers
- no-op behavior
- last-known-good state
- atomic apply/read logic
- safety invariants
- approved OTel API adaptation

It must not contain:

- Spring annotations
- `ApplicationContext` lookup
- `@ConfigurationProperties`
- servlet or WebFlux types
- Spring conditional logic
- JMX/OpenMBean transport implementation

### `platform-tracing-spring-boot-autoconfigure`

Owns Spring Boot integration.

It may contain:

- bean wiring
- typed startup properties
- conditional registration
- startup diagnostics
- Actuator integration
- desired-state mapping
- reconciliation between startup configuration and runtime state

It must not own:

- tracing domain algorithms
- wire schema/decoding
- sampling policy implementation
- scrubbing implementation
- JMX transport behavior
- application-facing API types

### Servlet adapter

Owns servlet-specific integration.

It must not depend on WebFlux/reactive adapter types.

### WebFlux adapter

Owns reactive-specific integration.

It must not depend on servlet adapter types.

Shared framework-neutral behavior belongs in API/core/autoconfigure only when it is genuinely neutral.

### `platform-tracing-otel-extension`

Owns:

- OpenTelemetry agent/SDK integration
- sampler/provider/processor/exporter glue
- JMX/OpenMBean adapters
- classloader-sensitive bridges
- runtime wiring to approved core contracts

It must not become an alternate domain core.

### Starters

Starters are thin dependency aggregators.

They may select the matching integration/autoconfiguration path.

They must not contain:

- domain logic
- duplicate auto-configuration
- runtime algorithms
- environment-specific behavior
- mutable global state

### Collector configuration

Owns collector-side telemetry pipeline configuration.

It must not become the owner of application/core domain policy merely because YAML is easy to change.

### Tests, samples, benchmarks, and E2E

These modules depend on production modules.

Production modules must not depend on them.

## Expected Dependency Direction

```text
platform-tracing-core
    -> platform-tracing-api

platform-tracing-spring-boot-autoconfigure
    -> platform-tracing-api
    -> platform-tracing-core

platform-tracing-autoconfigure-webmvc
    -> approved api/core/autoconfigure modules

platform-tracing-autoconfigure-webflux
    -> approved api/core/autoconfigure modules

platform-tracing-otel-extension
    -> approved api/core modules
    -> OTel integration artifacts

starters
    -> matching auto-configuration/integration modules

test/samples/bench/e2e
    -> production modules under test
```

Forbidden:

- `platform-tracing-api -> platform-tracing-core`
- `platform-tracing-core -> Spring Boot auto-configuration`
- servlet adapter -> WebFlux adapter
- WebFlux adapter -> servlet adapter
- production module -> test/sample/e2e module
- API -> JMX/OpenMBean implementation
- API -> Spring
- API -> OTel SDK implementation without explicit approved contract
- module cycles
- cycles hidden by changing `implementation` to `api`

Fix ownership rather than dependency scope symptoms.

## Criteria for a New Module

Create a module only for a real boundary:

- independently published contract
- distinct runtime or classloader
- optional dependency isolation
- servlet/reactive isolation
- agent/application separation
- independently testable adapter
- meaningful reduction of consumer dependencies
- separately owned capability

Before creating a module, document:

```text
Owner:
Consumers:
Published or internal:
Runtime/classloader:
Public contracts:
Dependencies exposed:
Dependencies hidden:
Why a package is insufficient:
Tests:
Publication:
```

Avoid over-splitting into tiny modules that only move package-level complexity into Gradle.

Do not create a separate module merely because a design diagram looks cleaner.

## Module Removal and Renaming

Because the solution is pre-production, a wrongly owned or obsolete module may be removed or renamed directly.

Required migration:

1. update settings
2. update project dependencies
3. update CI tasks/scripts
4. update publication metadata
5. update docs/samples
6. remove obsolete module
7. scan for old project path/artifact coordinates
8. run full verification

Do not keep an empty compatibility module or forwarding artifact by default.

