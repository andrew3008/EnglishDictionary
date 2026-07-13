# platform-tracing-api Model Naming Options and Scored Redesign

> Historical note: this document was produced before the final platform-tracing-api naming refactor chain
> (Batch A, PR-B1, PR-B2, Batch C). It may mention pre-refactor API names for audit/history.
> Current API names are documented in the final umbrella audit and module CHANGELOG.

## 1. Executive Summary

- Public API types analyzed: **81 top-level public types** under `platform-tracing-api/src/main/java`.
- Model-like types analyzed: **47** records, enums, annotations, value interfaces, constant registries, protocol descriptors, decisions, and snapshots.
- `KEEP_STRONG`: **34**.
- `KEEP_ACCEPTABLE`: **23**.
- `RENAME_RECOMMENDED`: **13**.
- `RENAME_STRONGLY_RECOMMENDED`: **7**.
- Delete/merge/split candidates: **4**.
- Scope note: `SpanSpecAttributeValue` nested records are treated as part of `SpanSpecAttributeValue`, not as separate top-level scoreboard rows.

Top 10 high-value naming changes:

1. `SpanSpec.options()` -> `SpanSpec.topology()` or, if Batch B renames the pair, `SpanSpec.start()`.
2. `Topology` -> **`SpanStartMode`**.
3. `SpecifiedSpan` -> **`SpanExecution`**.
4. `PlatformSpanBuilder` -> **`ManualSpanBuilder`**.
5. Remove public `SpanScope`; keep lifecycle mutation behind core/internal implementation or expose only `SpanHandle`.
6. `EnrichScope` -> **`SpanEnrichment`** and `GenericEnrichScope` -> **`GenericSpanEnrichment`**.
7. `RequestTraceContextSnapshot` -> **`RequestTraceContextSnapshot`**.
8. Merge `DatabaseTracing` into `DatabaseSpanBuilder` by returning `DatabaseSpanBuilder` from `TransportTracing.database()`.
9. `SensitiveDataRule` -> **`SpanAttributeScrubbingRule`**.
10. `OutboundPropagationDecision` -> **`OutboundPropagationDecision`** and `InboundTraceControl` -> **`InboundTraceControl`**.

Production-readiness verdict: **not production-ready as-is**. The API shape is strong, but several public names still carry stale, generic, or legacy-contaminated vocabulary. Because this is pre-production, the clean final API should win over churn.

## 2. Facts vs Assumptions

### Facts

- `PlatformTracing` has exactly two public entry methods: `traceContext()` and `manual()` in `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/PlatformTracing.java`.
- `ManualTracing` exposes `operation(String)`, `transport()`, and `spanFromSpec(SpanSpec)` in `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/ManualTracing.java`.
- `PlatformSpanBuilder` is the common fluent execution contract for manual builders in `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/PlatformSpanBuilder.java`.
- `SpanSpec.options()` returns `SpanTopologySpec` in `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpanSpec.java`.
- `SpanTopologySpec` contains only `topology()` and `links()` plus static factories/validation in `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpanTopologySpec.java`.
- `Topology` is a public enum with `CHILD`, `ROOT`, and `DETACHED` in `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/Topology.java`.
- `SpanScope` is still public and documented as v1/v2 API in `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/SpanScope.java`.
- `SpanHandle` is the v3 minimal lifecycle handle in `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpanHandle.java`.
- `DatabaseTracing extends DatabaseSpanBuilder` in `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/DatabaseTracing.java`.
- `RequestTraceContextSnapshot` is a nullable record used for error-handling integration in `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/context/RequestTraceContextSnapshot.java`.
- `ActiveTraceContextView` is a read-only active context view in `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/ActiveTraceContextView.java`.
- `EnrichScope` and `GenericEnrichScope` are mutation DSLs, not lifecycle scopes, in `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/enrich`.
- `SpanAttributeScrubbingRule` is the public scrubbing SPI implemented by the OTel extension in `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/spi/SpanAttributeScrubbingRule.java`.
- `SpanOptions` no longer exists as a source file; stale references remain in docs/tests, including `platform-tracing-api/src/test/java/space/br1440/platform/tracing/api/manual/arch/V3ManualApiArchTest.java`.
- Verification command over source found **81** top-level public API types: 6 annotations, 23 classes, 10 enums, 31 interfaces, and 11 records.

### Assumptions / Needs Verification

- Downstream services outside this monorepo are not considered production consumers yet.
- Batch B may require an architecture decision on whether the vocabulary should keep `topology` or switch fully to `start mode`.
- Removing public `SpanScope` assumes no external pre-production consumers are intentionally using it.
- The OTel `compileOnly` exposure in semconv/enrichment/SPI is intentional for advanced integrations.
- No Gradle tests were run; this is analysis-only per request.

## 3. Inventory Validation

| Inventory Claim | Repository Evidence | Status | Notes |
|---|---|---|---|
| API root is `PlatformTracing` with `traceContext()` and `manual()` | `PlatformTracing.java` | Confirmed | Correct root graph. |
| Inventory says 80 public types | Source scan found 81 top-level public types | Corrected | Likely missed one public annotation/type; scoreboard uses repository count. |
| `SpanOptions` removed; `SpanTopologySpec` exists | `SpanTopologySpec.java`; no `SpanOptions.java` | Confirmed | Docs still mention `SpanOptions`. |
| `SpanSpec.options()` returns topology/link value model | `SpanSpec.java` | Confirmed with naming issue | Method name is stale vocabulary. |
| `api.span.builder` removed | `rg --files platform-tracing-api/src/main/java` | Confirmed | Do not reintroduce. |
| `SpanScope` public legacy remains | `SpanScope.java`; `SpanHandle.java` Javadoc contrast | Confirmed | Public legacy name is a release risk. |
| `DatabaseTracing` is a navigator like HTTP/RPC/Kafka | `DatabaseTracing extends DatabaseSpanBuilder` | Partly wrong | It is also the builder contract, unlike peers. |
| `EnrichScope` exposes OTel `AttributeKey` | `EnrichScope.java` | Confirmed | Intentional but name should not say lifecycle scope. |
| `VersionedState` is shared runtime marker | `VersionedState.java`; core `ValidationSnapshot` imports | Confirmed | Previous refactoring rationale is sound. |
| Control protocol family is under `api.control.protocol` | protocol package files | Confirmed | Naming mostly consistent, one enum is weak. |

## 4. API Vocabulary Diagnosis

Strengths:

- The root API is compact: `PlatformTracing.traceContext()` and `PlatformTracing.manual()` are easy for Spring Boot services.
- Fluent manual builders have a consistent grammar: `.child()`, `.root()`, `.detached()`, `.linkedTo()`, `.start()`, `.run()`, `.call()`.
- Transport branches are mostly intuitive: HTTP/RPC/Kafka use navigator interfaces returning typed builders.
- Wire-value enums such as `SpanCategory` and `SpanResult` have explicit stable string values.
- Control protocol names are verbose but internally consistent.

Systemic weaknesses:

- `Platform*` is overused for types that are not root platform facades (`PlatformSpanBuilder`, `OutboundPropagationDecision`, `TraceControlHeaderInjector`).
- `Scope` is overloaded: `SpanScope` is lifecycle/closeable, while `EnrichScope` is a mutation DSL.
- `Topology` remains hard for non-developers and architects; the enum values are really a start-time parent selection mode.
- `SpanSpec.options()` is stale after `SpanOptions` -> `SpanTopologySpec`.
- `SpecifiedSpan` is an outlier adjective and hides that the type is an execution surface.
- `DatabaseTracing` violates the transport navigator pattern by being both navigation node and builder.
- `Context` appears in both `ActiveTraceContextView` and `RequestTraceContextSnapshot` without making active-vs-snapshot explicit.

Legacy contamination:

