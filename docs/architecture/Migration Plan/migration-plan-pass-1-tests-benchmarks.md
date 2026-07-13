# Platform Tracing Migration Plan — Pass 1
## Разделы 6–7 (продолжение документа)

> **Pass:** 1 из N  
> **Статус:** Завершены разделы 6 и 7. Разделы 8–13 намеренно не генерируются в этом проходе.  
> **Источник правды:** `platform-tracing-current-codebase-inventory.md` (snapshot 2026-06-11)

---

## 6. Plan сохранения тестов

### Принципы

1. **Zero net test deletion в волне 1.** Существующие тесты допустимо адаптировать (переместить, изменить импорты, обновить зависимость на новый модуль), но не удалять.
2. **Tests before moves.** Ни одно поведение не переносится в новый модуль до тех пор, пока его тесты не существуют в целевом слое или не продублированы во временной форме.
3. **DUPLICATE_BEFORE_MOVE** — единственная допустимая стратегия для кода горячего пути (sampling, scrubbing).
4. Адаптированные тесты сохраняют оригинальное намерение; изменение допустимо только в части импорта и вспомогательных зависимостей.

---

### 6.1. Sampling тесты

**Текущий модуль:** `platform-tracing-otel-extension` (78 test classes total; sampling subset)  
**Целевое разделение:** policy rules + `SamplerState` → `platform-tracing-core`; OTel `Sampler` callback → `platform-tracing-otel-extension`

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `CompositeSamplerTest`, `CompositeSamplerEdgeCasesTest` (инвентарь: `EdgeCasesTest`), `RouteRatioTest`, `SamplerStateHolderTest`, `SamplerRuntimeUpdateConcurrencyTest`, `PlatformSamplerProviderTest` |
| **Продублировать до переноса** | `CompositeSamplerTest` → дублировать в `platform-tracing-core` тестовый источник с использованием JDK-only harness (без OTel SDK); `SamplerStateHolderTest` → дублировать в core с использованием `DomainConfigHolder` напрямую; `RouteRatioTest` для каждого `*Rule` класса |
| **Адаптировать после split** | `CompositeSamplerTest` в `otel-extension` — обновить зависимость: `CompositeSampler` → thin OTel adapter, policy delegated from core; `SamplerRuntimeUpdateConcurrencyTest` — адаптировать, если state holder переехал |
| **Новые тесты** | Characterization test для sampling decision chain output (сравнение до/после split на идентичных входных данных); тест на отсутствие OTel-зависимостей в core sampling policy |
| **PR** | PR-5 (дублирование) → PR-6 (extraction + адаптация) |
| **Риск при пропуске** | Изменение порядка правил в цепочке KillSwitch→ForceHeader→QaTrace→RouteRatio→DefaultRatio→HardDrop→ParentDecision без регрессии. Неверный sampling rate в production. |

---

### 6.2. Scrubbing тесты

**Текущий модуль:** `platform-tracing-otel-extension` (scrubbing package + engine sub-packages)  
**Целевое разделение:** rule evaluation engine → `platform-tracing-core`; OTel `SpanProcessor` callback → `platform-tracing-otel-extension`

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `ScrubbingSpanProcessorTest`, `ScrubbingSpanProcessorAdvancedTest` (инвентарь: `AdvancedTest` в scrubbing package), `ScrubbingSecurityNegativeTest`, `MergeEngineTest`, `RuleCircuitBreakerTest`, `ExtensionRuleLoaderTest`, `ServiceLoaderSpanAttributeScrubbingRuleTest`, `ExceptionEventScrubbingE2ETest`, `BuiltInRulesTest` |
| **Продублировать до переноса** | Rule engine unit tests (`MergeEngineTest`, `RuleCircuitBreakerTest`, `BuiltInRulesTest`) → дублировать в `platform-tracing-core` test source до PR-7; `ScrubbingSecurityNegativeTest` → обязательно, критический security тест ReDoS/injection |
| **Адаптировать после split** | `ScrubbingSpanProcessorTest` — адаптировать: processor остаётся в otel-extension, но делегирует engine из core; `ExtensionRuleLoaderTest` — адаптировать: loader может быть в extension, engine в core |
| **Новые тесты** | Тест граничного поведения fail-open: engine exception → span export продолжается (не только на уровне processor, но и на уровне core engine); тест на отсутствие OTel-зависимостей в core scrubbing policy |
| **PR** | PR-5 (дублирование security + rule engine тестов в core) → PR-7 (extraction + адаптация processor) |
| **Риск при пропуске** | Потеря mandatory baseline scrubbing → compliance incident. Регрессия ReDoS защиты (`ScrubbingSecurityNegativeTest` отсутствует в core → уязвимость не обнаруживается). |

