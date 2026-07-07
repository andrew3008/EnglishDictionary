# Testing Rules

## Required
- JUnit 5
- Testcontainers
- deterministic tests

## Integration Tests
- real infrastructure preferred
- no embedded Redis

## Forbidden
- Thread.sleep
- random timing assumptions
- fixed port usage

## Anti-Patterns
- mocking infrastructure layers excessively
- non-reproducible tests