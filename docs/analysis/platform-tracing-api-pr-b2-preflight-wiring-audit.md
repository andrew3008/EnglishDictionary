# PR-B2 Pre-flight Wiring Audit — SpanAttributeScrubbingRule Rename

Date: 2026-07-11  
Repository: `E:\Platform_Traces`  
Auditor role: senior Java platform engineer / observability SPI reviewer  
Planned rename: `SensitiveDataRule` -> `SpanAttributeScrubbingRule`  
Policy: rename SPI/interface only; implementation class names deferred unless compile/runtime requires change.

---

## 1. Executive Verdict

**GO WITH WARNINGS**

Semantic fit is correct. Java compilation risk is manageable. The real blast radius is **runtime SPI discovery**:

- `META-INF/services` descriptor path must change with the interface FQN.
- `ServiceLoader.load(...)` call sites must switch to the new SPI type.
- External/custom extension JARs compiled against the old SPI will fail to load until rebuilt (acceptable pre-production hard break).
- Spring wiring is **not** the primary risk — scrubbing rules are agent/extension-loaded, not Spring beans.

Warnings are operational: actuator note string, e2e custom-rule resources, hygiene test path literals, and external extension author docs.

---

## 2. Semantic Fit

**GO — `SpanAttributeScrubbingRule` is semantically correct**

Evidence from current SPI contract:

| Source | Evidence |
| --- | --- |
| `SpanAttributeScrubbingRule.java:7-12` | Javadoc: «Правило обнаружения чувствительных данных в **значениях атрибутов span'ов**»; applied by `ScrubbingSpanProcessor` before export |
| `SpanAttributeScrubbingRule.java:69-74` | `evaluate(@Nonnull String key, @Nullable Object value)` — attribute key/value only |
| `ScrubbingSpanProcessor.java:35-60` | «Вычищает чувствительные данные из **значений атрибутов span'ов**»; «Процессор мутирует **только span attributes**. Events/links … не модифицируются» |

The SPI does **not** scrub:

- span events (including `exception.message`)
- links
- baggage
- resource attributes
- logs

`SpanAttributeScrubbingRule` accurately names the contract. It aligns with sibling types `ScrubbingDecision` / `ScrubbingAction`, which already describe attribute scrubbing outcomes.

**Not recommended:** keeping the vague `SensitiveDataRule` or renaming to something broader like `SensitiveDataScrubbingRule` (would over-promise coverage beyond attributes).

---

## 3. Current SPI Inventory

| Item | File | Role | Notes |
| --- | --- | --- | --- |
| `SensitiveDataRule` | `platform-tracing-api/.../api/spi/SensitiveDataRule.java` | Public scrubbing SPI | Rename target -> `SpanAttributeScrubbingRule` |
| `ScrubbingDecision` | `platform-tracing-api/.../api/spi/ScrubbingDecision.java` | Rule output record | Javadoc `@link SpanAttributeScrubbingRule#evaluate` — update link only |
| `ScrubbingAction` | `platform-tracing-api/.../api/spi/ScrubbingAction.java` | Action enum | Javadoc `@link SpanAttributeScrubbingRule` — update link only |
| `TracedAttribute` | `platform-tracing-api/.../api/annotation/TracedAttribute.java` | AOP annotation | Javadoc mentions SPI by old name — update text |
| `BuiltInSpanAttributeScrubbingRules` | `platform-tracing-otel-extension/.../scrubbing/BuiltInSpanAttributeScrubbingRules.java` | Built-in rule registry enum | **Not** the SPI; returns `SpanAttributeScrubbingRule` instances via `create()` / `resolve()` |
| `AbstractBuiltInRule` | `otel-extension/.../scrubbing/AbstractBuiltInRule.java` | Built-in rule base | Implements SPI; package-private |
| `ExtensionRuleLoader` | `otel-extension/.../scrubbing/loader/ExtensionRuleLoader.java` | Custom JAR loader | `ServiceLoader.load(SpanAttributeScrubbingRule.class, urlCL)` |
| `PlatformSpanProcessorFactory` | `otel-extension/.../factory/PlatformSpanProcessorFactory.java` | Rule collection at agent startup | Bundled SPI via `ServiceLoader.load(SpanAttributeScrubbingRule.class, …)` |
| `ScrubbingSpanProcessor` | `otel-extension/.../processor/ScrubbingSpanProcessor.java` | Runtime attribute scrubber | Consumes `List<SpanAttributeScrubbingRule>` |
| `RuleExecutionWrapper` | `otel-extension/.../scrubbing/engine/RuleExecutionWrapper.java` | Engine wrapper | Holds `SpanAttributeScrubbingRule` |
| `ScrubbingSnapshot` / `ScrubbingPolicyUpdate` | `otel-extension/.../scrubbing/` | Runtime policy | Compile rules from built-in names |

