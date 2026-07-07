# ADR: Named SPI — platform sampler и platform-trace-control propagator (Фаза 15)

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-06-09 |
| Стек | OTel SDK **1.62.0**, Agent **2.28.1**, Spring Boot **3.5.5**, Java 21 |
| Связанные | [ADR-sampler-compose.md](ADR-sampler-compose.md), [ADR-context-first-propagation.md](ADR-context-first-propagation.md), [ADR-otel-direct-integration.md](ADR-otel-direct-integration.md), [ADR-classloader-visibility-spike-finding.md](ADR-classloader-visibility-spike-finding.md) |
| Требования | `Traces Requests.txt` §3 (передача контекста), §4 (конфигурируемость без кода) |

## Контекст

До Фазы 15 платформенный sampler и `PlatformTraceControlPropagator` подключались **только** через
inline-customizer'ы (`addSamplerCustomizer` compose-over-existing; `addPropagatorCustomizer` always-append).
В чистом agent/autoconfigure-режиме оператор не мог включить платформенную политику стандартным
`otel.traces.sampler=...` / `otel.propagators=...` — требование §4 «конфигурируемость без изменения кода»
закрывалось не полностью. Нужно сделать платформенные компоненты **discoverable по имени** через официальные
SPI, не ломая существующий compose-путь и не создавая двойной регистрации/композиции.

## Решение

### Named sampler `platform` (`ConfigurableSamplerProvider`)

- `PlatformSamplerProvider implements ConfigurableSamplerProvider`, `getName()="platform"`,
  `createSampler(config)` делегирует в общий `PlatformSamplerBuilder.build(config)` — **единый источник истины**
  с inline-путём (force/QA/drop/route/ratio из `ConfigProperties`, `SafeSampler(CompositeSampler(holder), fallback)`).
- **Дефолтом `otel.traces.sampler=platform` НЕ ставим** — compose-over-existing остаётся default
  ([ADR-sampler-compose](ADR-sampler-compose.md)); named — явный opt-in. Рантайм существующих деплоев не меняется.

### Idempotency-guard (reconciliation named ↔ inline)

При `otel.traces.sampler=platform` named provider строит платформенный sampler, после чего SDK применяет
`addSamplerCustomizer`. Без guard'а получили бы **двойную композицию**. Решение — **маркер-интерфейс**
`PlatformManagedSampler` (реализуют `SafeSampler` и `CompositeSampler`):

- inline-customizer: если `existing` — `PlatformManagedSampler` → переиспользует его `CompositeSampler`/
  `SamplerStateHolder` для JMX и возвращает `existing` без повторной обёртки;
- defense-in-depth: если маркер не виден на верхнем уровне — распознаём по `getDescription()` (содержит
  `PlatformRuleBasedSampler`).

**Почему НЕ статический `AtomicBoolean`** (контр-аргумент к ревью архитектора): SDK autoconfigure может
собираться **несколько раз в одном JVM** (unit/slice-тесты, несколько `AutoConfiguredOpenTelemetrySdk`).
Статический флаг выставился бы на первой сборке и ошибочно пропустил последующие → скрытый баг и flaky-тесты.
Маркер-интерфейс stateless и корректен при многократной конфигурации.

### Named propagator `platform-trace-control` (`ConfigurablePropagatorProvider`)

- `PlatformTraceControlPropagatorProvider implements ConfigurablePropagatorProvider`,
  `getName()="platform-trace-control"`, `getPropagator(config)=PlatformTraceControlPropagatorBuilder.build(config)`
  (имена заголовков из `PropagationDefaults`, обёрнут в `SafeTextMapPropagator`).
- Строго **дополняет** W3C `tracecontext`/`baggage` (зона Агента), не заменяет
  ([ADR-context-first-propagation](ADR-context-first-propagation.md)).

### Дефолт propagator — `addPropertiesCustomizer`, а НЕ `addPropertiesSupplier`

`PlatformPropagatorsDefaultsCustomizer` дописывает `platform-trace-control` в `otel.propagators` **только если**
отсутствует и не `none`. Реализован как `addPropertiesCustomizer(Function<ConfigProperties, Map<String,String>>)`:

- **Почему не `addPropertiesSupplier`** (правка по ревью архитектора, принято): supplier имеет низший приоритет
  и не видит итоговый merge — если оператор задал `otel.propagators` через ENV/sysprop, supplier-дефолт был бы
  **проигнорирован** и платформенный пропагатор не добавился бы. Customizer читает **уже смерженный**
  `ConfigProperties` (вкл. `OTEL_PROPAGATORS`) и возвращает override — корректное «add if absent».
- Эталон upstream — `ResourceProviderPropertiesCustomizer`.

Always-append `PlatformTraceControlPropagator` из `addPropagatorCustomizer` **удалён** (его роль взяли named
provider + property-customizer); в `addPropagatorCustomizer` осталась только baggage-обёртка
`FilteringBaggagePropagator`.

### Поведение

| `otel.propagators` | Результат |
|---|---|
| не задан | `tracecontext,baggage,platform-trace-control` |
| явный без platform | дописывается `platform-trace-control` |
| явный с platform | один экземпляр (без дубля) |
| `none` | платформенный не добавляется |

| `otel.traces.sampler` | Результат |
|---|---|
| `platform` | named provider строит CompositeSampler; inline-customizer — no-op (guard) |
| не задан / `parentbased_*` / canary | compose-over-existing (текущее поведение) |

### Runtime «на лету»

И named, и inline путь используют **один** `SamplerStateHolder` (через JMX) — ratio/политика меняются без
рестарта (требование §4); named provider не фиксирует ratio на старте намертво.

### Classloader-видимость (риск, зафиксирован)

OTel Agent 2.x штатно вызывает `ServiceLoader` для `ConfigurableSamplerProvider`/`ConfigurablePropagatorProvider`
из `ExtensionClassLoader` (официальный `examples/extension`). Подтверждается e2e `PlatformSpiAgentSmokeTest`
(fail-fast) + `verifyExtensionSpiRegistration`. **Fallback**: если named SPI не виден — inline-customizer уже
работает и остаётся рабочим путём (рантайм сохраняется). Reflection-регистрация named SPI не применяется (не
предусмотрена API). Ср. [ADR-classloader-visibility-spike-finding](ADR-classloader-visibility-spike-finding.md).

## Последствия

- `otel.traces.sampler=platform` и `otel.propagators=...,platform-trace-control` работают в agent-режиме.
- 4 SPI в `META-INF/services` (+`verifyExtensionSpiRegistration`).
- Поведение существующих деплоев не изменилось (sampler-дефолт прежний; propagators-дефолт лишь расширяется с guard).

## Альтернативы (отклонено)

- `setSampler(...)` в tracer-customizer — отключает все `addSamplerCustomizer` (SPI-javadoc), ломает compose.
- Статический `AtomicBoolean` для idempotency — некорректен при многократной autoconfigure-сборке.
- `addPropertiesSupplier` для дефолта propagator — игнорируется при ENV-вводе.
- Дефолт `otel.traces.sampler=platform` в v0.1.0 — меняет рантайм существующих деплоев без необходимости.
