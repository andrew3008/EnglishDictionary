# Kubernetes Platform Standards

## Context

This repository contains enterprise-grade Spring Boot platform starters and infrastructure modules designed for Kubernetes environments.

Generated Kubernetes-related code and configuration must be:

* cloud-native
* observable
* secure-by-default
* horizontally scalable
* resilient
* GitOps-friendly

Target runtime:

* Kubernetes
* OpenShift
* managed cloud Kubernetes platforms

---

# Priority

This skill has very high priority for:

* Kubernetes integrations
* cloud-native Spring Boot starters
* deployment descriptors
* probes
* configuration management
* platform infrastructure modules

When conflicts occur:

* Kubernetes operational safety has priority
* observability has priority
* immutable infrastructure has priority

---

# Cloud-Native Principles

Applications must:

* be stateless where possible
* tolerate pod restarts
* tolerate rescheduling
* support horizontal scaling
* support rolling deployments

Avoid:

* local filesystem assumptions
* node affinity assumptions
* singleton runtime assumptions
* sticky session requirements

---

# Configuration Management

Use:

* ConfigMaps
* Secrets
* environment variables

Avoid:

* hardcoded configuration
* embedded credentials
* runtime configuration mutation

Spring Boot configuration should support:

* externalized configuration
* profile-independent deployments
* immutable container images

---

# Secrets

Secrets must:

* come from Kubernetes Secrets
* support secret rotation
* avoid logging sensitive values

Forbidden:

* hardcoded passwords
* embedded API keys
* secrets inside Docker images
* secrets committed to Git

Prefer:

* mounted secrets
* environment variable injection
* external secret operators

---

# Health Probes

Every service must expose:

* liveness probes
* readiness probes

Prefer:

* Spring Boot Actuator probes
* dedicated health groups

Avoid:

* expensive readiness checks
* database-heavy liveness checks

Recommended:

* liveness = JVM/process health
* readiness = dependency availability

---

# Resource Management

All workloads must define:

* CPU requests
* memory requests
* CPU limits where appropriate
* memory limits

Avoid:

* unlimited memory
* oversized JVM heaps
* container overcommit assumptions

Prefer:

* container-aware JVM settings
* explicit memory tuning

---

# JVM Configuration

Use:

* container-aware JVM defaults
* modern garbage collectors
* explicit memory percentages

Prefer:

* MaxRAMPercentage
* InitialRAMPercentage

Avoid:

* fixed large heap sizes
* legacy container JVM flags

---

# Scaling

Applications must:

* support horizontal pod autoscaling
* avoid in-memory coordination
* externalize shared state

Prefer:

* Redis/KeyDB distributed coordination
* database-backed coordination
* Kubernetes leader election where appropriate

Avoid:

* static singleton leaders
* pod identity assumptions

---

# Networking

Applications must:

* tolerate network retries
* support transient failure recovery
* use timeouts explicitly

Avoid:

* infinite retries
* blocking startup on optional services
* startup deadlocks

Prefer:

* circuit breakers
* retry policies
* connection pooling

---

# Observability

Every service must expose:

* metrics
* structured logs
* tracing
* health endpoints

Prefer:

* Micrometer
* OpenTelemetry
* Prometheus-compatible metrics

Avoid:

* plain text logs
* hidden failures
* missing correlation identifiers

---

# Logging

Use:

* structured JSON logging
* correlation IDs
* trace IDs

Never:

* log secrets
* log tokens
* log full credentials

Logs must be:

* machine-readable
* aggregation-friendly
* Kubernetes-compatible

---

# Deployment Strategy

Deployments must support:

* rolling updates
* zero-downtime deployments
* rollback safety

Avoid:

* mutable deployments
* manual configuration drift
* runtime patching

Prefer:

* immutable images
* GitOps workflows
* declarative manifests

---

# Containers

Containers must:

* be minimal
* run as non-root
* avoid unnecessary packages

Prefer:

* distroless images
* Alpine only when justified
* reproducible builds

Forbidden:

* SSH servers
* package managers in runtime images
* privileged containers

---

# Startup Behavior

Applications must:

* start predictably
* fail fast on critical configuration issues
* tolerate unavailable optional dependencies

Avoid:

* blocking startup indefinitely
* startup-time network scans
* expensive initialization logic

Prefer:

* lazy initialization
* async warmup
* startup observability

---

# Kubernetes API Usage

Kubernetes integrations must:

* use official client libraries
* minimize API calls
* support RBAC restrictions

Avoid:

* cluster-admin assumptions
* polling-heavy implementations
* uncontrolled watches

Prefer:

* informer-based patterns
* namespace-scoped permissions
* least privilege access

---

# Anti-Conflict Rules

## Configuration Ownership

Kubernetes owns:

* deployment configuration
* runtime environment
* scaling configuration
* secret injection

Spring Boot starters must NOT:

* hardcode deployment assumptions
* override Kubernetes runtime settings
* force environment-specific behavior

---

## Infrastructure Ownership

Infrastructure concerns belong ONLY to:

* Kubernetes manifests
* Helm charts
* platform operators
* infrastructure convention modules

Application code must NOT:

* create infrastructure dynamically
* mutate cluster resources unnecessarily
* assume cluster topology

---

## Secret Ownership

Secrets are owned ONLY by:

* Kubernetes Secrets
* secret management systems
* external secret operators

Applications must NEVER:

* generate secrets automatically
* persist secrets locally
* expose secret values in logs

---

## Networking Ownership

Kubernetes networking is owned ONLY by:

* ingress controllers
* service meshes
* cluster networking layers

Applications must NOT:

* hardcode hostnames
* assume fixed pod IPs
* assume static DNS timing

---

## Scaling Ownership

Scaling decisions belong ONLY to:

* Kubernetes autoscalers
* platform infrastructure
* operational policies

Application code must NOT:

* implement custom pod scaling logic
* rely on fixed replica counts
* assume singleton deployment topology

---

## Runtime Ownership

Kubernetes owns:

* pod lifecycle
* restart behavior
* scheduling
* node placement

Applications must tolerate:

* termination
* eviction
* restart
* rescheduling

Avoid runtime assumptions tied to pod identity.

---

## CI/CD Ownership

Deployment automation belongs ONLY to:

* CI/CD pipelines
* GitOps tooling
* platform deployment systems

Applications must NOT:

* self-deploy
* self-modify manifests
* mutate runtime deployment state

---

# Enterprise Rules

Generated Kubernetes-related code must:

* support long-term maintenance
* be GitOps-compatible
* avoid cloud vendor lock-in
* be easy to debug operationally

Prefer declarative infrastructure over hidden automation.

---

# Anti-Patterns

Forbidden:

* hardcoded namespaces
* hardcoded service IPs
* mutable container filesystems
* root containers
* startup sleeps
* blocking init loops
* local disk persistence assumptions
* static cluster assumptions
* direct node communication
* unmanaged Kubernetes watches
* infinite retries
* hidden side effects during startup
* runtime manifest mutation
* environment-specific branching logic

Avoid operational magic.