- `SpanScope` explicitly says v1/v2 and should not remain a public production API if v3 uses `SpanHandle`.
- Docs still mention `SpanOptions`, including `docs/tracing/platform-tracing-v3-manual-api.md` and `docs/analysis/platform-tracing-refactoring-plan.md`.
- `V3ManualApiArchTest` still allowlists `"SpanOptions"`.

Generic vocabulary overuse:

- `Options`, `State`, `Scope`, `Context`, `Rule`, `Decision`, and `Platform` are all acceptable only when qualified with exact domain role.
- `Topology` is accurate for engineers but not precise enough for the actual enum contract.

## 5. Full API Type Scoreboard

| Current Type | Package | Current Score | Category | Recommended Name / Action | Recommended Score | Delta | Rationale |
|---|---|---:|---|---|---:|---:|---|
| `PlatformTracing` | `api` | 94 | KEEP_STRONG | Keep | 94 | 0 | Root facade name is intentional and commercial-grade. |
| `Traced` | `api.annotation` | 92 | KEEP_STRONG | Keep | 92 | 0 | Standard annotation vocabulary. |
| `TracedAttribute` | `api.annotation` | 91 | KEEP_STRONG | Keep | 91 | 0 | Clear AOP attribute annotation. |
| `SuppressAgentInstrumentation` | `api.annotation` | 89 | KEEP_STRONG | Keep | 89 | 0 | Explicit and safer than short alternatives. |
| `PlatformAttributes` | `api.attributes` | 88 | KEEP_STRONG | Keep | 88 | 0 | Constants registry; platform prefix is appropriate. |
| `PlatformSamplingReasons` | `api.attributes` | 86 | KEEP_STRONG | Keep | 86 | 0 | Constants registry; domain clear. |
| `RequestTraceContextSnapshot` | `api.context` | 66 | RENAME_STRONGLY_RECOMMENDED | `RequestTraceContextSnapshot` | 91 | +25 | It is a nullable snapshot for request error handling, not active tracing context. |
| `TracingControlProtocol` | `api.control.protocol` | 90 | KEEP_STRONG | Keep | 90 | 0 | Aggregate protocol entrypoint is clear. |
| `TracingControlProtocolValidationResult` | `api.control.protocol.result` | 86 | KEEP_ACCEPTABLE | Keep | 86 | 0 | Verbose but exact in wire family. |
| `TracingControlProtocolViolation` | `api.control.protocol.result` | 86 | KEEP_ACCEPTABLE | Keep | 86 | 0 | Consistent protocol violation model. |
| `TracingControlProtocolFieldCategory` | `api.control.protocol.schema` | 82 | KEEP_ACCEPTABLE | Keep | 82 | 0 | Acceptable descriptor enum. |
| `TracingControlProtocolFieldDescriptor` | `api.control.protocol.schema` | 87 | KEEP_STRONG | Keep | 87 | 0 | Correct `Descriptor` suffix. |
| `TracingControlProtocolKeys` | `api.control.protocol.schema` | 84 | KEEP_ACCEPTABLE | Keep | 84 | 0 | Constants registry; verbose but scoped. |
| `TracingControlProtocolOperation` | `api.control.protocol.schema` | 86 | KEEP_STRONG | Keep | 86 | 0 | Operation enum name is exact. |
| `TracingControlProtocolSchema` | `api.control.protocol.schema` | 86 | KEEP_STRONG | Keep | 86 | 0 | Schema registry name is exact. |
| `TracingControlProtocolFieldType` | `api.control.protocol.schema` | 72 | RENAME_RECOMMENDED | `TracingControlProtocolFieldType` | 90 | +18 | Enum should be singular and field-scoped. |
| `TracingControlProtocolValidator` | `api.control.protocol.validation` | 88 | KEEP_STRONG | Keep | 88 | 0 | Behavior class; name is exact. |
| `TracingControlProtocolViolationCode` | `api.control.protocol.validation` | 86 | KEEP_ACCEPTABLE | Keep | 86 | 0 | Clear protocol-scoped code enum. |
| `TracingControlProtocolVersion` | `api.control.protocol.version` | 88 | KEEP_STRONG | Keep | 88 | 0 | Version value object is clear. |
| `ManualTracing` | `api.manual` | 90 | KEEP_STRONG | Keep | 90 | 0 | Entry point is clear. |
| `TransportTracing` | `api.manual` | 87 | KEEP_STRONG | Keep | 87 | 0 | Navigator group is clear. |
| `HttpTracing` | `api.manual` | 87 | KEEP_STRONG | Keep | 87 | 0 | Navigator group is clear. |
| `HttpServerSpanBuilder` | `api.manual` | 90 | KEEP_STRONG | Keep | 90 | 0 | Builder role and span kind are clear. |
| `HttpClientSpanBuilder` | `api.manual` | 90 | KEEP_STRONG | Keep | 90 | 0 | Builder role and span kind are clear. |
| `DatabaseTracing` | `api.manual` | 58 | MERGE_RECOMMENDED | Delete/merge into `DatabaseSpanBuilder` | 92 | +34 | It is a builder, not a navigator like peers. |
| `DatabaseSpanBuilder` | `api.manual` | 89 | KEEP_STRONG | Keep | 89 | 0 | Exact semantic builder. |
| `RpcTracing` | `api.manual` | 87 | KEEP_STRONG | Keep | 87 | 0 | Navigator group is clear. |
| `RpcServerSpanBuilder` | `api.manual` | 90 | KEEP_STRONG | Keep | 90 | 0 | Exact builder role. |
| `RpcClientSpanBuilder` | `api.manual` | 90 | KEEP_STRONG | Keep | 90 | 0 | Exact builder role. |
| `KafkaTracing` | `api.manual` | 87 | KEEP_STRONG | Keep | 87 | 0 | Navigator group is clear. |
| `KafkaProducerSpanBuilder` | `api.manual` | 90 | KEEP_STRONG | Keep | 90 | 0 | Exact builder role. |
| `KafkaConsumerSpanBuilder` | `api.manual` | 90 | KEEP_STRONG | Keep | 90 | 0 | Exact builder role. |
| `KafkaBatchSpanBuilder` | `api.manual` | 86 | KEEP_ACCEPTABLE | Keep | 86 | 0 | Clear enough for batch consumer spans. |
| `OperationSpanBuilder` | `api.manual` | 88 | KEEP_STRONG | Keep | 88 | 0 | Service developer friendly. |
| `PlatformSpanBuilder` | `api.manual` | 64 | RENAME_STRONGLY_RECOMMENDED | `ManualSpanBuilder` | 91 | +27 | Common manual builder, not a platform root facade. |
| `ActiveTraceContextView` | `api.manual` | 80 | RENAME_RECOMMENDED | `ActiveActiveTraceContextView` | 91 | +11 | Active/read-only should be explicit due `RequestTraceContextSnapshot`. |
| `RemoteServiceContextReaders` | `api.mdc` | 78 | KEEP_ACCEPTABLE | Keep | 78 | 0 | Utility class; acceptable but not elegant. |
| `RemoteServiceMdc` | `api.mdc` | 85 | KEEP_STRONG | Keep | 85 | 0 | Clear MDC bridge name. |
| `RemoteServiceTraceMirror` | `api.mdc` | 70 | RENAME_RECOMMENDED | `RemoteTraceContextMdcMirror` | 86 | +16 | `Mirror` needs source/target context. |
| `TracingMdcKeys` | `api.mdc` | 86 | KEEP_STRONG | Keep | 86 | 0 | Constants registry is clear. |
| `PlatformContextPropagation` | `api.propagation` | 82 | KEEP_ACCEPTABLE | Keep | 82 | 0 | Platform facade is acceptable here. |
| `PlatformHeaders` | `api.propagation` | 84 | KEEP_ACCEPTABLE | Keep | 84 | 0 | Constants registry; acceptable. |
| `RequestIdSupport` | `api.propagation` | 82 | KEEP_ACCEPTABLE | Keep | 82 | 0 | Utility support class; acceptable. |
| `OutboundPropagationPolicy` | `api.propagation.control` | 88 | KEEP_STRONG | Keep | 88 | 0 | Policy has behavior and decision logic. |
| `TraceControlHeaderInjector` | `api.propagation.control` | 67 | RENAME_RECOMMENDED | `TraceControlHeaderInjector` | 90 | +23 | It injects trace-control headers, not all platform outbound data. |
| `OutboundPropagationDecision` | `api.propagation.control` | 69 | RENAME_RECOMMENDED | `OutboundPropagationDecision` | 91 | +22 | Decision is outbound-specific. |
| `PlatformTraceContextKeys` | `api.propagation.control` | 76 | KEEP_ACCEPTABLE | Keep or `TraceControlContextKeys` | 84 | +8 | Platform prefix acceptable for OTel Context keys. |
| `InboundTraceControl` | `api.propagation.control` | 69 | RENAME_RECOMMENDED | `InboundTraceControl` | 91 | +22 | Extracted from incoming carrier/header values. |
| `TrustedDestinationMatcher` | `api.propagation.control` | 90 | KEEP_STRONG | Keep | 90 | 0 | Exact behavior name. |
| `VersionedState` | `api.runtime.state` | 88 | KEEP_STRONG | Keep | 88 | 0 | Marker role is clear; prior review supports it. |
| `VersionedStateHolder` | `api.runtime.state` | 86 | KEEP_STRONG | Keep | 86 | 0 | Holder role is clear. |
| `CategoryContract` | `api.semconv` | 74 | RENAME_RECOMMENDED | `SpanCategoryContract` | 91 | +17 | Category should be qualified by span domain. |
| `CategoryContracts` | `api.semconv` | 73 | RENAME_RECOMMENDED | `SpanCategoryContracts` | 90 | +17 | Registry should match singular model. |
| `DatabaseSemconvVersion` | `api.semconv` | 82 | KEEP_ACCEPTABLE | Keep | 82 | 0 | Annotation is concise and domain-scoped. |
| `KafkaSemconvVersion` | `api.semconv` | 82 | KEEP_ACCEPTABLE | Keep | 82 | 0 | Annotation is concise and domain-scoped. |
| `RpcSemconvVersion` | `api.semconv` | 82 | KEEP_ACCEPTABLE | Keep | 82 | 0 | Annotation is concise and domain-scoped. |
| `SemconvKeys` | `api.semconv` | 76 | RENAME_RECOMMENDED | `SemanticAttributeKeys` | 88 | +12 | `Semconv` is jargon; class contains attribute keys. |
| `SemconvViolation` | `api.semconv` | 80 | KEEP_ACCEPTABLE | Keep or `SemanticConventionViolation` | 86 | +6 | Current term is established in module. |
| `SemconvViolationException` | `api.semconv` | 80 | KEEP_ACCEPTABLE | Keep or `SemanticConventionViolationException` | 86 | +6 | Same family as violation. |
| `ValidationMode` | `api.semconv` | 69 | RENAME_RECOMMENDED | `SemconvValidationMode` | 90 | +21 | Generic outside imports; class name should carry context. |
| `EnrichScope` | `api.span.enrich` | 60 | RENAME_STRONGLY_RECOMMENDED | `SpanEnrichment` | 91 | +31 | It is not a lifecycle scope. |
| `GenericEnrichScope` | `api.span.enrich` | 59 | RENAME_STRONGLY_RECOMMENDED | `GenericSpanEnrichment` | 90 | +31 | Avoid overloaded `Scope`; make generic/platform-safe explicit. |
| `TraceparentParser` | `api.span` | 64 | RENAME_RECOMMENDED | `Traceparent` or `TraceparentParser` | 88 | +24 | Utility parses W3C traceparent, not arbitrary remote context. |
| `SqlSanitizer` | `api.span.sanitize` | 84 | KEEP_ACCEPTABLE | Keep | 84 | 0 | Utility name is direct. |
| `UrlSanitizer` | `api.span.sanitize` | 84 | KEEP_ACCEPTABLE | Keep | 84 | 0 | Utility name is direct. |
| `SpanCategory` | `api.span` | 90 | KEEP_STRONG | Keep | 90 | 0 | Avoid `SpanKind` collision with OTel. |
| `RemoteSpanLink` | `api.span` | 72 | RENAME_RECOMMENDED | `RemoteSpanLink` | 89 | +17 | Record is a link descriptor, not a whole context. |
| `SpanResult` | `api.span` | 82 | KEEP_ACCEPTABLE | Keep or `SpanOutcome` | 86 | +4 | Acceptable; result attribute already exists. |
| `SpanScope` | `api.span` | 45 | DELETE_RECOMMENDED | Remove from public API; move mutable scope internal | 92 | +47 | Public legacy lifecycle surface conflicts with v3 `SpanHandle`. |
| `SpanSpecAttributeValue` | `api.span.spec` | 75 | RENAME_RECOMMENDED | `SpanSpecAttributeValue` | 88 | +13 | Only used in `SpanSpec.attributes()`. |
| `SpanHandle` | `api.span.spec` | 88 | KEEP_STRONG | Keep | 88 | 0 | Minimal lifecycle handle is clear. |
| `SpanSpec` | `api.span.spec` | 88 | KEEP_STRONG | Keep; rename `options()` member | 93 | +5 | Type is strong; accessor is stale. |
| `SpanSpecBuilder` | `api.span.spec` | 89 | KEEP_STRONG | Keep | 89 | 0 | Builder role is exact. |
| `SpanSpecReason` | `api.span.spec` | 78 | KEEP_ACCEPTABLE | Keep | 78 | 0 | `Reason` is plain Java/API vocabulary; stronger-sounding alternatives are harder to read. |
| `SpanTopologySpec` | `api.span.spec` | 83 | KEEP_ACCEPTABLE | Keep if `Topology` remains; else `SpanStartSpec` | 90 | +7 | Stronger pair depends on Batch B decision. |
| `SpecifiedSpan` | `api.span.spec` | 55 | RENAME_STRONGLY_RECOMMENDED | `SpanExecution` | 91 | +36 | It is an execution surface for a spec. |
| `Topology` | `api.span.spec` | 62 | RENAME_STRONGLY_RECOMMENDED | `SpanStartMode` | 93 | +31 | Values define start-time parent selection. |
| `ScrubbingAction` | `api.spi` | 89 | KEEP_STRONG | Keep | 89 | 0 | Exact enum for scrubbing result action. |
| `ScrubbingDecision` | `api.spi` | 90 | KEEP_STRONG | Keep | 90 | 0 | Decision value object is clear. |
| `SensitiveDataRule` | `api.spi` | 66 | RENAME_STRONGLY_RECOMMENDED | `SpanAttributeScrubbingRule` | 92 | +26 | SPI evaluates span attribute values for scrubbing. |
| `ThrowingSupplier` | `api.util` | 88 | KEEP_STRONG | Keep | 88 | 0 | Established functional interface vocabulary. |

