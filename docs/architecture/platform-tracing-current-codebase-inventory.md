# Platform Tracing: Current Codebase Inventory

> **Snapshot date:** 2026-06-11
> **Repository:** `spring-boot-platform-tracing` (`e:\Platform_Traces`)
> **Scope:** factual inventory of existing Gradle modules, Java classes, tests, benchmarks and operational behavior — **без** migration plan и **без** нового architecture review.
>
> **Исторический snapshot:** разделы про public `SemconvKeys`, `SpanEnrichment`,
> `PlatformSpanContextKeys` и `api.span.sanitize` больше не описывают текущий код. Актуальная
> граница зафиксирована в
> [ADR-api-span-package-boundary](../decisions/ADR-api-span-package-boundary.md).

---

## 1. Purpose

Документ фиксирует **текущее состояние** Java/Gradle репозитория Platform Tracing для передачи в Perplexity / Claude / GPT models с целью подготовки **безопасного PR-by-PR migration plan** на согласованную Clean Core Hybrid Architecture.

```text
This document is not a migration plan.
This document is not a new architecture review.
This document is a preservation-oriented inventory of the current codebase.
This document exists to prevent loss of existing implementation, tests, benchmarks and operational behavior during migration to Clean Core Hybrid Architecture.
```

**Позиция:** `Preserve existing assets first, refactor second.`
На текущие Java-классы, тесты, benchmark'и и perf/integration наработки затрачены большие ресурсы; их нельзя потерять при переходе.

---

## 2. Executive summary

| Metric | Value |
|--------|-------|
| Gradle modules in `settings.gradle` | **14 active** (+ 1 commented out: `platform-tracing-semconv-lint`) |
| Additional module on disk, not in build | `platform-tracing-semconv-lint` (scaffold, excluded) |
| Production Java classes (`src/main/java`) | **279** |
| Test Java classes (`src/test/java`) | **213** |
| JMH benchmark classes (`src/jmh/java`) | **16** |

### Module groups

| Group | Modules |
|-------|---------|
| **Public consumption** | `platform-tracing-bom`, `platform-tracing-api`, `platform-tracing-spring-boot-starter-servlet`, `platform-tracing-spring-boot-starter-reactive` |
| **Internal runtime** | `platform-tracing-core`, `platform-tracing-otel-extension`, `platform-tracing-spring-boot-autoconfigure`, `platform-tracing-autoconfigure-webmvc`, `platform-tracing-autoconfigure-webflux` |
| **Verification / perf** | `platform-tracing-test`, `platform-tracing-e2e-tests`, `platform-tracing-bench`, `platform-tracing-perf-tests` |
| **Supporting (non-Java runtime)** | `platform-tracing-collector-config` (versioned Collector YAML + contract test) |

### Where things live today

| Concern | Current location |
|---------|------------------|
| **Public API / semconv / propagation contracts** | `platform-tracing-api` (59 main classes) |
| **Application-facing facade (`TraceOperations`, typed span builders)** | `platform-tracing-core` (30 main classes) — **реализация поверх OTel API**, не pure policy core |
| **OTel Java Agent extension (Sampler, SpanProcessor, JMX server, SPI)** | `platform-tracing-otel-extension` (99 main classes) — **наиболее ценный production runtime** |
| **Spring Boot common autoconfigure** | `platform-tracing-spring-boot-autoconfigure` (46 main classes) |
| **WebMVC-specific autoconfigure** | `platform-tracing-autoconfigure-webmvc` (7 main classes) |
| **WebFlux-specific autoconfigure** | `platform-tracing-autoconfigure-webflux` (11 main classes) |
| **Starters** | `platform-tracing-spring-boot-starter-servlet`, `platform-tracing-spring-boot-starter-reactive` (dependency-only, 0 Java classes) |
| **Shared test fixtures / ArchUnit** | `platform-tracing-test` (14 main + 17 test classes) |
| **E2E / Agent smoke** | `platform-tracing-e2e-tests` (42 test classes) |
| **JMH microbenchmarks** | `platform-tracing-bench` (16 JMH + 3 contract tests) |
| **Macro perf / load (M0–M10)** | `platform-tracing-perf-tests` (3 SUT classes + scripts/docker) |

### Highest preservation priority

1. **Sampling semantics** — `CompositeSampler`, rule chain, `SamplerStateHolder`, JMX ratio mutation, e2e smoke `RuntimeSamplingControlSmokeTest`
2. **Mandatory scrubbing** — `ScrubbingSpanProcessor`, scrubbing engine/rules, security negative tests, e2e scrubbing tests
3. **Export safety / queue policy** — `PlatformDropOldestExportSpanProcessor`, `SafeSpanExporter`, overflow tests
4. **Spring property binding** — `TracingProperties` (~700+ lines nested config)
5. **WebMVC / WebFlux stack isolation** — separate autoconfigure modules + regression matrix tests
6. **JMH + macro perf evidence** — PR-0 / E6 gates; M5 documented FAIL baseline (+48% CPU, +25% RSS vs budget)
7. **Developer starter experience** — BOM + exactly one starter

### Target-only components (not in codebase)

- `TracingConfigReconciler` — **Not found in current codebase** (exists only in target architecture docs)
- Desired State Configuration Layer types (`TracingDesiredState`, drift/apply result types) — **Not found**

---

## 3. Module taxonomy

### 3.1. Public consumption modules

#### `platform-tracing-bom`

```text
Module: platform-tracing-bom
Public-facing: yes
Intended consumers: all platform-tracing consumers; service build.gradle import
Should application teams depend on it directly: yes (via platform BOM or explicit import)
Current role: version alignment for Spring Boot, OTel BOM, instrumentation BOM, ArchUnit, Testcontainers, awaitility, okhttp
Target role: unchanged — public BOM
Migration sensitivity: LOW
Notes: publishes java-platform artifact; single source of truth for dependency versions
```

#### `platform-tracing-api`

```text
Module: platform-tracing-api
Public-facing: yes
Intended consumers: application code needing TraceOperations facade contracts, semconv, propagation, SPI scrubbing rules
Should application teams depend on it directly: yes (optional — only when explicit API needed; normally via starter)
Current role: public contracts — TraceOperations, @Traced, SpanFactory/SpanSpec, api.manual transport builders, semconv keys, propagation, mdc SPI, SpanAttributeScrubbingRule SPI
Target role: platform-tracing-api — contracts, semconv, propagation, wire schema
Migration sensitivity: HIGH (backward compatibility)
Notes: has compileOnly OTel context/api — MIGRATION_RISK vs target "JDK only"
```

#### `platform-tracing-spring-boot-starter-servlet`

```text
Module: platform-tracing-spring-boot-starter-servlet
Public-facing: yes
Intended consumers: Spring MVC / Servlet services
Should application teams depend on it directly: yes
Current role: pulls autoconfigure + webmvc + spring-boot-starter-web
Target role: unchanged public starter
Migration sensitivity: MEDIUM (dependency graph must remain stable)
Notes: 0 Java classes — pure dependency aggregator
```

#### `platform-tracing-spring-boot-starter-reactive`

```text
Module: platform-tracing-spring-boot-starter-reactive
Public-facing: yes
Intended consumers: WebFlux / Reactive services
Should application teams depend on it directly: yes
Current role: pulls autoconfigure + webflux + spring-boot-starter-webflux
Target role: unchanged public starter
Migration sensitivity: MEDIUM
Notes: 0 Java classes — pure dependency aggregator
```

### 3.2. Internal runtime implementation modules

#### `platform-tracing-core`

```text
Module: platform-tracing-core
Public-facing: no (transitive via autoconfigure — teams should not declare directly)
Internal role: DefaultTraceOperations facade, typed span builder implementations, AttributePolicy, exception recording
Framework dependencies: api io.opentelemetry:opentelemetry-api; api platform-tracing-api
Runtime role: application ClassLoader — used by Spring autoconfigure beans
ClassLoader assumption: Application CL
Target role: narrow pure-Java policy engine (target) — CURRENT STATE differs: OTel-coupled facade
Should application teams depend on it directly: no
Migration sensitivity: HIGH — SPLIT_CORE_AND_ADAPTER required for sampler/scrubbing currently in extension
Notes: MIGRATION_RISK — OTel types in "core-like" module today
```

#### `platform-tracing-otel-extension`

```text
Module: platform-tracing-otel-extension
Public-facing: no
Internal role: OTel Java Agent extension — CompositeSampler, SpanProcessors, SafeSpanExporter, JMX MBean server, SPI providers
Framework dependencies: compileOnly OTel SDK/SPI/javaagent-extension-api; implementation platform-tracing-api
Runtime role: Agent isolated CL (otel.javaagent.extensions); also standard jar for unit tests
ClassLoader assumption: Agent CL (extension); self-contained agentExtensionJar embeds api + slf4j
Target role: platform-tracing-otel-extension — thin OTel SPI adapter + private JMX
Should application teams depend on it directly: no
Migration sensitivity: HIGH
Notes: 99 production classes; verifyAgentJarContents + verifyExtensionSpiRegistration Gradle tasks
```

#### `platform-tracing-spring-boot-autoconfigure`

```text
Module: platform-tracing-spring-boot-autoconfigure
Public-facing: no (transitive via starter)
Internal role: TracingProperties, 13 AutoConfiguration classes, Actuator /actuator/tracing, SamplingControlClient (JMX client), RefreshScope integration
Framework dependencies: Spring Boot autoconfigure, Micrometer, compileOnly actuator/cloud-kafka
Runtime role: Application CL
ClassLoader assumption: Application CL
Target role: common Spring adapter, TracingConfigReconciler (new), Actuator READ, JMX client
Should application teams depend on it directly: no
Migration sensitivity: HIGH
Notes: Actuator currently exposes WriteOperation (mutation) — target: dev-only
```

#### `platform-tracing-autoconfigure-webmvc`

```text
Module: platform-tracing-autoconfigure-webmvc
Public-facing: no
Internal role: ServletTracingAutoConfiguration, response header filter, MVC observation conventions, outbound HTTP interceptor
Framework dependencies: api autoconfigure; compileOnly servlet/web/actuator
Runtime role: Application CL, Servlet stack only
ClassLoader assumption: Application CL
Target role: platform-tracing-autoconfigure-webmvc
Should application teams depend on it directly: no
Migration sensitivity: MEDIUM
Notes: WebStackIsolationTest verifies reactive beans not loaded
```

#### `platform-tracing-autoconfigure-webflux`

```text
Module: platform-tracing-autoconfigure-webflux
Public-facing: no
Internal role: ReactiveTracingAutoConfiguration, Reactor context propagation, WebFilter, reactive observation conventions
Framework dependencies: api autoconfigure; compileOnly webflux/reactor
Runtime role: Application CL, Reactive stack only
ClassLoader assumption: Application CL
Target role: platform-tracing-autoconfigure-webflux
Should application teams depend on it directly: no
Migration sensitivity: MEDIUM
Notes: TracingReactorEagerInitConfiguration for publishOn/context propagation hot path
```

### 3.3. Verification / test / performance modules

#### `platform-tracing-test`

```text
Module: platform-tracing-test
Category: test/support
What assets it contains: InMemorySpanExporter harness, JUnit5 extensions, span assertions, ArchUnit rules (TracingArchRules, OtelSdkArchRules), SemconvStrictTestAutoConfiguration
Which evidence gates it can support: unit/integration test infrastructure; arch constraints
Can be reused for PR-0: yes (fixtures for duplicated tests before core extraction)
Can be reused for PR-6/E6: partial (not perf harness itself)
Migration sensitivity: MEDIUM
Notes: api dependency on core — tests follow core boundary changes
```

#### `platform-tracing-e2e-tests`

```text
Module: platform-tracing-e2e-tests
Category: e2e
What assets it contains: Testcontainers SDK→Collector→Jaeger chain; Agent smoke tests; RuntimeSamplingControlSmokeTest; MDC/WebFlux/Reactor e2e; custom scrubbing rule smoke
Which evidence gates it can support: E1 (integration), E2 (tail sampling), runtime control smoke, agent SPI verification
Can be reused for PR-0: yes (RuntimeSamplingControlSmokeTest — baseline behavior lock)
Can be reused for PR-6/E6: partial (operational smoke, not macro perf)
Migration sensitivity: HIGH
Notes: requires -PrunE2e + Docker; depends on collector-config YAML module
```

#### `platform-tracing-bench`

```text
Module: platform-tracing-bench
Category: benchmark
What assets it contains: 16 JMH classes (sampler, scrubbing, queue, propagation, MDC, typed builders); baseline/compare Gradle tasks; PerformanceReleaseGateTest
Which evidence gates it can support: PR-0 micro perf baseline; E6 JMH regression gates
Can be reused for PR-0: yes
Can be reused for PR-6/E6: yes
Migration sensitivity: HIGH (comparability must be preserved across splits)
Notes: NOT published to Nexus; jmhSaveBaseline / jmhCompareBaseline per hardware profile
```

#### `platform-tracing-perf-tests`

```text
Module: platform-tracing-perf-tests
Category: perf/macro
What assets it contains: SUT Spring Boot app, docker-compose (SUT/Collector/k6), scenarios M0–M10, PowerShell runners, PerfAdminController → JMX bridge
Which evidence gates it can support: E6 macro perf (M5 delta vs M0); M6/M8/M9/M10 degradation scenarios
Can be reused for PR-0: partial (M0 host calibration)
Can be reused for PR-6/E6: yes (primary macro evidence module)
Migration sensitivity: HIGH
Notes: M5 official result FAIL documented in perf-results; PerfAdminController uses JMX for M10 reload
```

#### `platform-tracing-collector-config` (additional)

```text
Module: platform-tracing-collector-config
Category: support/e2e
What assets it contains: versioned OTel Collector YAML (tail_sampling policies); CollectorPolicyContractTest
Which evidence gates it can support: E2 tail-sampling integration
Can be reused for PR-0: unknown
Can be reused for PR-6/E6: no (not micro/macro SUT perf)
Migration sensitivity: LOW
Notes: not in user's original list but active in settings.gradle
```

---

## 4. Developer-facing module guide

```text
Application teams should depend on BOM + exactly one Spring Boot starter.
Application teams should not depend directly on autoconfigure/core/otel-extension modules.
```

