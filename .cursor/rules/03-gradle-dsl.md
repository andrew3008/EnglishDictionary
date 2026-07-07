# Gradle DSL Rules

## Core Principles
- configuration cache must be supported
- lazy configuration only
- deterministic builds

## Allowed
- Provider API
- Version catalogs
- Convention plugins

## Forbidden
- afterEvaluate
- eager task creation
- dynamic dependency versions
- allprojects/subprojects logic

## Plugin Rules
- one concern per plugin
- no side effects during apply()

## Anti-Patterns
- build-time mutation of project state
- hidden task registration