## 6. Strong Rename Recommendations

### PlatformSpanBuilder -> ManualSpanBuilder

| Field | Value |
|---|---|
| Current file | `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/PlatformSpanBuilder.java` |
| Current FQN | `space.br1440.platform.tracing.api.manual.PlatformSpanBuilder` |
| Current role | Common fluent builder contract for manual span topology, links, and execution terminals. |
| Current score | 64 |
| Recommended score | 91 |
| Delta | +27 |
| Why current name fails | `Platform` sounds like root infrastructure, not a manual builder contract. |
| Why recommended name wins | `ManualSpanBuilder` ties the contract to `PlatformTracing.manual()` and all manual branches. |
| Blast radius | High imports/types, low semantic risk. |
| Affected modules | API, core, autoconfigure tests, bench tests. |
| Tests likely affected | `*SpanBuilderTest`, `TracingRuntimeRoutingTest`, ArchUnit tests. |
| Docs likely affected | v3 manual API docs, refactoring plan. |

| Option | Score | Pros | Cons | Verdict |
|---|---:|---|---|---|
| `PlatformSpanBuilder` | 64 | Existing name; broad | Generic and root-ish | Reject |
| `ManualSpanBuilder` | 91 | Precise, short, app-facing | Requires broad rename | **Choose** |
| `SpanExecutionBuilder` | 84 | Highlights terminals | Less clear for semantic builders | Considered |
| `SemanticSpanBuilder` | 80 | Fits HTTP/DB/RPC/Kafka | Operation spans are not semconv-specific | Reject |
| `SpanStartBuilder` | 78 | Topology/start emphasis | Understates run/call terminals | Reject |