---

### 6.3. Validation / enrichment тесты

**Текущий модуль:** `platform-tracing-otel-extension`  
**Целевое разделение:** policy → core; processor adapter → otel-extension

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `ValidatingSpanProcessorTest`, `ValidationPolicyRuntimeTest`, `EnrichingSpanProcessorTest`, `CategoryContractsTest` (в `platform-tracing-api`), `SpanEnricherTest` |
| **Продублировать до переноса** | `ValidationPolicyRuntimeTest` → дублировать в core до PR-8; enrichment policy unit тесты (`SpanEnricherTest`) → дублировать в core |
| **Адаптировать после split** | `ValidatingSpanProcessorTest` — adapter делегирует в core policy; `EnrichingSpanProcessorTest` — аналогично |
| **Новые тесты** | Тест на `ValidationMode.STRICT` vs `LENIENT` в изолированном core без OTel; тест, что enrichment отключается при `enriching.enabled=false` на уровне policy (не processor) |
| **PR** | PR-5 (дублирование до начала extraction) → PR-8 (extraction) |
| **Риск при пропуске** | Потеря semconv validation enforcement; неверный режим LENIENT/STRICT после split. |

---

### 6.4. Export safety тесты

**Текущий модуль:** `platform-tracing-otel-extension` (processor + safety package)

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `PlatformDropOldestExportSpanProcessorTest` (overflow, lifecycle, builder validation — все варианты), `SafeSpanExporterTest`, `DegradedModeControllerTest`, `BspDropOldestSafetyAgentSmokeTest` (e2e), `BspOverflowSafetyAgentSmokeTest` (e2e) |
| **Продублировать до переноса** | Не требуется — `PlatformDropOldestExportSpanProcessor` и `SafeSpanExporter` остаются в `otel-extension` (не являются целевым SPLIT_CORE_AND_ADAPTER) |
| **Адаптировать после split** | Нет изменений в волне 1; тесты остаются в otel-extension |
| **Новые тесты** | Тест, что `drop-oldest` поведение неизменно при изменении конфигурации queue size через `TracingProperties`; сквозной тест `overflow → drop metric` |
| **PR** | PR-0 (зафиксировать как baseline); тесты не требуют движения в волне 1 |
| **Риск при пропуске** | Потеря export safety → BSP переполнение без backpressure, silent span loss. |

---

### 6.5. JMX / control-plane тесты

**Текущие модули:** `platform-tracing-otel-extension` (server) + `platform-tracing-spring-boot-autoconfigure` (client)

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `PlatformTracingControlTest`, `SamplingControlClientTest`, `RuntimeSamplingControlSmokeTest` (e2e — CRITICAL) |
| **Продублировать до переноса** | `RuntimeSamplingControlSmokeTest` → зафиксировать в PR-0 как baseline; никаких дублирований не требуется — классы остаются на месте до PR-3 (JMX wire spike) |
| **Адаптировать после split** | `PlatformTracingControlTest` → адаптировать после PR-3 (Cross-CL JMX wire spike): заменить typed MBean invoke тесты на Map-based wire тесты; `SamplingControlClientTest` → адаптировать: изменить assertions на новый wire format |
| **Новые тесты** | Тест: JMX client возвращает `Optional.empty()` при недоступном agent (не exception); тест: invalid config через MBean → `invalidConfigCounter` инкрементируется, LKG состояние сохраняется; тест Map wire contract (PR-3) |
| **PR** | PR-0 (baseline lock на `RuntimeSamplingControlSmokeTest`) → PR-3 (wire spike + новые тесты) → PR-10 адаптация |
| **Риск при пропуске** | Нарушение cross-CL boundary semantics → ClassCastException или classloading failure при runtime sampling control. Ops не может управлять sampling ratio через Actuator/JMX. |

---

### 6.6. Spring property binding тесты

