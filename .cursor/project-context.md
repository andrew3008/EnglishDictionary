# Project Context

This repository contains an enterprise platform tracing solution for Spring Boot microservices.

## Current phase

The tracing solution is **pre-production**.

Breaking changes are allowed when they materially improve architecture, maintainability, safety, or production readiness. Do not optimize for backward compatibility with the current pre-production API shape unless explicitly requested.

## Primary goals

- production-grade architecture
- clean public API boundaries
- clear separation between API contracts and implementation/runtime code
- observability by default
- safe runtime control
- deterministic behavior under Spring Boot autoconfiguration
- low startup overhead
- minimal and intentional transitive dependencies
- strong tests, architecture fitness functions, and e2e evidence
- simple adoption path for many internal Spring Boot services

## Refactoring policy

Architectural cleanup is preferred over compatibility preservation while the solution is not in production.

Accept risky or breaking refactorings when they are justified by:
- removal of accidental public API
- elimination of dual-state or duplicated implementations
- stronger module boundaries
- better runtime safety
- clearer ownership of domain rules
- better operator diagnostics
- reduced long-term maintenance cost

Do **not** propose cosmetic-only refactorings. Every significant change must reduce production risk, improve architectural integrity, simplify adoption, or strengthen verification.

## Compatibility policy

Backward compatibility is **not** a primary goal for the current tracing refactoring phase.

Avoid:
- deprecated bridges
- compatibility shims
- aliases for removed concepts
- preserving accidental APIs only because tests or old internal callers still reference them

Prefer:
- direct migration to the intended API
- deletion of obsolete abstractions
- updated tests and docs matching the new architecture
- clear ADRs for intentional breaking changes

## Architecture principles

- API modules define stable public contracts, vocabulary, annotations, value types, and narrow entry points.
- Core modules own implementation, runtime behavior, domain validation, policies, lifecycle, and safety rules.
- Spring Boot autoconfiguration modules own wiring, properties, conditional beans, diagnostics, and adoption defaults.
- OTel/JMX/agent integration modules own transport-specific adapters and classloader-sensitive integration code.
- Domain rules must not leak into wire decoders or generic API vocabulary.
- Implementation classes must not become accidental public API.
- Runtime mutation must fail closed unless explicitly enabled.
- Public schema introspection must not expose internal decoder/schema implementation details.

## Verification expectations

Every non-trivial change should include the narrowest useful verification set:

- affected module compile/test tasks
- architecture fitness rules
- import / dependency / forbidden API scans when relevant
- javadoc checks for public APIs
- e2e or integration tests for runtime-control and instrumentation flows when behavior crosses module boundaries

For Cursor, Codex, and Perplexity-generated work:
- follow repository `.editorconfig`
- use explicit Java imports only
- do not introduce wildcard imports
- do not create import-only churn
- preserve existing architecture rules
- distinguish verified facts from assumptions

## Target users

- internal backend teams
- platform teams
- SRE / operations teams
- cloud-native services
- Kubernetes workloads
- Spring Boot servlet and reactive microservices
