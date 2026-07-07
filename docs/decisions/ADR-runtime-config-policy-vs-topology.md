# ADR: Runtime-конфигурация трассировки — policy vs topology (Фаза 14)

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-06-09 |
| Контекст | Фаза 14 «Dynamic configuration»; pre-production (в проде не развёрнуто) |
| Стек | Spring Boot 3.5.5, OTel Java Agent 2.28.x, OTel SDK 1.62.0, Java 21 |
| Related | [ADR-dual-channel-properties-v0.1.md](./ADR-dual-channel-properties-v0.1.md), [ADR-otel-direct-integration.md](./ADR-otel-direct-integration.md), [ADR-processor-errors-metric.md](./ADR-processor-errors-metric.md) |

## Контекст

Требование Фазы 14 — менять часть настроек трассировки «на лету» без рестарта (sampling ratio,
log level, enable/disable, политики). Архитектура agent-first: SDK конфигурируется на старте
Агента (до `main()` Spring), Spring-бины живут в отдельном classloader. Полная перестройка SDK в
рантайме небезопасна и не требуется.

Нужно зафиксировать: что является **runtime-mutable policy**, а что — **startup-only topology**;
как устроен общий механизм атомарного обновления; и какие риски (JMM, classloader, Spring lifecycle,
ReDoS, startup-window) учтены.

## Решение

### 1. Policy (runtime-mutable) vs Topology (startup-only)

| Категория | Примеры | Изменение в рантайме |
|-----------|---------|----------------------|
| **Policy** (runtime) | sampling ratio, route-ratios, force/QA/drop-paths, scrubbing enable + правила, validation enable/strict, гранулярные toggles (facade/propagation/sampling/export), log level | **Да** — через immutable snapshot + atomic publish |
| **Topology** (startup-only) | exporter endpoint, processor chain, span limits, propagator chain, BSP queue size/batch/timeout, resource attributes | **Нет** — смена = k8s rolling update / Collector reload |

Обоснование topology=startup-only: OTel autoconfigure применяет конфигурацию один раз на старте
(«configurations ingested at application startup and remain unchanged»); смена endpoint/limits —
инфраструктурный concern (k8s/Collector), а не in-process surgery. Controlled-rebuild topology —
backlog (вне scope Фазы 14).

### 2. Один механизм: `DomainConfigHolder<T>` + immutable snapshot + atomic publish

- Общий контракт в `platform-tracing-api` (виден обоим classloader'ам): один класс
  `DomainConfigHolder<T extends Versioned>` (`current()`, `tryUpdate(...)`, `version()`).
  Без отдельных классов `ConfigVersion`/`LastKnownGoodConfigHolder` («no over-engineering»).
- Каждый домен (sampler / scrubbing / validation) — свой holder рядом со своим consumer'ом в
  classloader'е Agent extension (per-domain topology + LKG, см. Holder Topology).
- Версия — `long`-поле снимка; монотонность через CAS.
- **Last-known-good обязателен**: невалидный апдейт сохраняет предыдущий валидный снимок.

### 3. Контракт side-effect-free для CAS

Функция построения снимка (`UnaryOperator<T>`) и валидатор (`Predicate<T>`) обязаны быть
side-effect-free: при contention CAS-цикл может вызвать их N раз. Метрики/логи инкрементируются
**после** успешной публикации, не внутри функции построения.

### 4. Атомарность JMX-моста: один домен = один вызов

Spring (app CL) пушит изменения в Agent (extension CL) через JMX. Чтобы агент не видел
промежуточных неполных состояний, MBean принимает **все поля домена одним вызовом**
(`updateSamplingPolicy(...)`/`updateScrubbingPolicy(...)`/`updateValidationPolicy(...)`), а агент
собирает один `Supplier`/`UnaryOperator` и делает один `tryUpdate` (validate merged snapshot →
atomic publish). Серия отдельных сеттеров запрещена. Payload — плоские аргументы/`Map`, без
JSON-парсера в агенте.

### 5. Startup-window

Агент стартует до `main()` Spring. До первого JMX-пуша holders инициализируются значениями из
agent `ConfigProperties` (`-D`/`OTEL_*` via `PlatformTracingDefaultsProvider` / `ExtensionPropertyNames`). Spring `TracingProperties` —
механизм runtime-обновления, а не источник стартовых значений на стороне агента.

### 6. ReDoS (scrubbing regex)

Динамические scrubbing-правила (regex) валидируются на этапе `tryUpdate` через `Pattern.compile()`
с отловом `PatternSyntaxException` (→ last-known-good). Число/длина правил ограничены (bounded).
Существующий `RuleCircuitBreaker` (Фаза 11) гасит правило, чьё выполнение деградирует, что снижает
ReDoS-воздействие на CPU. **Предупреждение:** катастрофически сложный regex остаётся
ответственностью оператора; рекомендуется ревью правил.

### 7. Метрики

Namespace config-reload метрик зафиксирован как `platform.tracing.config.*` (counters реализуются
в PR-6). Согласно [ADR-processor-errors-metric](./ADR-processor-errors-metric.md) — `AtomicLong`/JMX
в agent CL, Prometheus через polling MeterBinder в Spring-стартере (Micrometer недоступен в agent CL).

### 8. Backward compatibility

Решение **не в проде** — backward-compatibility слой не вводится: переименования/смена сигнатур
JMX — чистый cutover, без migration-шим, dual-write и deprecated-алиасов.

## Последствия

- Чёткая граница «что можно менять в рантайме» снимает класс инцидентов «поменяли endpoint в
  рантайме и сломали экспорт».
- Один общий holder уменьшает дублирование и риск рассинхрона между доменами.
- JMX «один домен = один вызов» гарантирует atomic-publish и согласованность снимка.
- Topology-изменения требуют рестарта/Collector reload — задокументировано как ожидаемое.

## Вне scope (backlog)

- Controlled-rebuild topology (exporter endpoint switch, processor chain, span limits).
- Remote-config poller (DD/TUF-стиль) — источники = Spring RefreshScope + JMX + actuator.
- Staged/canary rollout — control-plane/k8s concern.
