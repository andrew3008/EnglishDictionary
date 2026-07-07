# Spring Boot Starter Standards

## Context
This project contains reusable Spring Boot platform starters.

Starters must behave predictably across hundreds of services.

## AutoConfiguration Rules
- Use Spring Boot 3 conventions
- Use @AutoConfiguration
- Avoid legacy spring.factories when possible
- Prefer AutoConfiguration.imports

## Conditional Beans
Always use:
- @ConditionalOnClass
- @ConditionalOnMissingBean
- @ConditionalOnProperty

Never register unconditional infrastructure beans.

## Configuration Properties
- Use @ConfigurationProperties
- Avoid @Value
- Use strongly typed properties

## Starter Design
Starter responsibilities:
- auto-configuration
- sensible defaults
- platform integration
- observability hooks

Starters must NOT:
- contain business logic
- expose internal implementation
- force infrastructure decisions

## Bean Design
- Beans must be singleton unless justified
- Avoid eager initialization
- Prefer lazy integrations

## Observability
Every starter should support:
- Micrometer
- structured logging
- health indicators
- tracing hooks

## Compatibility
Maintain compatibility with:
- Spring Boot minor upgrades
- Gradle configuration cache
- native image compatibility where possible

## Anti-patterns
Forbidden:
- direct environment access
- hardcoded ports
- static application context holders
- hidden side effects during startup