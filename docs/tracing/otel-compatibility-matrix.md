# OpenTelemetry Compatibility Matrix

Матрица совместимости платформенного стартера трассировки. Отвечает на вопрос: **какие версии Spring Boot / Java / OTel SDK / OTel Agent считаются поддерживаемыми**.

Для traceability (требование → класс) см. [traceability.md](./traceability.md).

Версии Gradle pins — единственный источник истины: [gradle.properties](../../gradle.properties). **Новые version properties не добавляем.**

## Version pins (Gradle)

| Property | Значение (v0.1.0) | Назначение |
|----------|-------------------|------------|
| `openTelemetryBomVersion` | 1.62.0 | OTel SDK/API BOM |
| `openTelemetryInstrumentationBomVersion` | 2.28.1 | Instrumentation BOM; **source of truth + expected Java Agent version** |
| `openTelemetryInstrumentationAlphaVersion` | 2.28.1-alpha | Java Agent extension API |
| `springBootVersion` | 3.5.5 | Spring Boot BOM |

Gradle task `verifyOtelBomAlignment` проверяет:

```
openTelemetryInstrumentationAlphaVersion == openTelemetryInstrumentationBomVersion + "-alpha"
```

## Java Agent — derived operational contract

```
expectedJavaAgentVersion = openTelemetryInstrumentationBomVersion
```

OpenTelemetry Java Agent поставляется runtime-инфраструктурой и **не входит** в Gradle dependency graph. Отдельной Gradle property для Agent в v0.1.0 нет. Поддерживаемая версия Agent выводится из `openTelemetryInstrumentationBomVersion`. Helm/Docker/platform launcher обязаны использовать тот же major.minor.patch.

Gradle **не** проверяет runtime Agent. Синхронизация — ответственность DevOps (checklist при каждом апгрейде instrumentation BOM).

## Supported in v0.1.0 (validated in CI)

| Компонент | Версия | Примечание |
|-----------|--------|------------|
| Java | **21** | Toolchain в корневом `build.gradle` |
| Spring Boot | **3.5.5** | Pin в `gradle.properties`; линия 3.5.x совместима по Spring policy, **platform validated on 3.5.5** |
| OTel SDK BOM | **1.62.0** | `openTelemetryBomVersion` |
| OTel instrumentation BOM | **2.28.1** | `openTelemetryInstrumentationBomVersion` |
| OTel Java Agent | **2.28.1** | `expectedJavaAgentVersion`; **validated on 2.28.1** |

## Patch-line compatibility (OTel Java Agent)

По [OTel instrumentation VERSIONING](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/VERSIONING.md): patch releases — bug-fix only; non-experimental configuration properties — breaking change policy. Линия **2.28.x совместима по OTel versioning contract**.

Platform policy:

- **Validated only on 2.28.1**
- Production use других patch в линии 2.28.x требует smoke validation (P1 e2e)

Для board: не «supports all 2.28.x without testing», а «2.28.x compatible by OTel contract; platform validated on 2.28.1».

## Not supported in v0.1.0

| Компонент | Версии | Статус |
|-----------|--------|--------|
| Java | 11, 17 | **Not supported** — CI не прогоняется |
| Spring Boot | 2.7.x, 3.0–3.4, 4.x | **Not supported** — CI не прогоняется |

Любая версия, не перечисленная в **Supported in v0.1.0**, считается unsupported до явного расширения матрицы архитекторами и регрессионного прогона.

## Правило обновления

1. Правка **только существующих** properties в `gradle.properties` (ревью архитекторов + регрессионный прогон).
2. `./gradlew verifyOtelBomAlignment` — compile-time alignment alpha/BOM.
3. Checklist DevOps: синхронизировать runtime Java Agent с `openTelemetryInstrumentationBomVersion` (текущий: **2.28.1**):
   - Maven: `io.opentelemetry.javaagent:opentelemetry-javaagent:2.28.1`
   - Helm/Docker/platform launcher: `-javaagent:/otel/opentelemetry-javaagent.jar` (образ должен содержать JAR этой версии)
   - Extension API: `2.28.1-alpha` (lockstep с instrumentation BOM)
   - MDC appender: `opentelemetry-logback-mdc-1.0:2.28.1-alpha`
4. P1 e2e smoke — required before production rollout (не блокирует architecture sign-off).
   - `./gradlew :platform-tracing-e2e-tests:test -PrunE2e` на Gentoo Docker (`DOCKER_HOST=tcp://192.168.100.70:2375`)
   - Re-validated: **2026-06-08**, 26/26 green, Agent 2.28.1 confirmed

## 1.62.0 impact assessment (tracing-репозиторий)

Оценка применимости изменений OTel SDK 1.62.0 к этому (tracing) модулю. Проверено по коду (`Grep` по затронутым символам).

