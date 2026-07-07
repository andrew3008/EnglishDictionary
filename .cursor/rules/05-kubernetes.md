# Kubernetes Rules

## Core Principles
- stateless services
- horizontal scalability
- immutable deployments

## Required
- readiness probes
- liveness probes
- externalized config

## Forbidden
- hardcoded hostnames
- pod identity assumptions
- filesystem state dependency

## Anti-Patterns
- startup blocking on external services
- cluster topology assumptions