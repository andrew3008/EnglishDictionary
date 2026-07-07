# Migration and Compatibility Standards

## Context

This repository contains enterprise-grade Spring Boot platform starters, Gradle convention plugins, and infrastructure modules used across multiple internal services.

Migration strategy must prioritize:

* backward compatibility
* operational safety
* gradual adoption
* predictable upgrades
* low migration risk

Generated migration-related code and recommendations must support long-term enterprise maintenance.

---

# Priority

This skill has very high priority for:

* API evolution
* starter upgrades
* dependency upgrades
* Spring Boot migrations
* Gradle migrations
* infrastructure migrations
* package refactoring
* configuration evolution

When conflicts occur:

* backward compatibility has priority
* operational stability has priority
* gradual migration has priority

---

# Migration Principles

All migrations must:

* be incremental
* be observable
* support rollback
* minimize breaking changes
* preserve operational stability

Avoid:

* big bang migrations
* forced rewrites
* hidden runtime behavior changes
* simultaneous infrastructure and API rewrites

Prefer:

* phased rollout
* compatibility layers
* feature flags
* explicit deprecation cycles

---

# Backward Compatibility

Public APIs must remain backward compatible whenever possible.

Avoid:

* removing public APIs abruptly
* renaming public packages without migration strategy
* changing configuration semantics silently
* changing runtime defaults unexpectedly

Prefer:

* additive changes
* deprecated compatibility shims
* migration aliases
* transitional adapters

---

# API Evolution

API changes must:

* preserve semantic behavior
* avoid signature-breaking changes
* preserve extension points

Before removing APIs:

* mark as deprecated
* document replacement
* provide migration examples
* support coexistence period

Avoid:

* incompatible constructor changes
* changing exception behavior silently
* hidden contract changes

---

# Configuration Migration

Configuration changes must:

* preserve existing keys temporarily
* support aliases
* provide migration warnings

Prefer:

* explicit migration logging
* compatibility property binding
* gradual default changes

Avoid:

* silent configuration removal
* incompatible default changes
* startup failures without migration guidance

---

# Spring Boot Migration

Spring Boot upgrades must:

* preserve auto-configuration behavior
* preserve conditional bean semantics
* preserve actuator compatibility

Verify:

* auto-configuration ordering
* bean conditions
* property binding
* observability integrations

Avoid:

* depending on internal Spring APIs
* reflection against unstable internals
* hidden lifecycle assumptions

---

# Gradle Migration

Gradle upgrades must:

* preserve configuration cache support
* preserve plugin compatibility
* avoid deprecated APIs

Prefer:

* Provider API
* lazy configuration
* version catalogs

Avoid:

* legacy task APIs
* eager configuration
* internal Gradle APIs

---

# Database and Redis Migration

Schema and Redis migrations must:

* support rolling deployments
* tolerate version skew
* avoid destructive changes

Prefer:

* additive schema changes
* backward-compatible serialization
* staged data migration

Avoid:

* destructive renames
* incompatible serialization changes
* immediate key invalidation

---

# Serialization Compatibility

Serialized formats must:

* tolerate unknown fields
* support older payloads
* preserve compatibility across deployments

Prefer:

* versioned payloads
* additive schema evolution
* optional fields

Avoid:

* field removals without migration
* incompatible binary formats
* strict deserialization assumptions

---

# Kubernetes Migration

Kubernetes-related migrations must:

* support rolling upgrades
* tolerate mixed-version clusters
* preserve probe compatibility

Avoid:

* startup dependency deadlocks
* incompatible readiness semantics
* cluster-wide assumptions

Prefer:

* gradual rollout
* canary deployment compatibility
* feature toggles

---

# Observability During Migration

Migrations must expose:

* migration progress metrics
* compatibility warnings
* structured migration logs
* deprecation notices

Prefer:

* explicit operational visibility
* migration dashboards
* feature rollout observability

Avoid:

* silent migration logic
* hidden compatibility layers
* untracked runtime transitions