**Текущий модуль:** `platform-tracing-spring-boot-autoconfigure`

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `TracingPropertiesBindingTest`, `TracingAutoConfigurationTest`, `SharedDefaultsAlignmentTest`, `PlatformTracingDefaultsProviderTest`, `ExtensionConfigTest`, `SdkModeDetectionAutoConfigurationTest` |
| **Продублировать до переноса** | Не требуется — `TracingProperties` не переносится; остаётся в autoconfigure |
| **Адаптировать после split** | После PR-10 (Desired State Config Reconciler): адаптировать `TracingAutoConfigurationTest` — добавить assertions для `TracingConfigReconciler` bean presence/absence; `SharedDefaultsAlignmentTest` — расширить для новых reconciler defaults |
| **Новые тесты** | Тест, что `platform.tracing.*` binding не нарушается при добавлении reconciler beans; тест на topology vs policy property separation (PR-10) |
| **PR** | PR-0 (зафиксировать baseline binding tests) → PR-10 (адаптировать для reconciler) |
| **Риск при пропуске** | Silent config bind failure → настройки sampling/scrubbing игнорируются в production без ошибок. |

---

### 6.7. Config refresh / RuntimeConfigApplier тесты

**Текущий модуль:** `platform-tracing-spring-boot-autoconfigure`

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `RuntimeConfigApplierTest`, `DualChannelDriftDiagnosticsTest`, `TracingRefreshScopeAutoConfiguration` тест (Requires manual review — точное имя не подтверждено в инвентаре) |
| **Продублировать до переноса** | `RuntimeConfigApplierTest` → до введения `TracingConfigReconciler` зафиксировать поведение RefreshScope→JMX batch apply как characterization тест |
| **Адаптировать после split** | После PR-10: `RuntimeConfigApplierTest` адаптировать — reconciler принимает `TracingDesiredState`; assert что JMX apply через `SamplingControlClient` сохраняет поведение; `DualChannelDriftDiagnosticsTest` → расширить для reconciler drift detection |
| **Новые тесты** | Тест, что при недоступном Config Server reconciler использует last-known-good state, а не сбрасывает конфигурацию; тест ReconcileResult (apply succeeded / drift detected / noop) |
| **PR** | PR-0 (зафиксировать baseline) → PR-10 (reconciler introduction + адаптация) |
| **Риск при пропуске** | Потеря RefreshScope semantics при переходе к reconciler → partial config apply, drift без уведомления. |

---

### 6.8. WebMVC тесты

**Текущий модуль:** `platform-tracing-autoconfigure-webmvc`

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `WebStackIsolationTest`, `DuplicateSpansRegressionMatrixTest` (webmvc часть), `TraceResponseHeaderServletFilterTest`, `ServletOutboundNoSpanArchTest` |
| **Продублировать до переноса** | `WebStackIsolationTest` → запускать в CI на каждом PR — тест должен быть зелёным во все PR от PR-0 до PR-13 |
| **Адаптировать после split** | Нет адаптаций в волне 1 — `platform-tracing-autoconfigure-webmvc` не изменяется структурно |
| **Новые тесты** | Тест, что starter-servlet не тянет `webflux` типы на classpath; тест, что `TraceResponseHeaderServletFilter` не регистрируется в reactive контексте |
| **PR** | PR-0 (baseline), PR-1 (taxonomy guardrails), все последующие PR — тест в CI |
| **Риск при пропуске** | Утечка WebFlux beans в Servlet стек → runtime error в deployment без WebFlux; дублирование HTTP spans в MVC приложении. |

---

### 6.9. WebFlux тесты

**Текущий модуль:** `platform-tracing-autoconfigure-webflux`

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `TracingReactorEagerInitConfigurationTest`, `ReactorContextPropagationIntegrationTest`, `MdcPropagationWebFluxIntegrationTest`, `DuplicateSpansRegressionMatrixTest` (webflux часть) |
| **Продублировать до переноса** | `TracingReactorEagerInitConfigurationTest` → критичен для hot path (Reactor Hooks); зафиксировать в PR-0 |
| **Адаптировать после split** | Нет адаптаций в волне 1 |
| **Новые тесты** | Тест, что `BridgeOtelReactorContextPropagation` не вызывается в Servlet контексте; тест eager init при отсутствии Reactor на classpath (conditional bean) |
| **PR** | PR-0 (baseline), CI на всех PR |
| **Риск при пропуске** | Потеря Reactor context propagation → trace ID не пробрасывается через `publishOn`/`subscribeOn`; MDC пуст в реактивных цепочках. |

