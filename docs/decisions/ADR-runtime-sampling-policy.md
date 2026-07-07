# ADR: Runtime Sampling Policy — ответственность head/tail, порядок решений, safety contract

| Поле | Значение |
|------|----------|
| Статус | **Accepted** |
| Дата | 2026-06-11 |
| Контекст возникновения | Запрос архитекторов «перенять практический опыт управления sampling'ом (OTel extension + runtime sampler) из Alibaba-решений (ARMS/LoongSuite)» |
| Связанные документы | [runtime-sampling-control.md](../tracing/runtime-sampling-control.md) (операционный runbook), [performance-test-matrix.md](../tracing/performance-test-matrix.md) (M10), [performance-budgets.yaml](../tracing/performance-budgets.yaml) (`dynconf-no-stall`) |
| Связанные ADR | [ADR-context-first-propagation.md](ADR-context-first-propagation.md), [ADR-collector-boundary.md](ADR-collector-boundary.md), [ADR-performance-model.md](ADR-performance-model.md) |
| Проверяющие тесты | `CompositeSamplerTest`, `CompositeSamplerEdgeCasesTest`, `SamplerStateHolderTest`, `PlatformTracingControlTest`, `SamplerRuntimeUpdateConcurrencyTest`, e2e `RuntimeSamplingControlSmokeTest`, перф-сценарии M10/M10c/M10d |

## Контекст

Динамическое управление head-sampling'ом реализовано в Фазах 11–14: `CompositeSampler`
(chain of responsibility) читает иммутабельный снимок `SamplerState` из
`SamplerStateHolder` (CAS + last-known-good поверх `DomainConfigHolder`), управление —
JMX `PlatformTracingControl` и Spring-мост `SamplingControlClient`/actuator `tracing`.

Три группы архитектурных решений при этом существовали **только неявно** — в порядке массива
`CompositeSampler.rules[]` и в строении снимка. Для architect review (сравнение с ARMS/LoongSuite)
и для эксплуатации эти решения должны быть зафиксированы формально. Этот ADR **ратифицирует
поведение, уже реализованное и покрытое тестами**, — он не вводит новых механизмов.

Сверка с open-source образцами Alibaba (`Platform_Traces_Examples/src`):

- `loongsuite-java-agent/examples/extension` — тот же SPI-паттерн (`ConfigurableSamplerProvider`
  + `AutoConfigurationCustomizerProvider`), но sampler статический (47 строк, без runtime-управления);
- `one-java-agent` — конфигурация читается один раз при старте (`trace.properties`), динамики нет;
- адаптивный движок ARMS (LFU top-1000, console push) — closed-source, в open-источниках отсутствует.

Вывод сверки: платформа уже реализует «open pattern» в более зрелом виде (атомарный policy-update,
LKG, audit trail, два транспорта); перенимать из Alibaba-кода нечего. Настоящий ADR закрывает
последний разрыв — формализацию политики.

## Решение 1. Разделение ответственности: head (SDK/agent) vs tail (Collector)

### 1.1. Контракт уровней

| Уровень | Где | Отвечает за | Не отвечает за |
|---------|-----|-------------|----------------|
| **Head** (`CompositeSampler`, agent extension) | `shouldSample()` на старте каждого span'а | Cost guard (global/route ratio), kill-switch, drop инфраструктурного шума, форс-запись (`X-Trace-On`, QA), наследование родительского решения | Гарантию сохранения errors/slow/high-priority трейсов |
| **Tail** (Collector, Фаза 16) | `tail_sampling` после сборки трейса | Retention: `errors-always-sample`, `slow-traces`, `platform-high-priority` | Снижение CPU/alloc-нагрузки в приложении (span уже создан и экспортирован) |

Основание: по спецификации OTel `Sampler.shouldSample()` вызывается **до создания span'а** —
ему доступны parent context, traceId, имя, kind, начальные атрибуты, но **не** финальный статус,
исключение или длительность. Head-сэмплер принципиально не может обещать «всегда сохранить
ошибки»: ошибка известна только на `span.end()`. Поэтому приоритетная retention-иерархия
(error → slow → priority), являющаяся industry-консенсусом (Honeycomb Refinery, Datadog,
SkyWalking `forceSampleErrorSegment`, Jaeger), реализуется на tail-уровне — политики в
[otel-collector-gateway-tail-sampling.yaml](../../platform-tracing-collector-config/src/main/resources/platform-tracing/collector/otel-collector-gateway-tail-sampling.yaml),
контракт значений `platform.sampling.reason` охраняется `CollectorPolicyContractTest`
(только множество `PlatformSamplingReasons.EXPORTED`).

