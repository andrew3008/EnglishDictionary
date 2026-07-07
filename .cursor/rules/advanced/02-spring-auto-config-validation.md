# Spring Boot Auto-Configuration Validation Rules

## Context
This repository defines enterprise Spring Boot starters.

---

## Auto-Configuration Rules

All auto-configurations MUST:
- use @AutoConfiguration
- be conditional by default
- be idempotent

---

## Required Conditions

Every bean MUST use at least one:

- @ConditionalOnClass
- @ConditionalOnMissingBean
- @ConditionalOnProperty

Never register unconditional infrastructure beans.

---

## Configuration Properties Rules

- MUST use @ConfigurationProperties
- MUST NOT use @Value
- MUST be immutable

---

## Validation Rules

Auto-config MUST:
- fail fast on invalid config
- provide meaningful error messages
- avoid silent fallback behavior

---

## Bean Creation Rules

Allowed:
- lazy bean creation
- conditional bean creation
- optional infrastructure wiring

Forbidden:
- eager initialization during startup
- side effects in @Configuration classes

---

## Compatibility Rules

Auto-config MUST:
- preserve backward compatibility
- support additive evolution only
- never break existing property contracts silently

---

## Anti-Patterns
- hidden bean overrides
- implicit auto-registration
- runtime conditional surprises