Approximate Java reference count: **~55 files** contain `SpanAttributeScrubbingRule` (api: 4, otel-extension: ~45, e2e/bench/autoconfigure: remainder).

---

## 4. Implementation Inventory

| Implementation | File | Spring Bean? | ServiceLoader? | Rename Recommendation |
| --- | --- | --- | --- | --- |
| `PasswordKeyRule` | `otel-extension/.../scrubbing/PasswordKeyRule.java` | No | No (built-in enum) | **KEEP** |
| `JwtRule` | `.../JwtRule.java` | No | No | **KEEP** |
| `EmailRule` | `.../EmailRule.java` | No | No | **KEEP** |
| `IpAddressRule` | `.../IpAddressRule.java` | No | No | **KEEP** |
| `OAuthHeaderRule` | `.../OAuthHeaderRule.java` | No | No | **KEEP** |
| `XAuthHeaderRule` | `.../XAuthHeaderRule.java` | No | No | **KEEP** |
| `WebhookTokenRule` | `.../WebhookTokenRule.java` | No | No | **KEEP** |
| `InfraCredentialRule` | `.../InfraCredentialRule.java` | No | No | **KEEP** |
| `SshCredentialRule` | `.../SshCredentialRule.java` | No | No | **KEEP** |
| `HardwareIdentityRule` | `.../HardwareIdentityRule.java` | No | No | **KEEP** |
| `UserIdentityRule` | `.../UserIdentityRule.java` | No | No | **KEEP** |
| `LocationRule` | `.../LocationRule.java` | No | No | **KEEP** |
| `ExampleMerchantAccountRule` | `otel-extension/src/test/.../ExampleMerchantAccountRule.java` | No | **Yes** (`@AutoService`) | **KEEP class name**; update `implements` + `@AutoService(SpanAttributeScrubbingRule.class)` |
| `MyCustomE2eRule` | `e2e-tests/src/customRule/.../MyCustomE2eRule.java` | No | **Yes** (manual `META-INF/services`) | **KEEP class name**; update `implements` + service descriptor |
| Test-only anonymous/inner rules | various processor/scrubbing tests | No | No | Update `implements` type only |

**No production implementation class is named `SensitiveData*`.**  
`BuiltInSpanAttributeScrubbingRules` is a registry enum name — misleading after SPI rename but **outside approved PR-B2 scope**. Defer enum rename to a follow-up if desired.

### Implementation naming policy verdict

**KEEP IMPLEMENTATION CLASS NAMES**

Rationale:

- Implementation names (`PasswordKeyRule`, `JwtRule`, …) remain clear and domain-specific.
- None embed the old SPI name `SpanAttributeScrubbingRule`.
- Spring does not register these as beans — no bean-name churn.
- ServiceLoader descriptors reference **implementation FQNs**, which stay unchanged under the approved policy.
- Only SPI interface FQN and `META-INF/services/<SPI FQN>` path must change.

---

## 5. Spring Wiring Audit

Scrubbing SPI is **agent/extension-runtime**, not Spring-application-runtime.

| Finding | File | Risk | Required PR-B2 Action |
| --- | --- | --- | --- |
| No `@Bean` / `@Component` / `@Qualifier` returning `SpanAttributeScrubbingRule` | repo-wide Java grep | **LOW** | None — compile-time type rename only |
| Spring configures built-in rule **names** (strings), not SPI types | `TracingProperties.Scrubbing.builtInRules`, `ScrubbingRuleNamesWire` | **LOW** | No type rename needed; config keys unchanged |
| Actuator read endpoint embeds old SPI name in user-visible `note` | `TracingActuatorEndpoint.java:172` — `"SPI-реализации SpanAttributeScrubbingRule грузятся..."` | **MEDIUM** | Update string to `SpanAttributeScrubbingRule` |
| `ScrubbingRuleNamesWire` javadoc references `BuiltInSpanAttributeScrubbingRules.resolve` | `autoconfigure/.../ScrubbingRuleNamesWire.java` | **LOW** | Optional javadoc touch only |
| Runtime config applier touches scrubbing domain by name, not SPI type | `RuntimeConfigApplier`, JMX scrubbing paths | **LOW** | No SPI type references found |
| `SemanticLayerAutoConfiguration` mentions PII-scrubbing in log text | `SemanticLayerAutoConfiguration.java` | **LOW** | No action |