---

### 6.10. Starter smoke тесты

**Текущие модули:** `platform-tracing-spring-boot-starter-servlet`, `platform-tracing-spring-boot-starter-reactive`

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `DuplicateHttpSpanAgentSmokeTest` (e2e), `PlatformExtensionAgentSmokeTest` (e2e), `ForceSamplingAgentSmokeTest` (e2e) — smoke-тесты через полный стартер |
| **Продублировать до переноса** | Нет необходимости — стартеры являются dependency-only модулями (0 Java классов) |
| **Адаптировать после split** | При изменении dependency graph стартера (если добавляется новый модуль) — обновить smoke тест на проверку classpath |
| **Новые тесты** | Автоматизированный тест: стартер-servlet не содержит webflux типов в compile classpath; стартер-reactive не содержит servlet типов |
| **PR** | PR-1 (module taxonomy guardrails — enforced by ArchUnit) |
| **Риск при пропуске** | Случайное добавление transitive dependency в стартер ломает BOM-only consumer experience. |

---

### 6.11. E2E тесты

**Текущий модуль:** `platform-tracing-e2e-tests` (42 test classes; требует `-PrunE2e` + Docker)

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | Все 42 класса. Приоритет: `RuntimeSamplingControlSmokeTest`, `ExceptionEventScrubbingE2ETest`, `BspDropOldestSafetyAgentSmokeTest`, `BspOverflowSafetyAgentSmokeTest`, `CustomRuleSmokeE2ETest`, `ReactorContextPropagationAgentE2ETest`, `ClassLoaderVisibilitySpikeE2ETest` |
| **Продублировать до переноса** | `RuntimeSamplingControlSmokeTest` → зафиксировать expected behavior в PR-0 как characterization; `ClassLoaderVisibilitySpikeE2ETest` → результат спайка документировать до PR-3 |
| **Адаптировать после split** | После PR-3 (JMX wire spike): адаптировать тесты, проверяющие JMX invoke semantics; после PR-6/PR-7: адаптировать `RuntimeSamplingControlSmokeTest` если изменится точка сборки sampler |
| **Новые тесты** | E2E тест для dev-only Actuator mutation guard (PR-11): запустить SUT с prod profile, убедиться что POST `/actuator/tracing` возвращает 404/403 |
| **PR** | PR-0 (baseline run задокументирован), PR-3 (CL spike), PR-6 (sampling e2e re-run), PR-7 (scrubbing e2e re-run), PR-11 (actuator prod guard e2e) |
| **Риск при пропуске** | Невидимые regression в agent deployment. E2E — единственный уровень, где тестируется связка SDK→Collector→Jaeger под настоящим Agent. |

---

### 6.12. ArchUnit тесты

**Текущие модули:** `platform-tracing-test` (14 main classes ArchUnit rules), `platform-tracing-spring-boot-autoconfigure` (arch tests), `platform-tracing-autoconfigure-webmvc`

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `TracingArchRulesTest`, `OtelSdkArchRulesTest`, `EscapeHatchArchRuleTest`, `OtelDirectIntegrationArchTest` (autoconfigure), `KafkaOutboundNoSpanArchTest`, `ServletOutboundNoSpanArchTest` |
| **Продублировать до переноса** | Нет дублирования — ArchUnit тесты расширяются, не дублируются |
| **Адаптировать после split** | PR-1: добавить fitness function правила для целевых dependency directions (`core` не должен зависеть от OTel, Spring); PR-4: добавить ArchUnit fitness functions как отдельный PR (PR-4 по roadmap) |
| **Новые тесты** | Правило: `platform-tracing-core` classes do not import OTel packages; правило: `platform-tracing-core` classes do not import Spring packages; правило: `platform-tracing-otel-extension` does not import Spring packages; правило: mutation-capable Actuator operations not reachable from `@ConditionalOnMissingBean(type="dev-profile")` (после PR-11) |
| **PR** | PR-1 (module taxonomy + initial guardrails), PR-4 (ArchUnit fitness functions) |
| **Риск при пропуске** | Тихое нарушение dependency direction → OTel типы просачиваются в core, Spring типы в extension, ломая ClassLoader isolation. |