### 1.2. Режимы эксплуатации

| Режим | Конфигурация | Назначение |
|-------|--------------|------------|
| **HEAD_COST_GUARD** (default) | ratio < 1.0 на head, tail-политики активны | Production: head ограничивает объём, tail сохраняет важное из прошедшего head |
| **COLLECTOR_RETENTION** | head ratio → 1.0 (runtime, без рестарта), решает tail | Инцидент/отладка: максимальная полнота ценой CPU/export overhead (бюджет — M5w evidence) |

Переключение между режимами — штатная runtime-операция (`setSamplingRatio`), не редеплой.

### 1.3. Явный backlog (не реализуем без отдельного ADR)

**AGENT_END_SPAN_GATE** — решение об экспорте на `span.end()` по статусу/длительности
(SDK-side «errors always» без Collector'а). Это уже не OTel `Sampler`, а processor по
`ReadableSpan`; дороже по CPU/allocations (span создаётся всегда) и дублирует tail-tier.
Реализация — только при подтверждённой prod-потребности «retention без Collector'а».

## Решение 2. Порядок решений и приоритеты (ратификация)

### 2.1. Канонический порядок цепочки

Порядок `CompositeSampler.rules[]` — **нормативный контракт**, его изменение требует
обновления этого ADR:

| # | Правило | Решение | `platform.sampling.reason` |
|---|---------|---------|----------------------------|
| 1 | `KillSwitchRule` | `enabled=false` → DROP всего | `kill_switch` (только метрика) |
| 2 | `HardDropRule` | `url.path` начинается с drop-префикса → DROP | `drop_path` |
| 3 | `ForceHeaderRule` | `X-Trace-On` ∈ `forceRecordValues` → RECORD_AND_SAMPLE | `force_header` |
| 4 | `QaTraceRule` | QA-сигнал → RECORD_AND_SAMPLE | `qa_trace` |
| 5 | `ParentDecisionRule` | наследует решение родителя | `parent_sampled` / `parent_drop` |
| 6 | `RouteRatioRule` | per-route ratio (prefix-match `url.path`) | `route_ratio` / `route_ratio_drop` |
| 7 | `DefaultRatioRule` | глобальный ratio (всегда возвращает решение) | `global_ratio` / `global_ratio_drop` |

### 2.2. Ратифицированные приоритеты (два «спорных момента»)

**P-1. Kill-switch абсолютен: `samplerEnabled=false` гасит всё, включая `X-Trace-On` и QA-сигнал.**
Обоснование: kill-switch — аварийный рычаг SRE «tracing создаёт проблему производственной
системе»; сценарий, в котором отдельный запрос может его обойти заголовком, обесценивает рычаг
и создаёт DoS-вектор. Кто угодно, способный слать заголовки, не должен уметь включать запись.
Тест: `CompositeSamplerEdgeCasesTest.kill_switch_отменяет_даже_force_и_qa`.

**P-2. Drop-path сильнее force-header: `X-Trace-On` не вскрывает пути из `dropPathPrefixes`.**
Обоснование (security-first): drop-префиксы закрывают health/metrics/infrastructure-шум и
потенциально чувствительные пути; возможность форсировать их трассировку заголовком — канал
обхода политики, управляемый клиентом запроса. Для QA-отладки таких путей правильная операция —
runtime-удаление префикса (`setDropPathPrefixes`) с фиксацией в audit trail, а не обход.
Тесты: `CompositeSamplerTest.force_сигнал_не_переопределяет_hard_drop`,
`CompositeSamplerEdgeCasesTest.форсирование_не_отменяет_drop_paths` (+ QA-варианты).

### 2.3. Детерминизм ratio-решений

`DefaultRatioRule`/`RouteRatioRule` делегируют скомпилированному `traceIdRatioBased` —
решение детерминировано по traceId. Следствия: при ratio 0.0 непомеченные запросы дропаются
гарантированно (используется в e2e `RuntimeSamplingControlSmokeTest` и бенчмарках);
один traceId получает одинаковое решение на всех инстансах с одинаковым ratio.

## Решение 3. Runtime Sampling Safety Contract

Инварианты hot-path (`shouldSample()` вызывается на старте **каждого** span'а):

| # | Инвариант | Механизм | Проверка |
|---|-----------|----------|----------|
| C-1 | Никакой перестройки SDK в runtime: `SdkTracerProvider`/BSP/exporter создаются один раз на startup, runtime меняет только данные политики | Стабильный объект `CompositeSampler` + снимок в `SamplerStateHolder` | архитектура Фазы 14; `PlatformSpiAutoconfigureIntegrationTest` |
| C-2 | Чтение конфигурации lock-free: один `volatile`-read снимка на вызов, никаких локов/синхронизации | `DomainConfigHolder.current()` | `CompositeSamplerBenchmark` `@Threads(8)` (деградация под контеншном = нарушение) |
| C-3 | Ноль компиляций/аллокаций конфигурационных объектов на hot-path: ratio-сэмплеры компилируются один раз при построении снимка | `SamplerState.defaultRatioSampler()`/`routeRatioSampler()` | `gc.alloc.rate.norm` в JMH |
| C-4 | Один домен = один атомарный апдейт: валидация **всех** полей до публикации, sampler не видит промежуточных состояний | `updateSamplingPolicy(...)` → CAS-публикация целого `SamplerState` | `PlatformTracingControlTest.updateSamplingPolicy_атомарно_меняет_все_поля_за_один_bump_версии`, `SamplerRuntimeUpdateConcurrencyTest` |
| C-5 | Last-known-good: невалидный апдейт не затирает рабочую конфигурацию (отказ + метрика + rate-limited лог, не fallback к 0%/100%) | `SamplerStateHolder.tryUpdate` + валидация в конструкторе `SamplerState` (side-effect-free для CAS-retry) | `SamplerStateHolderTest`, M10d (invalid-storm) |
| C-6 | Каждый апдейт аудируем: монотонная версия, источник, время | `version`/`source`/`updatedAt` в снимке; `getSamplingConfigVersion`, `getSamplingConfigLastUpdatedSource`, `getConfigAuditTrail` | `ConfigReloadDiagnosticsTest` |
| C-7 | Reload не деградирует hot-path под нагрузкой | — | перф-гейт M10 (`dynconf-no-stall`), M10c (config storm) |

### Known limitations (зафиксированы осознанно)

- **Мост только in-process.** `SamplingControlClient` и perf-admin вызывают MBean через
  локальный `MBeanServer` — единственную стандартную точку обмена application-CL ↔ agent-CL.
  Сигнатура `updateSamplingPolicy` использует `Map<String,Double>` — для **remote** JMX
  (OpenType/MXBean-ограничения) контракт не проектировался; remote JMX не поддерживается
  и не требуется. При появлении требования — отдельный ADR (вариант: параллельная
  операция с массивами `String[]`/`double[]`, без слома текущей).
- **Два транспорта, не три.** JMX (канонический) и actuator `tracing` (HTTP-зеркало для k8s).
  Третий механизм (file-watch/polling/OpAMP) не вводится без явного запроса ops — см. «Отклонённые альтернативы».

## Отклонённые альтернативы

| Альтернатива | Причина отказа | Триггер пересмотра |
|--------------|----------------|---------------------|
| `jaeger_remote` sampler (штатный в OTel Java: `otel.traces.sampler=jaeger_remote`) | Требует sampling-strategy endpoint (control plane) и конфликтует с `otel.traces.sampler=platform`; композиция «remote стратегия → `SamplerStateHolder`» не спроектирована | Запрос ops на централизованное fleet-управление; тогда первый кандидат — именно он (конфиг + адаптер, не своя разработка) |
| File-watch/HTTP-polling адаптер (паттерн `domstolene/da-otel-agent`) | Третий транспорт без потребности; риск второго authoritative-источника конфигурации | Запрос ops «push без JMX/HTTP-доступа к инстансу» |
| OpAMP / contrib `dynamic-control` | Протокол Beta, `opamp-java` в разработке; embed contrib JAR в prod без ADR запрещён | Стабилизация OpAMP Java + OTel Telemetry Policy spec |
| Adaptive sampling (ARMS LFU, EMA `dynsampler-go`, PID-контроллеры) | Требует feedback loop, hysteresis, fleet-координацию, защиту от осцилляций; open-реализации — Go/Collector-tier; нет prod-боли | Подтверждённая prod-боль «ручные route ratios не справляются с динамикой трафика» |
| Авто-push из Spring `Environment`/RefreshScope в agent holder | Второй authoritative-источник состояния; явная операторская операция (JMX/actuator) с audit trail предпочтительнее тихой синхронизации | Запрос ops на GitOps-управление sampling'ом |

## Последствия

- Изменение порядка `rules[]`, приоритетов P-1/P-2 или инвариантов C-1…C-7 — только через
  обновление этого ADR с ревью владельца компонента.
- Runbook операций — [runtime-sampling-control.md](../tracing/runtime-sampling-control.md).
- Сравнительная позиция для architect review: платформа покрывает функциональность
  «runtime sampling management» ARMS-консоли открытыми средствами (JMX/actuator + audit),
  за вычетом adaptive-движка, который отнесён в backlog с явным триггером.