### SpecifiedSpan -> SpanExecution

| Field | Value |
|---|---|
| Current file | `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpecifiedSpan.java` |
| Current FQN | `space.br1440.platform.tracing.api.span.spec.SpecifiedSpan` |
| Current role | Terminal execution surface returned by `ManualTracing.spanFromSpec(SpanSpec)`. |
| Current score | 55 |
| Recommended score | 91 |
| Delta | +36 |
| Why current name fails | Adjective form does not reveal command/execution role. |
| Why recommended name wins | `SpanExecution` describes `start/run/call/callChecked` directly. |
| Blast radius | Medium; mostly `ManualTracing`, core impl, tests. |
| Affected modules | API, core, spring-boot-autoconfigure tests, docs. |
| Tests likely affected | `SpecifiedSpanTest`, `TracingRuntimeRoutingTest`, topology matrix tests. |
| Docs likely affected | v3 manual API docs, refactoring plan. |

| Option | Score | Pros | Cons | Verdict |
|---|---:|---|---|---|
| `SpecifiedSpan` | 55 | References `SpanSpec` indirectly | Awkward and not role-revealing | Reject |
| `SpanExecution` | 91 | Clear command surface | Slightly abstract | **Choose** |
| `PreparedSpan` | 84 | Good before-start meaning | Less clear for run/call | Considered |
| `SpanCommand` | 80 | Command vocabulary | Java services may not expect it | Reject |
| `SpanInvocation` | 78 | Execution-ish | Too RPC-flavored | Reject |

### Topology -> SpanStartMode

| Field | Value |
|---|---|
| Current file | `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/Topology.java` |
| Current FQN | `space.br1440.platform.tracing.api.span.spec.Topology` |
| Current role | Enum selecting how a new span starts relative to active context: child, root, detached. |
| Current score | 62 |
| Recommended score | 93 |
| Delta | +31 |
| Why current name fails | Non-developers read topology as network/system graph, not start-time parent choice. |
| Why recommended name wins | `SpanStartMode` maps exactly to the moment and behavior being selected. |
| Blast radius | High references, low semantic risk. |
| Affected modules | API, core runtime/builders, autoconfigure tests, docs. |
| Tests likely affected | `SpanTopologySpecTopologyTest`, builder tests, metrics topology tests. |
| Docs likely affected | v3 manual API docs, refactoring plan, inventory docs. |

| Option | Score | Pros | Cons | Verdict |
|---|---:|---|---|---|
| `Topology` | 62 | Short; existing module term | Too abstract for public API | Reject |
| `SpanStartMode` | 93 | Exact and understandable | Requires member rename discussion | **Choose** |
| `SpanParentingMode` | 86 | Explains parent relation | Does not cover detached/link policy perfectly | Considered |
| `SpanRelationMode` | 78 | Relation-focused | Echoes removed `SpanRelation` | Reject |
| `SpanStartTopology` | 76 | Precise | Long and still says topology | Reject |

### EnrichScope -> SpanEnrichment

| Field | Value |
|---|---|
| Current file | `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/enrich/EnrichScope.java` |
| Current FQN | `space.br1440.platform.tracing.api.span.enrich.EnrichScope` |
| Current role | Category-confirmed mutation DSL for enriching an active span. |
| Current score | 60 |
| Recommended score | 91 |
| Delta | +31 |
| Why current name fails | `Scope` collides with lifecycle/closeable meaning from `SpanScope`. |
| Why recommended name wins | `SpanEnrichment` describes the operation surface without lifecycle implications. |
| Blast radius | Medium; core enrichment implementation/tests. |
| Affected modules | API, core. |
| Tests likely affected | `SpanEnricherTest` and enrichment assertions. |
| Docs likely affected | Enrichment docs if present. |

| Option | Score | Pros | Cons | Verdict |
|---|---:|---|---|---|
| `EnrichScope` | 60 | Existing package context | Scope overload | Reject |
| `SpanEnrichment` | 91 | Clear and concise | Noun not builder-like | **Choose** |
| `SpanEnricherScope` | 74 | Links to enricher | Still says scope | Reject |
| `SpanAttributeEnrichment` | 84 | Precise | Slightly long | Considered |

### GenericEnrichScope -> GenericSpanEnrichment

| Field | Value |
|---|---|
| Current file | `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/enrich/GenericEnrichScope.java` |
| Current FQN | `space.br1440.platform.tracing.api.span.enrich.GenericEnrichScope` |
| Current role | Platform-safe enrichment DSL for agent-created spans. |
| Current score | 59 |
| Recommended score | 90 |
| Delta | +31 |
| Why current name fails | Same `Scope` overload, plus "Generic" has no anchor. |
| Why recommended name wins | `GenericSpanEnrichment` keeps platform-safe/generic distinction and removes lifecycle implication. |
| Blast radius | Medium. |
| Affected modules | API, core, test assertions. |
| Tests likely affected | Enrichment tests. |
| Docs likely affected | Enrichment docs. |

| Option | Score | Pros | Cons | Verdict |
|---|---:|---|---|---|
| `GenericEnrichScope` | 59 | Existing | Scope overload and awkward verb | Reject |
| `GenericSpanEnrichment` | 90 | Clear pair with `SpanEnrichment` | Slightly broad | **Choose** |
| `PlatformSafeSpanEnrichment` | 86 | Very explicit | Longer | Considered |
| `GenericSpanAttributes` | 73 | Attribute-ish | Hides result/business methods | Reject |

### RequestTraceContextSnapshot -> RequestTraceContextSnapshot

| Field | Value |
|---|---|
| Current file | `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/context/RequestTraceContextSnapshot.java` |
| Current FQN | `space.br1440.platform.tracing.api.context.RequestTraceContextSnapshot` |
| Current role | Nullable request-context snapshot for error-handling integration. |
| Current score | 66 |
| Recommended score | 91 |
| Delta | +25 |
| Why current name fails | Too close to active `ActiveTraceContextView`; does not reveal snapshot/nullability role. |
| Why recommended name wins | `RequestTraceContextSnapshot` says request-scoped, trace-related, captured value. |
| Blast radius | Medium; autoconfigure error handling. |
| Affected modules | API, spring-boot-autoconfigure tests. |
| Tests likely affected | `RequestTraceContextSnapshotSupplierTest`. |
| Docs likely affected | error-handling integration docs. |

| Option | Score | Pros | Cons | Verdict |
|---|---:|---|---|---|
| `RequestTraceContextSnapshot` | 66 | Existing | Active-vs-snapshot ambiguity | Reject |
| `RequestTraceContextSnapshot` | 91 | Exact role | Longer | **Choose** |
| `TraceRequestContext` | 78 | Shorter | Still not snapshot | Reject |
| `ErrorTraceContextSnapshot` | 82 | Good for current use | Too tied to error handling | Considered |

### SensitiveDataRule -> SpanAttributeScrubbingRule