| App type | Dependency |
|----------|------------|
| **Spring MVC / Servlet** | `platform-tracing-spring-boot-starter-servlet` (+ platform BOM) |
| **WebFlux / Reactive** | `platform-tracing-spring-boot-starter-reactive` (+ platform BOM) |
| **Explicit tracing facade / semconv / propagation in app code** | `platform-tracing-api` only when needed |
| **Never directly** | `platform-tracing-core`, `platform-tracing-otel-extension`, `platform-tracing-spring-boot-autoconfigure`, `platform-tracing-autoconfigure-webmvc`, `platform-tracing-autoconfigure-webflux` |

**Platform Tracing team only:**

```text
platform-tracing-core
platform-tracing-otel-extension
platform-tracing-spring-boot-autoconfigure
platform-tracing-autoconfigure-webmvc
platform-tracing-autoconfigure-webflux
```

**Verification only:**

```text
platform-tracing-test
platform-tracing-e2e-tests
platform-tracing-bench
platform-tracing-perf-tests
platform-tracing-collector-config  (YAML + contract test, not app dependency)
```

**Agent deployment (ops/SRE, not Gradle dependency):**

```text
platform-tracing-otel-extension agentExtensionJar (classifier: agent)
  → otel.javaagent.extensions=/path/to/platform-tracing-otel-extension-*-agent.jar
```

---

## 5. Repository / Gradle module inventory

| Module | Path | Purpose | Main classes | Test classes | Published | Preservation |
|--------|------|---------|--------------|--------------|-----------|--------------|
| `platform-tracing-bom` | `/platform-tracing-bom` | Dependency BOM | 0 | 0 | yes | HIGH |
| `platform-tracing-api` | `/platform-tracing-api` | Public contracts | 59 | 8 | yes | HIGH |
| `platform-tracing-core` | `/platform-tracing-core` | Facade impl over OTel API | 30 | 11 | yes | HIGH |
| `platform-tracing-otel-extension` | `/platform-tracing-otel-extension` | Agent extension | 99 | 78 | yes (+ agent jar) | HIGH |
| `platform-tracing-spring-boot-autoconfigure` | `/platform-tracing-spring-boot-autoconfigure` | Common Spring adapter | 46 | 34 | yes | HIGH |
| `platform-tracing-autoconfigure-webmvc` | `/platform-tracing-autoconfigure-webmvc` | Servlet autoconfigure | 7 | 8 | yes | HIGH |
| `platform-tracing-autoconfigure-webflux` | `/platform-tracing-autoconfigure-webflux` | Reactive autoconfigure | 11 | 9 | yes | HIGH |
| `platform-tracing-spring-boot-starter-servlet` | `/platform-tracing-spring-boot-starter-servlet` | Servlet starter | 0 | 0 | yes | HIGH |
| `platform-tracing-spring-boot-starter-reactive` | `/platform-tracing-spring-boot-starter-reactive` | Reactive starter | 0 | 0 | yes | HIGH |
| `platform-tracing-test` | `/platform-tracing-test` | Test support library | 14 | 17 | yes | MEDIUM |
| `platform-tracing-e2e-tests` | `/platform-tracing-e2e-tests` | E2E verification | 0 | 42 | no | HIGH |
| `platform-tracing-bench` | `/platform-tracing-bench` | JMH benchmarks | 0 | 3 | no | HIGH |
| `platform-tracing-perf-tests` | `/platform-tracing-perf-tests` | Macro perf SUT | 3 | 0 | no | HIGH |
| `platform-tracing-collector-config` | `/platform-tracing-collector-config` | Collector YAML | 0 | 1 | unknown | MEDIUM |
| `platform-tracing-semconv-lint` | `/platform-tracing-semconv-lint` | Semconv linter scaffold | 10 | 2 | no (excluded) | LOW |

**Detailed per-module notes:**

### `platform-tracing-api`

```text
Module: platform-tracing-api
Path: platform-tracing-api/
Purpose: Public tracing contracts without Spring; no runtime implementation deps
Main packages: api, api.semconv, api.propagation.control, api.mdc, api.spi
Key dependencies: compileOnly OTel context/api; jakarta.annotation-api (no slf4j-api)
Published artifact: yes
Runtime role: Application CL — shared between app and agent extension (embedded in agent jar)
ClassLoader assumption: Application CL + embedded in Agent CL via fat agent jar
Current architecture role: public API layer (contracts only)
Target architecture candidate: platform-tracing-api
Public/internal/test/perf category: public
Migration sensitivity: HIGH
Preservation priority: HIGH
Notes: Implementation in core (propagation.control, mdc.remote, runtime.versioned); compileOnly OTel = MIGRATION_RISK
```

### `platform-tracing-core`

```text
Module: platform-tracing-core
Path: platform-tracing-core/
Purpose: TraceOperations implementation, typed span builders, AttributePolicy, exception handling
Main packages: core, core.span, core.semconv, core.exception, core.propagation.control
Key dependencies: api platform-tracing-api; api opentelemetry-api
Published artifact: yes
Runtime role: Application CL Spring beans
ClassLoader assumption: Application CL
Current architecture role: facade/runtime impl (NOT pure policy core today)
Target architecture candidate: platform-tracing-core (requires extraction/refactor)
Public/internal/test/perf category: internal (transitive)
Migration sensitivity: HIGH
Preservation priority: HIGH
Notes: DefaultTraceOperations + *SpanBuilderImpl must preserve behavior; OTel api dependency = MIGRATION_RISK
```

### `platform-tracing-otel-extension`

```text
Module: platform-tracing-otel-extension
Path: platform-tracing-otel-extension/
Purpose: Java Agent extension — sampling, processors, export safety, JMX control plane (server)
Main packages: otel.extension.sampler, .processor, .scrubbing, .jmx, .factory, .propagation, .resource, .safety
Key dependencies: implementation api; compileOnly OTel SDK/SPI/javaagent-extension-api
Published artifact: yes (standard jar + agentExtensionJar classifier)
Runtime role: Agent CL
ClassLoader assumption: Agent isolated CL; api classes duplicated inside agent jar
Current architecture role: OTel adapter + most production policy runtime today
Target architecture candidate: platform-tracing-otel-extension
Public/internal/test/perf category: internal (agent deployment)
Migration sensitivity: HIGH
Preservation priority: HIGH
Notes: Largest test suite (78 classes); SPI registration verified at build time
```

*(Remaining modules follow same taxonomy as section 3; see Appendix A for paths.)*

---

## 6. Current dependency graph

| Module | Depends on (runtime/api) | Test depends on | Problematic / notes | Matches target direction |
|--------|--------------------------|-----------------|---------------------|--------------------------|
| `platform-tracing-api` | BOM, slf4j-api, jakarta.annotation-api; compileOnly OTel | JUnit, AssertJ, OTel (tests) | compileOnly OTel in public API | **partial** — MIGRATION_RISK |
| `platform-tracing-core` | BOM, **api** api, **api** otel-api | test, otel-sdk | OTel as api transitive dep | **no** — MIGRATION_RISK |
| `platform-tracing-otel-extension` | BOM, implementation api; compileOnly OTel/agent | test, otel-sdk, **test test** | Correct agent isolation | **partial** |
| `platform-tracing-spring-boot-autoconfigure` | BOM, **api** api, **api** core, Spring, Micrometer, otel-api | test, web, webflux, actuator, **otel-extension** | test pulls extension | **partial** |
| `platform-tracing-autoconfigure-webmvc` | BOM, **api** autoconfigure, **api** api, Micrometer, otel-api | test, webflux (isolation test) | extra api api beyond autoconfigure | **partial** |
| `platform-tracing-autoconfigure-webflux` | BOM, **api** autoconfigure, **api** api, context-propagation | test, **starter-reactive** | test uses full starter | **partial** |
| `platform-tracing-spring-boot-starter-servlet` | BOM, api autoconfigure, api webmvc, spring-boot-starter-web | — | Correct starter shape | **yes** |
| `platform-tracing-spring-boot-starter-reactive` | BOM, api autoconfigure, api webflux, spring-boot-starter-webflux | — | Correct starter shape | **yes** |
| `platform-tracing-test` | BOM, **api** api, **api** core, otel-sdk | — | Exposes core to test consumers | **partial** |
| `platform-tracing-e2e-tests` | test: api, core, otel-extension, collector-config, starters | — | Heavy test classpath (intentional) | **yes** (test module) |
| `platform-tracing-bench` | jmh: api, core, otel-extension, otel-sdk | contract tests | Measures pre-split boundaries | **partial** |
| `platform-tracing-perf-tests` | implementation starter-servlet, web, actuator | — | SUT-only | **yes** (perf module) |

**Target direction comparison:**

```text
platform-tracing-api -> JDK only                    CURRENT: partial (compileOnly OTel)     MIGRATION_RISK
platform-tracing-core -> api + JDK                  CURRENT: no (api OTel)                  MIGRATION_RISK
platform-tracing-otel-extension -> core + api + OTel CURRENT: no (missing core policy dep)  MIGRATION_RISK
platform-tracing-spring-boot-autoconfigure -> core + api + Spring  CURRENT: yes shape, extra test deps on extension
starters -> autoconfigure + web*                    CURRENT: yes
```

---

## 7. Current package map

| Package | Module | Approx. class count | Main responsibility | Policy | Adapter | Hot path | Tests | Migration target | Notes |
|---------|--------|--------------------:|---------------------|--------|---------|----------|-------|------------------|-------|
| `...api` | api | 1 | TraceOperations interface | no | no | partial | yes | API | |
| `...api.semconv` | api | 6 | Semconv keys, CategoryContracts | yes | no | no | yes | API | |
| `...api.mdc` | api | 2 | MDC contracts (`TracingMdcKeys`, `RemoteServiceNameSource`) | yes | no | no | partial | API | impl in core.mdc.remote |
| `...api.propagation.control` | api | 7 | Propagation control contracts (interfaces + records) | yes | no | yes | partial | API | impl in core.propagation.control |
| `...api.span` | api | 4 | SpanFactory, categories, links | partial | no | yes | partial | API | |
| `...api.span.spec` | api | 11 | SpanSpec pipeline (builder → execution) | partial | no | yes | partial | API | |
| `...api.manual` | api | 15 | Manual/transport span builders | partial | no | yes | partial | API | |
| `...api.spi` | api | 3 | SpanAttributeScrubbingRule SPI | yes | no | yes | yes | API | |
| `...core.propagation.control` | core | 5 | Default outbound/inbound propagation control impl | yes | partial | yes | yes | CORE | TrustedDestinationMatchers, Default* |
| `...core.semconv` | core | 4 | AttributePolicy, ValidatedAttributes | yes | partial | yes | yes | CORE | |
| `...otel.extension.sampler` | otel-ext | 18 | CompositeSampler, rules, state | yes | yes | **yes** | **yes** | SPLIT_CORE_AND_ADAPTER | primary sampling |
| `...otel.extension.processor` | otel-ext | 11 | Scrub/validate/enrich/export processors | yes | yes | **yes** | **yes** | OTEL_EXTENSION_ADAPTER | |
| `...otel.extension.scrubbing` | otel-ext | 19+ | Rule engine, loaders, merge | yes | partial | **yes** | **yes** | SPLIT_CORE_AND_ADAPTER | |
| `...otel.extension.jmx` | otel-ext | 2 | PlatformTracingControl MBean | partial | yes | no | yes | OTEL_EXTENSION_ADAPTER | Map payloads partial |
| `...autoconfigure` | autoconfigure | 12+ | Properties, core autoconfig | no | yes | partial | yes | SPRING_AUTOCONFIGURE | |
| `...autoconfigure.actuator` | autoconfigure | 6 | TracingActuatorEndpoint, drift diagnostics | partial | yes | no | yes | SPRING_AUTOCONFIGURE | mutation present |
| `...autoconfigure.sampling` | autoconfigure | 2 | SamplingControlClient JMX client | no | yes | no | yes | SPRING_AUTOCONFIGURE | |
| `...autoconfigure.servlet` | webmvc | 7 | Servlet filters, MVC conventions | no | yes | yes | yes | WEBMVC_AUTOCONFIGURE | |
| `...autoconfigure.reactive` | webflux | 11 | WebFilter, Reactor propagation | no | yes | yes | yes | WEBFLUX_AUTOCONFIGURE | |
| `...test.*` | test | 14 | Harness, ArchUnit | no | no | no | yes | TEST_SUPPORT | |

---

## 8. Key production class inventory

### 8.1. Mandatory classes (from requirements checklist)

