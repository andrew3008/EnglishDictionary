# TraceparentParser Refactoring Post-Audit

Date: 2026-07-14

## Verdict

PASS. The approved DELETE_AND_REPLACE_WITH_OTEL refactoring is implemented by slices.

`TraceparentParser` is deleted from Java sources and the API jar. `OtelTraceparentReader` delegates W3C `traceparent` extraction to `W3CTraceContextPropagator`, returns `RemoteSpanLink`, preserves `traceFlags` as byte, and keeps `traceState == null` for single-header parsing.

No aliases, no `@Deprecated` bridges, no new Gradle dependencies, no `api.propagation.internal`, and no promotion of `opentelemetry-api` from `compileOnly` to `api`.

Ready for commit after human review.

## Pre-Flight Findings

- Existing parser: `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/propagation/TraceparentParser.java`.
- Production strict call sites:
  - `DefaultSpanSpecBuilder.fromTraceparent(...)`
  - `AbstractSemanticSpanBuilder.fromTraceparent(...)`
- Soft sample call site:
  - `TraceOperationsV3Samples`, using the old lenient parser path.
- `RemoteSpanLink` remained the target record: `traceId`, `spanId`, `byte traceFlags`, nullable `traceState`.
- `platform-tracing-api` already had `slf4j-api` and OTel API/Context as `compileOnly` for main sources; no dependency scope change was needed.

## OTel Behavior Probe

Pre-flight probe used binary OTel jars from the local Gradle cache and `W3CTraceContextPropagator.extract(...)`.

Observed behavior:

| Case | Input shape | OTel result |
| --- | --- | --- |
| `version_ff` | `ff-...-...-01` | invalid |
| `uppercase` | uppercase trace/span ids | valid, normalized lowercase |
| `v00_extra` | `00-...-...-01-extra` | invalid |
| `v01_extra` | `01-...-...-01-extra` | valid |
| `flags_len_1` | one hex flag char | invalid |
| `flags_len_3` | three hex flag chars | invalid |

These observations are captured in `OtelTraceparentReaderTest`.

## Files Created

- `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/propagation/OtelTraceparentReader.java`
- `platform-tracing-api/src/test/java/space/br1440/platform/tracing/api/propagation/OtelTraceparentReaderTest.java`
- `docs/decisions/ADR-traceparent-otel-delegation.md`
- `docs/analysis/traceparent-parser-post-audit.md`

## Files Deleted

- `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/propagation/TraceparentParser.java`
- `platform-tracing-api/src/test/java/space/br1440/platform/tracing/api/propagation/TraceparentParserTest.java`

## Files Modified

- `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/DefaultSpanSpecBuilder.java`
- `platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/AbstractSemanticSpanBuilder.java`
- `platform-tracing-samples/src/main/java/space/br1440/platform/tracing/samples/TraceOperationsV3Samples.java`
- `platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/KafkaConsumerBatchLinksTest.java`
- `platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/KafkaBatchSpanBuilderIntegrationTest.java`
- `platform-tracing-test/src/main/java/space/br1440/platform/tracing/test/arch/ModuleTaxonomyArchRules.java`
- `platform-tracing-api/src/test/java/space/br1440/platform/tracing/api/runtime/state/arch/RuntimeStateArchTest.java`
- `platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/arch/CorePolicyPackagePurityArchTest.java`
- `platform-tracing-test/src/test/java/space/br1440/platform/tracing/test/arch/TracingArchRulesTest.java`
- `platform-tracing-api/build.gradle`
- `CHANGELOG.md`
- `platform-tracing-api/CHANGELOG.md`
- `docs/tracing/platform-tracing-v3-kafka-batch-links.md`
- `docs/analysis/platform-tracing-api-class-hierarchy-inventory.md`

## Tests Added or Updated

- Added `OtelTraceparentReaderTest` for valid/invalid inputs, null/blank behavior, strict `require`, sanitized exception messages, long invalid input, bit-preserving flags, and probe-derived OTel edge cases.
- Updated Kafka batch link tests to use `OtelTraceparentReader.require(...)`.
- Updated the platform arch self-test to assert no loadable `TraceparentParser` and to check the new access restrictions.

## ArchUnit Rules Added

- `API_PROPAGATION_HAS_NO_PUBLIC_PARSERS`: prevents reintroducing public `*Parser` types under `api.propagation`.
- `OTEL_TRACEPARENT_READER_ACCESS_RESTRICTED`: restricts `OtelTraceparentReader` dependents to approved platform implementation, samples, and tests.

Rules are wired into API/core ArchUnit tests and the shared `platform-tracing-test` self-test.

## Verification Results

Commands:

```powershell
.\gradlew.bat :platform-tracing-api:compileJava :platform-tracing-core:compileJava
.\gradlew.bat :platform-tracing-api:test :platform-tracing-core:test :platform-tracing-test:test
.\gradlew.bat build
.\gradlew.bat :platform-tracing-api:dependencies --configuration compileClasspath
.\gradlew.bat :platform-tracing-api:dependencies --configuration runtimeClasspath
jar tf platform-tracing-api/build/libs/platform-tracing-api-0.1.0-SNAPSHOT.jar | Select-String -Pattern 'TraceparentParser|OtelTraceparentReader'
rg -n "TraceparentParser" --glob "*.java"
rg -n "parseTraceparent|requireTraceparent" --glob "*.java"
```

Results:

- Compile: BUILD SUCCESSFUL.
- Focused API/core/test modules: BUILD SUCCESSFUL.
- Full build: BUILD SUCCESSFUL.
- Compile classpath includes OTel API/Context as compile classpath dependencies.
- Runtime classpath does not include OTel API/Context as runtime dependencies; it contains BOM constraints plus `jakarta.annotation-api` and `slf4j-api`.
- API jar contains:
  - `space/br1440/platform/tracing/api/propagation/OtelTraceparentReader.class`
  - `space/br1440/platform/tracing/api/propagation/OtelTraceparentReader$CarrierGetter.class`
- API jar does not contain `TraceparentParser`.
- Java grep for `TraceparentParser`: empty.
- Java grep for `parseTraceparent` / `requireTraceparent`: empty.

Full build notes:

- Existing Javadoc/classpath warnings remain.
- A Docker connection error was printed after `BUILD SUCCESSFUL`; it did not fail the build.

## Post-Audit Notes

- Current user-facing tracing docs now recommend `fromTraceparent(...)` for applications and document that single-string parsing cannot recover `tracestate`.
- Historical analysis/ADR documents still contain old `TraceparentParser` mentions as prior-state history.
- No git commit was created.
