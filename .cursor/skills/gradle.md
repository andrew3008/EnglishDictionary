# Gradle Platform Standards

## Context

This repository contains enterprise-grade Gradle convention plugins, platform DSL modules, and Spring Boot starter infrastructure.

Gradle build logic must be:

* deterministic
* configuration-cache compatible
* performant
* maintainable
* enterprise scalable

The repository is used by multiple internal backend teams.

---

# Priority

This skill has very high priority for:

* Gradle convention plugins
* buildSrc replacements
* included builds
* platform BOM modules
* dependency management
* Spring Boot platform starters

When conflicts occur with other skills:

* Gradle correctness has priority
* configuration cache compatibility has priority
* lazy configuration has priority

---

# Gradle Version

Use:

* Gradle 8+
* Kotlin DSL where possible

Avoid:

* deprecated Gradle APIs
* legacy eager APIs
* Groovy dynamic patterns when avoidable

---

# Configuration Cache

All generated Gradle code MUST support:

* configuration cache
* build cache
* parallel execution

Forbidden:

* accessing Project during task execution
* mutable global state
* runtime task mutation
* non-cacheable task inputs

Prefer:

* Provider API
* Property API
* ValueSource API

---

# Lazy Configuration

Always prefer:

* tasks.register(...)
* providers.gradleProperty(...)
* layout.buildDirectory.dir(...)

Avoid:

* tasks.create(...)
* afterEvaluate(...)
* allprojects {}
* subprojects {}

Use configuration avoidance APIs everywhere possible.

---

# Plugin Design

Convention plugins must:

* configure one concern only
* be composable
* avoid side effects
* avoid hidden dependency injection

Plugins must NOT:

* automatically apply unrelated plugins
* force repositories
* override user configuration unexpectedly

---

# Dependency Management

Dependency versions must be managed ONLY through:

* version catalogs
* BOMs
* platform modules

Forbidden:

* hardcoded dependency versions
* dynamic dependency versions
* duplicated version declarations

Examples of forbidden patterns:

* implementation("org.foo:bar:latest.release")
* implementation("org.foo:bar:+")

---

# Multi-Module Builds

Modules must:

* have explicit ownership
* avoid cyclic dependencies
* expose minimal APIs

Avoid:

* shared mutable build logic
* giant root build.gradle files
* hidden cross-project coupling

Prefer:

* convention plugins
* included builds
* version catalogs

---

# Spring Boot Integration

Spring Boot plugins must:

* preserve configuration cache compatibility
* avoid eager task realization
* avoid build-time reflection

Starter modules must not contain:

* application-specific logic
* deployment logic
* environment-specific assumptions

---

# Publishing

Publishing configuration must:

* be reproducible
* support CI/CD execution
* avoid local machine assumptions

Artifacts must include:

* sources JAR
* javadoc JAR where applicable

---

# Performance

Build logic must:

* minimize configuration time
* avoid unnecessary task realization
* support parallel execution

Avoid:

* expensive filesystem scans
* shell execution during configuration
* blocking external calls

---

# Testing

Convention plugins must be tested using:

* Gradle TestKit
* functional tests
* isolated temporary projects

Tests must validate:

* configuration cache compatibility
* incremental builds
* task up-to-date behavior

---

# Security

Never:

* hardcode credentials
* log secrets
* embed tokens in build scripts

Use:

* Gradle credentials API
* environment variables
* encrypted CI secrets

---

# Observability

Builds should expose:

* clear task logging
* meaningful failure messages
* actionable diagnostics

Avoid:

* noisy logs
* hidden failures
* swallowed exceptions

---

# Anti-Conflict Rules

## Dependency Ownership

Dependency versions are owned ONLY by:

* version catalogs
* BOM modules
* platform modules

Spring starters must NOT redefine dependency versions.

---

## Repository Ownership

Repositories are configured ONLY in:

* settings.gradle
* enterprise repository convention plugins

Individual modules must NOT declare repositories unless explicitly required.

Forbidden:
repositories {
mavenCentral()
}

inside feature modules.

---

## Plugin Ownership

Convention plugins own:

* shared build logic
* compiler configuration
* publishing defaults
* quality gates

Application modules must NOT duplicate convention plugin logic.

---

## Configuration Ownership

Configuration defaults belong ONLY to:

* convention plugins
* platform infrastructure modules

Feature modules must avoid:

* repeated compiler flags
* duplicated test configuration
* duplicated publishing setup

---

## Spring vs Gradle Responsibilities

Gradle is responsible for:

* dependency management
* publishing
* packaging
* build lifecycle

Spring starters are responsible for:

* runtime auto-configuration
* infrastructure wiring
* observability integration

Do not mix responsibilities.

---

## CI/CD Ownership

CI/CD-specific behavior belongs ONLY to:

* CI convention plugins
* pipeline modules

Application modules must not:

* detect CI vendors directly
* hardcode pipeline behavior

---

# Enterprise Rules

Generated build logic must:

* be removable
* be debuggable
* avoid framework lock-in
* support long-term maintenance

Prefer explicit configuration over hidden automation.

---

# Anti-Patterns

Forbidden:

* afterEvaluate
* evaluationDependsOn
* mutable static state
* reflection-heavy Gradle logic
* cross-project mutation
* hidden task dependencies
* shell execution during configuration phase
* direct filesystem traversal during configuration
* dynamic version resolution
* allprojects {}
* subprojects {}
* implicit task realization

Avoid build magic.
