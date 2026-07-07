# Multi-Module Gradle Governance Rules

## Context
Enterprise builds consist of multi-module Spring Boot + platform DSL + infrastructure layers.

---

## Module Design Rules

Each module MUST:
- have single responsibility
- define clear ownership boundary
- avoid cyclic dependencies

Module types:
- platform-starter
- domain-service
- infrastructure-module
- gradle-convention-plugin

---

## Dependency Direction Rules

Allowed dependency flow:

domain → platform-api → infrastructure

Forbidden:
- infrastructure → domain
- platform → application
- test → production code coupling

---

## Root Project Rules

Root build MUST:
- NOT contain business logic
- NOT define production dependencies
- ONLY aggregate modules

Forbidden:
- allprojects {}
- subprojects {} logic with business concerns

---

## Version Governance

All versions MUST be managed via:
- version catalogs (libs.versions.toml)
- BOM modules

Forbidden:
- inline dependency versions
- dynamic versions (+, latest.release)

---

## Convention Plugin Rules

Plugins MUST:
- configure only one concern
- be idempotent
- be configuration-cache safe

Forbidden:
- afterEvaluate
- side-effect plugin application
- implicit dependency injection into project state

---

## Anti-Patterns
- shared "common" mega-module
- circular module dependencies
- version duplication across modules