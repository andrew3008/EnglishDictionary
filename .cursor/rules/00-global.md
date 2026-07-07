# Global Engineering Rules

These rules apply to ALL generated code in the repository.

## Core Principles
- explicit over implicit
- backward compatibility first
- minimal magic
- observable by default
- deterministic behavior

## Hard Rules
- never introduce hidden runtime behavior
- never break backward compatibility without migration path
- never introduce static mutable state
- never introduce reflection-based logic for core flows

## Defaults
- Java 21
- Spring Boot 3+
- Gradle Kotlin DSL