**Conclusion:** Spring context startup is **not** blocked by SPI rename. Do not assume Spring safety from compile alone for actuator strings and docs.

---

## 6. ServiceLoader / Runtime Discovery Audit

| Finding | File/Resource | Risk | Required PR-B2 Action |
| --- | --- | --- | --- |
| **SPI service descriptor (e2e custom rule)** | `e2e-tests/src/customRule/resources/META-INF/services/space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule` | **HIGH** | **Delete old file.** Create `META-INF/services/space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule` with same content: `space.br1440.e2e.customrule.MyCustomE2eRule` |
| `@AutoService` test rule | `ExampleMerchantAccountRule.java` — `@AutoService(SpanAttributeScrubbingRule.class)` | **HIGH** | Change to `@AutoService(SpanAttributeScrubbingRule.class)`; rebuild generates new `META-INF/services/...SpanAttributeScrubbingRule` under test output |
| Bundled SPI load at agent startup | `PlatformSpanProcessorFactory.java:149` — `ServiceLoader.load(SpanAttributeScrubbingRule.class, …)` | **HIGH** | Change to `SpanAttributeScrubbingRule.class` |
| Custom extension JAR load | `ExtensionRuleLoader.java:102-105` — parent CL + `ServiceLoader.load(SpanAttributeScrubbingRule.class, urlCL)` | **HIGH** | Change all `SpanAttributeScrubbingRule.class` references to `SpanAttributeScrubbingRule.class` |
| Extension loader parent CL anchor | `ExtensionRuleLoader.java:102` — `SpanAttributeScrubbingRule.class.getClassLoader()` | **HIGH** | Use `SpanAttributeScrubbingRule.class.getClassLoader()` |
| E2E classloader probe | `ClassLoaderVisibilityTestProbe.java` — multiple `ServiceLoader.load(SpanAttributeScrubbingRule.class, …)` | **HIGH** | Update imports and `ServiceLoader.load` type |
| ServiceLoader unit test | `ServiceLoaderSensitiveDataRuleTest.java` | **HIGH** | Rename test class optional; update `ServiceLoader.load` + `implements` types |
| JAR hygiene test literal | `ExtensionRuleLoaderTest.java:53` — `"space/br1440/platform/tracing/api/spi/SpanAttributeScrubbingRule.class"` | **HIGH** | Update path to `.../SpanAttributeScrubbingRule.class` |
| Forbidden prefix hygiene | `ExtensionRuleLoader.FORBIDDEN_PREFIXES` — `space/br1440/platform/tracing/api/` | **LOW** | Prefix still valid; no change |
| No `module-info.java` provides clause | repo grep | **LOW** | N/A |
| No `Class.forName("...SpanAttributeScrubbingRule")` in production | repo grep | **LOW** | N/A |
| External custom JARs compiled against old SPI | runtime | **HIGH (expected)** | Document hard break in CHANGELOG; authors must rebuild with new SPI + new service file name |

### Provider file rename rule (mandatory)

```text
FROM: META-INF/services/space.br1440.platform.tracing.api.spi.SensitiveDataRule
TO:   META-INF/services/space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule
```

File **contents** remain implementation FQNs (e.g. `space.br1440.e2e.customrule.MyCustomE2eRule`) unless implementation classes are intentionally renamed (not in PR-B2 scope).

### `PlatformTraceControlPropagatorProvider` analogue

There is **no** separate `SensitiveDataRuleProvider` class. Discovery is standard Java `ServiceLoader` on the SPI interface itself. The critical rename is the **service descriptor filename**, not a provider wrapper class.

---

## 7. Tests to Update or Add

### Must update (existing)