---

### 6.13. JMH benchmark тесты (контрактные)

**Текущий модуль:** `platform-tracing-bench` (16 JMH классов + 3 contract tests)

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `PerformanceReleaseGateTest` (hard budget contract), `ScrubbingFixtures` (contract test), `PerformanceBudgetsContractTest` (Requires manual review — точное имя не подтверждено) |
| **Продублировать до переноса** | `PerformanceReleaseGateTest` → не дублировать; зафиксировать что он проходит в PR-0 baseline |
| **Адаптировать после split** | После PR-6/PR-7/PR-8: обновить benchmark dependencies в `platform-tracing-bench/build.gradle` — если sampling/scrubbing policy переехали в `core`, JMH должен зависеть от `core`, а не только от `otel-extension` |
| **Новые тесты** | Нет новых contract тестов в волне 1; после PR-12 добавить сравнение `jmhCompareBaseline` |
| **PR** | PR-0 (baseline capture), PR-6/PR-7/PR-8 (обновление deps), PR-12 (final comparison) |
| **Риск при пропуске** | `PerformanceReleaseGateTest` проходит на старом коде, падает на разделённом — регрессия не обнаружена до release. |

---

### 6.14. Macro perf тесты (M0–M10)

**Текущий модуль:** `platform-tracing-perf-tests` (3 SUT classes + scripts/docker; сценарии M0–M10)

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | Все M0–M10 сценарии: `m0.env`, `m5.env`, `m6.env`, `m8.env`, `m10.env`, `m10c.env`, `m10d.env`; `steady-state.js`; `PerfAdminController`; documented M5 FAIL baseline (+48% CPU, +25% RSS) |
| **Продублировать до переноса** | `PerfAdminController` → не переносить в production-подобный код; использовать только внутри `perf-tests`; M0 baseline run → выполнить до PR-0 merge |
| **Адаптировать после split** | После PR-12 (tiered pipeline defaults): перезапустить M5 сценарий, зафиксировать delta vs M0; при изменении JMX bridge (PR-3): обновить `PerfAdminController` если API MBean меняется |
| **Новые тесты** | Нет новых сценариев в волне 1; M5 re-run обязателен после PR-12 |
| **PR** | PR-0 (M0 baseline run), PR-12 (M5 re-run + delta measurement) |
| **Риск при пропуске** | М5 FAIL (+48% CPU, +25% RSS) — уже задокументированный fail — может ухудшиться после tiered pipeline изменений без обнаружения. |

---

### 6.15. Test fixtures / test support

**Текущий модуль:** `platform-tracing-test` (14 main + 17 test classes)

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `InMemorySpanExporter` harness, `JUnit5` extensions (`OtelSdkExtension*`), `SpanProcessorHarness`, `SamplerHarness`, `SemconvStrictTestAutoConfiguration`, `TracingArchRules`, `OtelSdkArchRules` |
| **Продублировать до переноса** | Не требуется — `platform-tracing-test` является shared test infrastructure |
| **Адаптировать после split** | После PR-6/PR-7: при необходимости расширить `SamplerHarness` и `SpanProcessorHarness` для тестирования pure policy в `platform-tracing-core` (без OTel SDK); dependency `api platform-tracing-core` в test module должна оставаться корректной после split |
| **Новые тесты** | Harness без OTel для тестирования core policy (JDK-only); добавить в `platform-tracing-test` если применяется как shared fixture |
| **PR** | PR-5 (перед extraction — расширить harness); PR-6/PR-7 (использовать расширенный harness) |
| **Риск при пропуске** | Тесты core policy вынуждены использовать OTel SDK → нарушение Clean Core изоляции; слабое покрытие pure policy без OTel. |

---

### Сводная таблица по тестовым группам

