# Spring Boot Starter Rules

## Core Rules
- starters must be purely infrastructure
- no business logic in starters
- use @AutoConfiguration only

## Conditional Beans
Always use:
- @ConditionalOnClass
- @ConditionalOnMissingBean
- @ConditionalOnProperty

## Forbidden
- spring.factories legacy usage (unless required)
- eager bean initialization
- hidden auto-behavior

## Configuration
- always use @ConfigurationProperties
- never use @Value in platform code

## Anti-Patterns
- implicit bean registration
- runtime side effects during startup