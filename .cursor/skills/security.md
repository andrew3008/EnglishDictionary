# Security Platform Standards

## Context

This repository contains enterprise-grade Spring Boot platform starters, Gradle convention plugins, infrastructure modules, and cloud-native platform integrations.

All generated code must be secure-by-default.

Security requirements apply to:

* APIs
* infrastructure integrations
* Redis/KeyDB
* Kubernetes
* observability
* Gradle build logic
* CI/CD integrations
* distributed systems

Generated code must prioritize:

* least privilege
* secure defaults
* operational safety
* auditability
* defense in depth

---

# Priority

This skill has very high priority for:

* authentication
* authorization
* secret management
* infrastructure integrations
* distributed coordination
* CI/CD
* Kubernetes integrations
* cloud-native runtime behavior

When conflicts occur:

* security has priority
* least privilege has priority
* secret safety has priority

---

# Security Principles

All generated code must:

* follow least privilege
* avoid implicit trust
* validate external input
* minimize attack surface
* fail securely

Prefer:

* explicit security boundaries
* immutable infrastructure
* externalized secrets
* short-lived credentials

Avoid:

* hidden trust assumptions
* permissive defaults
* security through obscurity
* implicit infrastructure access

---

# Authentication

Authentication must:

* use standardized mechanisms
* support token rotation
* preserve auditability

Prefer:

* OAuth2
* OpenID Connect
* JWT validation using trusted issuers
* short-lived tokens

Avoid:

* custom authentication protocols
* long-lived static tokens
* insecure session handling

Never:

* trust unsigned tokens
* bypass token validation
* hardcode authentication secrets

---

# Authorization

Authorization must:

* follow least privilege
* be explicit
* remain auditable

Prefer:

* role-based access control
* permission-based authorization
* namespace-scoped permissions

Avoid:

* global admin assumptions
* implicit elevated access
* hardcoded authorization bypasses

Never:

* trust client-side authorization
* expose unrestricted internal endpoints
* disable authorization checks in production code

---

# Secret Management

Secrets must:

* be externalized
* support rotation
* avoid local persistence

Prefer:

* Kubernetes Secrets
* Vault integrations
* external secret operators
* environment variable injection

Never:

* commit secrets to Git
* log secrets
* embed secrets in Docker images
* expose secrets in metrics

Forbidden:

* plaintext passwords in configuration
* API keys in source code
* secrets inside test fixtures committed to repository

---

# Transport Security

All external communication must:

* support TLS
* validate certificates
* use secure protocols

Prefer:

* TLS 1.2+
* mutual TLS where applicable
* certificate rotation support

Avoid:

* insecure HTTP
* disabled certificate validation
* self-signed production certificates without governance

Never:

* disable SSL verification globally
* trust all certificates
* bypass hostname verification

---

# API Security

APIs must:

* validate input
* sanitize external data
* enforce authorization
* expose minimal information

Prefer:

* schema validation
* explicit DTOs
* rate limiting
* structured error responses

Avoid:

* exposing stack traces
* returning internal infrastructure details
* over-permissive CORS configuration

Never:

* trust client input directly
* deserialize untrusted polymorphic payloads
* expose internal admin endpoints publicly

---

# Dependency Security

Dependencies must:

* come from trusted repositories
* remain version-controlled
* support vulnerability scanning

Prefer:

* BOM-managed versions
* version catalogs
* reproducible builds

Avoid:

* dynamic dependency versions
* unmaintained libraries
* shadow dependencies without governance

Never:

* use dependencies with known critical vulnerabilities
* fetch dependencies from untrusted repositories
* bypass dependency verification

---

# Redis and Infrastructure Security

Redis/KeyDB integrations must:

* support authentication
* support TLS where applicable
* restrict network exposure

Avoid:

* public Redis exposure
* unrestricted cluster access
* insecure serialization

Never:

* expose Redis without authentication
* use Java native serialization
* trust infrastructure networks implicitly

---

# Kubernetes Security

Kubernetes workloads must:

* run as non-root
* use least-privilege RBAC
* support immutable infrastructure

Prefer:

* namespace isolation
* network policies
* PodSecurity standards
* read-only root filesystems where possible

Avoid:

* privileged containers
* cluster-admin permissions
* mutable runtime infrastructure

Never:

* mount Docker socket
* run privileged workloads unnecessarily
* expose secrets via environment dumps

---

# Logging Security

Logs must:

* preserve auditability
* avoid sensitive data exposure
* support incident investigation

Never log:

* passwords
* access tokens
* refresh tokens
* private keys
* session identifiers
* sensitive personal data

Prefer:

