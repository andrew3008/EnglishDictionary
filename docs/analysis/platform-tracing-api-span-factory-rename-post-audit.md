# SpanFactory Rename Post-Audit

## 1. Executive Verdict

PASS.

The active public API shape is now `TraceOperations.spans()` plus `SpanFactory` in `api.span`. The old `ManualTracing` public interface was deleted, no compatibility alias was added, and active Java call sites use `spans()` / `fromSpec(...)`.

## 2. Rename Verification

| Old | New | Evidence | Result |
| --- | --- | --- | --- |
| `ManualTracing` | `SpanFactory` | `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/SpanFactory.java` | PASS |
| `TraceOperations.manual()` | `TraceOperations.spans()` | `TraceOperations` declares `SpanFactory spans()` | PASS |
| `ManualTracing.operation(String)` | `SpanFactory.operation(String)` | `SpanFactory` declares `operation(String)` | PASS |
| `ManualTracing.spanFromSpec(SpanSpec)` | `SpanFactory.fromSpec(SpanSpec)` | `SpanFactory` declares `fromSpec(SpanSpec)` | PASS |
| `ManualTracing.transport()` | `SpanFactory.transport()` | `SpanFactory` declares `transport()` | PASS |

## 3. TraceOperations Shape Verification

`TraceOperations` exposes only `traceContext()` and `spans()`. `ActiveTraceContextView` remains imported from `api.manual`; no canonical `api.context.ActiveTraceContextView` exists in this repository.

## 4. SpanFactory Method Verification

`SpanFactory.operation(String)`, `SpanFactory.transport()`, and `SpanFactory.fromSpec(SpanSpec)` are present. `operationSpan(String)` and `spanFromSpec(SpanSpec)` are absent.

## 5. Implementation Verification

`DefaultManualTracing` was renamed to `DefaultSpanFactory` and now implements `SpanFactory`. `DefaultTraceOperations` and `NoopTraceOperations` return `SpanFactory` from `spans()`.

## 6. Spring / Bean Verification

No direct Spring bean of `ManualTracing` existed. No `manualTracing` alias bean was added. The diagnostics helper carrying the old term was renamed from `ManualTracingDiagnostics` to `SpanFactoryDiagnostics`, and the diagnostics bean method is now `spanFactoryDiagnostics`.

## 7. Do-not-touch Verification

The `api.manual` package remains in place for approved builder types. Do-not-touch types such as `ManualSpanBuilder`, `OperationSpanBuilder`, `TransportTracing`, `SpanSpec`, and `ActiveTraceContextView` were not renamed.

## 8. Compatibility Alias Verification

No `ManualTracing` interface remains in active source. No deprecated bridge, forwarding default method, or alias type was added.

## 9. Current Documentation Verification

Created `docs/decisions/ADR-span-factory-api-shape.md`. Updated current tracing docs, root changelog, and `platform-tracing-api/CHANGELOG.md`. Renamed the current API reference from `platform-tracing-v3-manual-api.md` to `platform-tracing-v3-span-factory-api.md`.

## 10. Grep Results and Classification

Active Java old-name scan excluding build output:

```text
Get-ChildItem -Path . -Recurse -Include *.java |
  Where-Object { $_.FullName -notmatch '\\build\\' } |
  Select-String -CaseSensitive -Pattern "ManualTracing|\.manual\(\)|\.spanFromSpec\(|operationSpan|\.operationSpan\("
```

Result: zero matches.

The requested broad PowerShell scan is case-insensitive by default and therefore matches approved do-not-touch names such as `OperationSpanBuilder` for the `operationSpan` pattern. It also reports old names in changelog/ADR/historical analysis documents and generated build reports. These are classified as allowed historical or generated-output matches, not active API usage.

New-name scan confirms `SpanFactory`, `spans()`, `operation(...)`, and `fromSpec(...)` in API, core, autoconfigure, tests, samples, and docs.

## 11. Test / Gradle / E2E Verification

| Command | Result |
| --- | --- |
| `.\gradlew.bat compileJava compileTestJava --no-daemon` | PASS |
| `.\gradlew.bat :platform-tracing-api:test :platform-tracing-core:test :platform-tracing-spring-boot-autoconfigure:test --no-daemon` | PASS |
| `.\gradlew.bat :platform-tracing-otel-extension:test --no-daemon` | PASS |
| `.\gradlew.bat :platform-tracing-test:test --no-daemon` | PASS |
| `$env:DOCKER_HOST = "tcp://192.168.100.70:2375"; .\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --no-daemon` | PASS on rerun |
| `git diff --check` | PASS; line-ending warnings only |

The first e2e attempt timed out at the tool boundary and left a Gradle test-results file locked. After `.\gradlew.bat --stop`, the rerun completed successfully.

## 12. Findings

### P0

None.

### P1

None.

### P2

None.

## 13. Final Recommendation

Ready for architect review. Do not commit until the reviewer accepts the breaking API shape and the historical-doc grep classification.