| Field | Value |
|---|---|
| Current file | `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/spi/SpanAttributeScrubbingRule.java` |
| Current FQN | `space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule` |
| Current role | SPI rule evaluating span attribute key/value pairs and returning `ScrubbingDecision`. |
| Current score | 66 |
| Recommended score | 92 |
| Delta | +26 |
| Why current name fails | Describes intent but not surface: it is specifically an attribute scrubbing rule. |
| Why recommended name wins | `SpanAttributeScrubbingRule` aligns with `ScrubbingDecision` and exact inputs. |
| Blast radius | Medium-high; OTel extension rule classes. |
| Affected modules | API, otel-extension, bench/e2e custom rules. |
| Tests likely affected | scrubbing rule tests, e2e custom rule probe. |
| Docs likely affected | phase-15 extension SPI docs. |

| Option | Score | Pros | Cons | Verdict |
|---|---:|---|---|---|
| `SensitiveDataRule` | 66 | Business-readable | Too broad for span attribute SPI | Reject |
| `SpanAttributeScrubbingRule` | 92 | Exact and consistent | Longer | **Choose** |
| `AttributeScrubbingRule` | 88 | Shorter | Could apply outside tracing | Considered |
| `ScrubbingRule` | 74 | Short | Too generic | Reject |

### SpanSpec.options() -> topology()

| Field | Value |
|---|---|
| Current file | `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpanSpec.java` |
| Current FQN | `space.br1440.platform.tracing.api.span.spec.SpanSpec#options()` |
| Current role | Accessor for topology/link value slice. |
| Current score | 52 |
| Recommended score | 91 |
| Delta | +39 |
| Why current name fails | Stale from removed `SpanOptions`; too broad for topology + links. |
| Why recommended name wins | `topology()` matches `SpanTopologySpec`; if Batch B chooses start-mode vocabulary, use `start()`/`startSpec()` consistently. |
| Blast radius | High call-site rename, low semantic risk. |
| Affected modules | API, core, spring-boot-autoconfigure tests, docs. |
| Tests likely affected | all tests asserting `spec.options().topology()` or links. |
| Docs likely affected | all `SpanOptions` docs. |

| Option | Score | Pros | Cons | Verdict |
|---|---:|---|---|---|
| `options()` | 52 | Existing | Misleading and stale | Reject |
| `topology()` | 91 | Best if type remains `SpanTopologySpec` | `spec.topology().topology()` unless enum/member renamed | **Choose in Batch A** |
| `start()` | 86 | Best if pair becomes `SpanStartSpec` | Verb-like accessor | Batch B option |
| `topologySpec()` | 80 | Explicit | Clunky | Reject |

## 7. Recommended Renames

### DatabaseTracing -> merge into DatabaseSpanBuilder

`DatabaseTracing` in `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/DatabaseTracing.java` has no members and extends `DatabaseSpanBuilder`. It breaks the transport pattern where `HttpTracing`, `RpcTracing`, and `KafkaTracing` are navigators. Recommended action: delete `DatabaseTracing`, change `TransportTracing.database()` to return `DatabaseSpanBuilder`, and update `DatabaseSpanBuilderImpl implements DatabaseSpanBuilder`.

Options: current `DatabaseTracing` (58), `DatabaseSpanBuilder` return type (92, chosen), `DatabaseTracingBuilder` (70, reject), `DatabaseClientSpanBuilder` (76, too narrow).

### SpanSpecAttributeValue -> SpanSpecAttributeValue

Current file: `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpanSpecAttributeValue.java`. The type is only used by `SpanSpec.attributes()` and `SpanSpecBuilder.attribute(...)`, not by mutable `SpanScope`. Rename to `SpanSpecAttributeValue` if Batch A already touches the spec package. Score: 75 -> 88.

Options: `SpanSpecAttributeValue` (75), `SpanSpecAttributeValue` (88, chosen), `AttributeValue` (62), `TraceAttributeValue` (76).

### RemoteSpanLink -> RemoteSpanLink

Current file: `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/RemoteSpanLink.java`. The record is not an active context; it is a descriptor for a remote link. Rename to `RemoteSpanLink`. Score: 72 -> 89.

Options: `RemoteSpanLink` (72), `RemoteSpanLink` (89, chosen), `SpanLink` (84), `RemoteSpanContext` (78, OTel collision).

### TraceparentParser -> Traceparent

Current file: `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/TraceparentParser.java`. The utility parses W3C `traceparent` and produces `RemoteSpanLink`. `TraceparentParser` is too broad. Score: 64 -> 88.

Options: `TraceparentParser` (64), `Traceparent` (88, chosen), `TraceparentParser` (86), `RemoteTraceContext` (80).

### OutboundPropagationDecision -> OutboundPropagationDecision

Current file: `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/propagation/control/OutboundPropagationDecision.java`. The record answers what platform headers may be propagated on outbound calls. Score: 69 -> 91.

Options: `OutboundPropagationDecision` (69), `OutboundPropagationDecision` (91, chosen), `TraceHeaderPropagationDecision` (86), `PropagationDecision` (75).

### InboundTraceControl -> InboundTraceControl

Current file: `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/propagation/control/InboundTraceControl.java`. The record is extracted from incoming headers/carriers. Score: 69 -> 91.

Options: `InboundTraceControl` (69), `InboundTraceControl` (91, chosen), `TraceControlHeaders` (82), `TraceControlRequest` (80).

### TraceControlHeaderInjector -> TraceControlHeaderInjector

Current file: `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/propagation/control/TraceControlHeaderInjector.java`. It injects specific trace-control headers, not all outbound platform state. Score: 67 -> 90.

Options: `TraceControlHeaderInjector` (67), `TraceControlHeaderInjector` (90, chosen), `OutboundTraceHeaderInjector` (87), `PlatformHeaderInjector` (78).

### CategoryContract / CategoryContracts -> SpanCategoryContract / SpanCategoryContracts

Current files: `CategoryContract.java`, `CategoryContracts.java` under `api.semconv`. The package context helps, but imports lose that context. Score: 74/73 -> 91/90.

Options: `CategoryContract` (74), `SpanCategoryContract` (91, chosen), `SemanticCategoryContract` (82), `SpanSemconvContract` (80).

### SemconvKeys -> SemanticAttributeKeys

Current file: `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/semconv/SemconvKeys.java`. `Semconv` is an internal abbreviation; the class contains OTel semantic attribute keys. Score: 76 -> 88. This is a Batch C rename because semconv abbreviations are already common among observability engineers.

### ValidationMode -> SemconvValidationMode

Current file: `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/semconv/ValidationMode.java`. Generic enum names are risky in imports. Score: 69 -> 90.

### TracingControlProtocolFieldType -> TracingControlProtocolFieldType

Current file: `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/control/protocol/schema/TracingControlProtocolFieldType.java`. Enum should be singular and field-scoped. Score: 72 -> 90.

### RemoteServiceTraceMirror -> RemoteTraceContextMdcMirror

Current file: `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/mdc/RemoteServiceTraceMirror.java`. The current name leaves "mirror from what into what" unclear. Score: 70 -> 86.

### ActiveTraceContextView -> ActiveActiveTraceContextView

Current file: `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/ActiveTraceContextView.java`. The current name is acceptable, but `ActiveActiveTraceContextView` better separates it from request snapshots. Score: 80 -> 91. Batch C unless `RequestTraceContextSnapshot` is renamed in Batch A.

## 8. Keep Decisions

### 8.1 KEEP_STRONG

