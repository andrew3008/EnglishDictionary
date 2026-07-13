# Final Umbrella Audit — platform-tracing-api Naming Refactor

## 1. Executive Verdict

PASS

The Java/API refactor chain is ready for the architect gate: approved renames are applied in active Java source, wire contracts are unchanged, compatibility aliases were not found, ServiceLoader/SPI migration is correct, current documentation has been cleaned up, and full Docker-backed E2E passed with `--rerun-tasks`.

For PASS:
- no unresolved P0/P1/P2 findings;
- full Docker-backed E2E green;
- no compatibility aliases;
- wire contracts unchanged.

## 2. Scope Audited

Audited the complete pre-production naming refactor chain:

- Batch A — manual API / relationship / `SpanScope` / `DatabaseSpanBuilder` cleanup.
- PR-B1 — context / propagation naming cleanup.
- PR-B2 — scrubbing SPI rename and runtime discovery migration.
- Batch C — approved partial optional naming cleanup.
- TraceOperations root API rename: `PlatformTracing` to `TraceOperations`.

Read available decision/audit artifacts:

- `docs/analysis/platform-tracing-api-batch-a-post-audit.md`
- `docs/analysis/platform-tracing-api-pr-b1-post-audit.md`
- `docs/analysis/platform-tracing-api-pr-b2-post-audit.md`
- `docs/analysis/platform-tracing-api-pr-b2-warning-closure.md`
- `docs/analysis/platform-tracing-e2e-failing-tests-runbook.md`
- `docs/analysis/platform-tracing-api-batch-c-decision.md`
- `docs/analysis/platform-tracing-api-batch-c-post-audit.md`
- `docs/decisions/ADR-api-naming-refactor-batch-a.md`
- `docs/decisions/ADR-api-naming-refactor-pr-b2.md`
- `docs/decisions/ADR-trace-operations-root-api.md`
- `docs/analysis/platform-tracing-api-trace-operations-rename-post-audit.md`
- `platform-tracing-api/CHANGELOG.md`
- `CHANGELOG.md`

Also inspected current source directly across API, core, autoconfigure, otel-extension, e2e, bench, samples, and current docs.

## 3. Batch A Verification

| Area | Evidence | Result |
| --- | --- | --- |
| Relationship family | `SpanRelationship`, `SpanRelationshipSpec`, `ImmutableSpanRelationshipSpec`, `SpanSpec.relationship()`, and `SpanRelationshipSpec.kind()` exist; active source uses `spec.relationship().kind()` and `.links()`. | PASS |
| OTel `SpanKind` separation | `SpanRelationshipSpec.kind()` Javadoc states it is not OpenTelemetry `SpanKind`; protocol/client/server kind remains derived from `SpanCategory`. | PASS |
| Manual builder rename | `ManualSpanBuilder` exists and current builders extend it; active Java source has no `PlatformSpanBuilder` type. | PASS |
| Spec execution rename | `SpanExecution` / core `SpanExecutionImpl` exist; active Java source has no `SpecifiedSpan` or `SpecifiedSpanImpl`. | PASS |
| Enrichment rename | `SpanEnrichment`, `GenericSpanEnrichment`, core defaults, and public APIs use new names; active Java source has no old enrichment types. | PASS |
| Semconv validation mode | `SemconvValidationMode` exists and is used by semconv policy code. | PASS |
| Public `SpanScope` deletion | No public API `SpanScope` was found under `platform-tracing-api/src/main/java`; core uses internal `OwningSpanScope`. | PASS |
| `DatabaseTracing` merge | `DatabaseTracing.java` absent; `TransportTracing.database()` returns `DatabaseSpanBuilder`; `DatabaseSpanBuilder` carries `@DatabaseSemconvVersion("1.28.0")`. | PASS |
| Topology vocabulary | Active manual relationship code uses relationship vocabulary. Remaining topology vocabulary is in unrelated control protocol / Spring bean topology / historical docs contexts. | PASS |

## 4. PR-B1 Verification

| Area | Evidence | Result |
| --- | --- | --- |
| Context pair | `RequestTraceContextSnapshot` is a nullable captured record for request/error handling; `ActiveTraceContextView` is a live read-only trace/span/correlation view. | PASS |
| Inbound/outbound pair | `InboundTraceControl.fromHeaders(...)` extracts incoming carrier values; `OutboundPropagationDecision` controls outbound propagation; `TraceControlHeaderInjector` writes only platform trace-control headers. | PASS |
| Traceparent parser | `TraceparentParser` is a `@UtilityClass` parser with static `parseTraceparent` / `requireTraceparent`, returning `RemoteSpanLink`; it is not a value object. | PASS |
| Builder method rename | Active Java source uses `fromTraceparent(...)`; no active Java source `fromRemoteContext(...)` remains. | PASS |
| Propagator classes | `InboundTraceControlPropagator`, `InboundTraceControlPropagatorBuilder`, and `InboundTraceControlPropagatorProvider` exist and are used. | PASS |
| SPI name preservation | `InboundTraceControlPropagatorProvider.NAME` remains `platform-trace-control`; this is intentional wire/SPI stability. | PASS |

