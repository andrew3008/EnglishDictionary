# Architecture Boundaries Rules

## Layering Rules
Strict separation:
- API layer
- service layer
- infrastructure layer
- platform layer

## Forbidden
- business logic in infrastructure
- infrastructure logic in API layer
- cross-layer imports
- circular dependencies

## Ownership
- platform owns infrastructure
- application owns business logic only

## Anti-Patterns
- god services
- shared utils dumping ground
- hidden coupling between modules