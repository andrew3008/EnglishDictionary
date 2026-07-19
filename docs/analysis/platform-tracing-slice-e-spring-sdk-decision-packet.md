# Slice E: Spring Without Agent Decision Packet

> Дата: 2026-07-19  
> Ветка: `feature/runtime-control-hardening`  
> Статус: `OPEN / ARCHITECTURE DECISION REQUIRED`  
> Блокирует: завершение Slice E и переход `E -> F`

## 1. Решение, которое требуется

Нужно утвердить единственную production-семантику режима Spring без Java Agent:

1. starter самостоятельно владеет полноценным OpenTelemetry SDK bootstrap;
2. платформа сохраняет agent-first модель, а Spring без Agent требует внешний SDK runtime
   либо завершается fail-fast при включённой трассировке.

До решения production-код не должен создавать частичный SDK и не должен молча считать
no-op runtime рабочим режимом `STARTER`.

## 2. Подтверждённые факты

| Evidence | Факт |
|---|---|
| `docs/decisions/ADR-otel-direct-integration.md` | Принята agent-first модель; SDK-only path без Agent отложен как P2 |
| `docs/decisions/ADR-sdk-mode-detection.md` | Starter никогда не создаёт `SdkTracerProvider`; `STARTER` описан как consume-mode |
| published starter runtime graph | Есть `opentelemetry-api`; нет `opentelemetry-sdk`, exporter, actuator OTel autoconfigure и `platform-tracing-otel-extension` |
| `SdkModeResolver` | `AUTO` без marker, external bean и functional global выбирает `STARTER` |
| `TracingCoreAutoConfiguration` | Нефункциональный global переводит runtime в `UNAVAILABLE`, затем публикуется `NoopTraceOperations` |
| `SdkModeDetectionAutoConfigurationTest` | Текущее поведение `STARTER -> NoopTraceOperations` зафиксировано тестом |
| authoritative plan §7.1 | Для Spring без Agent требует starter-owned SDK bootstrap |

Следствие: текущая модель одновременно утверждает, что NoOp разрешён только для `DISABLED`,
и возвращает NoOp для `STARTER`. Режим диагностически выглядит выбранным, но telemetry не
создаётся и не экспортируется.

## 3. Option A: Starter-Owned SDK

Для production-grade реализации недостаточно создать `SdkTracerProvider.builder().build()`.
До кода должны быть утверждены:

- зависимости и publication metadata SDK/exporter модулей;
- ownership `Resource`, sampler, propagators и platform span processors;
- OTLP endpoint, TLS, credentials, retry и timeout configuration source;
- `GlobalOpenTelemetry` registration и конфликт с пользовательским bean/global;
- startup/shutdown/force-flush lifecycle;
- parity с agent-side SPI и запрет двойной instrumentation;
- health/actuator diagnostics и packaged Spring-without-Agent E2E до Collector.

Это отдельный product capability и отдельный reviewable PR, а не минимальная правка Slice E.
Потребуется supersede двух принятых ADR.

## 4. Option B: Agent-First Fail-Fast

Рекомендуемое решение для текущего rollout на 80 сервисов:

- Spring + Agent остаётся основным production mode;
- Spring без Agent поддерживается только при явно предоставленном functional external
  `OpenTelemetry` runtime, владелец которого отвечает за SDK lifecycle и export;
- `AUTO` без agent marker и без functional external runtime завершает startup диагностируемой
  ошибкой, если `platform.tracing.enabled=true`;
- `DISABLED` остаётся единственным режимом с no-op facade;
- `STARTER` удаляется из enum/configuration surface как несуществующий ownership mode;
- starter не получает SDK/exporter dependencies и не регистрирует `OpenTelemetry` bean.

Предлагаемое fail-fast сообщение:

```text
Platform tracing is enabled, but neither an OpenTelemetry Java Agent nor a functional external OpenTelemetry runtime was detected; attach the Agent, provide an OpenTelemetry bean, or set platform.tracing.sdk.mode=DISABLED
```

## 5. Сравнение

| Критерий | Option A | Option B |
|---|---|---|
| Соответствие действующим ADR | Нет, требуется supersede | Да |
| Риск тихой потери telemetry | Высокий до полного exporter wiring | Устранён fail-fast семантикой |
| Новые runtime dependencies | SDK + exporter + autoconfigure | Нет |
| Риск double SDK/instrumentation | Существенный | Минимальный, guard сохраняется |
| Стоимость и размер изменения | Высокие, отдельный capability | Низкие, локальный semantic fix |
| Rollback | Сложный | Простой |

## 6. Proposed Decision

**APPROVE OPTION B: AGENT-FIRST FAIL-FAST.** Это сохраняет принятые ADR, не вводит второй
bootstrap plane и делает отсутствие telemetry явной startup-ошибкой вместо скрытого degraded
режима.

После approval Slice E выполняет только следующий intentional behavioral/API delta:

1. удалить `SdkMode.STARTER` и связанные configuration/documentation references;
2. изменить `AUTO` без runtime на fail-fast;
3. сохранить `EXTERNAL`, `AGENT`, `DISABLED` и существующую mismatch matrix;
4. обновить `ADR-sdk-mode-detection.md`, §7.1 плана и operator diagnostics;
5. заменить characterization `STARTER -> NoOp` на startup-failure test;
6. повторить Spring context matrix, packaged Agent E2E, build и architecture gates.

## 7. Approval Form

Архитектор должен выбрать одну формулировку:

```text
Slice E Spring-without-Agent: APPROVED OPTION B AS PROPOSED.
```

либо предоставить для Option A точный SDK/exporter/resource/propagator/lifecycle ownership
contract и разрешить supersede действующих ADR. До этого Slice E остаётся OPEN.