## 5. PR-B2 Verification

| Area | Evidence | Result |
| --- | --- | --- |
| Public SPI rename | `SpanAttributeScrubbingRule.java` exists; `SensitiveDataRule.java` is absent. | PASS |
| No Java old SPI usage | No production Java import/implements of `SensitiveDataRule` found; only negative FQN guard test references the old name. | PASS |
| Negative guard | `SpanAttributeScrubbingRuleRemovalTest` asserts old FQN fails and new FQN loads. | PASS |
| Registry rename | `BuiltInSpanAttributeScrubbingRules` exists and active Java uses it; `BuiltInSensitiveDataRules` absent from current Java source. | PASS |
| Rule implementation names | Domain-specific rule implementation names remain unchanged, consistent with ADR. | PASS |
| Force-header default fix | `ExtensionConfigReader.listValue(...)` falls back to supplied default when both list and raw string are absent; tests cover absent force-record values. | PASS |
| Force-header behavior | Full E2E gate passed after the `force-record-values` default fix; `X-Trace-On=on` at ratio `0` is covered by E2E smoke path. | PASS |

## 6. Batch C Verification

| Area | Evidence | Result |
| --- | --- | --- |
| Approved renames | `RemoteSpanLink`, `SpanSpecAttributeValue`, `TracingControlProtocolFieldType`, and `SpanSpecAttributeValueConverter` exist and are used consistently. | PASS |
| Old approved names | Active Java/source module grep found no stale old approved Batch C names except changelog old-to-new table entries. | PASS |
| Skipped/rejected candidates | Java grep for `SpanCategoryContract`, `SpanCategoryContracts`, `RemoteTraceMdcMirror`, `RemoteServiceMdcReaders` returned zero matches. | PASS |
| Decision record | `platform-tracing-api-batch-c-decision.md` documents why semantic-contract and MDC candidates are skipped/rejected and not debt. | PASS |
| `RemoteSpanLink` semantics | Record carries remote trace id, span id, flags, and optional tracestate; used for pre-start span links and traceparent parsing. | PASS |
| `SpanSpecAttributeValue` semantics | Sealed value hierarchy is scoped to `span.spec`, `SpanSpec.attributes()`, builders, and core conversion; not a general runtime abstraction. | PASS |
| `TracingControlProtocolFieldType` scope | Java type name changed only; enum constants and protocol key strings are unchanged. | PASS |

## 7. Wire Contract Audit

| Contract | Evidence | Result |
| --- | --- | --- |
| `SpanCategory.value()` | Values remain `http_server`, `http_client`, `database`, `rpc_server`, `rpc_client`, `kafka_producer`, `kafka_consumer`, `internal`. | PASS |
| `SpanResult.value()` | Values remain `success`, `failure`, `timeout`, `cancelled`, `rejected`, `skipped`. | PASS |
| Tracing-control keys | `TracingControlProtocolKeys` constants remain unchanged, including `contractVersion`, `operation`, `sampling.ratio`, `sampling.routeRatios`, `validation.mode`, `exporter.endpoint`, and `sdk.mode`. | PASS |
| Tracing-control operations | Operation constants remain `APPLY_RUNTIME_POLICY`, `VALIDATE_RUNTIME_POLICY`, `READ_APPLIED_STATE`, `READ_SCHEMA`. | PASS |
| Protocol field enum constants | `TracingControlProtocolFieldType` constants are exactly `STRING`, `BOOLEAN`, `INTEGER`, `LONG`, `DOUBLE`, `STRING_ARRAY`, `ROUTE_RATIOS_MAP`. | PASS |
| Schema / validator behavior | `TracingControlProtocolSchema`, `FieldTypeSupport`, and `TracingControlProtocolValidator` retain the same key/type mappings and validation branches, with only Java type-name updates. | PASS |
| Propagator SPI name | `platform-trace-control` remains the named OTel propagator SPI. | PASS |
| Scrubbing property | `platform.tracing.scrubbing.built-in-rules` remains unchanged intentionally. | PASS |
| ServiceLoader SPI paths | Old scrubbing SPI descriptor removed; new descriptor path is `META-INF/services/space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule`. | PASS |

## 8. Compatibility Alias Audit

No compatibility alias or deprecated bridge for the naming refactor chain was found in active API/source:

- No old public API classes retained for Batch A, PR-B1, PR-B2, or Batch C.
- No duplicate old/new scrubbing SPI descriptors.
- No `@Deprecated` alias for old public API names.
- No adapters from old API names to new API names.