| Группа | Текущих тестов | Дублировать до | Адаптировать после | PR дублирования | Zero-delete |
|--------|---------------|---------------|-------------------|-----------------|-------------|
| Sampling | ~8 классов | PR-5 | PR-6 | PR-5 | ✅ |
| Scrubbing | ~9 классов | PR-5 | PR-7 | PR-5 | ✅ |
| Validation/enrichment | ~5 классов | PR-5 | PR-8 | PR-5 | ✅ |
| Export safety | ~5 классов + 2 e2e | Не нужно | Нет изменений | — | ✅ |
| JMX / control-plane | ~3 + 1 e2e | PR-0 baseline | PR-3, PR-10 | PR-0 | ✅ |
| Spring property binding | ~9 классов | PR-0 baseline | PR-10 | PR-0 | ✅ |
| Config refresh | ~2 классов | PR-0 baseline | PR-10 | PR-0 | ✅ |
| WebMVC | ~4 класса | CI на всех PR | Нет в волне 1 | — | ✅ |
| WebFlux | ~4 класса | CI на всех PR | Нет в волне 1 | — | ✅ |
| Starter smoke | ~3 e2e | — | PR-1 (ArchUnit) | PR-1 | ✅ |
| E2E (42 класса) | 42 класса | PR-0 baseline | PR-3, PR-6, PR-11 | PR-0 | ✅ |
| ArchUnit | ~10 классов | — | PR-1, PR-4 | PR-4 | ✅ |
| JMH contract | 3 класса | PR-0 baseline | PR-6/7/8 deps | PR-0 | ✅ |
| Macro perf M0–M10 | скрипты | PR-0 (M0 run) | PR-12 (M5 re-run) | PR-0 | ✅ |
| Test fixtures | 14+17 классов | — | PR-5/6/7 расширение | PR-5 | ✅ |

---

## 7. Benchmark and performance preservation plan

### 7.1. Принципы сохранения benchmark'ов

1. **Имена и параметры benchmark'ов не меняются до PR-0 baseline capture.** Переименование JMH класса или изменение `@Param` значений делает baseline несравнимым. Любые такие изменения разрешены только после PR-0 и только с новым baseline capture.

2. **Сравнимость важнее чистоты во время волны 1.** Перемещение кода в другой модуль не должно изменять package import в JMH классе до тех пор, пока не зафиксирован before/after baseline.

3. **JMH baseline capture обязателен до extraction.** Порядок: `./gradlew jmhSaveBaseline` (на зафиксированном hardware профиле) → extraction PR → `./gradlew jmhCompareBaseline`.

4. **Macro perf M0/M5 обязательны до и после tiered pipeline.** M0 = calibration. M5 = production-realistic delta. Оба должны быть выполнены в PR-0 и PR-12.

5. **`PerformanceReleaseGateTest` должен проходить на каждом PR.** Это контрактный тест, не micro benchmark. Failure = block merge.

6. **Зависимости `platform-tracing-bench` обновляются после, а не до extraction PR.** Если `CompositeSampler` переехал в `core`, `build.gradle` в `bench` обновляется только в PR-6, не в PR-5.

---

### 7.2. Benchmark'и, которые должны быть запущены до extraction

#### Таблица обязательных pre-extraction benchmark'ов

| JMH Benchmark | Что измеряет | Связанный production класс | Критичность | Должен выполниться до PR |
|---------------|-------------|---------------------------|-------------|--------------------------|
| `CompositeSamplerBenchmark` | Overhead вызова `CompositeSampler.shouldSample()` на типичном запросе | `CompositeSampler` | **HIGH** | PR-6 (sampling extraction) |
| `CompositeSamplerPolicyBranchesBenchmark` | Стоимость каждой ветки rule chain (KillSwitch, ForceHeader, RouteRatio и т.д.) | `*Rule` классы | **HIGH** | PR-6 |
| `CompositeSamplerConcurrentUpdateBenchmark` | Latency + throughput при concurrent reads и periodic atomic state update | `SamplerStateHolder`, `DomainConfigHolder` | **HIGH** | PR-6 (state holder split риск) |
| `ScrubbingEngineBenchmark` | Throughput rule evaluation engine для типичного span | `scrubbing.engine.*`, `MergeEngine` | **CRITICAL** | PR-7 (scrubbing extraction) |
| `ScrubbingPerRuleBenchmark` | Стоимость каждого `SpanAttributeScrubbingRule` при обработке span attribute | `BuiltInSpanAttributeScrubbingRules`, regex rules | **HIGH** | PR-7 |
| `QueueOfferBenchmark` | Offer throughput: `PlatformDropOldestExportSpanProcessor` vs standard BSP | `PlatformDropOldestExportSpanProcessor` | **HIGH** | PR-0 (export safety baseline) |
| `CompositePipelineBenchmark` | Полная цепочка: scrub → validate → enrich → export; latency per span | `PlatformCompositeSpanProcessor` + chain | **HIGH** | PR-8 (pipeline defaults) |
| `AttributePolicyBenchmark` | Стоимость attribute allow/deny/eager policy eval | `AttributePolicy` | **MEDIUM** | PR-6/PR-8 (core split) |
| `TypedBuilderBenchmark` | Allocation + latency typed span builder usage | `*SpanBuilderImpl` | **MEDIUM** | PR-0 (builder baseline) |
| `HeaderPropagationBenchmark` | inject/extract latency `PlatformTraceControlPropagator` | propagation classes | **MEDIUM** | PR-0 |
| `MdcCorrelationBenchmark` | MDC bridge overhead per span | `RemoteServiceMdc` | **MEDIUM** | PR-0 |
| `TracedAspectBenchmark` | AOP overhead для `@Traced` методов | `TracingAspect` | **MEDIUM** | PR-0 |
| `PerformanceReleaseGateTest` | Контрактная проверка: hard perf budgets из `performance-budgets.yaml` | все hot path классы | **HIGH** | PR-0 и каждый PR после |