| Type | Score | Why Strong |
|---|---:|---|
| `PlatformTracing` | 94 | Root platform facade; precise. |
| `Traced` | 92 | Conventional annotation. |
| `TracedAttribute` | 91 | Clear annotation pair. |
| `SuppressAgentInstrumentation` | 89 | Explicit suppression contract. |
| `PlatformAttributes` | 88 | Stable platform attribute constants. |
| `TracingControlProtocol` | 90 | Strong aggregate protocol name. |
| `TracingControlProtocolFieldDescriptor` | 87 | Correct descriptor suffix. |
| `TracingControlProtocolOperation` | 86 | Singular enum role. |
| `TracingControlProtocolSchema` | 86 | Schema registry. |
| `TracingControlProtocolValidator` | 88 | Behavior class. |
| `TracingControlProtocolVersion` | 88 | Version value object. |
| `ManualTracing` | 90 | Clear manual entrypoint. |
| `TransportTracing` | 87 | Clear navigator group. |
| `HttpTracing` | 87 | Clear navigator. |
| `HttpServerSpanBuilder` | 90 | Exact builder. |
| `HttpClientSpanBuilder` | 90 | Exact builder. |
| `DatabaseSpanBuilder` | 89 | Exact builder after deleting `DatabaseTracing`. |
| `RpcTracing` | 87 | Clear navigator. |
| `RpcServerSpanBuilder` | 90 | Exact builder. |
| `RpcClientSpanBuilder` | 90 | Exact builder. |
| `KafkaTracing` | 87 | Clear navigator. |
| `KafkaProducerSpanBuilder` | 90 | Exact builder. |
| `KafkaConsumerSpanBuilder` | 90 | Exact builder. |
| `OperationSpanBuilder` | 88 | Service developer friendly. |
| `RemoteServiceMdc` | 85 | Clear MDC bridge. |
| `TracingMdcKeys` | 86 | Clear constants class. |
| `OutboundPropagationPolicy` | 88 | Policy has behavior. |
| `TrustedDestinationMatcher` | 90 | Exact matcher role. |
| `VersionedState` | 88 | Clear marker. |
| `VersionedStateHolder` | 86 | Clear holder. |
| `SpanCategory` | 90 | Avoids OTel `SpanKind` collision. |
| `SpanHandle` | 88 | Good v3 lifecycle handle. |
| `SpanSpec` | 88 | Strong type; rename only `options()`. |
| `SpanSpecBuilder` | 89 | Strong builder. |
| `SpanSpecReason` | 78 | `Reason` is direct and builder-friendly; heavier alternatives are too bureaucratic and review-hostile. |
| `ScrubbingAction` | 89 | Clear enum. |
| `ScrubbingDecision` | 90 | Clear decision model. |
| `ThrowingSupplier` | 88 | Established utility vocabulary. |

### 8.2 KEEP_ACCEPTABLE

| Type | Score | Why Not Rename | Better Names Considered |
|---|---:|---|---|
| `TracingControlProtocolValidationResult` | 86 | Exact within verbose protocol family. | `ProtocolValidationResult` rejected: loses family. |
| `TracingControlProtocolViolation` | 86 | Exact within family. | `ProtocolViolation` rejected: collision risk. |
| `TracingControlProtocolFieldCategory` | 82 | Acceptable descriptor enum. | `FieldSection` weaker. |
| `TracingControlProtocolKeys` | 84 | Constants registry. | `TracingControlProtocolHeaderKeys` too narrow. |
| `TracingControlProtocolViolationCode` | 86 | Clear enough. | `TracingControlProtocolErrorCode` less specific. |
| `KafkaBatchSpanBuilder` | 86 | Marker builder is understandable. | `KafkaConsumerBatchSpanBuilder` longer. |
| `RemoteServiceContextReaders` | 78 | Utility; not central app API. | `RemoteServiceMdcReaders`. |
| `PlatformContextPropagation` | 82 | Platform facade is acceptable here. | `TraceContextPropagation`. |
| `PlatformHeaders` | 84 | Constants registry. | `PlatformHeaderNames`. |
| `RequestIdSupport` | 82 | Utility class. | `RequestIdSanitizer`. |
| `PlatformTraceContextKeys` | 76 | OTel context-key registry; low app exposure. | `TraceControlContextKeys`. |
| `DatabaseSemconvVersion` | 82 | Concise annotation. | `DatabaseSemanticConventionVersion`. |
| `KafkaSemconvVersion` | 82 | Concise annotation. | `KafkaSemanticConventionVersion`. |
| `RpcSemconvVersion` | 82 | Concise annotation. | `RpcSemanticConventionVersion`. |
| `SemconvViolation` | 80 | Consistent with semconv package. | `SemanticConventionViolation`. |
| `SemconvViolationException` | 80 | Consistent pair. | `SemanticConventionViolationException`. |
| `SqlSanitizer` | 84 | Direct utility. | `SqlStatementSanitizer`. |
| `UrlSanitizer` | 84 | Direct utility. | `UrlValueSanitizer`. |
| `SpanResult` | 82 | Stable attribute language. | `SpanOutcome`. |
| `SpanTopologySpec` | 83 | Acceptable if topology vocabulary remains. | `SpanStartSpec`. |

## 9. Delete / Merge / Split Candidates

| Type | Candidate Action | Evidence | Benefit | Risk | Recommendation |
|---|---|---|---|---|---|
| `SpanScope` | Delete from public API; move mutable lifecycle scope to core/internal | `SpanScope.java` says v1/v2; `SpanHandle.java` is v3; core `OwningSpanScope` implements it | Removes public legacy surface | Core internals need replacement type | **Do in Batch B** |
| `DatabaseTracing` | Merge/delete | `DatabaseTracing extends DatabaseSpanBuilder` with no methods | Restores transport navigator consistency | Return type rename in API/core/tests | **Do in Batch A** |
| `SpanTopologySpec` + `Topology` | Possible pair rename/split vocabulary | `Topology` confusion; `SpanTopologySpec` stores topology + links | Cleaner public language: `SpanStartSpec` + `SpanStartMode` | Accessor naming decision | **Needs design decision before Batch B** |
| `SpanSpecAttributeValue` | Possible narrow rename, not split | Only used by `SpanSpec` attributes | Avoids confusion with mutable span attributes | Wider spec package churn | **Batch C unless touching spec deeply** |

## 10. Builder and Fluent API Naming Review

Builder-chain map:

```text
PlatformTracing
  traceContext() -> ActiveTraceContextView
  manual() -> ManualTracing
    operation(name) -> OperationSpanBuilder -> ManualSpanBuilder terminals
    transport() -> TransportTracing
      http().server/client() -> Http*SpanBuilder
      database() -> DatabaseSpanBuilder       [recommended: remove DatabaseTracing]
      rpc().server/client() -> Rpc*SpanBuilder
      kafka().producer/consumer/batch() -> Kafka*SpanBuilder
    spanFromSpec(spec) -> SpanExecution       [recommended rename from SpecifiedSpan]

SpanSpec.builder(name) -> SpanSpecBuilder -> build() -> SpanSpec
```

Terminal method review:

- `start()` is strong for returning `SpanHandle`.
- `run(Runnable)`, `call(Supplier)`, and `callChecked(ThrowingSupplier)` are clear and should remain.
- `.child()`, `.root()`, `.detached()`, `.linkedTo()` are strong app-facing grammar and should remain even if `Topology` becomes `SpanStartMode`.
- `fromTraceparent(String... traceparents)` is acceptable, but if `TraceparentParser` becomes `Traceparent`, a future method rename to `fromTraceparent(...)` would be clearer.

| Builder Type | Current Role | Current Score | Recommended Action | Notes |
|---|---|---:|---|---|
| `PlatformSpanBuilder` | Common manual builder contract | 64 | Rename to `ManualSpanBuilder` | High-value Batch A. |
| `OperationSpanBuilder` | Internal operation spans | 88 | Keep | Good service API. |
| `HttpServerSpanBuilder` | HTTP server spans | 90 | Keep | Good. |
| `HttpClientSpanBuilder` | HTTP client spans | 90 | Keep | Good. |
| `DatabaseSpanBuilder` | DB spans | 89 | Keep | Return it directly. |
| `DatabaseTracing` | Empty builder alias | 58 | Merge/delete | Pattern violation. |
| `RpcServerSpanBuilder` | RPC server spans | 90 | Keep | Good. |
| `RpcClientSpanBuilder` | RPC client spans | 90 | Keep | Good. |
| `KafkaProducerSpanBuilder` | Kafka producer spans | 90 | Keep | Good. |
| `KafkaConsumerSpanBuilder` | Kafka consumer spans | 90 | Keep | Good. |
| `KafkaBatchSpanBuilder` | Kafka batch consumer spans | 86 | Keep | Acceptable. |
| `SpanSpecBuilder` | Governed spec builder | 89 | Keep | Rename dependent value types only. |
| `SpecifiedSpan` | Spec execution surface | 55 | Rename to `SpanExecution` | Not a builder but fluent terminal surface. |