| Изменение 1.62.0 | Применимость | Действие |
|---|---|---|
| Security #8378 (GHSA-rcgg-9c38-7xpx): лимиты W3C baggage на extract | **Применимо** (через делегирование `FilteringBaggagePropagator`) | Наследуется автоматически; regression `FilteringBaggagePropagatorBaggageLimitsTest` |
| OTLP `JdkHttpSender` bounded thread pool (#8276) | Применимо (авто) | Без кода — выигрыш надёжности экспорта |
| BREAKING Prometheus: host `0.0.0.0`→`localhost`, unit `"1"`→`"ratio"` | **N/A** — метрики/Prometheus вне этого repo | — |
| BREAKING Declarative config → отдельный артефакт `opentelemetry-sdk-extension-declarative-config` (#8265) | **N/A** — нет зависимости | — |
| BREAKING incubating `EnvironmentGetter/Setter` нормализация (1.61.0) | **N/A** — нет incubator-зависимостей | — |
| `PeriodicMetricReaderBuilder.setMaxExportBatchSize` (#8296) | **N/A** — нет метрик-ридеров | — |
| B3/OtTrace/Jaeger (`opentelemetry-extension-trace-propagators`) | Только `testImplementation`; в production не конфигурируется | — |

## Extension SPI surface (Фаза 15)

`platform-tracing-otel-javaagent-extension` подключается к OTel Java Agent / SDK autoconfigure через `META-INF/services`.
Все интерфейсы — из артефакта `opentelemetry-sdk-extension-autoconfigure-spi` (версия из OTel SDK BOM **1.62.0**),
загружаются `ExtensionClassLoader`'ом Агента из self-contained `agentExtensionJar` (classifier `agent`).

| SPI-интерфейс | Реализация платформы | Activation | Стабильность SPI | Fallback при невидимости named SPI |
|---|---|---|---|---|
| `AutoConfigurationCustomizerProvider` | `PlatformAutoConfigurationCustomizer` | всегда (customize-канал) | **stable** | — (базовый путь) |
| `ResourceProvider` (`ConditionalResourceProvider`) | `SafeResourceProvider`/`PlatformResourceProvider` | всегда | `ConditionalResourceProvider` — **internal/unstable** | деградирует до обычного `ResourceProvider` (per-key omit) |
| `ConfigurableSamplerProvider` | `PlatformSamplerProvider` (`platform`) | `otel.traces.sampler=platform` (явный opt-in) | **stable** | inline `addSamplerCustomizer` (compose-over-existing) — работает без named |
| `ConfigurablePropagatorProvider` | `InboundTraceControlPropagatorProvider` (`platform-trace-control`) | `otel.propagators=...,platform-trace-control` (дефолт дописывается ENV-aware) | **stable** | дефолт-customizer + baggage-wrapper в inline-канале |

Примечания:

- `ConfigurableSpanExporterProvider` **не реализуем** — экспорт через стандартный OTLP→Collector + `SafeSpanExporter`-wrapper
  ([ADR-safe-span-exporter-v1](../decisions/ADR-safe-span-exporter-v1.md)).
- Sampler-дефолт `otel.traces.sampler=platform` **не** устанавливается (compose-over-existing остаётся default,
  [ADR-sampler-compose](../decisions/ADR-sampler-compose.md)); named — явный opt-in.
- Idempotency-guard sampler — через маркер `PlatformManagedSampler` (не статический флаг), корректен при многократной
  autoconfigure-сборке в одном JVM ([ADR-named-spi-sampler-propagator](../decisions/ADR-named-spi-sampler-propagator.md)).
- Риск видимости named SPI из `ExtensionClassLoader` для Agent 2.x — штатно поддерживается (официальный `examples/extension`);
  подтверждается e2e `PlatformSpiAgentSmokeTest` (fail-fast). Ср. [ADR-classloader-visibility-spike-finding](../decisions/ADR-classloader-visibility-spike-finding.md).

### Extension version ↔ supported agent ↔ SDK SPI

| Extension (platform) | OTel Java Agent | SDK autoconfigure-spi | Статус |
|---|---|---|---|
| v0.1.0 | **2.28.1** (`expectedJavaAgentVersion`) | **1.62.0** (из SDK BOM) | validated |
| v0.1.0 | 2.28.x (прочие patch) | 1.62.0 | compatible by OTel contract; требует smoke (P1 e2e) |

Привязка к существующим pins ([gradle.properties](../../gradle.properties)); **новые version properties не добавляем**
([ADR-otel-direct-integration](../decisions/ADR-otel-direct-integration.md)).

## Связанные документы

- [ADR-otel-direct-integration.md](../decisions/ADR-otel-direct-integration.md)
- [ADR-named-spi-sampler-propagator.md](../decisions/ADR-named-spi-sampler-propagator.md)
- [ADR-sdk-mode-detection.md](../decisions/ADR-sdk-mode-detection.md)
- [ADR-config-precedence.md](../decisions/ADR-config-precedence.md)
