# Redis Rules

## Core Principles
- Redis is NOT a database
- Redis is ephemeral infrastructure

## Required
- TTL for all ephemeral data
- namespaced keys

## Key Format
env:service:domain:entity:id

## Forbidden
- Java serialization
- no-TTL keys for temporary data
- global keys without namespace

## Anti-Patterns
- cache as source of truth
- distributed lock without TTL