| Test | Module | Required Change |
| --- | --- | --- |
| `ServiceLoaderSensitiveDataRuleTest` | otel-extension | `ServiceLoader.load(SpanAttributeScrubbingRule.class, …)`; inner `TestRule implements SpanAttributeScrubbingRule` |
| `ExtensionRuleLoaderTest` | otel-extension | Hygiene jar entry path `SpanAttributeScrubbingRule.class` |
| `BuiltInRulesTest` | otel-extension | Type references in assertions |
| `ScrubbingSpanProcessorTest` / `AdvancedTest` / `SecurityNegativeTest` / characterization tests | otel-extension | Anonymous `implements SpanAttributeScrubbingRule` |
| `MergeEngineTest`, `ScrubbingEngineCharacterizationTest` | otel-extension | Type parameters |
| `ScrubbingSnapshotTest`, `ScrubbingPolicyHolderTest`, `ScrubbingPolicyUpdateTest` | otel-extension | `BuiltInSpanAttributeScrubbingRules.resolve` return type usage |
| `PlatformSpanProcessorFactoryScrubbingAdoptionTest` | otel-extension | Factory SPI collection |
| `ClassLoaderVisibilityTestProbe` + `ClassLoaderVisibilityE2ETest` | e2e-tests | `ServiceLoader` type + imports |
| `TracingE2ETest` | e2e-tests | Import + scrubbing setup types |
| `ScrubbingEngineBenchmark`, `ScrubbingPerRuleBenchmark`, `CompositePipelineBenchmark` | bench | Import/type |
| `ScrubbingBenchmarkFixtureContractTest` | bench | Fixture types |
| `TracingActuatorEndpointTest` | autoconfigure | If asserting scrubbing `note` text — update expected string |

### Recommended add (if absent after rename)

| Proposed test | Module | Purpose |
| --- | --- | --- |
| `SpanAttributeScrubbingRuleServiceDescriptorTest` | platform-tracing-api or otel-extension | Assert old `META-INF/services/...SpanAttributeScrubbingRule` absent on test classpath; new descriptor present for `@AutoService` rule |
| `SpanAttributeScrubbingRuleSpiAbsenceTest` | platform-tracing-api | `Class.forName("...SpanAttributeScrubbingRule")` → `ClassNotFoundException` (mirror `SpanScopeRemovalTest` pattern) |

---

## 8. Documentation to Update

| Document | Required Change |
| --- | --- |
| `platform-tracing-api/CHANGELOG.md` | Add PR-B2 section: `SpanAttributeScrubbingRule` → `SpanAttributeScrubbingRule`; note `META-INF/services` path change for extension authors |
| New ADR `docs/decisions/ADR-api-naming-refactor-pr-b2.md` | Record SPI rename + ServiceLoader descriptor migration |
| `docs/analysis/platform-tracing-api-class-hierarchy-inventory.md` | Footer accepted update for PR-B2 |
| `docs/analysis/platform-tracing-api-model-naming-options.md` | Mark PR-B2 accepted; footer update |
| `platform-tracing-api/build.gradle` | Comment on line 19 referencing `SpanAttributeScrubbingRule` |
| `TracingActuatorEndpoint.java` scrubbing note | User-visible string (see Spring audit) |
| `TracedAttribute.java` javadoc | SPI name reference |
| Extension author guidance (if published) | Custom JAR must implement `SpanAttributeScrubbingRule` and ship new service descriptor filename |

Historical/archive docs (`docs/architecture/*`, perplexity-review packages) may retain old names as archaeology — not blocking PR-B2 code.

---

## 9. Forbidden Change Guard

PR-B2 must not touch Batch C / completed names. Current repo state:

| Forbidden item | Current state | Risk |
| --- | --- | --- |
| `SpanLinkContext` | Unchanged | None |
| `SpanAttributeValue` | Unchanged | None |
| `CategoryContract(s)` | Unchanged | None |
| `TracingControlProtocolTypes` / keys | Unchanged | None |
| `RemoteServiceTraceMirror` / `RemoteServiceContextReaders` | Unchanged | None |
| Batch A names (`SpanRelationship`, `ManualSpanBuilder`, …) | Unchanged | None |
| Batch B1 names (`ActiveTraceContextView`, `TraceparentParser`, …) | Unchanged | None |
| `SpanCategory`, `SpanResult`, wire values | Unchanged | None |
| `SemconvKeys`, `SemconvViolation` | Unchanged | None |

**Accidental risk:** renaming `BuiltInSpanAttributeScrubbingRules` enum during PR-B2 — **out of scope**; do not rename unless explicitly expanding scope.

**Accidental risk:** renaming `ScrubbingDecision` / `ScrubbingAction` — not in PR-B2 plan; keep as-is (only update javadoc links).

---

## 10. Exact Codex Implementation Checklist

1. **Rename SPI file and type**
   - `SensitiveDataRule.java` -> `SpanAttributeScrubbingRule.java`
   - Package stays `space.br1440.platform.tracing.api.spi`
   - Update class/interface Javadoc; keep method signatures unchanged

