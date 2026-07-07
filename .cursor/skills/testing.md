# Testing Standards

## Context
Platform starters require production-grade automated testing.

## Unit Testing
Use:
- JUnit 5
- AssertJ
- Mockito only when necessary

Prefer:
- state-based testing
- deterministic assertions

## Integration Testing
Always use:
- Testcontainers

Allowed containers:
- PostgreSQL
- KeyDB
- Redis
- Kafka
- MinIO

## Spring Testing
Use:
- ApplicationContextRunner for starter testing
- @SpringBootTest only when necessary

Prefer lightweight context validation.

## AutoConfiguration Testing
Verify:
- conditional bean registration
- property binding
- absence of unwanted beans

## Container Rules
Containers must:
- be reusable
- use dynamic ports
- avoid fixed localhost assumptions

## Performance
Tests must:
- run in parallel safely
- avoid sleeps
- use Awaitility for async verification

## Anti-patterns
Forbidden:
- Thread.sleep
- shared mutable test state
- external environment dependencies
- real cloud services