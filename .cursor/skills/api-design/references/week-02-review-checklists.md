# Week 02 — Review Checklists

## Model checklist

- Can API purpose be stated in one sentence?
- Bounded context defined for broad terms?
- Canonical glossary exists?
- One concept → one primary name?
- Same words mean same things across surfaces?
- Input, runtime state, and result separated?

## Roles and types checklist

- Primary semantic role per public type?
- Value types immutable and valid at creation?
- Entity/lifecycle objects have ownership and states?
- Policy separated from mechanism and orchestration?
- Result distinguishes outcomes needing different actions?
- View explicitly live or snapshot?
- Invalid combinations unrepresentable where possible?

## Naming checklist

- Each word narrows interpretation?
- Name true for all supported implementations?
- Qualifiers express scope, time, role, or ownership?
- No new/legacy/v2 without semantic necessity?
- Suffixes (Builder, Provider, Policy, View, Snapshot, Runtime) honored?
- Negative space explainable?

## Operations checklist

- Preconditions, postconditions, invariants defined?
- Commands and queries without hidden effects?
- Temporal semantics explicit?
- Repeat and idempotence defined?
- Errors classified by consumer action?
- absence / empty / disabled / unavailable / unsupported distinguished?

## Spring Boot checklist

- Properties grouped by concepts, not implementation classes?
- Defaults explained?
- Back-off semantics precise?
- Customizer vs provider vs replacement distinguished?
- Bean types express roles?
- Ordering not hidden dependency graph?
- Operational names aligned with Java/configuration language?

## Anti-pattern checklist

- Abstractions based on shared laws, not shared form?
- Symmetric operations truly reversible?
- Abstraction usable without hidden mechanism?
- Interface capability-oriented, not feature backlog?
- Facade has one coherent responsibility?

## Control questions (self-test)

1. Why can semantically precise API be syntactically longer yet easier?
2. Conceptual integrity vs uniform naming style?
3. Why is one term correct in one bounded context and ambiguous in another?
4. What obligations does suffix `Snapshot` create?
5. View vs immutable value?
6. When is runtime object entity not value?
7. Why separate policy from mechanism?
8. Weakness of boolean as result type?
9. When is enum worse than open value type or registry key?
10. Why does `getOrCreate` hinder reasoning?
11. Temporal guarantees for `shutdown()`?
12. Stable generalization vs accidental shared method?
13. Intentional integration API vs leakage?
14. Why `enabled=false` has no universal starter semantics?
15. Customizer vs provider vs replacement bean?
16. Facade as capability vs backlog?
17. ActiveTraceContextView vs RequestTraceContextSnapshot?
18. When is TracingRuntime better than TracingImplementation?
19. Semantic Contract Map vs Public Contract Inventory?
20. Binary compatible but semantic breaking changes?

## Final theses

1. API is a small language.
2. Semantics defines concepts, states, actions, guarantees.
3. Conceptual integrity beats external symmetry.
4. Ubiquitous language spans code, config, Spring, ops, docs.
5. Seven roles detect mixed responsibility.
6. Types justified by essential distinctions/invariants.
7. Dense names reduce misinterpretation via negative space.
8. Operations defined by effects and time.
9. Stable abstraction = shared laws and substitutability.
10. Starter publishes activation, defaults, back-off, replacement, ordering language.
11. Good rename names removed ambiguity and new testable obligations.
12. Inventory lists surfaces; Semantic Contract Map binds them into one model.

## Primary sources

- [Eric Evans — DDD Reference](https://www.domainlanguage.com/ddd/reference/)
- [Joshua Bloch — How to Design a Good API](https://research.google/pubs/how-to-design-a-good-api-and-why-it-matters/)
- [Liskov & Wing — Behavioral Subtyping](https://www.cs.cmu.edu/afs/cs/project/venari/www/subtype-tr.html)
- [Spring Boot Auto-configuration](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)
- [Spring Boot Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
- [OpenTelemetry Semantic Conventions — Naming](https://opentelemetry.io/docs/specs/semconv/general/naming/)
