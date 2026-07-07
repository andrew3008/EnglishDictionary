# Distributed Systems Rules

## Core Principles
- assume partial failure
- assume retries
- assume eventual consistency

## Required
- idempotent operations
- timeout-aware logic
- retry-safe design

## Forbidden
- singleton assumptions
- local memory coordination
- implicit ordering guarantees

## Anti-Patterns
- blocking distributed flows
- hidden coordination logic