Non-refactor `@Deprecated` occurrences exist in unrelated extension/bench code, such as legacy config accessors and benchmark-report helpers. These are not compatibility aliases for the naming refactor chain.

## 9. Current Documentation Audit

Current user-facing manual API docs, architecture inventory, support matrix, semantic-convention mapping, traceability matrix, and changelogs are current:

- `docs/tracing/platform-tracing-v3-manual-api.md` uses new manual/context/link/spec names.
- `docs/tracing/platform-tracing-v3-kafka-batch-links.md` uses `RemoteSpanLink`.
- `docs/tracing/otel-compatibility-matrix.md` and `docs/tracing/requirements-coverage-dossier.md` use `InboundTraceControlPropagatorProvider`.
- `docs/tracing/traceability.md` now uses `SpanRelationship`, `SpanHandle.close()`, `TraceControlHeaderInjector`, `InboundTraceControl.fromHeaders`, `InboundTraceControlPropagatorProvider`, `InboundTraceControlPropagatorBuilder`, and `InboundTraceControlPropagatorProviderTest`.
- `docs/architecture/platform-tracing-current-codebase-inventory.md` now uses current Batch A / PR-B1 / PR-B2 names for API, propagation, enrichment, and extension entries.
- `docs/SUPPORTED.md` now describes sampler context flow through `InboundTraceControl` and `InboundTraceControlPropagator`.
- `docs/semconv-mapping.md` now describes enrichment through `GenericSpanEnrichment` and `SpanEnrichment`.
- `platform-tracing-api/CHANGELOG.md` and root `CHANGELOG.md` contain expected old-to-new tables.

Historical banners were added to current-looking analysis documents that intentionally preserve pre-refactor inventory/scoring text:

- `docs/analysis/platform-tracing-api-class-hierarchy-inventory.md`
- `docs/analysis/platform-tracing-api-model-naming-options.md`

No current operational guide or current inventory tells users to use old API/SPI names.

## 10. ServiceLoader / SPI Audit

| Check | Evidence | Result |
| --- | --- | --- |
| Old descriptor absent | No file named `META-INF/services/space.br1440.platform.tracing.api.spi.SensitiveDataRule` found in workspace. | PASS |
| New descriptor present | `platform-tracing-e2e-tests/src/customRule/resources/META-INF/services/space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule` contains `space.br1440.e2e.customrule.MyCustomE2eRule`. | PASS |
| Descriptor target exists | `MyCustomE2eRule` exists and implements `SpanAttributeScrubbingRule`. | PASS |
| `ServiceLoader.load` call sites | Production and probe call sites load `SpanAttributeScrubbingRule.class`. | PASS |
| AutoService | `ExampleMerchantAccountRule` uses `@AutoService(SpanAttributeScrubbingRule.class)`. | PASS |
| Spring/actuator vocabulary | `TracingActuatorEndpoint` uses `SpanAttributeScrubbingRule`; no old SPI bean/qualifier/conditional references found. | PASS |

## 11. Docker-backed E2E Evidence

Fresh verification was run with remote Docker:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --no-daemon --rerun-tasks
```

Result:

- PASS
- `BUILD SUCCESSFUL in 6m 33s`
- `34 actionable tasks: 34 executed`

Supporting verification:

| Command | Result |
| --- | --- |
| `.\gradlew.bat compileJava compileTestJava --no-daemon` | PASS |
| `.\gradlew.bat :platform-tracing-api:test :platform-tracing-core:test :platform-tracing-spring-boot-autoconfigure:test --no-daemon` | PASS |
| `.\gradlew.bat :platform-tracing-otel-extension:test --no-daemon` | PASS |
| `git diff --check` | PASS |

Gradle/JDK emitted non-blocking warnings: deprecated Gradle features, `JsonInclude.Include.NON_EMPTY` classpath warnings, a WebFlux deprecated API note, and a JVM class-sharing warning.

## 12. Grep Results and Classification

Required grep checks were run.

Old public/API names:

```powershell
Get-ChildItem -Path . -Recurse -Include *.java,*.md,*.puml,*.txt,*.properties |
  Select-String -Pattern "SpanTopologySpec|ImmutableSpanTopologySpec|SpecifiedSpan|PlatformSpanBuilder|EnrichScope|GenericEnrichScope|DatabaseTracing|TracingRequestContext|TraceContextView|PlatformTraceControl|PlatformPropagationDecision|PlatformOutboundInjector|RemoteContext|fromRemoteContext|SensitiveDataRule|BuiltInSensitiveDataRules|SpanLinkContext|SpanAttributeValue|TracingControlProtocolTypes"
