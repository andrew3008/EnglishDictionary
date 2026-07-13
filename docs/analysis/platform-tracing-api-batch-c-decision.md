# Batch C Decision - platform-tracing-api

Date: 2026-07-13

## 1. Executive Verdict

**DO PARTIAL BATCH C**

Batch C is worthwhile only for names where the current API vocabulary actively misstates the domain. Approved items are limited to:

- `SpanLinkContext` -> `RemoteSpanLink`
- `SpanAttributeValue` -> `SpanSpecAttributeValue`
- `TracingControlProtocolTypes` -> `TracingControlProtocolFieldType`

The semantic-contract and MDC candidates are skipped or rejected because the proposed names are cosmetic, too broad, or less accurate than the existing names.

## 2. Candidate Matrix

| Candidate | Current Role | Current Scope | Proposed Name | Value | Risk | Decision | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `SpanLinkContext` | Public record containing W3C/OpenTelemetry trace id, span id, flags, tracestate for span links | Public API, `platform-tracing-api`, package `space.br1440.platform.tracing.api.span`; consumed by `ManualSpanBuilder.linkedTo`, `SpanRelationshipSpec.links`, `SpanSpecBuilder`, `TraceparentParser`, core runtime, Kafka batch link aspect, tests/docs | `RemoteSpanLink` | High: current name sounds like generic link context; actual value is a remote span context used only as a link target | Medium: public API rename with broad tests/docs updates, but no wire values | **DO** | It stores remote trace/span identifiers and is converted by `OtelTracingRuntime.toRemoteSpanContext`; `TraceparentParser` returns it from W3C `traceparent`; no non-link usage was found |
| `SpanAttributeValue` | Public sealed value hierarchy for attributes stored in `SpanSpec` | Public API, `platform-tracing-api`, package `space.br1440.platform.tracing.api.span.spec`; consumed by `SpanSpec`, `DefaultSpanSpecBuilder`, core manual builders, `SpanAttributeValueConverter`, runtime/tests/docs | `SpanSpecAttributeValue` | Medium: clarifies this is the manual spec-building whitelist, not a general runtime span attribute model | Medium: public API rename, sealed permits/nested types and converter updates | **DO** | The type lives under `span.spec`, is exposed by `SpanSpec.attributes()`, and exists to preserve typed values before building/executing a spec |
| `CategoryContract(s)` | Public semantic contract record and registry keyed by `SpanCategory` | Public API, `platform-tracing-api`, package `space.br1440.platform.tracing.api.semconv`; consumed by `AttributePolicy`, semconv tests, docs | `SpanCategoryContract(s)` | Low: in the `semconv` package and already has a `SpanCategory category()` field | Low/Medium: public API churn in semconv docs/tests | **SKIP** | Current name is not misleading enough to justify churn; no evidence of competing contract-category concept in code |
| `TracingControlProtocolTypes` | Public enum for schema field type metadata (`STRING`, `BOOLEAN`, `DOUBLE`, etc.) | Public API and wire-adjacent schema vocabulary, `platform-tracing-api`, package `space.br1440.platform.tracing.api.control.protocol.schema`; consumed by schema descriptors, validators, tests/docs | `TracingControlProtocolFieldType` | High: enum describes one field's type, not multiple protocol types | Medium/High: wire-adjacent; must preserve enum constants and `name()` values | **DO** | `TracingControlProtocolFieldDescriptor.type()` is singular; validation uses enum constants as expected-type strings, which remain unchanged by Java type rename |
| `RemoteServiceTraceMirror` | Public trace-scoped map from trace id to remote service MDC value | Public API, `platform-tracing-api`, package `space.br1440.platform.tracing.api.mdc`; consumed by `RemoteServiceMdc`, Spring Boot remote service provider/tests | `RemoteTraceMdcMirror` | Negative: proposed name suggests mirroring remote trace, but the stored value is remote service | Low/Medium: public API churn in MDC docs/tests | **REJECT** | Javadoc and methods show `put(traceId, remoteService)` and `get(traceId)`; current name's "RemoteService" is important and should not be dropped |
| `RemoteServiceContextReaders` | Public registry of fallback readers for remote service context outside ThreadLocal MDC | Public API, `platform-tracing-api`, package `space.br1440.platform.tracing.api.mdc`; consumed by Spring Boot remote service provider/tests | `RemoteServiceMdcReaders` | Ambiguous: package is MDC, but readers are not necessarily reading MDC fields | Low/Medium: public API churn and possible conceptual narrowing | **REJECT** | Candidate guidance requires readers to specifically read MDC fields; implementation accepts arbitrary `Supplier<Optional<String>>` and is described as reading beyond ThreadLocal MDC |

## 3. Approved Implementation Scope

Implement exactly:

1. `SpanLinkContext` -> `RemoteSpanLink`
2. `SpanAttributeValue` -> `SpanSpecAttributeValue`
3. `TracingControlProtocolTypes` -> `TracingControlProtocolFieldType`

Update API, core, autoconfigure, tests, current docs, API inventory, naming options, CHANGELOG, and wire-adjacent ADR/decision notes where these names are current-state vocabulary.

## 4. Skipped / Deferred / Rejected Items

- `CategoryContract` / `CategoryContracts` -> `SpanCategoryContract` / `SpanCategoryContracts`: **SKIP**. The current name is local to the semconv package, the record has an explicit `SpanCategory category()`, and the rename is mostly cosmetic.
- `RemoteServiceTraceMirror` -> `RemoteTraceMdcMirror`: **REJECT**. The proposed name drops the key domain object: remote service. The class mirrors remote service by trace id, not a remote trace.
- `RemoteServiceContextReaders` -> `RemoteServiceMdcReaders`: **REJECT**. The readers are extensible suppliers for remote service context beyond ThreadLocal MDC, not strictly MDC-field readers.

No candidate is deferred.

## 5. Guardrails

Forbidden changes:

- Do not change `SpanCategory.value()`.
- Do not change `SpanResult.value()`.
- Do not change `TracingControlProtocolKeys` string values.
- Do not change tracing-control schema wire key names.
- Do not change `platform-trace-control` SPI name.
- Do not change `platform.tracing.scrubbing.built-in-rules`.
- Do not change ServiceLoader SPI names unrelated to approved Batch C candidates.
- Do not rename Batch A/B1/B2 names.
- For `TracingControlProtocolFieldType`, rename Java type only; keep enum constants and therefore `name()` wire-adjacent values unchanged.

## 6. Verification Plan

Run after implementation:

```powershell
.\gradlew.bat compileJava compileTestJava
.\gradlew.bat :platform-tracing-api:test :platform-tracing-core:test :platform-tracing-spring-boot-autoconfigure:test
.\gradlew.bat :platform-tracing-otel-extension:test
```

Run old-name checks for approved candidates:

```powershell
Get-ChildItem -Path . -Recurse -Include *.java,*.md,*.puml,*.txt,*.properties |
  Select-String -Pattern "SpanLinkContext|SpanAttributeValue|TracingControlProtocolTypes"
```

Classify remaining hits as:

- `OK_HISTORICAL_DOC`
- `OK_DECISION_DOC`
- `FAIL_CURRENT_CODE`
- `FAIL_CURRENT_DOC`
- `FAIL_WIRE_VALUE_CHANGE_RISK`

Because `TracingControlProtocolFieldType` is wire-adjacent, verify schema/validator tests and document that enum constants and wire key strings are unchanged.
