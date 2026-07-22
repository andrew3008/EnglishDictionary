# ADR: прямая интеграция с OpenTelemetry SDK/SPI (direct integration, no source copy)

| Поле | Значение |
|------|----------|
| Статус | **Принято; уточнено B1-C 2026-07-20** |
| Дата | 2026-05-25 |
| Контекст | Architecture sign-off, guardrails P0 |
| Стек | OTel SDK BOM **1.61.0**, OTel instrumentation BOM **2.27.0**, OTel Java Agent **2.27.0** |

## Проблема

Архитекторы требуют строить платформенный стартер как **тонкий Spring starter поверх официального OpenTelemetry SDK и autoconfigure SPI**, а не как собственный tracing runtime. «Прямое» означает direct dependency, direct API usage, direct SPI implementation — **не** копирование исходников `BatchSpanProcessor`, `SpanLimits`, `ResourceProvider` и прочих классов SDK.

Нужно формально зафиксировать инвариант и защитить его от регрессий на CI.

## Решение

1. **OTel BOM + API + SDK + autoconfigure SPI** — единственная extension surface для platform policy layer.
2. **Controlled Agent-first production model** — bytecode-инструментация и SDK принадлежат
   подписываемой Controlled Platform Agent Distribution; произвольный stock Agent,
   application SDK и `opentelemetry-spring-boot-starter` не являются поддерживаемыми runtime.
3. **Platform-компоненты** реализуют официальные SPI (`SpanProcessor`, `Sampler`, `ResourceProvider`, `AutoConfigurationCustomizerProvider`) в модуле `platform-tracing-otel-javaagent-extension`.
4. **Upstream-компоненты не форкаем:** `BatchSpanProcessor`, OTLP exporter, W3C propagators, `SdkTracerProvider` — только через официальный SDK/Agent.
5. **Guardrails на CI:** ArchUnit (`OtelDirectIntegrationArchTest`) + Gradle task `verifyOtelBomAlignment`.
6. Spring starter предоставляет facade/adapters/diagnostics и не создаёт `SdkTracerProvider`.
   Production mode surface и gate separation определены
   [ADR-sdk-mode-detection.md](ADR-sdk-mode-detection.md).
7. Architecture Committee approval communicated by the project owner: `CP-E APPROVED`,
   `SLICE E CLOSED`, `SLICE F UNBLOCKED`. `RG-CONTROLLED-AGENT OPEN`; production rollout forbidden.

## Version governance

Отдельной Gradle property `openTelemetryJavaAgentVersion` в v0.1.0 **нет** (architecture decision).

```
expectedJavaAgentVersion = openTelemetryInstrumentationBomVersion
```

| Слой | Ответственность |
|------|----------------|
| **Gradle (P0)** | `openTelemetryInstrumentationAlphaVersion == openTelemetryInstrumentationBomVersion + "-alpha"` |
| **Documentation / DevOps (P0)** | Matrix + runbook: runtime Agent == `openTelemetryInstrumentationBomVersion` |
| **E2E (P1, required before rollout)** | Assert `effectiveJavaAgentVersion == expectedJavaAgentVersion` |
| **Actuator (P1, optional)** | Diagnostic compatibility block; не заменяет e2e smoke |

> No dedicated `openTelemetryJavaAgentVersion` property is introduced in v0.1.0 by architecture decision. Java Agent version is treated as runtime infrastructure configuration and must be synchronized with `openTelemetryInstrumentationBomVersion` through compatibility matrix, Helm/Docker runbooks and E2E smoke validation.

**Board wording:** P1 runtime smoke is **not required for architecture sign-off**, but **required before production rollout**. Sign-off ≠ production-ready.

## Альтернативы

| Альтернатива | Почему отвергнута |
|--------------|-------------------|
| Копирование классов OTel SDK в репозиторий | Fork/merge-debt, расхождение с upstream |
| `opentelemetry-spring-boot-starter` вместо Agent | Противоречит agent-first; дублирует bytecode instrumentation |
| Отдельная property для Java Agent | Архитекторы против расширения `gradle.properties` |
| Кастомный `BatchSpanProcessor` до измерений | Преждевременное усложнение; сначала стандартный BSP + OTLP |

## Deferred (P2)

- `PlatformTraceControlPropagator` — если header-capture в `CompositeSampler` недостаточен.
- ~~`SafeSpanExporter` — после e2e/load validation.~~ **Реализован (Фаза 10)** — см. [ADR-safe-span-exporter-v1.md](./ADR-safe-span-exporter-v1.md).
- ~~`DropOldestQueueSpanProcessor` — только при подтверждённом gap стандартного BSP.~~ **Реализован** как `PlatformDropOldestExportSpanProcessor` — см. [ADR-drop-oldest-export-processor-v1.md](./ADR-drop-oldest-export-processor-v1.md).
- `PrioritySpanProcessor` — **отклонён** (Фаза 10): приоритизация на Collector tail-sampling во избежание orphaned spans, см. [ADR-safe-span-exporter-v1.md](./ADR-safe-span-exporter-v1.md).
- SDK-only path без Java Agent отклонён решением B1-C для production starter; test-only SDK
  допустим только в непубликуемых fixtures/source sets.

## Audit evidence

- Initial audit: 2026-05-25 — platform SPI-компоненты реализуют официальные интерфейсы; локальных копий SDK-классов нет.
- **Permanent guardrails:**
  - `OtelDirectIntegrationArchTest` (ArchUnit Rules 1–4)
  - `./gradlew verifyOtelBomAlignment`
- Опциональная ручная проверка (board appendix):

```bash
grep -R "class .*BatchSpanProcessor" platform-*/src/main/java
grep -R "class .*SdkTracerProvider" platform-*/src/main/java
grep -R "class .*SpanLimits" platform-*/src/main/java
```

Source of truth — ArchUnit + Gradle task, не одноразовый grep.

## Связанные артефакты

- [otel-compatibility-matrix.md](../tracing/otel-compatibility-matrix.md)
- [ADR-composite-processor.md](./ADR-composite-processor.md)
- [ADR-sampler-compose.md](./ADR-sampler-compose.md)
- [ADR-platform-resource-override.md](./ADR-platform-resource-override.md)
- `OtelDirectIntegrationRules.java` / `OtelDirectIntegrationArchTest.java`
- `PlatformAutoConfigurationCustomizer.java`
