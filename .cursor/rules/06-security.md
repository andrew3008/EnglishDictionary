# Security Rules

## Core Principles
- least privilege
- secure by default
- explicit trust boundaries

## Forbidden
- hardcoded secrets
- logging sensitive data
- disabling TLS validation
- insecure deserialization

## Required
- external secret management
- validated inputs
- secure transport

## Anti-Patterns
- trust-all configurations
- implicit authorization bypass