> **Примечание:** `StartSpanBenchmark`, `SpanLimitsBenchmark`, `ContextScopeBenchmark` — не упомянуты в приоритетном списке, но присутствуют в инвентаре. **Requires manual review** — уточнить что именно они измеряют перед PR-0.

---

### 7.3. Benchmark'и, которые должны быть запущены после extraction

#### Mapping benchmark → PR

| PR | Benchmark'и для re-run | Ожидаемый результат |
|----|----------------------|---------------------|
| **PR-6** (sampling extraction) | `CompositeSamplerBenchmark`, `CompositeSamplerPolicyBranchesBenchmark`, `CompositeSamplerConcurrentUpdateBenchmark`, `AttributePolicyBenchmark` | `jmhCompareBaseline` — delta в пределах noise; `PerformanceReleaseGateTest` pass |
| **PR-7** (scrubbing extraction) | `ScrubbingEngineBenchmark`, `ScrubbingPerRuleBenchmark`, `CompositePipelineBenchmark` (scrubbing path) | `jmhCompareBaseline` — delta в пределах noise; `PerformanceReleaseGateTest` pass |
| **PR-8** (validation/enrichment extraction) | `CompositePipelineBenchmark` (full pipeline), `AttributePolicyBenchmark`, `TypedBuilderBenchmark` | `jmhCompareBaseline` — delta в пределах noise |
| **PR-12** (tiered pipeline defaults) | **Полный benchmark suite** (`./gradlew jmh`) + `PerformanceReleaseGateTest` + M5 macro perf re-run | E6 evidence: `CompositePipelineBenchmark` delta vs PR-0 baseline документируется как evidence для committee |

#### Правило обновления dependencies bench модуля

```text
PR-6: обновить platform-tracing-bench/build.gradle:
  - добавить jmh dependency на platform-tracing-core (sampling policy)
  - сохранить dependency на platform-tracing-otel-extension (OTel adapter)
  - NOT изменять benchmark class names или @Param values

PR-7: обновить platform-tracing-bench/build.gradle:
  - добавить jmh dependency на platform-tracing-core (scrubbing engine)

PR-8: обновить platform-tracing-bench/build.gradle:
  - добавить jmh dependency на platform-tracing-core (validation/enrichment policy)
```

---

### 7.4. Macro perf сценарии

#### M0 — Host calibration baseline

```text
Назначение: измерение ambient noise текущей машины; устранение HW/OS variance из результатов
Когда запускать: обязательно до PR-0 merge, на dedicated CI runner или тестовой машине
Сценарий: минимальная нагрузка без agent extension — baseline CPU/RSS/latency
Результат: perf-results/m0-<date>.json — зафиксировать в репозитории
Пересчёт: при каждом изменении hardware профиля
Использование: denominator для M5 delta calculation
```

#### M5 — Agent + Extension + Export delta