* structured logging
* security event logging
* trace-aware audit logs

Avoid:

* excessive debug logging in production
* full request body logging
* hidden security failures

---

# Observability Security

Observability integrations must:

* avoid leaking sensitive metadata
* sanitize telemetry
* preserve tenant isolation

Avoid:

* high-cardinality user identifiers
* secrets in metrics
* raw payload tracing

Prefer:

* sanitized tracing attributes
* operational metadata only
* explicit telemetry filtering

---

# Serialization Security

Serialization must:

* validate input types
* avoid arbitrary code execution risks
* support schema governance

Prefer:

* explicit DTOs
* JSON serialization
* schema-aware deserialization

Avoid:

* reflection-heavy deserialization
* unrestricted polymorphism
* unsafe binary serialization

Never:

* use Java native serialization
* deserialize untrusted arbitrary classes
* trust external payload structures blindly

---

# CI/CD Security

CI/CD integrations must:

* use short-lived credentials
* isolate environments
* preserve auditability

Prefer:

* workload identity
* ephemeral credentials
* signed artifacts

Avoid:

* long-lived shared tokens
* mutable release pipelines
* hidden deployment behavior

Never:

* expose secrets in pipeline logs
* store plaintext deployment credentials
* bypass artifact verification

---

# Runtime Security

Applications must:

* fail securely
* validate external dependencies
* support graceful degradation

Prefer:

* explicit timeout handling
* resilience patterns
* startup validation

Avoid:

* permissive fallback behavior
* insecure startup defaults
* hidden trust assumptions

---

# Testing Security

Security-sensitive functionality must be tested for:

* authorization enforcement
* secret handling
* TLS behavior
* serialization safety
* infrastructure isolation

Prefer:

* integration testing
* containerized testing
* isolated test environments

Avoid:

* production secrets in tests
* shared test credentials
* insecure local-only assumptions

---

# Anti-Conflict Rules

## Secret Ownership

Secrets are owned ONLY by:

* secret management infrastructure
* Vault integrations
* Kubernetes Secrets
* external secret providers

Application modules must NOT:

* persist secrets locally
* generate unmanaged credentials
* redefine secret storage semantics

---

## Authentication Ownership

Authentication infrastructure belongs ONLY to:

* identity providers
* security platform modules
* OAuth/OIDC integrations

Application modules must NOT:

* implement custom authentication protocols
* bypass centralized authentication
* redefine token validation semantics

---

## Authorization Ownership

Authorization semantics belong ONLY to:

* platform authorization modules
* RBAC infrastructure
* security governance layers

Feature modules must NOT:

* hardcode admin access
* bypass authorization enforcement
* introduce hidden privilege escalation

---

## TLS Ownership

TLS configuration belongs ONLY to:

* platform infrastructure
* service mesh layers
* centralized security configuration

Application code must NOT:

* disable TLS validation
* trust arbitrary certificates
* redefine transport security behavior

---

## Logging Ownership

Security-sensitive logging rules belong ONLY to:

* observability infrastructure
* audit logging systems
* security governance layers

Application modules must NOT:

* log secrets
* expose sensitive payloads
* bypass audit logging policies

---

## Serialization Ownership

Serialization security belongs ONLY to:

* shared serialization infrastructure
* platform protocol governance
* security-reviewed serializers

Application modules must NOT:

* introduce unsafe serialization
* bypass payload validation
* deserialize arbitrary classes

---

## Infrastructure Ownership

Infrastructure security belongs ONLY to:

* Kubernetes infrastructure
* CI/CD governance
* platform security modules

Application code must NOT:

* assume elevated infrastructure privileges
* mutate security boundaries dynamically
* bypass runtime isolation controls

---

## Dependency Ownership

Dependency governance belongs ONLY to:

* platform dependency management
* security scanning infrastructure
* approved artifact repositories

Application modules must NOT:

* fetch dependencies dynamically
* bypass dependency governance
* introduce unapproved repositories

---

# Enterprise Rules

Generated security-related code must:

* support long-term governance
* remain auditable
* avoid vendor lock-in
* support incident investigation
* preserve operational safety

Prefer explicit security boundaries over hidden trust assumptions.

---

# Anti-Patterns

Forbidden:

* hardcoded secrets
* disabled TLS validation
* trust-all SSL configuration
* Java native serialization
* plaintext credentials
* unrestricted admin access
* hidden authorization bypasses
* logging secrets
* insecure deserialization
* dynamic dependency resolution
* privileged containers without justification
* cluster-admin assumptions
* insecure default configuration
* permissive wildcard CORS
* mutable runtime security state
* hidden infrastructure trust assumptions

Avoid security magic.