| Class | Module | Package | Current responsibility | Framework dep | Stateful | Hot path | Tests | Target layer | Preserve/Refactor/Split | Notes |
|-------|--------|---------|------------------------|---------------|----------|----------|-------|--------------|---------------------------|-------|
| `CompositeSampler` | otel-extension | sampler | Head sampling decision chain | OTel Sampler | yes | **yes** | **yes** | SPLIT_CORE_AND_ADAPTER | **Split** | Rule chain in core, OTel callback in extension |
| `SamplerStateHolder` | otel-extension | sampler | Atomic sampler config state | none | yes | **yes** | yes | SPLIT_CORE_AND_ADAPTER | **Split** | Wraps `VersionedStateHolder<SamplerState>` |
| `VersionedStateHolder` | core | runtime.versioned | Versioned atomic config holder (CAS) | none | yes | **yes** | yes | CORE | **Preserve** | Relocated from deleted `api.config` |
| `ScrubbingSpanProcessor` | otel-extension | processor | Mandatory PII scrubbing on span start/end | OTel SpanProcessor | yes | **yes** | **yes** | SPLIT_CORE_AND_ADAPTER | **Split** | |
| `ValidatingSpanProcessor` | otel-extension | processor | Semconv validation on spans | OTel | yes | yes | yes | SPLIT_CORE_AND_ADAPTER | **Split** | optional tier |
| `EnrichingSpanProcessor` | otel-extension | processor | Platform attribute enrichment | OTel | yes | yes | yes | SPLIT_CORE_AND_ADAPTER | **Split** | optional tier |
| `PlatformCompositeSpanProcessor` | otel-extension | processor | Processor chain orchestration | OTel | yes | yes | yes | OTEL_EXTENSION_ADAPTER | **Preserve** | adapter glue |
| `PlatformDropOldestExportSpanProcessor` | otel-extension | processor | Export queue with drop-oldest | OTel | yes | **yes** | **yes** | OTEL_EXTENSION_ADAPTER | **Preserve** | overflow tests extensive |
| `SafeSpanExporter` | otel-extension | exporter | Fail-safe export wrapper | OTel | yes | yes | yes | OTEL_EXTENSION_ADAPTER | **Preserve** | |
| `PlatformTracingControl` | otel-extension | jmx | JMX MBean server (agent side) | JMX | yes | no | yes | OTEL_EXTENSION_ADAPTER | **Preserve** then wire-format migration | Map-based ops partial |
| `PlatformTracingControlMBean` | otel-extension | jmx | MBean interface | JMX | no | no | via impl | API wire schema | **Preserve** | |
| `SamplingControlClient` | autoconfigure | sampling | JMX client from Spring CL | JMX invoke | no | no | yes | SPRING_AUTOCONFIGURE | **Preserve** | no extension import by design |
| `TracingActuatorEndpoint` | autoconfigure | actuator | GET/POST /actuator/tracing | Spring Actuator | partial | no | yes | SPRING_AUTOCONFIGURE | **Refactor** (dev-only mutation) | WriteOperation exists today |
| `TracingProperties` | autoconfigure | autoconfigure | All platform.tracing.* binding | Spring | yes | no | yes | SPRING_AUTOCONFIGURE | **Preserve** | 700+ lines nested |
| `DualChannelDriftDiagnostics` | autoconfigure | actuator | Spring vs Agent property drift | Spring | yes | no | yes | SPRING_AUTOCONFIGURE | **Preserve** | target drift detection precursor |
| `PlatformAutoConfigurationCustomizer` | otel-extension | extension | Agent SPI entry — wires sampler/processors/JMX | OTel SPI | yes | no | yes | OTEL_EXTENSION_ADAPTER | **Preserve** | |
| `PlatformSamplerFactory` | otel-extension | factory | Builds CompositeSampler + state | OTel | no | no | yes | SPLIT_CORE_AND_ADAPTER | **Split** | |
| `PlatformSpanProcessorFactory` | otel-extension | factory | Builds processor chain | OTel | no | no | yes | OTEL_EXTENSION_ADAPTER | **Preserve** | |
| `CategoryContracts` | api | semconv | Category contract registry | none | no | no | yes | API | **Preserve** | |
| `SemconvKeys` | api | semconv | Platform semconv key constants | none | no | partial | partial | API | **Preserve** | |
| `PlatformAttributes` | api | attributes | Standard attribute keys | none | no | yes | partial | API | **Preserve** | |
| `PlatformSamplingReasons` | api | attributes | Sampling reason attribute values | none | no | yes | yes | API | **Preserve** | |
| `TracingConfigReconciler` | — | — | — | — | — | — | — | SPRING_AUTOCONFIGURE | — | **Not found in current codebase** |

### 8.2. Additional key production classes