---

# Deprecation Policy

Deprecated APIs must:

* include replacement guidance
* remain functional during transition period
* emit clear warnings where appropriate

Avoid:

* immediate removal
* undocumented deprecations
* breaking patch releases

Prefer:

* semantic versioning
* migration documentation
* staged removal strategy

---

# Feature Flags

Use feature flags for:

* risky migrations
* behavioral changes
* infrastructure transitions

Feature flags must:

* be observable
* support rollback
* have clear ownership

Avoid:

* permanent feature flags
* hidden runtime branching
* nested flag complexity

---

# Rollback Safety

All migrations must support:

* rollback strategy
* partial deployment tolerance
* mixed-version operation

Avoid:

* irreversible startup migrations
* destructive initialization logic
* one-way runtime transitions

Prefer:

* reversible migrations
* compatibility adapters
* version-tolerant communication

---

# Testing Requirements

Migration testing must verify:

* backward compatibility
* rolling deployment behavior
* mixed-version interoperability
* configuration compatibility
* serialization compatibility

Prefer:

* Testcontainers
* compatibility integration tests
* upgrade-path verification

Avoid:

* testing only fresh installations
* ignoring downgrade scenarios
* environment-specific assumptions

---

# Documentation

Every migration must include:

* migration steps
* rollback instructions
* compatibility notes
* operational risks
* observability expectations

Avoid:

* undocumented behavior changes
* implicit migration requirements
* hidden operational assumptions

---

# Anti-Conflict Rules

## Compatibility Ownership

Backward compatibility is owned ONLY by:

* platform maintainers
* public API contracts
* migration infrastructure

Feature teams must NOT:

* remove shared APIs unilaterally
* introduce incompatible runtime behavior
* break shared configuration contracts

---

## Configuration Ownership

Configuration evolution belongs ONLY to:

* platform configuration modules
* migration infrastructure
* compatibility layers

Application modules must NOT:

* silently redefine configuration semantics
* override migration aliases
* remove compatibility mappings

---

## API Ownership

Public API contracts belong ONLY to:

* platform API modules
* shared starter contracts
* enterprise integration boundaries

Internal refactoring must NOT:

* leak into public APIs
* force consumer rewrites
* change extension semantics unexpectedly

---

## Serialization Ownership

Serialization formats are owned ONLY by:

* shared protocol modules
* platform serialization infrastructure

Application modules must NOT:

* change payload formats incompatibly
* introduce breaking schema assumptions
* remove fields without compatibility strategy

---

## Infrastructure Ownership

Infrastructure migration behavior belongs ONLY to:

* infrastructure modules
* deployment systems
* migration orchestration layers

Applications must NOT:

* self-migrate infrastructure dynamically
* mutate shared infrastructure unexpectedly
* assume synchronized deployments

---

## Deployment Ownership

Deployment sequencing belongs ONLY to:

* CI/CD pipelines
* platform deployment systems
* GitOps workflows

Application code must NOT:

* depend on deployment order assumptions
* require synchronized pod restarts
* assume cluster-wide instant rollout

---

## Feature Flag Ownership

Feature flags belong ONLY to:

* migration orchestration
* operational rollout strategy
* platform governance

Feature teams must NOT:

* leave permanent migration flags
* create hidden operational toggles
* bypass rollout observability

---

# Enterprise Rules

Generated migration logic must:

* support long-term maintenance
* minimize operational risk
* preserve enterprise stability
* remain easy to debug

Prefer explicit compatibility layers over hidden migration magic.

---

# Anti-Patterns

Forbidden:

* breaking changes in patch releases
* hidden runtime migrations
* irreversible startup mutations
* incompatible serialization rewrites
* destructive schema changes
* forced synchronized deployments
* silent configuration behavior changes
* removing deprecated APIs without transition
* startup-time destructive migrations
* migration logic without rollback
* hidden feature flag behavior
* incompatible package renames without adapters
* cluster-wide assumptions during rollout

Avoid migration magic.