## 11. Enum and Value-Type Naming Review

| Type | Values / Fields | Current Score | Recommended Name / Action | Notes |
|---|---|---:|---|---|
| `SpanCategory` | `HTTP_SERVER`, `HTTP_CLIENT`, `DATABASE`, `RPC_*`, `KAFKA_*`, `INTERNAL` | 90 | Keep | Stable wire values. |
| `SpanResult` | `SUCCESS`, `FAILURE`, `TIMEOUT`, `CANCELLED`, `REJECTED`, `SKIPPED` | 82 | Keep acceptable | `SpanOutcome` is better but not urgent. |
| `RemoteSpanLink` | `traceId`, `spanId`, `traceFlags`, `traceState` | 72 | `RemoteSpanLink` | Record is a link descriptor. |
| `SpanSpecAttributeValue` | nested typed values | 75 | `SpanSpecAttributeValue` | Narrow to spec usage. |
| `SpanSpecReason` | governance enum values | 78 | Keep | Values are already reason-like; avoid heavier governance wording. |
| `SpanTopologySpec` | `topology`, `links` | 83 | Keep or `SpanStartSpec` | Depends on Batch B vocabulary. |
| `Topology` | `CHILD`, `ROOT`, `DETACHED` | 62 | `SpanStartMode` | Strongly recommended. |
| `RequestTraceContextSnapshot` | nullable IDs | 66 | `RequestTraceContextSnapshot` | Snapshot role. |
| `InboundTraceControl` | force/qa/request id/reason/raw | 69 | `InboundTraceControl` | Inbound carrier model. |
| `OutboundPropagationDecision` | three outbound booleans | 69 | `OutboundPropagationDecision` | Outbound decision model. |
| `CategoryContract` | category/allowlist/required/forbidden | 74 | `SpanCategoryContract` | Domain qualification. |
| `ValidationMode` | strict/warn/disabled | 69 | `SemconvValidationMode` | Avoid generic import. |
| `ScrubbingDecision` | action/reason/maxLength/terminal | 90 | Keep | Excellent. |
| `ScrubbingAction` | keep/mask/drop/hash/truncate | 89 | Keep | Excellent. |
| `TracingControlProtocolFieldType` | field types | 72 | `TracingControlProtocolFieldType` | Singular enum. |

## 12. Proposed API Naming Conventions

| Concept | Preferred Suffix / Name Pattern | Use When | Avoid |
|---|---|---|---|
| Root facade | `PlatformTracing` | Single service-facing root | Extra `Platform*` on every type |
| Manual builder | `*SpanBuilder`, base `ManualSpanBuilder` | Fluent app-facing span builders | `PlatformSpanBuilder` |
| Execution surface | `*Execution` | Object with `start/run/call` terminals | Adjectives like `SpecifiedSpan` |
| Immutable spec | `*Spec` | Complete governed input model | `Options` for narrow model slices |
| Start/parent choice | `SpanStartMode` | Child/root/detached start behavior | Bare `Topology` |
| Link descriptor | `RemoteSpanLink` | Cross-trace/span link data | `Context` unless it is an active context |
| Handle | `*Handle` | Minimal closeable resource returned to apps | `Scope` for closeable public v3 API |
| Scope | Internal only, or mutation DSL must not use it | Actual context/lifecycle scope | `EnrichScope` as public mutation DSL |
| View | `*View` | Read-only active live view | Snapshot values |
| Snapshot | `*Snapshot` | Captured nullable/current-state value | `Context` alone |
| Policy | `*Policy` | Behavior that decides | Immutable data records |
| Decision | `*Decision` | Result of policy | Generic platform decision without direction |
| Contract | `*Contract` | Rules/constraints by domain | Unqualified `CategoryContract` |
| Constants | `*Keys`, `*Attributes`, `*Reasons` | Static registries | Singular model names |
| SPI rule | `*Rule` qualified by target/action | Extension point implementing behavior | Vague `SpanAttributeScrubbingRule` |
| Protocol wire model | Long `TracingControlProtocol*` family | Public wire/JMX protocol | Short names that collide outside package |

Applied to current types:

- `PlatformSpanBuilder` should become `ManualSpanBuilder`.
- `SpecifiedSpan` should become `SpanExecution`.
- `Topology` should become `SpanStartMode`.
- `SpanScope` should leave the public API.
- `RequestTraceContextSnapshot` should become `RequestTraceContextSnapshot`.
- `EnrichScope`/`GenericEnrichScope` should become enrichment nouns.

## 13. Blast Radius Map

| Change | API Files | Core Files | Autoconfigure Files | Test Files | Docs | Risk | Recommended Batch |
|---|---|---|---|---|---|---|---|
| `SpanSpec.options()` -> `topology()` | `SpanSpec`, `SpanSpecImpl` | `OtelTracingRuntime`, builders/specs | metrics tests | many spec/topology tests | v3 docs/refactoring plan | Low semantic / high churn | A |
| `PlatformSpanBuilder` -> `ManualSpanBuilder` | manual builders | `AbstractSemanticSpanBuilder`, default builders | tests imports | builder tests | manual API docs | Low semantic / high churn | A |
| `SpecifiedSpan` -> `SpanExecution` | `ManualTracing`, type file | `SpecifiedSpanImpl` rename | metrics tests | `SpecifiedSpanTest` | docs | Low-medium | A |
| Delete/merge `DatabaseTracing` | `TransportTracing`, DB files | `DatabaseSpanBuilderImpl`, transport impl | tests | DB builder tests | docs | Low | A |
| `Topology` -> `SpanStartMode` | spec files | runtime/builders | metrics tests | topology tests | docs | Low semantic / high churn | B |
| Remove public `SpanScope` | delete/move API type | `OwningSpanScope`, `SpanHandleImpl`, runtime | metrics docs/tests | lifecycle/metrics tests | docs | Medium | B |
| Enrichment rename pair | enrich files | enrichment impl | none likely | enrichment tests | docs | Low-medium | B |
| Propagation model renames | propagation/control files | autoconfigure interceptors | Kafka/HTTP propagation | propagation tests | propagation docs | Medium | B |
| Semconv contract renames | semconv files | `AttributePolicy`, builders | config tests | semconv tests | semconv docs | Medium | C |
| `SensitiveDataRule` rename | SPI file | otel-extension | e2e custom rule | scrubbing tests | extension docs | Medium | C |
| Control enum `Types` -> `FieldType` | protocol schema | extension/JMX wire tests | none | protocol tests/e2e wire | protocol docs | Low | C |

## 14. Rename Batches

### Batch A - High-value API vocabulary cleanup

Included changes:

- `SpanSpec.options()` -> `topology()` while keeping `SpanTopologySpec`.
- `PlatformSpanBuilder` -> `ManualSpanBuilder`.
- `SpecifiedSpan` -> `SpanExecution`.
- Delete/merge `DatabaseTracing`; `TransportTracing.database()` returns `DatabaseSpanBuilder`.
- Update stale `SpanOptions` references in tests/docs touched by these renames.

Why now:

- These are high-value naming fixes with low semantic risk.
- They remove stale vocabulary and builder pattern inconsistency before production.

Tests to run:

- `.\gradlew.bat :platform-tracing-api:test --tests "*V3ManualApiArchTest*" --tests "*SpanSpecBuilderFinalStateTest*"`
- `.\gradlew.bat :platform-tracing-core:test --tests "*SpanBuilder*" --tests "*SpanExecution*" --tests "*TracingRuntimeRoutingTest*"`
- `.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --tests "*MeteredTopologyMatrixTest*"`