```

Classification:

| Location | Classification |
| --- | --- |
| `platform-tracing-api/CHANGELOG.md`, `CHANGELOG.md` | OK_CHANGELOG |
| `docs/decisions/**` naming ADRs and wire ADR notes | OK_DECISION_DOC |
| `docs/analysis/**` post-audits, decisions, inventories, prior reviews, and `perplexity-review/**` archives | OK_HISTORICAL_DOC |
| `platform-tracing-api/src/test/java/.../SpanAttributeScrubbingRuleRemovalTest.java` old `SensitiveDataRule` FQN | OK_HISTORICAL_DOC / negative guard |
| `docs/tracing/phase-15-autoconfigure-extension-spi-plan.md`, `docs/research-*.md`, architecture option/migration plans, and Jira/refactoring plans | OK_HISTORICAL_DOC |
| Active Java matches for `TraceContextView` inside `ActiveTraceContextView` and local helper names such as `remoteContext(...)` | OK_HISTORICAL_DOC / substring overlap, not stale old symbol |
| Current user-facing docs and current architecture inventory | Clean after documentation cleanup |
| Active Java production source old names | Clean |
| Compatibility aliases | Clean |
| Wire-risk occurrences | Clean |

New names:

```powershell
Get-ChildItem -Path . -Recurse -Include *.java,*.md,*.puml,*.txt,*.properties |
  Select-String -Pattern "SpanRelationship|SpanRelationshipSpec|ManualSpanBuilder|SpanExecution|SpanEnrichment|GenericSpanEnrichment|SemconvValidationMode|RequestTraceContextSnapshot|ActiveTraceContextView|InboundTraceControl|OutboundPropagationDecision|TraceControlHeaderInjector|TraceparentParser|SpanAttributeScrubbingRule|BuiltInSpanAttributeScrubbingRules|RemoteSpanLink|SpanSpecAttributeValue|TracingControlProtocolFieldType"
```

Result: expected current usage found across API, core, autoconfigure, otel-extension, e2e, bench, samples, docs, and changelogs.

Rejected Batch C names:

```powershell
Get-ChildItem -Path . -Recurse -Include *.java |
  Select-String -Pattern "SpanCategoryContract|SpanCategoryContracts|RemoteTraceMdcMirror|RemoteServiceMdcReaders"
```

Result: zero Java matches.

Compatibility aliases:

```powershell
Get-ChildItem -Path . -Recurse -Include *.java |
  Select-String -Pattern "@Deprecated|compatibility|alias|bridge|SensitiveDataRule|PlatformSpanBuilder|SpecifiedSpan|DatabaseTracing|SpanScope"
```

Result: no naming-refactor alias/bridge types found. Matches are unrelated compatibility wording in `opus-mcp-server`, unrelated `@Deprecated` members in extension/bench code, the intentional negative SPI guard test, and internal `OwningSpanScope`.

## 13. Git Scope Review

`git status --short --untracked-files=all` currently reports docs-only tracked cleanup plus untracked Batch C/new audit artifacts.

Tracked documentation cleanup:

- `docs/SUPPORTED.md`
- `docs/analysis/platform-tracing-api-class-hierarchy-inventory.md`
- `docs/analysis/platform-tracing-api-model-naming-options.md`
- `docs/architecture/platform-tracing-current-codebase-inventory.md`
- `docs/semconv-mapping.md`
- `docs/tracing/traceability.md`

Untracked Batch C/new audit artifacts:

- `docs/analysis/platform-tracing-api-batch-c-decision.md`
- `docs/analysis/platform-tracing-api-batch-c-post-audit.md`
- `docs/analysis/platform-tracing-api-final-umbrella-audit.md`
- `platform-tracing-api/.../TracingControlProtocolFieldType.java`
- `platform-tracing-api/.../RemoteSpanLink.java`
- `platform-tracing-api/.../SpanSpecAttributeValue.java`
- `platform-tracing-api/.../TracingControlProtocolFieldTypeTest.java`
- `platform-tracing-api/.../SpanSpecAttributeValueTest.java`
- `platform-tracing-core/.../SpanSpecAttributeValueConverter.java`
- `platform-tracing-core/.../SpanSpecAttributeValueConverterEmptyListRoundTripTest.java`
- `platform-tracing-core/.../SpanSpecAttributeValueConverterMixedListTypeTest.java`

No staged diff was present. `git diff --name-only` returned only the tracked documentation cleanup above. `git diff --check` passed.

No accidental root secret/config file was found by a root-file scan for `.env`, secret, credential, or token names.

## 14. Findings

### P0

### P1

### P2

## 15. Final Recommendation

The complete platform-tracing-api naming refactor chain is ready for architect review / merge / release gate.

Code, wire contracts, ServiceLoader/SPI migration, compatibility-alias checks, current documentation, and full Docker-backed E2E are ready.
