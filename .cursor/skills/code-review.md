# Code Review Standards

## Review Priorities
Review for:
- maintainability
- backward compatibility
- observability
- startup impact
- memory allocations

## Reject
Reject code with:
- hidden side effects
- reflection abuse
- unnecessary abstractions
- blocking calls in reactive flows

## Performance
Prefer:
- lazy initialization
- batching
- immutable structures