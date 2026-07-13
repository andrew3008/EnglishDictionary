# ADR: PR-B2 Scrubbing SPI Naming

## Status

Accepted - 2026-07-11.

## Context

The former `SensitiveDataRule` SPI is used by the OTel extension scrubbing pipeline to evaluate
span attribute key/value pairs. `ScrubbingSpanProcessor` mutates span attributes only; it does not
scrub span events, links, logs, baggage, or resource attributes.

## Decision

Rename the public SPI to `SpanAttributeScrubbingRule`.

The ServiceLoader descriptor path changes from:

```text
META-INF/services/space.br1440.platform.tracing.api.spi.SensitiveDataRule
```

to:

```text
META-INF/services/space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule
```

Implementation class names such as `PasswordKeyRule`, `JwtRule`, `ExampleMerchantAccountRule`, and
`MyCustomE2eRule` stay unchanged because they are already rule-specific.

## Architect-Requested Registry Rename (Warning Closure)

The original PR-B2 policy kept rule implementation class names unchanged.

After architect feedback, the internal registry enum was also renamed from
`BuiltInSensitiveDataRules` to `BuiltInSpanAttributeScrubbingRules` because it retained old SPI
vocabulary and appeared in operational documentation.

Specific rule implementation classes remain unchanged because their names are domain-specific rule
names, not stale SPI vocabulary.

This is not Batch C, not compatibility support, and not a deprecated bridge. It is part of PR-B2
warning closure to eliminate stale scrubbing SPI vocabulary from internal registry names and
current-state docs.

The Spring property `platform.tracing.scrubbing.built-in-rules` and its config name strings
(`password`, `jwt`, `email`, etc.) remain unchanged intentionally.

## Consequences

This is a hard pre-production break. External custom rule JAR authors must rebuild against
`SpanAttributeScrubbingRule` and publish the new ServiceLoader descriptor. No compatibility alias,
deprecated bridge, or duplicate SPI descriptor is provided.
