# PR-B2 Post-Audit — SpanAttributeScrubbingRule

Date: 2026-07-12 (final re-audit after warning-closure work)  
Repository: `E:\Platform_Traces`  
Auditor role: strict senior Java observability SPI and runtime-discovery auditor

---

## 1. Executive Verdict

**PASS** (PR-B2 rename wiring) / **PASS** (architect zero-warning gate)

**PR-B2 rename and runtime discovery: PASS.** All SPI, ServiceLoader, AutoService, Spring, guard-test, and PR-B2-critical E2E evidence is green.

**Architect zero-warning gate: PASS.** Full `.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --no-daemon --info --rerun-tasks` is green after deterministic sampler fixes and the force-header config-default fix.

**2026-07-12 E2E hardening update:** Remote Gentoo Docker is reachable (`192.168.100.70:2375`, Docker `28.0.4`, Gentoo Linux), collector paths use Jaeger's container IP rather than Docker DNS, collector tests pass, direct agent Jaeger endpoints use host-mapped OTLP HTTP endpoints correctly, and the force-header/platform sampler path now passes at ratio `0`.

---

## 2. API SPI Rename Verification

| Check | Evidence | Result |
| --- | --- | --- |
| Public SPI `SpanAttributeScrubbingRule` | `platform-tracing-api/.../spi/SpanAttributeScrubbingRule.java` | PASS |
| `SensitiveDataRule.java` removed | Git rename `R`; glob finds 0 old file | PASS |
| No alias / bridge | `rg` Java: no `implements SensitiveDataRule`, no `@Deprecated` bridge | PASS |
| Method semantics equivalent | `name()`, `priority()`, `isExcluded()`, `critical()`, `evaluate()` unchanged | PASS |
| Negative FQN guard | `SpanAttributeScrubbingRuleRemovalTest` — old FQN throws, new FQN loads | PASS |

---

## 3. Old Name Residuals

| Occurrence | File | Classification | Action Needed |
| --- | --- | --- | --- |
| `Class.forName("...SensitiveDataRule")` | `SpanAttributeScrubbingRuleRemovalTest.java` | OK_NEGATIVE_TEST | None |
| `BuiltInSensitiveDataRules` in Java | — | — | Zero matches (renamed to `BuiltInSpanAttributeScrubbingRules`) |
| Historical migration tables | ADR, CHANGELOG, preflight/post-audit docs | OK_HISTORICAL_DOC | None |
| Current architecture/jira/tracing docs | Updated in warning-closure sweep | PASS | None |

---

## 4. ServiceLoader Descriptor Verification

| Descriptor | State | Result |
| --- | --- | --- |
| `META-INF/services/...SensitiveDataRule` | Absent from resources | PASS |
| `META-INF/services/...SpanAttributeScrubbingRule` | e2e customRule → `MyCustomE2eRule` | PASS |
| No duplicate old+new pair | Git: old deleted, new added | PASS |

---

## 5. ServiceLoader Call Site Verification

| File | SPI | Result |
| --- | --- | --- |
| `ExtensionRuleLoader.java` | `SpanAttributeScrubbingRule.class` | PASS |
| `PlatformSpanProcessorFactory.java` | `SpanAttributeScrubbingRule.class` | PASS |
| `ClassLoaderVisibilityTestProbe.java` | `SpanAttributeScrubbingRule.class` | PASS |
| `ServiceLoaderSpanAttributeScrubbingRuleTest.java` | `SpanAttributeScrubbingRule.class` | PASS |

---

## 6. AutoService Verification

| File | Annotation | Result |
| --- | --- | --- |
| `ExampleMerchantAccountRule.java` | `@AutoService(SpanAttributeScrubbingRule.class)` | PASS |
| Any old `@AutoService(SensitiveDataRule.class)` | None | PASS |

---

## 7. Spring Wiring Verification

| Finding | Result |
| --- | --- |
| No `@Bean`/`@Component` of old SPI | PASS |
| Actuator note says `SpanAttributeScrubbingRule` | PASS |
| `platform.tracing.scrubbing.built-in-rules` unchanged | PASS |

---

## 8. Implementation Naming Decision

`BuiltInSpanAttributeScrubbingRules` — **PASS** (internal registry, aligned with SPI vocabulary). Documented in ADR "Architect-Requested Registry Rename" section. Not public API. Property `built-in-rules` config name strings unchanged.

---

## 9. Test / Gradle Verification

| Command | Result |
| --- | --- |
| `compileJava compileTestJava :platform-tracing-otel-extension:compileJava` | PASS |
| `:platform-tracing-api:test` (incl. guard) | PASS |
| `:platform-tracing-otel-extension:test` | PASS |
| Docker `hello-world` via `192.168.100.70:2375` | PASS |
| PR-B2 E2E: `CustomRuleSmokeE2ETest`, `ClassLoaderVisibilityE2ETest` | PASS |
| Collector E2E: `TracingE2ETest`, `ExceptionEventScrubbingE2ETest`, `CollectorProductionPolicyE2ETest` | **PASS** |
| Baseline agent: `DbSemconvAgentSmokeTest` | **PASS** |
| Extension/resource/reactor targeted reruns after deterministic sampler fix | **PASS** |
| Force-header targeted rerun: `ForceSamplingAgentSmokeTest` | **PASS** |
| Platform sampler/control subset: `ForceSamplingAgentSmokeTest`, `PlatformSpiAgentSmokeTest`, `RuntimeSamplingControlSmokeTest` | **PASS** |
| Full `:platform-tracing-e2e-tests:test -PrunE2e` | **PASS** |

---

## 10. Forbidden Change Check

Batch C symbols and unrelated API names — **not modified** in PR-B2 Java diff. PASS.

---

## 11. Docs / ADR / CHANGELOG

ADR registry-rename section, CHANGELOG, inventory, naming-options — PASS.

---

## 12. Git Status Scope Check

~95 paths across api, otel-extension, e2e, bench, spring, docs — consistent with full SPI rename + warning closure. Not "5 files."

---

## 13. Findings

### P0

None in PR-B2 rename/runtime-discovery path.

### P1

None. The previous full-E2E blocker is closed. Root cause was config-default binding in `ExtensionConfigReader.listValue(...)`: absent `platform.tracing.sampling.force-record-values` could clear the default `["on"]` force value, causing `X-Trace-On=on` to abstain and ratio `0` to drop the span.

### P2

None remaining for PR-B2 rename itself.

---

## 14. Final Recommendation

**PR-B2 rename is ready to commit** from an SPI/runtime-discovery perspective.

**Architect zero-warning gate is satisfied** after the full Docker-backed `-PrunE2e` verification.

**Batch C can start** after PR-B2 merge; Batch C Java symbols were not touched.