```text
Назначение: измерение реального overhead platform-tracing agent extension в production-realistic условиях
Когда запускать: до PR-0 (зафиксировать текущий FAIL baseline), после PR-12 (re-run для E6 gate)
Текущий задокументированный результат: FAIL — +48% CPU overhead vs M0, +25% RSS vs M0
Сценарий: полная нагрузка с agent extension — CompositeSampler + ScrubbingSpanProcessor + full processor chain + export
Результат ожидаемый после PR-12: tiered pipeline должен снизить overhead до budget (точные пороги в performance-budgets.yaml)
Требует: docker-compose.perf.yml + k6 steady-state.js + Collector
Использование: основное evidence E6 для migration committee
```

#### M6 / M8 — Degraded mode сценарии

```text
Назначение: проверка поведения при Collector failure / backpressure
Когда запускать: Requires manual review — точные сценарии m6.env и m8.env требуют ручной проверки содержимого
M6: предположительно — Collector недоступен; SafeSpanExporter должен не блокировать application
M8: предположительно — BSP queue saturation; PlatformDropOldestExportSpanProcessor drop-oldest path
Обязательность в волне 1: нет обязательного re-run; должны быть выполнены если PR-12 изменяет export/queue behavior
Использование: деградированный режим evidence; DegradedModeController / CircuitBreaker поведение
```

#### M10 / M10c / M10d — Config reload под нагрузкой

```text
Назначение: проверка runtime config mutation через JMX при live traffic
Когда запускать: до PR-3 (JMX wire spike baseline), после PR-10 (reconciler introduction)
M10: JMX setSamplingRatio под нагрузкой — latency spike допустимый?
M10c: Requires manual review — точный сценарий не задокументирован в инвентаре
M10d: Requires manual review — точный сценарий не задокументирован в инвентаре
Связанные классы: PerfAdminController → JMX invoke → PlatformTracingControl.setSamplingRatio()
Критическое поведение: DomainConfigHolder LKG semantics — invalid config не нарушает текущий трафик
Использование: runtime control plane evidence; основание для PR-3 wire migration decision
```

---

### 7.5. Performance acceptance criteria

Критерии сформулированы консервативно: во время волны 1 цель — сохранить поведение, а не улучшить.

| Критерий | Правило |
|----------|---------|
| **Имена benchmark'ов до PR-0** | Имена JMH классов и `@Param` значения не изменяются до завершения PR-0 baseline capture. Нарушение = блокировка PR-0. |
| **Hot-path extraction без JMH evidence** | Запрещено. Любой PR, перемещающий sampling или scrubbing policy, обязан содержать `jmhCompareBaseline` результат как часть PR description. |
| **PR-12 без E6 macro perf** | PR-12 не мержится без задокументированного M5 re-run результата. Ожидаемое improvement должно достичь budget из `performance-budgets.yaml`. |
| **M5 re-run после tiered pipeline** | Обязателен. M5 FAIL (+48% CPU, +25% RSS) — текущий задокументированный baseline; tiered pipeline defaults (PR-12) — основная возможность его исправить. |
| **Regression выше шума** | Любой `jmhCompareBaseline` результат с delta >5% по throughput или latency p99 требует manual review перед merge. Конкретный threshold определяется профилем в `performance-budgets.yaml`. |
| **`PerformanceReleaseGateTest`** | Должен проходить на каждом PR от PR-0 до PR-13. Failure = block merge, не warning. |
| **Macro perf runner** | `run-perf-scenario.ps1` и `run-official-matrix.ps1` не изменяются в волне 1. Изменения в SUT (`PerfAdminController`) допустимы только если меняется JMX API (PR-3, PR-10). |
| **Hardware профиль** | Все benchmark'и одной волны должны выполняться на одном и том же hardware профиле. Смена машины между PR-0 и PR-12 invalidates baseline comparison. |

---

### 7.6. Output summary

```text
Pass 1 completed.

Generated sections:
  - 6. Test preservation plan
  - 7. Benchmark and performance preservation plan

Sections 8–13 intentionally not generated in this pass.
```

---

*Документ сгенерирован на основе: `platform-tracing-current-codebase-inventory.md` (snapshot 2026-06-11). Целевая архитектура — Clean Core Hybrid. PR sequence: PR-0 through PR-13 per approved roadmap. Не содержит architecture review, не предлагает module collapse или big-bang rewrite.*
