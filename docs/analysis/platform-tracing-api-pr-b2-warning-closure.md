# PR-B2 Warning Closure

Date: 2026-07-12 (updated after Docker-backed verification)

## 1. Closed Warnings

| Warning | Action | Status |
| --- | --- | --- |
| Missing negative FQN guard | Added `SpanAttributeScrubbingRuleRemovalTest` in `platform-tracing-api` | **CLOSED** |
| Undocumented registry rename | ADR section "Architect-Requested Registry Rename" added | **CLOSED** |
| Stale current-state docs | Broad doc sweep to `SpanAttributeScrubbingRule` / `BuiltInSpanAttributeScrubbingRules` | **CLOSED** |
| ServiceLoader / AutoService | Re-verified; no old SPI descriptors or annotations | **CLOSED** |
| E2E bean-scan collision | `CustomRuleSmokeController` extracted; `MicrometerStatusMappingE2ETest` uses `@SpringBootConfiguration` + `@Import` | **CLOSED** |
| Docker unavailable | `DOCKER_HOST=tcp://192.168.100.70:2375`; `docker ps` and `hello-world` succeed | **CLOSED** |

## 2. Verification Commands

| Command | Result | Notes |
| --- | --- | --- |
| `docker version` / `docker ps` / `docker run --rm hello-world` | **PASS** | Remote Gentoo daemon at `192.168.100.70:2375` |
| `.\gradlew.bat :platform-tracing-api:test --tests SpanAttributeScrubbingRuleRemovalTest` | **PASS** | Negative + positive FQN guard |
| `.\gradlew.bat :platform-tracing-otel-extension:test --tests ServiceLoaderSpanAttributeScrubbingRuleTest` | **PASS** | AutoService / ServiceLoader |
| `.\gradlew.bat compileJava compileTestJava :platform-tracing-otel-extension:compileJava` | **PASS** | (prior run) |
| PR-B2 E2E subset: `CustomRuleSmokeE2ETest`, `ClassLoaderVisibilityE2ETest`, `MicrometerStatusMappingE2ETest` | **PASS** | ~35s; custom rule + classloader probe + Micrometer |
| Collector E2E: `TracingE2ETest`, `ExceptionEventScrubbingE2ETest`, `CollectorProductionPolicyE2ETest` | **PASS** | Collector -> Jaeger uses container IP, not Docker DNS alias |
| Baseline agent: `DbSemconvAgentSmokeTest` | **PASS** | Windows child JVM -> remote Docker Jaeger mapped OTLP HTTP endpoint works |
| Extension/resource/reactor targeted reruns after deterministic sampler fix | **PASS** | `platform.tracing.sampling.ratio=1.0` set in the relevant smoke runners/tests |
| Force-header targeted rerun: `ForceSamplingAgentSmokeTest` | **PASS** | Fixed config-default binding for absent `platform.tracing.sampling.force-record-values`; `X-Trace-On=on` records at ratio `0` |
| Platform sampler/control subset: `ForceSamplingAgentSmokeTest`, `PlatformSpiAgentSmokeTest`, `RuntimeSamplingControlSmokeTest` | **PASS** | Named platform sampler + trace-control propagator path verified |
| `.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e` (full suite) | **PASS** | Full Docker-backed E2E run green on 2026-07-12 |

### PR-B2-critical E2E evidence

These tests prove the rename wiring end-to-end:

- `CustomRuleSmokeE2ETest` — `MyCustomE2eRule` loaded via `META-INF/services/...SpanAttributeScrubbingRule`, masks attribute in Jaeger
- `ClassLoaderVisibilityE2ETest` — `ServiceLoader.load(SpanAttributeScrubbingRule.class, ...)` finds custom rule in agent CL
- `TracingE2ETest` (8 tests) — passed in full run

### Remaining full-suite failures (current)

None confirmed after the force-header closure pass.

The previous force-header failures were not PR-B2 SPI rename issues and not Docker/Testcontainers/Jaeger connectivity issues. Root cause was config-default binding in `ExtensionConfigReader.listValue(...)`: absent list property `platform.tracing.sampling.force-record-values` could be interpreted as an empty list, clearing the default `["on"]` force value. After fixing the default fallback, `ForceSamplingAgentSmokeTest`, `PlatformSpiAgentSmokeTest`, `RuntimeSamplingControlSmokeTest`, and the full `-PrunE2e` suite pass.

OTLP endpoint audit remains clean: agent child JVMs correctly use host-mapped Jaeger OTLP HTTP endpoints from `JaegerTestContainerSupport.otlpHttpEndpoint(jaeger)`; collector tests use Jaeger's container network IP. No remaining evidence points to Gentoo Docker DNS or mapped-port connectivity.

### Historical full-suite failures (closed)

Current status after E2E hardening: the remaining confirmed blocker is force-header/platform sampler behavior at ratio `0`. A fresh isolated `ForceSamplingAgentSmokeTest` still fails with `spanNames=[]`; related full-suite failures are `PlatformSpiAgentSmokeTest` and `RuntimeSamplingControlSmokeTest`. The earlier extension/resource/reactor failures were deterministic sampling problems, not Docker networking, and pass in targeted reruns after setting `platform.tracing.sampling.ratio=1.0` where export is expected.

OTLP endpoint audit: agent child JVMs correctly use host-mapped Jaeger OTLP HTTP endpoints from `JaegerTestContainerSupport.otlpHttpEndpoint(jaeger)`; collector tests use Jaeger's container network IP. No remaining evidence points to Gentoo Docker DNS or mapped-port connectivity.

| Test class | Root cause (preliminary) |
| --- | --- |
| `ForceSamplingAgentSmokeTest` | `spanNames=[]` at ratio=0 with X-Trace-On — force-header / platform sampler export |
| `PlatformSpiAgentSmokeTest` | Same force-header / named sampler path |
| `RuntimeSamplingControlSmokeTest` | Phase-2: expected 1 forced span at runtime ratio=0, got 0 |
| `ResourceIdentityAgentSmokeE2ETest` (2) | Resource not visible in Jaeger query |
| `PlatformExtensionAgentSmokeTest` | JDBC span not in Jaeger |
| `ReactorContextPropagationAgentE2ETest` | HTTP span not in Jaeger (app propagation OK) |

OTLP `traces.endpoint` fix applied to `AgentWebFluxProcessRunner`, `AgentJdbcSmokeProcessRunner`, `ResourceIdentityAgentSmokeProcessRunner`, `AgentMdcLoggingProcessRunner` — did not green the three export tests above in isolated rerun.

## 3. Grep Checks (2026-07-12)

### Old SPI in Java

Only intentional negative guard:

```text
SpanAttributeScrubbingRuleRemovalTest.java: Class.forName("...SensitiveDataRule")
```

### Old registry `BuiltInSensitiveDataRules`

Zero matches in `*.java`. Remaining hits only in ADR/historical audit markdown.

### Service descriptors

- Old `...SensitiveDataRule` — absent
- New `...SpanAttributeScrubbingRule` — present in e2e `customRule/resources`

## 4. Final Verdict

2026-07-12 update: **PR-B2 warning closure is complete and the full Docker-backed E2E gate is green**.

**PR-B2 warning closure: COMPLETE for code, docs, ADR, guard test, and PR-B2-critical E2E.**

**Full-suite `-PrunE2e`: GREEN** after deterministic sampler fixes and the force-header config-default fix.

Post-audit verdict for architects requiring **entire** e2e module green: **PASS**.