| Class | Module | Responsibility | Target | Notes |
|-------|--------|----------------|--------|-------|
| `DefaultTraceOperations` | core | Main TraceOperations impl | CORE (adapter today) | SPLIT_CORE_AND_ADAPTER |
| `AttributePolicy` | core | Attribute allow/deny/eager policy | CORE | Preserve |
| `RuntimeConfigApplier` | autoconfigure | Applies RefreshScope props to agent via JMX | SPRING_AUTOCONFIGURE | Precursor to reconciler |
| `TracingRefreshScopeAutoConfiguration` | autoconfigure | @RefreshScope TracingProperties | SPRING_AUTOCONFIGURE | Config Server path today |
| `ExtensionPropertyNames` / `ExtensionDefaults` / `PlatformTracingDefaultsProvider` | otel-extension | Agent-side configuration | OTEL_EXTENSION_ADAPTER | Dual-channel |
| `ServletTracingAutoConfiguration` | webmvc | Servlet stack autoconfig | WEBMVC_AUTOCONFIGURE | |
| `ReactiveTracingAutoConfiguration` | webflux | Reactive stack autoconfig | WEBFLUX_AUTOCONFIGURE | |
| `TracingReactorEagerInitConfiguration` | webflux | Reactor Hooks eager init | WEBFLUX_AUTOCONFIGURE | Hot path |
| `TraceResponseHeaderServletFilter` | webmvc | X-Trace-* response headers | WEBMVC_AUTOCONFIGURE | |
| `TraceResponseHeaderWebFilter` | webflux | Reactive response headers | WEBFLUX_AUTOCONFIGURE | |
| `PerfAdminController` | perf-tests | /perf/admin/* → JMX for M10 | PERF_TESTS | TEST_BEFORE_MOVE if reused in prod patterns |

---

## 9. Preservation-critical components

### 9.1. Sampling

```text
Current classes: CompositeSampler, SamplerStateHolder, SamplerState, *Rule (KillSwitch, ForceHeader, QaTrace, RouteRatio, DefaultRatio, HardDrop, ParentDecision), PlatformSamplerFactory, PlatformSamplerProvider, SafeSampler
Current decision chain: KillSwitch → ForceHeader (X-Trace-On) → QaTrace (X-QA-Trace) → RouteRatio → DefaultRatio → HardDrop → ParentDecision (see CompositeSampler)
Current state holders: SamplerStateHolder (wraps VersionedStateHolder<SamplerState>)
Hot path: yes — every root span sampling decision
Config inputs: ExtensionPropertyNames / ConfigProperties / PlatformTracingDefaultsProvider (agent); platform.tracing.sampling.* via TracingProperties → JMX apply
Runtime mutation support: yes — JMX setSamplingRatio; Actuator POST samplingRatio; RefreshScope batch apply via RuntimeConfigApplier
Tests: CompositeSamplerTest, RouteRatioTest, EdgeCasesTest, SamplerStateHolderTest, SamplerRuntimeUpdateConcurrencyTest, PlatformSamplerProviderTest, RuntimeConfigApplierTest, RuntimeSamplingControlSmokeTest (e2e)
Benchmarks: CompositeSamplerBenchmark, CompositeSamplerPolicyBranchesBenchmark, CompositeSamplerConcurrentUpdateBenchmark
Target split: policy rules + state → core; OTel Sampler adapter → otel-extension
Preservation risk: HIGH
Migration recommendation: DUPLICATE_BEFORE_MOVE — copy rule chain tests to core harness before extraction; keep JMH baselines unchanged until split proven equivalent
```

### 9.2. Scrubbing

```text
Current classes: ScrubbingSpanProcessor, ScrubbingPolicyHolder, ScrubbingSnapshot, scrubbing.engine.*, scrubbing.loader.*, BuiltInRules, SpanAttributeScrubbingRule (api SPI)
Rule model: SPI SpanAttributeScrubbingRule + built-in rules + YAML/ServiceLoader loaders + merge engine + per-rule circuit breaker
Sensitive data handling: attribute key/value scrubbing, exception events, IP truncation, SQL/URL sanitizers in api
Fail-open/fail-closed: processor catches errors — span export continues (non-blocking); rule circuit breaker disables hot rules
Regex/ReDoS risks: RuleCircuitBreaker, ScrubbingSecurityNegativeTest, KeyMatcher tests
Tests: ScrubbingSpanProcessorTest, AdvancedTest, SecurityNegativeTest, BuiltInRulesTest, MergeEngineTest, ServiceLoaderSpanAttributeScrubbingRuleTest, ExceptionEventScrubbingE2ETest
Benchmarks: ScrubbingEngineBenchmark, ScrubbingPerRuleBenchmark, ScrubbingFixtures (contract test)
Target split: rule evaluation engine → core; OTel SpanProcessor callback → extension
Preservation risk: CRITICAL (mandatory baseline pipeline)
Migration recommendation: MUST_KEEP all scrubbing tests; run ScrubbingEngineBenchmark before/after split
```

### 9.3. Validation

```text
Current classes: ValidatingSpanProcessor, ValidationPolicyHolder, ValidationSnapshot, CategoryContracts (api), ValidationMode (api)
Semantic validation rules: category contracts, mandatory attributes, eager-only keys
Strict/permissive behavior: ValidationMode LENIENT/STRICT via config
Tests: ValidatingSpanProcessorTest, ValidationPolicyRuntimeTest, CategoryContractsTest
Target split: validation policy → core; processor adapter → extension
Preservation risk: MEDIUM (optional tier but heavily tested)
```

### 9.4. Enrichment

```text
Current classes: EnrichingSpanProcessor, SpanEnricher (core), DefaultSpanEnrichment (core)
Source of enrichment data: MDC, configured attributes, remote service context
Target attributes: platform.* semantic attributes, service metadata
Mandatory or optional: optional tier (enriching.enabled)
Tests: EnrichingSpanProcessorTest, AdvancedTest, SpanEnricherTest
Target split: enrichment policy → core; OTel processor → extension
Preservation risk: MEDIUM
```

### 9.5. Export / processor safety

```text
Current classes: PlatformDropOldestExportSpanProcessor, SafeSpanExporter, PlatformCompositeSpanProcessor, MetricsSpanProcessor, SpanWatchdogProcessor, DegradedModeController, CircuitBreaker (safety package)
Safe exporter behavior: wraps delegate; rate-limited error logging; no export storm
Queue/drop behavior: drop-oldest with metrics by reason; configurable queue size
Degraded behavior: DegradedModeController, TokenBucketRateLimiter, PlatformLogControl
Tests: PlatformDropOldest*Test (overflow, lifecycle, builder validation), SafeSpanExporterTest, DegradedModeControllerTest, BspDropOldestSafetyAgentSmokeTest (e2e)
Benchmarks: QueueOfferBenchmark, CompositePipelineBenchmark
Topology/startup-only relevance: queue/exporter config from TracingProperties + PlatformTracingDefaultsProvider
Preservation risk: HIGH
```

### 9.6. JMX / control plane

```text
MBean classes: PlatformTracingControl, PlatformTracingControlMBean (otel-extension, Agent CL)
Client classes: SamplingControlClient (autoconfigure, App CL)
Current payload types: primitives (double ratio), String[], Map/list operations for config reload (see PlatformTracingControl methods)
Raw Java DTO usage: no cross-CL DTO sharing — invoke-by-name design
Map/Object usage: yes — partial Map-based config operations on MBean
Validation behavior: invalid config increments invalidConfigCounter; failures logged WARN, non-blocking
Failure behavior: SamplingControlUnavailableException → HTTP 503 on Actuator; JMX client returns Optional.empty()
LKG behavior: agent keeps last applied SamplerState on invalid update (VersionedStateHolder pattern)
Tests: PlatformTracingControlTest, SamplingControlClientTest, RuntimeSamplingControlSmokeTest
Target migration: validated Map/OpenMBean-compatible wire
Preservation risk: HIGH
Notes: Risk: raw Java DTO crosses App CL / Agent CL boundary — CURRENT design avoids this via MBeanServer.invoke; future Map wire must preserve semantics
```

### 9.7. Spring Boot common autoconfigure

```text
Auto-configuration classes: TracingCoreAutoConfiguration, SemanticLayerAutoConfiguration, TracingMetricsAutoConfiguration, TracingAopAutoConfiguration, TracingObservationAutoConfiguration, TracingActuatorAutoConfiguration, RequestContextSupplierAutoConfiguration, ServiceNameProviderAutoConfiguration, TracingRefreshScopeAutoConfiguration, TracingAsyncContextAutoConfiguration, PlatformOutboundPropagationAutoConfiguration, PlatformKafkaAutoConfiguration, PlatformKafkaOutboundAutoConfiguration
Properties classes: TracingProperties (prefix platform.tracing)
Conditional beans: @ConditionalOnClass, @ConditionalOnProperty throughout
Actuator endpoints: TracingActuatorEndpoint — GET read + POST write (enabled, samplingRatio)
JMX client integration: SamplingControlClient wired into Actuator + RuntimeConfigApplier
Config refresh support: TracingRefreshScopeAutoConfiguration + RuntimeConfigApplier → JMX batch apply
Desired-state related classes: DualChannelDriftDiagnostics (drift detection); TracingConfigReconciler NOT FOUND
Test coverage: TracingAutoConfigurationTest, TracingPropertiesBindingTest, TracingActuatorEndpointTest, DualChannelDriftDiagnosticsTest, RuntimeConfigApplierTest
Target role: common Spring adapter + reconciler (new)
Preservation risk: HIGH
```

### 9.8. WebMVC-specific autoconfigure

```text
Module: platform-tracing-autoconfigure-webmvc
Auto-config classes: ServletTracingAutoConfiguration, WebMvcSuppressMicrometerTracingAutoConfiguration
Servlet filters/interceptors: TraceResponseHeaderServletFilter, PlatformOutboundHttpInterceptor
MVC-specific tracing behavior: PlatformServerRequestObservationConvention, PlatformClientRequestObservationConvention; suppress duplicate Micrometer HTTP spans
Dependencies: api autoconfigure; compileOnly servlet/web
Tests: TraceResponseHeaderServletFilterTest, WebStackIsolationTest, DuplicateSpansRegressionMatrixTest, ServletOutboundNoSpanArchTest
Starter connection: platform-tracing-spring-boot-starter-servlet
Target role: WEBMVC_AUTOCONFIGURE unchanged
Preservation risk: MEDIUM-HIGH
```

### 9.9. WebFlux-specific autoconfigure

```text
Module: platform-tracing-autoconfigure-webflux
Auto-config classes: ReactiveTracingAutoConfiguration, TracingReactorEagerInitConfiguration, WebFluxSuppressMicrometerTracingAutoConfiguration
WebFilter / Reactor: TraceResponseHeaderWebFilter, PlatformOutboundExchangeFilterFunction, RemoteServiceContextPropagation, TracingReactorContextPropagationStartupRunner
Reactive tracing behavior: PlatformReactive*ObservationConvention; BridgeOtelReactorContextPropagation
Dependencies: api autoconfigure, context-propagation
Tests: TracingReactorEagerInitConfigurationTest, ReactorContextPropagationIntegrationTest, MdcPropagationWebFluxIntegrationTest, DuplicateSpansRegressionMatrixTest
Starter connection: platform-tracing-spring-boot-starter-reactive
Target role: WEBFLUX_AUTOCONFIGURE unchanged
Preservation risk: MEDIUM-HIGH
```

### 9.10. Starters

```text
Starter module: platform-tracing-spring-boot-starter-servlet
Dependencies it pulls: BOM (consumer), autoconfigure, autoconfigure-webmvc, spring-boot-starter-web
Intended app type: Servlet / Spring MVC
Auto-config modules included: all META-INF/spring AutoConfiguration.imports from autoconfigure + webmvc
Current developer experience: single dependency declaration
Target developer experience: unchanged
Preservation risk: HIGH

Starter module: platform-tracing-spring-boot-starter-reactive
Dependencies it pulls: BOM (consumer), autoconfigure, autoconfigure-webflux, spring-boot-starter-webflux
Intended app type: WebFlux / Reactive
Auto-config modules included: autoconfigure + webflux imports
Current developer experience: single dependency declaration
Target developer experience: unchanged
Preservation risk: HIGH
```

### 9.11. Semantic conventions / public API

```text
Constants: SemconvKeys, PlatformAttributes, PlatformSamplingReasons, PlatformHeaders, TracingMdcKeys
Span API: SpanFactory + api.span.spec.* (SpanSpecBuilder → SpanExecution); api.manual.* transport builders; core span impl
Public interfaces: TraceOperations, SpanHandle, SpanAttributeScrubbingRule SPI, PlatformContextPropagation
Propagation contracts: InboundTraceControl, TraceControlHeaderInjector, OutboundPropagationPolicy, TrustedDestinationMatcher, InboundTraceControlExtractor (api); Default* + TrustedDestinationMatchers (core.propagation.control)
Backward compatibility sensitivity: HIGH — app code compiles against api
Tests: CategoryContractsTest, SanitizerTest, propagation tests, RemoteServiceMdcTest
Target role: platform-tracing-api unchanged as public contract module
Preservation risk: CRITICAL
```

---

## 10. Current test inventory

**Total:** 213 test classes across modules. Full list — Appendix C.

### Summary by category

| Category | Count (approx.) | Representative tests | Tag |
|----------|----------------:|----------------------|-----|
| **Unit tests** | 120+ | `CompositeSamplerTest`, `AttributePolicyTest`, `VersionedStateHolderTest` | MUST_KEEP |
| **Spring Boot tests** | 40+ | `TracingAutoConfigurationTest`, `TracingPropertiesBindingTest` | MUST_KEEP |
| **WebMVC tests** | 8 | `WebStackIsolationTest`, `TraceResponseHeaderServletFilterTest` | MUST_KEEP |
| **WebFlux tests** | 9 | `TracingReactorEagerInitConfigurationTest`, `ReactorContextPropagationIntegrationTest` | MUST_KEEP |
| **OTel extension tests** | 78 | processor/sampler/scrubbing/resource/propagation suites | MUST_KEEP |
| **JMX / control-plane tests** | 6+ | `PlatformTracingControlTest`, `SamplingControlClientTest`, `RuntimeConfigApplierTest` | MUST_KEEP / ADAPT_FOR_NEW_ARCHITECTURE |
| **Config / property binding** | 5+ | `TracingPropertiesBindingTest`, `SharedDefaultsAlignmentTest`, `PlatformTracingDefaultsProviderTest` | MUST_KEEP |
| **E2E tests** | 42 | `TracingE2ETest`, `RuntimeSamplingControlSmokeTest`, Agent*Smoke* | MUST_KEEP |
| **Contract tests** | 4 | `PerformanceBudgetsContractTest`, `CollectorPolicyContractTest` | MUST_KEEP |
| **Perf tests** | 0 Java (scripts/docker) | M0–M10 via `run-perf-scenario.ps1` | MUST_KEEP |
| **ArchUnit tests** | 10+ | `ExtensionNoSpringDependencyArchTest`, `OtelDirectIntegrationArchTest` | ADAPT_FOR_NEW_ARCHITECTURE |
| **Test fixtures** | 14+ main in test module | `InMemorySpanExporter`, `TracingArchRules` | KEEP_IN_TEST_SUPPORT |

### Key test table (selected)

| Test class | Module | Type | What it protects | Related production | Fast/slow | Migration relevance | Keep/Adapt |
|------------|--------|------|------------------|-------------------|-----------|---------------------|------------|
| `RuntimeSamplingControlSmokeTest` | e2e | e2e smoke | JMX ratio + X-Trace-On under Agent | CompositeSampler, SamplingControlClient | slow | PR-0 baseline | **MUST_KEEP** |
| `CompositeSamplerConcurrentUpdateBenchmark` | bench | JMH | concurrent read/write sampling | SamplerStateHolder | slow | PR-0/E6 | **MUST_KEEP** |
| `ScrubbingSecurityNegativeTest` | otel-ext | unit/security | ReDoS/injection paths | scrubbing engine | fast | scrubbing split | **MUST_KEEP** |
| `DualChannelDriftDiagnosticsTest` | autoconfigure | Spring | spring vs agent drift | DualChannelDriftDiagnostics | fast | reconciler precursor | **ADAPT** |
| `WebStackIsolationTest` | webmvc | Spring | stack isolation | servlet vs reactive autoconfig | fast | module taxonomy | **MUST_KEEP** |
| `DuplicateSpansRegressionMatrixTest` | webmvc/webflux | regression | no duplicate HTTP spans | suppress autoconfig | medium | starter path | **MUST_KEEP** |
| `PerformanceReleaseGateTest` | bench | contract | release perf gate | performance-budgets.yaml | fast | E6 | **MUST_KEEP** |
| `RuntimeConfigApplierTest` | autoconfigure | Spring | RefreshScope→JMX apply | RuntimeConfigApplier | fast | Config Server migration | **ADAPT** |
| `ClassLoaderVisibilitySpikeE2ETest` | e2e | spike | CL boundary assumptions | agent extension | slow | JMX wire migration | **ADAPT** |

### Gaps (MISSING_TEST_FOR_CRITICAL_BEHAVIOR)

| Area | Gap |
|------|-----|
| `TracingConfigReconciler` | Not applicable — class does not exist yet |
| Prod Actuator mutation disabled by default | No test found enforcing prod profile mutation off — **MISSING_TEST_FOR_CRITICAL_BEHAVIOR** (target requirement) |
| Map/OpenMBean JMX wire contract | Current tests use typed MBean invoke — **ADAPT_FOR_NEW_ARCHITECTURE** needed after wire change |
| Pure core policy unit tests | Policy currently tested via OTel adapters — **DUPLICATE_BEFORE_MOVE** required |

---

## 11. Benchmark / performance inventory

| Benchmark/perf asset | Module | Type | Measures | Related production | Hot path | Baseline | PR-0 | E6 | Priority |
|---------------------|--------|------|----------|-------------------|----------|----------|------|-----|----------|
| `CompositeSamplerBenchmark` | bench | JMH | sampler overhead | CompositeSampler | yes | yes (baselines/) | yes | yes | HIGH |
| `CompositeSamplerPolicyBranchesBenchmark` | bench | JMH | rule branch costs | *Rule classes | yes | yes | yes | yes | HIGH |
| `CompositeSamplerConcurrentUpdateBenchmark` | bench | JMH | concurrent update | SamplerStateHolder | yes | yes | yes | yes | HIGH |
| `ScrubbingEngineBenchmark` | bench | JMH | scrubbing engine | scrubbing.engine | yes | yes | yes | yes | HIGH |
| `ScrubbingPerRuleBenchmark` | bench | JMH | per-rule cost | SpanAttributeScrubbingRule | yes | yes | yes | yes | HIGH |
| `QueueOfferBenchmark` | bench | JMH | BSP vs drop-oldest offer path | PlatformDropOldestExportSpanProcessor | yes | yes | yes | yes | HIGH |
| `CompositePipelineBenchmark` | bench | JMH | full processor pipeline | processor chain | yes | yes | partial | yes | HIGH |
| `AttributePolicyBenchmark` | bench | JMH | attribute policy eval | AttributePolicy | yes | yes | yes | yes | MEDIUM |
| `TypedBuilderBenchmark` | bench | JMH | span builder alloc/latency | *SpanBuilderImpl | yes | yes | yes | partial | MEDIUM |
| `HeaderPropagationBenchmark` | bench | JMH | propagation inject/extract | InboundTraceControlPropagator | yes | yes | yes | partial | MEDIUM |
| `MdcCorrelationBenchmark` | bench | JMH | MDC bridge | RemoteServiceMdc | partial | yes | partial | partial | MEDIUM |
| `TracedAspectBenchmark` | bench | JMH | @Traced AOP overhead | TracingAspect | partial | yes | partial | partial | MEDIUM |
| `PerformanceReleaseGateTest` | bench | contract | hard budgets closed | performance-budgets.yaml | n/a | yes | no | yes | HIGH |
| M0 scenario | perf-tests | macro/load | host noise calibration | SUT baseline | n/a | yes (perf-results/) | yes | yes | HIGH |
| M5 scenario | perf-tests | macro/load | agent+extension+export delta | full stack | n/a | yes (FAIL documented) | partial | yes | HIGH |
| M10/M10c/M10d | perf-tests | macro/load | config reload under load | RuntimeConfigApplier, JMX | no | partial | no | yes | HIGH |
| M6/M8* | perf-tests | macro/degraded | collector failure modes | SafeSpanExporter, degraded | no | partial | no | yes | MEDIUM |

**Gap:** no JMH benchmark dedicated to `SamplingControlClient` JMX invoke path (cold path — acceptable).

---

## 12. Current configuration model

| Property prefix | Class | Module | Current fields (top-level) | Runtime mutable | Topology or policy | Target source | Migration notes |
|-----------------|-------|--------|---------------------------|-----------------|-------------------|---------------|-----------------|
| `platform.tracing` | `TracingProperties` | autoconfigure | enabled, sdk, service, resource, facade, sampling, limits, queue, scrubbing, exporter, response, serviceNames, aop, suppression, enriching, validation, semantic, watchdog, propagation, kafka, contextPropagation, diagnostics | **yes** (RefreshScope + Actuator write for subset) | **both** | HELM_ENV_BOOTSTRAP_DEFAULT + CONFIG_SERVER_RUNTIME_POLICY | Split topology vs policy per target |
| `platform.tracing.sampling.*` | `TracingProperties.Sampling` | autoconfigure | ratio, routes, killSwitch, qaTrace, forceHeaders | **yes** (JMX/Actuator/Refresh) | policy | CONFIG_SERVER_RUNTIME_POLICY | Core extraction candidate |
| `platform.tracing.scrubbing.*` | `TracingProperties.Scrubbing` | autoconfigure | enabled, rules, mode | partial via JMX reload | policy | CONFIG_SERVER_RUNTIME_POLICY | Mandatory baseline |
| `platform.tracing.validation.*` | `TracingProperties.Validation` | autoconfigure | enabled, mode | partial | policy | CONFIG_SERVER_RUNTIME_POLICY | Optional tier |
| `platform.tracing.enriching.*` | `TracingProperties.Enriching` | autoconfigure | enabled, attributes | partial | policy | CONFIG_SERVER_RUNTIME_POLICY | Optional tier |
| `platform.tracing.exporter.*` / `queue.*` | nested classes | autoconfigure | endpoint, protocol, queue size, overflow policy | mostly startup | topology | HELM_ENV_STARTUP_TOPOLOGY | |
| `platform.tracing.sdk.mode` | `TracingProperties.Sdk` | autoconfigure | AUTO/AGENT/STARTER/EXTERNAL/DISABLED | no | topology/diagnostic | HELM_ENV_BOOTSTRAP_DEFAULT | Dual-channel with agent configuration |
| OTel agent env | `ExtensionPropertyNames` / `PlatformTracingDefaultsProvider` | otel-extension | mirrors sampling/scrubbing/validation/etc. | via JMX reload | policy (applied) | INTERNAL_DERIVED | Agent applied state, not source of truth |
| `management.endpoints.web.exposure` | (Spring) | consumer app | actuator exposure | yes | topology | HELM_ENV_STARTUP_TOPOLOGY | Mutation exposure risk |
| Starter metadata | `META-INF/spring/*` | autoconfigure/web* | AutoConfiguration.imports | no | topology | INTERNAL_DERIVED | |

**Not found:** dedicated `TracingDesiredState` / reconciler property classes — target-only.

---

## 13. Current control-plane model

| Mechanism | Present | Details |
|-----------|---------|---------|
| **Startup properties** | yes | `application.yaml`, env vars, Helm → `TracingProperties` |
| **Spring properties** | yes | `@ConfigurationProperties` prefix `platform.tracing` |
| **Actuator endpoints** | yes | `GET /actuator/tracing` (READ); `POST /actuator/tracing/{property}/{value}` (MUTATION: enabled, samplingRatio) |
| **JMX calls** | yes | `SamplingControlClient` → `PlatformTracingControl` MBean (Agent CL) |
| **Environment variables** | yes | Standard Spring relaxed binding |
| **System properties** | yes | OTel SDK + platform properties |
| **Config refresh** | yes | Spring Cloud `@RefreshScope` + `RuntimeConfigApplier` → JMX batch apply |
| **Custom runtime mutation** | yes | PerfAdminController (perf-tests only) → JMX |

```text
Current mutation caller: TracingActuatorEndpoint (POST), RuntimeConfigApplier (refresh), SamplingControlClient, PerfAdminController (perf only)
Current mutation target: PlatformTracingControl MBean → SamplerStateHolder / policy holders in Agent
Current validation: VersionedStateHolder replace; invalid config counter; partial validation in MBean methods
Current LKG behavior: VersionedStateHolder retains last good version on failed replace
Current audit: Actuator write logged INFO; JMX WARN on failures
Current drift detection: DualChannelDriftDiagnostics (Spring properties vs Agent effective config)
Current absent agent behavior: SamplingControlUnavailableException / Optional.empty(); local SDK-only mode
Current prod/dev profile distinction: NOT enforced in code for Actuator mutation — WriteOperation always registered if actuator present
Current Actuator MUTATION exposure: YES — no prod disable guard found — MIGRATION_RISK vs target (dev-only mutation)
TracingConfigReconciler: Not found in current codebase
Config Server as authority: partial — RefreshScope binding exists; no dedicated reconciler
```

---

## 14. Classloader boundary inventory

| Boundary | Classes | Notes |
|----------|---------|-------|
| **Agent CL** | All `platform-tracing-otel-extension` main classes; embedded `platform-tracing-api` inside agent jar | Isolated Agent CL |
| **Application CL** | `platform-tracing-core`, autoconfigure, web*, starters, api (from app jar) | Standard Spring Boot CL |
| **Shared API** | `platform-tracing-api` — loaded in both CLs (duplicate definition in agent fat jar) | Must remain CL-neutral |
| **JMX payload** | primitives, String[], Map via OpenMBean-compatible types | No shared Java DTO interface across CLs |
| **Risky cross-CL** | None found for DTO passing — **by design** invoke-by-name | Preserve this property |
| **Safe crossing** | MBeanServer.invoke with open types; VersionedStateHolder in agent (via embedded core) | |

```text
Risk: raw Java DTO crosses App CL / Agent CL boundary.
Current status: AVOIDED — SamplingControlClient does not import extension types.
Migration requirement: replace with validated Map/OpenMBean-compatible wire contract (target), preserving invoke-by-name decoupling.
Evidence: ClassLoaderVisibilitySpikeE2ETest, verifyExtensionDeps Gradle task.
```

---

## 15. Current architecture smell inventory

| Smell | Evidence | Migration risk | Target rule | Handling |
|-------|----------|----------------|-------------|----------|
| **Mixed policy + adapter in otel-extension** | CompositeSampler, ScrubbingSpanProcessor co-locate OTel callbacks with rule logic | HIGH | policy → core | SPLIT_CORE_AND_ADAPTER |
| **OTel types in core-like module** | `platform-tracing-core` api depends on otel-api | HIGH | core = pure Java | Extract policy; keep OTel in extension |
| **OTel compileOnly in public api** | `platform-tracing-api` compileOnly otel-context/api | MEDIUM | api = JDK only | Move OTel-specific keys behind interfaces |
| **Actuator mutation not prod-guarded** | `TracingActuatorEndpoint` @WriteOperation unconditional | HIGH | dev-only mutation | ADAPT — profile guard before removing code |
| **No TracingConfigReconciler** | RefreshScope + RuntimeConfigApplier only | MEDIUM | Config Server authority | ADD in migration wave, preserve RefreshScope behavior |
| **Dual config channels** | TracingProperties + agent configuration (`PlatformTracingDefaultsProvider`) | MEDIUM | desired vs applied | DualChannelDriftDiagnostics → reconciler input |
| **Policy runtime in Agent not core** | Sampler/scrub state in extension | HIGH | narrow core | Move with tests duplicated first |
| **Tests coupled to module boundaries** | bench jmh depends on otel-extension | MEDIUM | dependency direction | Update jmh deps after split, keep benchmark names |
| **Benchmark gaps** | No JMX client JMH | LOW | — | Optional |
| **semconv-lint excluded** | settings.gradle commented | LOW | — | REVIEW_FOR_COLLAPSE_LATER or enable later |

---

## 16. Mapping current modules to target architecture

```text
Do not collapse modules during first migration wave.
First migration wave should clarify taxonomy, enforce dependency direction, preserve tests and move behavior safely.
```

| Current module | Current role | Target category | Target role | Keep module? | Rename? | Collapse? | Public | Migration action | Notes |
|----------------|-------------|-----------------|-------------|--------------|---------|-----------|--------|------------------|-------|
| `platform-tracing-bom` | BOM | BOM | version alignment | yes | no | no | yes | KEEP_PUBLIC | |
| `platform-tracing-api` | public contracts | API | contracts/semconv/wire | yes | no | no | yes | KEEP_PUBLIC | |
| `platform-tracing-core` | OTel facade impl | CORE | pure policy engine | yes | no | **DO_NOT_COLLAPSE_NOW** | no | KEEP_INTERNAL + SPLIT | behavior preserved |
| `platform-tracing-otel-extension` | agent extension | OTEL_EXTENSION | thin adapter + JMX | yes | no | no | no | KEEP_INTERNAL | |
| `platform-tracing-spring-boot-autoconfigure` | Spring common | SPRING_AUTOCONFIGURE | reconciler + actuator read | yes | no | no | no | KEEP_INTERNAL | add reconciler |
| `platform-tracing-autoconfigure-webmvc` | Servlet | WEBMVC | stack-specific | yes | no | no | no | HIDE_BEHIND_STARTER | |
| `platform-tracing-autoconfigure-webflux` | Reactive | WEBFLUX | stack-specific | yes | no | no | no | HIDE_BEHIND_STARTER | |
| `platform-tracing-spring-boot-starter-servlet` | servlet starter | STARTER | public entry | yes | no | no | yes | KEEP_PUBLIC | |
| `platform-tracing-spring-boot-starter-reactive` | reactive starter | STARTER | public entry | yes | no | no | yes | KEEP_PUBLIC | |
| `platform-tracing-test` | test support | TEST_SUPPORT | fixtures | yes | no | no | no | KEEP_VERIFICATION | |
| `platform-tracing-e2e-tests` | e2e | E2E | integration evidence | yes | no | no | no | KEEP_VERIFICATION | |
| `platform-tracing-bench` | JMH | BENCH | micro perf | yes | no | no | no | KEEP_VERIFICATION | |
| `platform-tracing-perf-tests` | macro perf | PERF | M0–M10 | yes | no | no | no | KEEP_VERIFICATION | |
| `platform-tracing-collector-config` | YAML | SUPPORT | collector policies | yes | no | REVIEW_FOR_COLLAPSE_LATER | no | KEEP_VERIFICATION | not runtime jar |
| `platform-tracing-semconv-lint` | lint scaffold | TOOL | CI lint | deferred | no | deferred | no | DEFER | excluded from build |

---

## 17. Mapping current code to target architecture

| Current class/package | Current module | Target module | Target package (indicative) | Migration action | Preserve tests | Risk | Notes |
|----------------------|----------------|---------------|-------------------------------|------------------|----------------|------|-------|
| `VersionedStateHolder` | core | core | `core.runtime.versioned` | DONE (relocated from api) | yes | LOW | agent-internal CAS primitive |
| `CompositeSampler` + rules | otel-extension | core + otel-extension | `core.sampling` + adapter | SPLIT_CORE_AND_ADAPTER | DUPLICATE_BEFORE_MOVE | HIGH | |
| `ScrubbingSpanProcessor` + engine | otel-extension | core + otel-extension | `core.scrubbing` | SPLIT_CORE_AND_ADAPTER | MUST_KEEP | CRITICAL | |
| `ValidatingSpanProcessor` | otel-extension | core + otel-extension | `core.validation` | SPLIT_CORE_AND_ADAPTER | yes | MEDIUM | |
| `DefaultTraceOperations` | core | core | `core` | KEEP_AS_IS (then decouple OTel) | yes | HIGH | TEST_BEFORE_MOVE |
| `TracingProperties` | autoconfigure | autoconfigure | `autoconfigure` | KEEP_IN_SPRING_AUTOCONFIGURE | yes | HIGH | |
| `RuntimeConfigApplier` | autoconfigure | autoconfigure | `autoconfigure.configsource` | KEEP → evolve to reconciler | ADAPT | MEDIUM | |
| `TracingConfigReconciler` | — | autoconfigure | `autoconfigure.configsource` | DEFER (new) | n/a | — | Not found |
| `SamplingControlClient` | autoconfigure | autoconfigure | `autoconfigure.sampling` | KEEP_IN_SPRING_AUTOCONFIGURE | yes | HIGH | |
| `PlatformTracingControl` | otel-extension | otel-extension | `extension.jmx` | KEEP_IN_EXTENSION_AS_ADAPTER | ADAPT | HIGH | Map wire |
| `ServletTracingAutoConfiguration` | webmvc | webmvc | `autoconfigure.servlet` | KEEP_IN_WEBMVC_AUTOCONFIGURE | yes | MEDIUM | |
| `ReactiveTracingAutoConfiguration` | webflux | webflux | `autoconfigure.reactive` | KEEP_IN_WEBFLUX_AUTOCONFIGURE | yes | MEDIUM | |
| Starters (build.gradle) | starter-* | starter-* | — | KEEP_IN_STARTER | n/a | LOW | |
| `InMemorySpanExporter` etc. | test | test | `test` | KEEP_IN_TEST_SUPPORT | yes | LOW | |
| E2E tests | e2e-tests | e2e-tests | — | KEEP_IN_E2E_TESTS | MUST_KEEP | HIGH | |
| JMH benchmarks | bench | bench | — | KEEP_IN_BENCH | MUST_KEEP | HIGH | |
| Perf scripts/SUT | perf-tests | perf-tests | — | KEEP_IN_PERF_TESTS | MUST_KEEP | HIGH | |

---

## 18. Asset preservation plan

### Production behavior

```text
What exists today: CompositeSampler chain, mandatory ScrubbingSpanProcessor, export queue, SafeSpanExporter, TraceOperations facade, WebMVC/WebFlux filters
Why it is valuable: production tracing semantics contracted with platform services
How to preserve during migration: characterization tests + e2e smoke + JMH before any move; no behavior change in PR-0
Which PR should protect it: PR-0 (baseline lock), PR-1+ (incremental split)
Required tests before moving: RuntimeSamplingControlSmokeTest, ScrubbingSecurityNegativeTest, PlatformDropOldest*Test
```

### Public API / semconv

```text
What exists today: 59 classes in api — builders, SemconvKeys, CategoryContracts, propagation
Why it is valuable: app compile-time contracts
How to preserve: no breaking changes to public types; binary compatibility gate
Which PR should protect it: PR-0
Required tests before moving: CategoryContractsTest, all api unit tests
```

### Sampling / Scrubbing / Validation / Enrichment / Export

```text
Preservation pattern: DUPLICATE_BEFORE_MOVE unit tests → extract policy to core → keep adapter delegation → compare JMH/e2e
PR-0: lock JMH + RuntimeSamplingControlSmokeTest + ScrubbingEngineBenchmark baselines
PR-6/E6: jmhCompareBaseline + M5 macro gate
```

### Spring property binding / WebMVC / WebFlux / Starters

```text
What exists today: TracingProperties, 13+ autoconfig classes, 2 stack modules, 2 starters
How to preserve: KEEP module boundaries; ENFORCE_DEPENDENCY_RULES; WebStackIsolationTest must pass every PR
Required tests: TracingPropertiesBindingTest, WebStackIsolationTest, DuplicateSpansRegressionMatrixTest
```

### JMX / Config / Tests / Benchmarks / Perf evidence

```text
JMX: preserve invoke-by-name semantics through Map wire migration
Config: preserve RefreshScope path until reconciler proven equivalent
Tests: 213 test classes — zero net deletion policy for migration wave 1
Benchmarks: 16 JMH classes — names and params frozen for comparability
Perf: M0/M5/M10 scenarios — reuse for E6
Operational: Actuator READ responses must remain shape-stable for SRE dashboards
```

---

## 19. Risk-ranked migration concerns

| Risk | Modules | Classes | Why risky | Loss if mishandled | Protection | PR |
|------|---------|---------|-----------|-------------------|------------|-----|
| Loss of sampling semantics | otel-extension | CompositeSampler, *Rule | complex ordered chain | wrong sample rate / force-trace broken | e2e smoke + JMH + unit tests | PR-0, PR-2 |
| Loss of PII scrubbing | otel-extension | ScrubbingSpanProcessor, engine | mandatory baseline | compliance incident | Scrubbing*Test, e2e scrubbing | PR-0, PR-3 |
| Loss of mandatory span attributes | api, core, otel-ext | CategoryContracts, ValidatingSpanProcessor | semconv enforcement | observability gaps | CategoryContractsTest | PR-1 |
| Breaking current tests | all | 213 tests | module split changes classpath | regression blindness | zero-delete + ADAPT | every PR |
| Breaking benchmark comparability | bench | 16 JMH | package moves change JIT | false perf signals | KEEP_IN_BENCH names | PR-0, PR-6 |
| Breaking Spring property binding | autoconfigure | TracingProperties | 700+ lines nested | config silently ignored | TracingPropertiesBindingTest | PR-7A |
| Breaking WebMVC behavior | webmvc | ServletTracingAutoConfiguration | filter ordering | missing headers/spans | WebStackIsolationTest | PR-4 |
| Breaking WebFlux behavior | webflux | TracingReactorEagerInitConfiguration | Reactor hooks | context loss | ReactorContextPropagationIntegrationTest | PR-4 |
| Breaking starter DX | starters | build.gradle only | teams depend on these | classpath leaks | starter smoke builds | PR-0 |
| Breaking JMX runtime updates | otel-ext, autoconfigure | PlatformTracingControl, SamplingControlClient | cross-CL | ops cannot tune ratio | RuntimeSamplingControlSmokeTest | PR-5 |
| Breaking Config Server semantics | autoconfigure | RefreshScope, RuntimeConfigApplier | no reconciler yet | drift / partial apply | RuntimeConfigApplierTest, E7 | PR-7A |
| Hot path change without perf evidence | otel-ext, core | sampler, scrubbing | micro changes invisible in unit tests | M5 FAIL worsens | JMH + M5 | PR-6 |
| Accidentally exposing mutation in prod | autoconfigure | TracingActuatorEndpoint | WriteOperation present | unauthorized tuning | ADD prod guard test | PR-8 |
| Moving OTel types into core | core | DefaultTraceOperations | target forbids | violates Clean Core | ArchUnit gate | PR-1 |
| Moving Spring types into core | core | none today | prevention | coupling | ExtensionNoSpringDependencyArchTest pattern | PR-1 |
| Collapsing modules too early | all | — | loses stack isolation | Servlet pulls WebFlux | DO_NOT_COLLAPSE_NOW | n/a |
| App teams depend on internal modules | core, otel-ext | — | Gradle allows transitive | unsupported coupling | ENFORCE_DEPENDENCY_RULES | PR-0 |

---

## 20. Perplexity-ready migration planning section

### Perplexity Input Summary

#### 1. Current module taxonomy

- **Public (4):** bom, api, starter-servlet, starter-reactive
- **Internal runtime (5):** core, otel-extension, spring-boot-autoconfigure, autoconfigure-webmvc, autoconfigure-webflux
- **Verification (4+1):** test, e2e-tests, bench, perf-tests, collector-config (YAML support)
- **Excluded scaffold:** semconv-lint (on disk, not in settings.gradle)

#### 2. Current high-value classes/components

- **Agent runtime (99 classes):** CompositeSampler, SpanProcessors (scrub/validate/enrich/export), SafeSpanExporter, PlatformTracingControl JMX
- **Spring adapter (46 classes):** TracingProperties, Actuator, SamplingControlClient, RefreshScope integration
- **Public API (~86 classes):** semconv, SpanFactory/SpanSpec, api.manual, propagation, mdc contracts
- **Core facade (30 classes):** DefaultTraceOperations, AttributePolicy, typed span builders — OTel-coupled today
- **NOT FOUND:** TracingConfigReconciler, TracingDesiredState types

#### 3. Current tests and benchmarks to preserve

- **213** test classes; **42** e2e; **78** otel-extension unit tests
- **16** JMH benchmarks with baseline infrastructure
- **Macro perf:** M0–M10 scenarios, documented M5 FAIL (+48% CPU, +25% RSS)
- **Critical smokes:** `RuntimeSamplingControlSmokeTest`, `PerformanceReleaseGateTest`

#### 4. Target architecture summary

Clean Core Hybrid: pure Java `platform-tracing-core` policy; thin OTel adapter; thin Spring adapter with **TracingConfigReconciler**; Config Server = runtime policy authority; Helm/env = startup topology; Actuator READ in prod; Actuator MUTATION dev-only; JMX private Map wire; baseline = sampling + mandatory scrubbing + export.

#### 5. Non-negotiable preservation constraints

```text
Preserve existing assets first, refactor second.
Do not collapse modules in first migration wave.
Do not delete tests, benchmarks, or e2e smokes.
Do not break starter dependency experience (BOM + one starter).
Do not change hot path behavior without JMH/M5 evidence.
Do not expose Actuator mutation in prod (requires guard — not present today).
Do not pass raw Java DTOs across Agent/App ClassLoader boundary.
```

#### 6. Questions for migration planning

```text
Given this current codebase inventory and the agreed Clean Core Hybrid target architecture, propose a PR-by-PR migration plan that preserves all existing behavior, tests, benchmarks and developer-facing modules.

Which classes should be split first to minimize regression risk?

Which tests must be duplicated or strengthened before extracting platform-tracing-core?

Which existing benchmarks are sufficient for PR-0 and PR-6/E6?

How should current JMX mutation code be migrated to validated Map/OpenMBean-compatible wire format without losing current behavior?

How should current Spring properties be mapped to Helm/bootstrap vs Config Server/runtime desired policy?

How should WebMVC and WebFlux modules be preserved behind their starters without confusing application developers?

Which modules should remain public, which should be internal, and which should be verification-only?

Which module collapses, if any, should be deferred until after first production rollout?
```

---

## 21. Appendix A — Raw module list

| # | Module | Path |
|---|--------|------|
| 1 | `platform-tracing-bom` | `e:\Platform_Traces\platform-tracing-bom` |
| 2 | `platform-tracing-api` | `e:\Platform_Traces\platform-tracing-api` |
| 3 | `platform-tracing-core` | `e:\Platform_Traces\platform-tracing-core` |
| 4 | `platform-tracing-otel-extension` | `e:\Platform_Traces\platform-tracing-otel-extension` |
| 5 | `platform-tracing-spring-boot-autoconfigure` | `e:\Platform_Traces\platform-tracing-spring-boot-autoconfigure` |
| 6 | `platform-tracing-autoconfigure-webmvc` | `e:\Platform_Traces\platform-tracing-autoconfigure-webmvc` |
| 7 | `platform-tracing-autoconfigure-webflux` | `e:\Platform_Traces\platform-tracing-autoconfigure-webflux` |
| 8 | `platform-tracing-spring-boot-starter-servlet` | `e:\Platform_Traces\platform-tracing-spring-boot-starter-servlet` |
| 9 | `platform-tracing-spring-boot-starter-reactive` | `e:\Platform_Traces\platform-tracing-spring-boot-starter-reactive` |
| 10 | `platform-tracing-test` | `e:\Platform_Traces\platform-tracing-test` |
| 11 | `platform-tracing-e2e-tests` | `e:\Platform_Traces\platform-tracing-e2e-tests` |
| 12 | `platform-tracing-bench` | `e:\Platform_Traces\platform-tracing-bench` |
| 13 | `platform-tracing-perf-tests` | `e:\Platform_Traces\platform-tracing-perf-tests` |
| 14 | `platform-tracing-collector-config` | `e:\Platform_Traces\platform-tracing-collector-config` |
| — | `platform-tracing-semconv-lint` (excluded) | `e:\Platform_Traces\platform-tracing-semconv-lint` |

---

## 22. Appendix B � Raw production class list

**Total: 279 entries.** Format: `Module / package / class`

```text
    platform-tracing-api / space.br1440.platform.tracing.api / TraceOperations
    platform-tracing-api / space.br1440.platform.tracing.api.annotation / SuppressAgentInstrumentation
    platform-tracing-api / space.br1440.platform.tracing.api.annotation / Traced
    platform-tracing-api / space.br1440.platform.tracing.api.annotation / TracedAttribute
    platform-tracing-api / space.br1440.platform.tracing.api.attributes / PlatformAttributes
    platform-tracing-api / space.br1440.platform.tracing.api.attributes / PlatformSamplingReasons
    platform-tracing-api / space.br1440.platform.tracing.api.context / RequestTraceContextSnapshot
    platform-tracing-api / space.br1440.platform.tracing.api.mdc / RemoteServiceNameSource
    platform-tracing-api / space.br1440.platform.tracing.api.mdc / TracingMdcKeys
    platform-tracing-api / space.br1440.platform.tracing.api.propagation / PlatformContextPropagation
    platform-tracing-api / space.br1440.platform.tracing.api.propagation / PlatformHeaders
    platform-tracing-api / space.br1440.platform.tracing.api.propagation / RequestIdSupport
    platform-tracing-api / space.br1440.platform.tracing.api.propagation.control / OutboundPropagationPolicy
    platform-tracing-api / space.br1440.platform.tracing.api.propagation.control / TraceControlHeaderInjector
    platform-tracing-api / space.br1440.platform.tracing.api.propagation.control / OutboundPropagationDecision
    platform-tracing-api / space.br1440.platform.tracing.api.propagation.control / PlatformTraceContextKeys
    platform-tracing-api / space.br1440.platform.tracing.api.propagation.control / InboundTraceControl
    platform-tracing-api / space.br1440.platform.tracing.api.propagation.control / InboundTraceControlExtractor
    platform-tracing-api / space.br1440.platform.tracing.api.propagation.control / TrustedDestinationMatcher
    platform-tracing-api / space.br1440.platform.tracing.api.semconv / CategoryContract
    platform-tracing-api / space.br1440.platform.tracing.api.semconv / CategoryContracts
    platform-tracing-api / space.br1440.platform.tracing.api.semconv / SemconvKeys
    platform-tracing-api / space.br1440.platform.tracing.api.semconv / SemconvViolation
    platform-tracing-api / space.br1440.platform.tracing.api.semconv / SemconvViolationException
    platform-tracing-api / space.br1440.platform.tracing.api.semconv / SemconvValidationMode
    platform-tracing-api / space.br1440.platform.tracing.api.span / SpanCategory
    platform-tracing-api / space.br1440.platform.tracing.api.span / RemoteSpanLink
    platform-tracing-api / space.br1440.platform.tracing.api.span / SpanFactory
    platform-tracing-api / space.br1440.platform.tracing.api.span / SpanResult
    platform-tracing-api / space.br1440.platform.tracing.api.span.spec / DefaultSpanSpecBuilder
    platform-tracing-api / space.br1440.platform.tracing.api.span.spec / ImmutableSpanRelationshipSpec
    platform-tracing-api / space.br1440.platform.tracing.api.span.spec / SpanExecution
    platform-tracing-api / space.br1440.platform.tracing.api.span.spec / SpanHandle
    platform-tracing-api / space.br1440.platform.tracing.api.span.spec / SpanRelationship
    platform-tracing-api / space.br1440.platform.tracing.api.span.spec / SpanRelationshipSpec
    platform-tracing-api / space.br1440.platform.tracing.api.span.spec / SpanSpec
    platform-tracing-api / space.br1440.platform.tracing.api.span.spec / SpanSpecAttributeValue
    platform-tracing-api / space.br1440.platform.tracing.api.span.spec / SpanSpecBuilder
    platform-tracing-api / space.br1440.platform.tracing.api.span.spec / SpanSpecImpl
    platform-tracing-api / space.br1440.platform.tracing.api.span.spec / SpanSpecReason
    platform-tracing-api / space.br1440.platform.tracing.api.manual / ActiveTraceContextView
    platform-tracing-api / space.br1440.platform.tracing.api.manual / DatabaseSpanBuilder
    platform-tracing-api / space.br1440.platform.tracing.api.manual / HttpClientSpanBuilder
    platform-tracing-api / space.br1440.platform.tracing.api.manual / HttpServerSpanBuilder
    platform-tracing-api / space.br1440.platform.tracing.api.manual / HttpTracing
    platform-tracing-api / space.br1440.platform.tracing.api.manual / KafkaBatchSpanBuilder
    platform-tracing-api / space.br1440.platform.tracing.api.manual / KafkaConsumerSpanBuilder
    platform-tracing-api / space.br1440.platform.tracing.api.manual / KafkaProducerSpanBuilder
    platform-tracing-api / space.br1440.platform.tracing.api.manual / KafkaTracing
    platform-tracing-api / space.br1440.platform.tracing.api.manual / ManualSpanBuilder
    platform-tracing-api / space.br1440.platform.tracing.api.manual / OperationSpanBuilder
    platform-tracing-api / space.br1440.platform.tracing.api.manual / RpcClientSpanBuilder
    platform-tracing-api / space.br1440.platform.tracing.api.manual / RpcServerSpanBuilder
    platform-tracing-api / space.br1440.platform.tracing.api.manual / RpcTracing
    platform-tracing-api / space.br1440.platform.tracing.api.manual / TransportTracing
    platform-tracing-api / space.br1440.platform.tracing.api.span.enrich / SpanEnrichment
    platform-tracing-api / space.br1440.platform.tracing.api.span.enrich / GenericSpanEnrichment
    platform-tracing-api / space.br1440.platform.tracing.api.span.sanitize / SqlSanitizer
    platform-tracing-api / space.br1440.platform.tracing.api.span.sanitize / UrlSanitizer
    platform-tracing-api / space.br1440.platform.tracing.api.spi / ScrubbingAction
    platform-tracing-api / space.br1440.platform.tracing.api.spi / ScrubbingDecision
    platform-tracing-api / space.br1440.platform.tracing.api.spi / SpanAttributeScrubbingRule
    platform-tracing-api / space.br1440.platform.tracing.api.util / ThrowingSupplier
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / PlatformClientRequestBuilderSetter
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / PlatformOutboundExchangeFilterFunction
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / PlatformReactiveClientRequestObservationConvention
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / PlatformReactiveServerRequestObservationConvention
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / ReactiveTracingAutoConfiguration
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / RemoteServiceContextPropagation
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / RemoteServiceReactorContext
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / TraceResponseHeaderWebFilter
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / TracingReactorContextPropagationStartupRunner
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / TracingReactorEagerInitConfiguration
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / WebFluxSuppressMicrometerTracingAutoConfiguration
    platform-tracing-autoconfigure-webmvc / space.br1440.platform.tracing.autoconfigure.servlet / PlatformClientRequestObservationConvention
    platform-tracing-autoconfigure-webmvc / space.br1440.platform.tracing.autoconfigure.servlet / PlatformHttpRequestSetter
    platform-tracing-autoconfigure-webmvc / space.br1440.platform.tracing.autoconfigure.servlet / PlatformOutboundHttpInterceptor
    platform-tracing-autoconfigure-webmvc / space.br1440.platform.tracing.autoconfigure.servlet / PlatformServerRequestObservationConvention
    platform-tracing-autoconfigure-webmvc / space.br1440.platform.tracing.autoconfigure.servlet / ServletTracingAutoConfiguration
    platform-tracing-autoconfigure-webmvc / space.br1440.platform.tracing.autoconfigure.servlet / TraceResponseHeaderServletFilter
    platform-tracing-autoconfigure-webmvc / space.br1440.platform.tracing.autoconfigure.servlet / WebMvcSuppressMicrometerTracingAutoConfiguration
    platform-tracing-core / space.br1440.platform.tracing.core.propagation.control / DefaultInboundTraceControlExtractor
    platform-tracing-core / space.br1440.platform.tracing.core.propagation.control / DefaultOutboundPropagationPolicy
    platform-tracing-core / space.br1440.platform.tracing.core.propagation.control / DefaultTraceControlHeaderInjector
    platform-tracing-core / space.br1440.platform.tracing.core.propagation.control / GlobTrustedDestinationMatcher
    platform-tracing-core / space.br1440.platform.tracing.core.propagation.control / TrustedDestinationMatchers
    platform-tracing-core / space.br1440.platform.tracing.core / DefaultTraceOperations
    platform-tracing-core / space.br1440.platform.tracing.core / NoOpPlatformContextPropagation
    platform-tracing-core / space.br1440.platform.tracing.core / NoopTraceOperations
    platform-tracing-core / space.br1440.platform.tracing.core / OtelPlatformContextPropagation
    platform-tracing-core / space.br1440.platform.tracing.core.exception / ExceptionMessagePolicy
    platform-tracing-core / space.br1440.platform.tracing.core.exception / ExceptionRecorder
    platform-tracing-core / space.br1440.platform.tracing.core.semconv / AttributePolicy
    platform-tracing-core / space.br1440.platform.tracing.core.semconv / EagerOnlyKeys
    platform-tracing-core / space.br1440.platform.tracing.core.semconv / SemconvMetrics
    platform-tracing-core / space.br1440.platform.tracing.core.semconv / ValidatedAttributes
    platform-tracing-core / space.br1440.platform.tracing.core.manual / AbstractSemanticSpanBuilder
    platform-tracing-core / space.br1440.platform.tracing.core.span / AbstractTypedSpanBuilder
    platform-tracing-core / space.br1440.platform.tracing.core.span / DatabaseSpanBuilderImpl
    platform-tracing-core / space.br1440.platform.tracing.core.span / DefaultSpanEnrichment
    platform-tracing-core / space.br1440.platform.tracing.core.span / DefaultGenericSpanEnrichment
    platform-tracing-core / space.br1440.platform.tracing.core.span / HttpClientSpanBuilderImpl
    platform-tracing-core / space.br1440.platform.tracing.core.span / HttpServerSpanBuilderImpl
    platform-tracing-core / space.br1440.platform.tracing.core.span / InternalSpanBuilderImpl
    platform-tracing-core / space.br1440.platform.tracing.core.span / KafkaConsumerSpanBuilderImpl
    platform-tracing-core / space.br1440.platform.tracing.core.span / KafkaProducerSpanBuilderImpl
    platform-tracing-core / space.br1440.platform.tracing.core.span / NonOwningSpanScope
    platform-tracing-core / space.br1440.platform.tracing.core.span / NoOpSpanScope
    platform-tracing-core / space.br1440.platform.tracing.core.span / OwningSpanScope
    platform-tracing-core / space.br1440.platform.tracing.core.span / PlatformSpanContextKeys
    platform-tracing-core / space.br1440.platform.tracing.core.span / PlatformSpanNameBuilder
    platform-tracing-core / space.br1440.platform.tracing.core.span / RpcClientSpanBuilderImpl
    platform-tracing-core / space.br1440.platform.tracing.core.span / RpcServerSpanBuilderImpl
    platform-tracing-core / space.br1440.platform.tracing.core.span / SpanEnricher
    platform-tracing-core / space.br1440.platform.tracing.core.span / SpanKinds
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension / PlatformAutoConfigurationCustomizer
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.configuration / AutoConfigurationCustomizerOrdering
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.configuration / ExtensionPropertyNames
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.configuration / ExtensionDefaults
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.configuration / PlatformTracingDefaultsProvider
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.exception / TracingValidationException
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.exporter / SafeSpanExporter
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.factory / FactoryUtils
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.factory / PlatformExportProcessorFactory
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.factory / PlatformPropagatorFactory
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.factory / PlatformSamplerFactory
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.factory / PlatformSpanProcessorFactory
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.factory / PlatformTracingJmxRegistrar
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.probe / ClassLoaderVisibilityTestProbe (test-only extension JAR; F1 — see classloader-visibility-test-only-probe-implementation-plan.md)
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.jmx / PlatformTracingControl
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.jmx / PlatformTracingControlMBean
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / BaggageSpanProcessor
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / ClassificationSpanProcessor
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / EnrichingSpanProcessor
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / MetricsSpanProcessor
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / PlatformCompositeSpanProcessor
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / PlatformDropOldestExportSpanProcessor
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / ScrubbingSpanProcessor
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / SpanWatchdogProcessor
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / ValidatingSpanProcessor
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / ValidationPolicyHolder
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / ValidationSnapshot
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.propagation / FilteringBaggagePropagator
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.propagation / PlatformPropagationGate
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.propagation / PlatformPropagatorsDefaultsCustomizer
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.propagation / InboundTraceControlPropagator
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.propagation / InboundTraceControlPropagatorBuilder
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.propagation / InboundTraceControlPropagatorProvider
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.propagation / PropagationDefaults
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.propagation / PropagationPolicy
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.propagation / SafeTextMapPropagator
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / BuildInfoReader
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / ClasspathResourceLoader
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / EnvironmentNormalizer
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / HostNameResolver
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / ManifestVersionReader
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / PlatformResourceProvider
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / ProcfsContainerIdDetector
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / ResourceAttributeResolver
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / ResourceStartupDiagnostics
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / ResourceValidationDiagnostics
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / ResourceValidationMode
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / SafeResourceProvider
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.safety / CircuitBreaker
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.safety / ConfigReloadDiagnostics
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.safety / DegradedModeController
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.safety / PlatformLogControl
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.safety / PlatformThrowables
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.safety / RateLimitedLogger
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.safety / TokenBucketRateLimiter
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.safety / TracingDiagnostics
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / CompositeSampler
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / DefaultRatioRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / ForceHeaderRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / ForwardingSamplingResult
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / HardDropRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / KillSwitchRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / ParentDecisionRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / PlatformManagedSampler
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / PlatformManagedSamplers
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / PlatformSamplerBuilder
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / PlatformSamplerProvider
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / QaTraceRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / RouteRatioRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / SafeSampler
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / SamplerState
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / SamplerStateHolder
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / SamplingRequest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / SamplingRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / AbstractBuiltInRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / BuiltInSpanAttributeScrubbingRules
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / EmailRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / HardwareIdentityRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / InfraCredentialRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / IpAddressRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / IpPrefixTruncator
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / JwtRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / KeyMatcher
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / LocationRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / OAuthHeaderRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / PasswordKeyRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / ScrubbingPolicyHolder
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / ScrubbingRulesLoader
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / ScrubbingSnapshot
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / SshCredentialRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / UserIdentityRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / WebhookTokenRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / XAuthHeaderRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing.circuitbreaker / RuleCircuitBreaker
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing.diagnostics / FailedProviderReason
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing.diagnostics / StartupDiagnostics
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing.engine / MergeEngine
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing.engine / PriorityHardening
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing.engine / RuleExecutionWrapper
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing.loader / ExtensionRuleLoader
    platform-tracing-perf-tests / space.br1440.platform.tracing.perftests.sut / OrdersController
    platform-tracing-perf-tests / space.br1440.platform.tracing.perftests.sut / PerfAdminController
    platform-tracing-perf-tests / space.br1440.platform.tracing.perftests.sut / SutApplication
    platform-tracing-semconv-lint / space.br1440.platform.tracing.semconv.lint / Linter
    platform-tracing-semconv-lint / space.br1440.platform.tracing.semconv.lint / LintReport
    platform-tracing-semconv-lint / space.br1440.platform.tracing.semconv.lint / LintRule
    platform-tracing-semconv-lint / space.br1440.platform.tracing.semconv.lint / LintSeverity
    platform-tracing-semconv-lint / space.br1440.platform.tracing.semconv.lint / LintViolation
    platform-tracing-semconv-lint / space.br1440.platform.tracing.semconv.lint / PlatformSpec
    platform-tracing-semconv-lint / space.br1440.platform.tracing.semconv.lint / SpanRecord
    platform-tracing-semconv-lint / space.br1440.platform.tracing.semconv.lint.cli / CommandLineLinter
    platform-tracing-semconv-lint / space.br1440.platform.tracing.semconv.lint.cli / SpanJsonReader
    platform-tracing-semconv-lint / space.br1440.platform.tracing.semconv.lint.rules / AttributeRule
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / RequestContextSupplierAutoConfiguration
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / RuntimeConfigApplier
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / SemanticLayerAutoConfiguration
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / ServiceNameProviderAutoConfiguration
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / TracingActuatorAutoConfiguration
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / TracingAopAutoConfiguration
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / TracingCoreAutoConfiguration
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / TracingMetricsAutoConfiguration
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / TracingObservationAutoConfiguration
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / TracingObservationSuppressStartupRunner
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / TracingProperties
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / TracingRefreshScopeAutoConfiguration
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.actuator / DropOldestAspirationDiagnostics
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.actuator / DualChannelDriftDiagnostics
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.actuator / OtelEffectiveConfigSnapshot
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.actuator / OtelEnvHintsBuilder
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.actuator / ResourceEffectiveSnapshot
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.actuator / TracingActuatorEndpoint
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.aspect / TracedAspect
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.async / PlatformContextTaskDecorator
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.async / ThreadPoolTaskExecutorContextPropagationBeanPostProcessor
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.async / TracingAsyncContextAutoConfiguration
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.errorhandling / RequestTraceContextSnapshotSupplier
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.health / TracingHealthIndicator
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.kafka / KafkaBatchLinksAspect
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.kafka / PlatformKafkaAutoConfiguration
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.kafka / PlatformKafkaHeaderSetter
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.kafka / PlatformKafkaOutboundAutoConfiguration
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.kafka / PlatformKafkaProducerFactoryCustomizer
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.kafka / PlatformKafkaProducerInterceptor
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.metrics / MeteredPlatformTracing
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.metrics / MicrometerSemconvMetrics
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.metrics / PlatformTracingConfigMetricsBinder
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.metrics / PlatformTracingMetrics
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.metrics / PlatformTracingSafeWrapperMetricsBinder
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.metrics / PlatformTracingSamplerMetricsBinder
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.propagation / PlatformOutboundPropagationAutoConfiguration
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.sampling / SamplingControlClient
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.sampling / SamplingControlUnavailableException
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.servicename / PlatformLocalServiceNameProvider
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.servicename / PlatformRemoteServiceNameProvider
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.support / DurationToMillis
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.support / OtelAgentDetector
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.support / SdkMode
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.support / SdkModeDiagnostics
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.support / SdkModeResolver
    platform-tracing-test / space.br1440.platform.tracing.test / TraceOperationsTestExtension
    platform-tracing-test / space.br1440.platform.tracing.test.arch / OtelDirectIntegrationRules
    platform-tracing-test / space.br1440.platform.tracing.test.arch / OtelSdkArchRules
    platform-tracing-test / space.br1440.platform.tracing.test.arch / TracingArchRules
    platform-tracing-test / space.br1440.platform.tracing.test.assertions / SamplerDecisionAssert
    platform-tracing-test / space.br1440.platform.tracing.test.assertions / SpanAssertions
    platform-tracing-test / space.br1440.platform.tracing.test.harness / SamplerHarness
    platform-tracing-test / space.br1440.platform.tracing.test.harness / SpanProcessorHarness
    platform-tracing-test / space.br1440.platform.tracing.test.junit / OtelSdkExtension
    platform-tracing-test / space.br1440.platform.tracing.test.junit / OtelSdkTest
    platform-tracing-test / space.br1440.platform.tracing.test.junit.internal / ScopeMode
    platform-tracing-test / space.br1440.platform.tracing.test.junit.internal / SdkResource
    platform-tracing-test / space.br1440.platform.tracing.test.junit.internal / StoreKeys
    platform-tracing-test / space.br1440.platform.tracing.test.semconv / SemconvStrictTestAutoConfiguration
```

---

## 23. Appendix C � Raw test class list

**Total: 213 entries.** Format: `Module / package / test class`

```text
    platform-tracing-core / space.br1440.platform.tracing.core.mdc.remote / RemoteServiceMdcTest
    platform-tracing-core / space.br1440.platform.tracing.core.runtime.versioned / VersionedStateHolderTest
    platform-tracing-api / space.br1440.platform.tracing.api.propagation / RequestIdSupportTest
    platform-tracing-core / space.br1440.platform.tracing.core.propagation.control / DefaultInboundTraceControlExtractorTest
    platform-tracing-core / space.br1440.platform.tracing.core.propagation.control / DefaultOutboundPropagationPolicyTest
    platform-tracing-core / space.br1440.platform.tracing.core.propagation.control / DefaultTraceControlHeaderInjectorTest
    platform-tracing-core / space.br1440.platform.tracing.core.propagation.control / TrustedDestinationMatchersTest
    platform-tracing-api / space.br1440.platform.tracing.api.semconv / CategoryContractsTest
    platform-tracing-api / space.br1440.platform.tracing.api.span.sanitize / SanitizerTest
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / BridgeOtelReactorContextPropagationIntegrationTest
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / DuplicateSpansRegressionMatrixTest
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / MdcPropagationWebFluxIntegrationTest
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / PlatformReactiveClientRequestObservationConventionTest
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / PlatformReactiveServerRequestObservationConventionTest
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / ReactorContextPropagationIntegrationTest
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / TracingReactorContextPropagationStartupRunnerTest
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / TracingReactorEagerInitConfigurationTest
    platform-tracing-autoconfigure-webflux / space.br1440.platform.tracing.autoconfigure.reactive / WebFluxSuppressMicrometerTracingAutoConfigurationTest
    platform-tracing-autoconfigure-webmvc / space.br1440.platform.tracing.autoconfigure.servlet / DuplicateSpansRegressionMatrixTest
    platform-tracing-autoconfigure-webmvc / space.br1440.platform.tracing.autoconfigure.servlet / PlatformClientRequestObservationConventionTest
    platform-tracing-autoconfigure-webmvc / space.br1440.platform.tracing.autoconfigure.servlet / PlatformServerRequestObservationConventionTest
    platform-tracing-autoconfigure-webmvc / space.br1440.platform.tracing.autoconfigure.servlet / ServletOutboundNoSpanArchTest
    platform-tracing-autoconfigure-webmvc / space.br1440.platform.tracing.autoconfigure.servlet / SuppressMicrometerTracingMetricsTest
    platform-tracing-autoconfigure-webmvc / space.br1440.platform.tracing.autoconfigure.servlet / TraceResponseHeaderServletFilterTest
    platform-tracing-autoconfigure-webmvc / space.br1440.platform.tracing.autoconfigure.servlet / WebMvcSuppressMicrometerTracingAutoConfigurationTest
    platform-tracing-autoconfigure-webmvc / space.br1440.platform.tracing.autoconfigure.servlet / WebStackIsolationTest
    platform-tracing-bench / space.br1440.platform.tracing.bench.contract / PerformanceBudgetsContractTest
    platform-tracing-bench / space.br1440.platform.tracing.bench.contract / PerformanceReleaseGateTest
    platform-tracing-bench / space.br1440.platform.tracing.bench.contract / ScrubbingBenchmarkFixtureContractTest
    platform-tracing-collector-config / space.br1440.platform.tracing.collectorconfig / CollectorPolicyContractTest
    platform-tracing-core / space.br1440.platform.tracing.core / DefaultTraceOperationsInSpanTest
    platform-tracing-core / space.br1440.platform.tracing.core / DefaultTraceOperationsTest
    platform-tracing-core / space.br1440.platform.tracing.core / NoOpPlatformContextPropagationTest
    platform-tracing-core / space.br1440.platform.tracing.core / OtelPlatformContextPropagationTest
    platform-tracing-core / space.br1440.platform.tracing.core.arch / OtelDirectIntegrationArchTest
    platform-tracing-core / space.br1440.platform.tracing.core.bsp / BatchSpanProcessorOverflowPolicyProbeTest
    platform-tracing-core / space.br1440.platform.tracing.core.exception / ExceptionRecorderTest
    platform-tracing-core / space.br1440.platform.tracing.core.semconv / AttributePolicyTest
    platform-tracing-core / space.br1440.platform.tracing.core.span / EscapeHatchSpanBuilderTest
    platform-tracing-core / space.br1440.platform.tracing.core.span / InternalSpanBuilderImplTest
    platform-tracing-core / space.br1440.platform.tracing.core.span / SpanEnricherTest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e / CollectorProductionPolicyE2ETest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e / ExceptionEventScrubbingE2ETest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e / TracingE2ETest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / AgentJdbcSmokeMain
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / AgentMdcPlatformLoggingAgentE2ETest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / AgentMdcPlatformLoggingSmokeMain
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / AgentSpringForceSamplingSmokeMain
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / AgentStatusMappingSmokeTest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / AgentWebFluxReactorPropagationSmokeMain
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / BspDropOldestSafetyAgentSmokeTest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / BspOverflowSafetyAgentSmokeTest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / BspOverflowSafetyMain
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / CollectorUnavailableResilienceTest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / CustomRuleSmokeE2ETest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / CustomRuleSmokeMain
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / DbSemconvAgentSmokeTest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / DuplicateHttpSpanAgentSmokeTest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / DuplicateHttpSpanSmokeMain
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / ForceSamplingAgentSmokeTest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / MdcLoggingSmokeController
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / MicrometerStatusMappingE2ETest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / OtelCollectorFileExporterSmokeTest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / PlatformExtensionAgentSmokeTest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / PlatformSpiAgentSmokeTest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / ProbeSmokeController
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / ReactorContextPropagationAgentE2ETest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / ReactorPropagationSmokeController
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / ResourceIdentityAgentSmokeE2ETest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / ResourceIdentityAgentSmokeMain
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / RuntimeSamplingControlSmokeMain
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.smoke / RuntimeSamplingControlSmokeTest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.spike / ClassLoaderVisibilitySpikeE2ETest
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.spike / ClassLoaderVisibilitySpikeMain
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.support / AgentHttpSpringSmokeProcessRunner
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.support / AgentJdbcSmokeProcessRunner
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.support / AgentMdcLoggingProcessRunner
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.support / AgentWebFluxProcessRunner
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.support / JaegerTestContainerSupport
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.support / JaegerV3QueryClient
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.support / LongLivedAgentSmokeProcess
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.support / OtelCollectorSpanExportReader
    platform-tracing-e2e-tests / space.br1440.platform.tracing.e2e.support / ResourceIdentityAgentSmokeProcessRunner
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension / BspDropOldestNoDoubleExportTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension / PlatformAutoConfigurationCustomizerExportProcessorTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension / PlatformAutoConfigurationCustomizerProcessorsTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension / PlatformAutoConfigurationCustomizerTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension / PlatformSpiAutoconfigureIntegrationTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.arch / ExtensionNoSpringDependencyArchTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.arch / OtelDirectIntegrationArchTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.arch / OtelDirectIntegrationExtensionSpiRules
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.arch / ResourceKeysNotInSpanProcessorsArchTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.arch / SafeBoundaryArchTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.arch / SpiApiCompatibilityArchTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.configuration / ExtensionConfigTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.configuration / PlatformTracingDefaultsProviderResourceEnvTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.configuration / PlatformTracingDefaultsProviderTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.exporter / SafeSpanExporterTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.factory / PlatformExportProcessorFactorySafeWrapTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.jmx / PlatformTracingControlTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / BaggageSpanProcessorTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / ClassificationSpanProcessorTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / EnrichingSpanProcessorAdvancedTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / EnrichingSpanProcessorTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / MetricsSpanProcessorTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / PlatformCompositeSpanProcessorTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / PlatformDropOldestExportSpanProcessorBuilderValidationTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / PlatformDropOldestExportSpanProcessorLifecycleTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / PlatformDropOldestExportSpanProcessorOverflowPolicyTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / ScrubbingPolicyRuntimeTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / ScrubbingSecurityNegativeTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / ScrubbingSpanProcessorAdvancedTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / ScrubbingSpanProcessorTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / SpanLimitsVerificationTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / SpanWatchdogProcessorAdvancedTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / SpanWatchdogProcessorTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / ValidatingSpanProcessorTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.processor / ValidationPolicyRuntimeTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.propagation / FilteringBaggagePropagatorBaggageLimitsTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.propagation / PlatformPropagatorsDefaultsCustomizerTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.propagation / InboundTraceControlPropagatorProviderTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.propagation / InboundTraceControlPropagatorTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.propagation / SafeTextMapPropagatorTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / BuildInfoReaderTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / EnvironmentNormalizerTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / HostNameResolverTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / ManifestVersionReaderTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / PlatformResourceProviderTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / ProcfsContainerIdDetectorTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / ResourceAttributeResolverTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / ResourceMergeIntegrationTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / ResourceValidationDiagnosticsTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.resource / TestConfigProperties
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.safety / CircuitBreakerTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.safety / ConfigReloadDiagnosticsTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.safety / DegradedModeControllerTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.safety / PlatformLogControlTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.safety / PlatformThrowablesTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.safety / RateLimitedLoggerTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.safety / TokenBucketRateLimiterTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.safety / TracingDiagnosticsTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / CompositeSamplerEdgeCasesTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / CompositeSamplerRouteRatioTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / CompositeSamplerTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / PlatformSamplerProviderTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / SafeSamplerTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / SamplerRuntimeUpdateConcurrencyTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.sampler / SamplerStateHolderTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / BuiltInRulesTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / ExampleMerchantAccountRule
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / IpPrefixTruncatorTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / KeyMatcherTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / ScrubbingRulesLoaderTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing / ServiceLoaderSpanAttributeScrubbingRuleTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing.circuitbreaker / RuleCircuitBreakerTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing.engine / MergeEngineTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.scrubbing.loader / ExtensionRuleLoaderTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.spike / BaggageFilteringSpikeTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.spike / BspReplacementSpikeTest
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.spike.baggage / RecordingTextMapSetter
    platform-tracing-otel-extension / space.br1440.platform.tracing.otel.extension.spike.baggage / SpikeFilteringBaggagePropagator
    platform-tracing-semconv-lint / space.br1440.platform.tracing.semconv.lint / PlatformSpecLinterTest
    platform-tracing-semconv-lint / space.br1440.platform.tracing.semconv.lint.cli / CommandLineLinterTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / RequestContextSupplierAutoConfigurationTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / RuntimeConfigApplierTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / SdkModeDetectionAutoConfigurationTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / SemanticLayerAutoConfigurationTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / ServiceNameProviderAutoConfigurationTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / SharedDefaultsAlignmentTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / TracingAutoConfigurationTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / TracingObservationSuppressStartupTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure / TracingPropertiesBindingTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.actuator / DropOldestAspirationDiagnosticsTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.actuator / DualChannelDriftDiagnosticsTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.actuator / OtelEffectiveConfigSnapshotDefaultsContractTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.actuator / OtelEffectiveConfigSnapshotTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.actuator / OtelEnvHintsBuilderTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.actuator / ResourceEffectiveSnapshotTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.actuator / TracingActuatorEndpointProcessorErrorsTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.actuator / TracingActuatorEndpointTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.arch / KafkaOutboundNoSpanArchTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.arch / OtelDirectIntegrationArchTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.aspect / TracedAspectTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.async / PlatformContextTaskDecoratorTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.async / ThreadPoolTaskExecutorBppTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.async / TracingAsyncContextAutoConfigurationTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.errorhandling / RequestTraceContextSnapshotSupplierTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.mdc / MdcPropagationAsyncIntegrationTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.mdc / MdcPropagationCompletableFutureTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.mdc / MicrometerTracingMdcBridgeSmokeTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.metrics / PlatformTracingMetricsTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.sampling / SamplingControlClientTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.servicename / PlatformLocalServiceNameProviderTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.servicename / PlatformRemoteServiceNameProviderTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.support / DurationToMillisTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.support / OtelAgentDetectorTest
    platform-tracing-spring-boot-autoconfigure / space.br1440.platform.tracing.autoconfigure.support / SdkModeResolverTest
    platform-tracing-test / space.br1440.platform.tracing.test / TraceOperationsTestExtensionTest
    platform-tracing-test / space.br1440.platform.tracing.test.arch / EscapeHatchArchRuleTest
    platform-tracing-test / space.br1440.platform.tracing.test.arch / OtelDirectIntegrationRulesTest
    platform-tracing-test / space.br1440.platform.tracing.test.arch / OtelSdkArchRulesTest
    platform-tracing-test / space.br1440.platform.tracing.test.arch / TracingArchRulesTest
    platform-tracing-test / space.br1440.platform.tracing.test.arch.fixture / AllowedEscapeHatchUsage
    platform-tracing-test / space.br1440.platform.tracing.test.arch.fixture / ViolatingEscapeHatchUsage
    platform-tracing-test / space.br1440.platform.tracing.test.harness / SamplerHarnessTest
    platform-tracing-test / space.br1440.platform.tracing.test.harness / SpanProcessorHarnessTest
    platform-tracing-test / space.br1440.platform.tracing.test.junit / OtelSdkExtensionBuilderTest
    platform-tracing-test / space.br1440.platform.tracing.test.junit / OtelSdkExtensionClassScopeTest
    platform-tracing-test / space.br1440.platform.tracing.test.junit / OtelSdkExtensionMethodScopeTest
    platform-tracing-test / space.br1440.platform.tracing.test.junit / OtelSdkExtensionNestedTest
    platform-tracing-test / space.br1440.platform.tracing.test.junit / OtelSdkExtensionSharedScopeTest
    platform-tracing-test / space.br1440.platform.tracing.test.junit / OtelSdkTestAnnotationTest
    platform-tracing-test / space.br1440.platform.tracing.violations / BatchSpanProcessor
    platform-tracing-test / space.br1440.platform.tracing.violations / SpanProcessor
```

---

## 24. Appendix D — Raw benchmark/perf list

| Module | Path | File | Type |
|--------|------|------|------|
| bench | `src/jmh/.../bench/` | `CompositeSamplerBenchmark.java` | JMH |
| bench | `src/jmh/.../bench/` | `CompositeSamplerPolicyBranchesBenchmark.java` | JMH |
| bench | `src/jmh/.../bench/` | `CompositeSamplerConcurrentUpdateBenchmark.java` | JMH |
| bench | `src/jmh/.../bench/` | `CompositePipelineBenchmark.java` | JMH |
| bench | `src/jmh/.../bench/` | `ScrubbingEngineBenchmark.java` | JMH |
| bench | `src/jmh/.../bench/` | `ScrubbingPerRuleBenchmark.java` | JMH |
| bench | `src/jmh/.../bench/` | `ScrubbingFixtures.java` | JMH fixture |
| bench | `src/jmh/.../bench/` | `AttributePolicyBenchmark.java` | JMH |
| bench | `src/jmh/.../bench/` | `TypedBuilderBenchmark.java` | JMH |
| bench | `src/jmh/.../bench/` | `TracedAspectBenchmark.java` | JMH |
| bench | `src/jmh/.../bench/` | `StartSpanBenchmark.java` | JMH |
| bench | `src/jmh/.../bench/` | `HeaderPropagationBenchmark.java` | JMH |
| bench | `src/jmh/.../bench/` | `SpanLimitsBenchmark.java` | JMH |
| bench | `src/jmh/.../bench/` | `MdcCorrelationBenchmark.java` | JMH |
| bench | `src/jmh/.../bench/` | `ContextScopeBenchmark.java` | JMH |
| bench | `src/jmh/.../bench/` | `QueueOfferBenchmark.java` | JMH |
| bench | `baselines/<profile>/` | `results.json` | baseline artifact |
| bench | `docs/tracing/` | `performance-budgets.yaml` | governance |
| perf-tests | `scripts/` | `run-perf-scenario.ps1` | macro runner |
| perf-tests | `scripts/` | `run-official-matrix.ps1` | matrix runner |
| perf-tests | `scripts/` | `analyze-perf-run.ps1` | analysis |
| perf-tests | `/` | `docker-compose.perf.yml` | load topology |
| perf-tests | `scenarios/` | `m0.env` … `m10d.env`, `queue-saturation*.env` | scenario config |
| perf-tests | `docker/k6/scenarios/` | `steady-state.js` | k6 load script |
| perf-tests | `src/main/.../sut/` | `PerfAdminController.java` | JMX admin bridge |

---

## 25. Appendix E — Files requiring manual review

| File | Why manual review needed | Related target rule | Recommended reviewer |
|------|-------------------------|---------------------|----------------------|
| `platform-tracing-otel-extension/.../jmx/PlatformTracingControl.java` | Large MBean surface; partial Map ops; migration to OpenMBean wire | validated Map JMX transport | Agent/platform owner |
| `platform-tracing-spring-boot-autoconfigure/.../actuator/TracingActuatorEndpoint.java` | WriteOperation exposed without prod guard | Actuator MUTATION dev-only | SRE + platform owner |
| `platform-tracing-spring-boot-autoconfigure/.../TracingProperties.java` | 700+ lines; topology vs policy split non-obvious | Helm vs Config Server sources | Config/platform architect |
| `platform-tracing-spring-boot-autoconfigure/.../RuntimeConfigApplier.java` | Precursor to reconciler; RefreshScope semantics | TracingConfigReconciler | Platform owner |
| `platform-tracing-core/.../DefaultTraceOperations.java` | OTel-coupled "core" — split boundary unclear | pure Java core | Staff engineer |
| `platform-tracing-otel-extension/.../sampler/CompositeSampler.java` | SPLIT_CORE_AND_ADAPTER — policy/adapter interleaved | core vs otel-extension | Staff engineer |
| `platform-tracing-otel-extension/.../scrubbing/engine/*` | Mandatory baseline scrubbing engine | core scrubbing policy | Security + platform |
| `platform-tracing-api/build.gradle` | compileOnly OTel deps in public API | api = JDK only | Architect |
| `platform-tracing-core/build.gradle` | api OTel transitive to consumers | core dependency direction | Architect |
| `platform-tracing-perf-tests/.../PerfAdminController.java` | Production-like JMX bridge pattern in perf SUT | private JMX only | SRE |
| `platform-tracing-semconv-lint/*` | Excluded from build — future module or delete? | REVIEW_FOR_COLLAPSE_LATER | Architect |

---

*Document generated from Gradle build files, `settings.gradle`, and filesystem scan of `src/main/java`, `src/test/java`, `src/jmh/java` (2026-06-11). No production code was modified.*