2. **Update related API javadoc links**
   - `ScrubbingDecision.java`, `ScrubbingAction.java`, `TracedAttribute.java`

3. **Repo-wide type reference migration**
   - Replace `SensitiveDataRule` with `SpanAttributeScrubbingRule` in all Java sources/tests/bench/e2e
   - Do **not** rename implementation classes (`*Rule`) unless compile forces it

4. **ServiceLoader descriptor migration (critical)**
   - Delete every `META-INF/services/space.br1440.platform.tracing.api.spi.SensitiveDataRule`
   - Create `META-INF/services/space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule`
   - Known source: `e2e-tests/src/customRule/resources/META-INF/services/...`
   - Regenerated: `@AutoService` output for `ExampleMerchantAccountRule` after annotation update

5. **Update ServiceLoader call sites**
   - `PlatformSpanProcessorFactory.collectScrubbingRules`
   - `ExtensionRuleLoader.load`
   - `ClassLoaderVisibilityTestProbe`
   - `ServiceLoaderSensitiveDataRuleTest` (and rename test class optionally)

6. **Update `@AutoService` annotation**
   - `ExampleMerchantAccountRule`: `@AutoService(SpanAttributeScrubbingRule.class)`

7. **Update e2e custom rule**
   - `MyCustomE2eRule implements SpanAttributeScrubbingRule`
   - Service descriptor file path + verify `customRuleJar` task packages new descriptor

8. **Update string literals tied to SPI FQN/path**
   - `ExtensionRuleLoaderTest` hygiene jar entry: `.../SpanAttributeScrubbingRule.class`
   - `e2e-tests/build.gradle` comments mentioning `SpanAttributeScrubbingRule` (comments only)

9. **Update actuator note string**
   - `TracingActuatorEndpoint` scrubbing `note` field

10. **Add guard test (recommended)**
    - `SpanAttributeScrubbingRuleRemovalTest` or equivalent in `platform-tracing-api` asserting old FQN absent

11. **Documentation**
    - CHANGELOG PR-B2 section
    - ADR `ADR-api-naming-refactor-pr-b2.md`
    - Inventory/naming-options accepted footers

12. **Verification commands**

```bash
# Stale SPI name in Java sources (expect 0 outside docs)
grep -r "SpanAttributeScrubbingRule" platform-tracing-api/src platform-tracing-core/src \
  platform-tracing-otel-extension/src platform-tracing-spring-boot-autoconfigure/src \
  platform-tracing-e2e-tests/src platform-tracing-bench/src

# Old service descriptor must be gone
Get-ChildItem -Recurse -Filter "SpanAttributeScrubbingRule" platform-tracing-e2e-tests platform-tracing-otel-extension

# New descriptor must exist (e2e custom rule + test generated)
Get-ChildItem -Recurse -Filter "SpanAttributeScrubbingRule" platform-tracing-e2e-tests platform-tracing-otel-extension

./gradlew compileJava compileTestJava
./gradlew :platform-tracing-otel-extension:test
./gradlew :platform-tracing-api:test
./gradlew :platform-tracing-e2e-tests:test -PrunE2e   # if Docker/agent e2e available
```

13. **Explicit STOP-list for PR-B2**
    - Do not rename `BuiltInSpanAttributeScrubbingRules` enum
    - Do not rename built-in `*Rule` implementation classes
    - Do not rename `ScrubbingDecision` / `ScrubbingAction`
    - Do not touch Batch C types or Batch A/B1 completed names

---

## 11. Final Recommendation

**Proceed with PR-B2 implementation** using `SpanAttributeScrubbingRule` as the final SPI name.

**Keep implementation class names** (`PasswordKeyRule`, `JwtRule`, `MyCustomE2eRule`, …). The approved policy is safe: no Spring bean name coupling, no misleading `SensitiveData*` implementation class names, and ServiceLoader contents stay as implementation FQNs.

**Treat ServiceLoader descriptor migration as the primary wiring task**, not an afterthought. A compile-green rename with a forgotten `META-INF/services` filename change will fail silently at agent startup (zero custom rules loaded).

**PR-B2 is ready for Codex** after acknowledging:

1. External extension JAR authors must rebuild against the new SPI and ship the new service descriptor path.
2. Actuator scrubbing note and a few javadoc strings need updating.
3. `BuiltInSpanAttributeScrubbingRules` enum name remains slightly stale — acceptable deferral.

Suggested commit scope: `refactor(api): PR-B2 rename SpanAttributeScrubbingRule to SpanAttributeScrubbingRule`.
