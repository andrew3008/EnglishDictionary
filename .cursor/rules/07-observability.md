# Observability Rules

## Required
- metrics (Micrometer)
- tracing (OpenTelemetry)
- structured logs

## Rules
- no secrets in logs
- correlation IDs required
- trace propagation mandatory

## Forbidden
- silent failures
- hidden retries
- uninstrumented async flows

## Anti-Patterns
- high-cardinality metrics misuse
- unstructured logging