Risk:

- High import churn, low behavior risk.

### Batch B - Structural or risky model changes

Included changes:

- `Topology` -> `SpanStartMode`; decide whether `SpanTopologySpec` stays or becomes `SpanStartSpec`.
- Remove public `SpanScope`; introduce internal core lifecycle scope if needed.
- `EnrichScope` -> `SpanEnrichment`; `GenericEnrichScope` -> `GenericSpanEnrichment`.
- Propagation model cleanup: `InboundTraceControl` -> `InboundTraceControl`, `OutboundPropagationDecision` -> `OutboundPropagationDecision`, `TraceControlHeaderInjector` -> `TraceControlHeaderInjector`.

Prerequisite decisions:

- Whether public docs should use `topology` or `start mode` vocabulary.
- Whether any non-monorepo pre-production user still needs mutable `SpanScope`.

Tests to run:

- Full `platform-tracing-api:test`.
- Targeted core runtime/topology/enrichment tests.
- Autoconfigure propagation/Kafka tests.

Risk:

- Medium, mostly because `SpanScope` removal touches internals and metrics wording.

### Batch C - Optional consistency cleanup

Included changes:

- `SpanSpecAttributeValue` -> `SpanSpecAttributeValue`.
- `RemoteSpanLink` -> `RemoteSpanLink`.
- `TraceparentParser` -> `Traceparent`.
- `CategoryContract(s)` -> `SpanCategoryContract(s)`.
- `ValidationMode` -> `SemconvValidationMode`.
- `SemconvKeys` -> `SemanticAttributeKeys`.
- `SensitiveDataRule` -> `SpanAttributeScrubbingRule`.
- `TracingControlProtocolFieldType` -> `TracingControlProtocolFieldType`.
- `RemoteServiceTraceMirror` -> `RemoteTraceContextMdcMirror`.
- Possibly `ActiveTraceContextView` -> `ActiveActiveTraceContextView`.

Whether to skip:

- Skip parts of Batch C only if architects want to freeze lower-risk utility/SPI names after Batch A/B.

### Batch D - Do not do / rejected changes

- Do not rename `PlatformTracing`; it is the correct root facade.
- Do not rename `SpanCategory` to `SpanKind`; that collides with OTel vocabulary.
- Do not reintroduce `SpanOptions` or `SpanRelation`.
- Do not add deprecated aliases or compatibility bridges.
- Do not expose `SpanSpec.links()`; keep topology/link ownership in one value slice.
- Do not rename stable wire values (`SpanCategory.value()`, `SpanResult.value()`, control protocol keys).

## 15. Recommended Refactoring Strategy

- Implement one batch at a time.
- Do not mix semantic behavior changes with renames.
- Compile after each batch.
- Update tests and docs in the same batch as the public rename.
- Avoid transitional aliases, deprecated duplicates, and migration adapters.
- Use IDE-safe rename for public types, then manually review fluent call sites for readability.
- Keep `SpanSpecBuilder` fluent grammar stable unless a separate design decision changes the topology vocabulary.
- After Batch A, rerun inventory grep for stale `SpanOptions`, `SpecifiedSpan`, `PlatformSpanBuilder`, and `DatabaseTracing`.

## 16. Next Implementation Prompt for Codex

```text
Implement only Batch A from docs/analysis/platform-tracing-api-model-naming-options.md.

Scope:
1. Rename PlatformSpanBuilder to ManualSpanBuilder.
2. Rename SpecifiedSpan to SpanExecution.
3. Change SpanSpec.options() to SpanSpec.topology(), keeping SpanTopologySpec unchanged.
4. Delete/merge DatabaseTracing so TransportTracing.database() returns DatabaseSpanBuilder.
5. Update imports, implementations, tests, and docs touched by these four changes.
6. Remove stale SpanOptions references touched by the batch, especially V3ManualApiArchTest.

Constraints:
- No compatibility aliases.
- No @Deprecated bridges.
- No semantic behavior changes.
- Do not rename Topology or SpanTopologySpec in this batch.
- Do not remove SpanScope in this batch.

Verify:
.\gradlew.bat :platform-tracing-api:test --tests "*V3ManualApiArchTest*" --tests "*SpanSpecBuilderFinalStateTest*"
.\gradlew.bat :platform-tracing-core:test --tests "*SpanBuilder*" --tests "*SpanExecution*" --tests "*TracingRuntimeRoutingTest*"
.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --tests "*MeteredTopologyMatrixTest*"
```

## Verification Commands

Executed read-only commands:

```powershell
Get-Content docs\analysis\platform-tracing-api-class-hierarchy-inventory.md -Encoding UTF8
rg "^public (interface|record|enum|class)|^public final class|^public abstract class|^sealed interface|^non-sealed" platform-tracing-api/src/main/java
rg --files platform-tracing-api/src/main/java
rg "import space\.br1440\.platform\.tracing\.api" platform-tracing-core platform-tracing-spring-boot-autoconfigure platform-tracing-test platform-tracing-bench platform-tracing-e2e-tests
rg "Deprecated|legacy|api\.span\.builder|PlatformSpanBuilder|SpanOptions|SpanTopologySpec|SpecifiedSpan|SpanScope|VersionedState|RequestTraceContextSnapshot|ActiveTraceContextView" platform-tracing-api/src/main/java docs/analysis docs/tracing
rg "SpanOptions|SpanTopologySpec|\.options\(\)|SpanSpec\.builder|spanFromSpec|SpecifiedSpan|SpanScope" platform-tracing-api platform-tracing-core platform-tracing-spring-boot-autoconfigure platform-tracing-test platform-tracing-bench platform-tracing-e2e-tests
```

Additional source reads:

```powershell
Get-Content platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/PlatformTracing.java
Get-Content platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/*.java
Get-Content platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/*.java
Get-Content platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/SpanScope.java
Get-Content platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/enrich/*.java
Get-Content platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/semconv/*.java
Get-Content platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/propagation/control/*.java
```

Gradle was not run because this task is analysis-only and no source code was modified.

---

## Batch A Accepted Update - 2026-07-11

Batch A has been accepted and implemented. Current public API names are `SpanRelationship`, `SpanRelationshipSpec`, `SpanSpec.relationship()`, `ManualSpanBuilder`, `SpanExecution`, `SpanEnrichment`, `GenericSpanEnrichment`, `SemconvValidationMode`, and `DatabaseSpanBuilder`.

Public `SpanScope` and `DatabaseTracing` are removed. Database semconv marker `@DatabaseSemconvVersion("1.28.0")` now belongs to `DatabaseSpanBuilder`.

## PR-B1 Accepted Update - 2026-07-11

PR-B1 has been accepted and implemented as a context/propagation naming slice. Current names are:

| Area | Current API |
| --- | --- |
| Request context snapshot | `RequestTraceContextSnapshot` |
| Active context view | `ActiveTraceContextView` |
| Inbound trace-control carrier model | `InboundTraceControl` |
| Outbound propagation policy result | `OutboundPropagationDecision` |
| Trace-control header writer | `TraceControlHeaderInjector` |
| W3C traceparent parser | `TraceparentParser` in `api.propagation` |
| Builder strict traceparent links | `fromTraceparent(...)` |

Semantic verdicts: the request context type is a captured nullable snapshot for error-handling, the active context view is a live read-only view over the active span, trace-control extraction is inbound, propagation decisions are outbound, the injector writes only trace-control headers, and `TraceparentParser` is parser behavior rather than a value object.

## PR-B2 Accepted Update - 2026-07-11

PR-B2 has been accepted and implemented. `SensitiveDataRule` is now `SpanAttributeScrubbingRule`.
The SPI remains span-attribute-only, and ServiceLoader providers must use
`META-INF/services/space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule`.
Implementation class names and `BuiltInSpanAttributeScrubbingRules` remain unchanged by design.
