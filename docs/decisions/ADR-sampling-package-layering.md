# ADR: Слоистая декомпозиция пакета `core.sampling` (model / policy / engine / config)

## Status

**Accepted — реализовано (pre-production).**

Решение не вышло в продакшен, поэтому ломающие изменения структуры пакетов и видимости допустимы без `@Deprecated`. Нормативное поведение rule chain сохранено (см. раздел «Сохранённые инварианты»); permissive exception fallback отдельно исправлен решением CP-2 на fail-closed `DROP`.

## Context

Пакет `space.br1440.platform.tracing.core.sampling` был «плоским»: модель состояния, правила (chain-of-responsibility), движок исполнения и компиляция/валидация конфигурации лежали в одном пакете. Дополнительно нормализация route-ratio / drop / force дублировалась в двух местах:

- `SamplingPolicySnapshot` (core) — нормализация при построении снимка;
- `SamplerState` (otel-extension) — собственные копии `normalizeDropPaths/normalizeForceValues/normalizeRouteRatios`, после чего вызывался `SamplingPolicySnapshot.fromConfiguration`.

Это создавало два источника правды для нормализации и размывало границы ответственности. Доменная валидация runtime-обновлений (`SamplerPolicyUpdate.validateDomain`) и lenient-компиляция config-пути жили в разных модулях без явного контракта.

`platform-tracing-otel` не должен зависеть от OpenTelemetry, Spring или JMX (см. `platform-tracing-module-taxonomy.md`).

## Decision

### 1. Целевая структура пакетов

```
core.sampling.model    — чистое состояние (без логики слоёв выше)
core.sampling.policy   — семантика правил + порядок цепочки
core.sampling.engine   — исполнение цепочки
core.sampling.config   — compile-time нормализация и доменная валидация
```

| Пакет | Классы | Зависит от |
|-------|--------|-----------|
| `model` | `SamplingPolicyRequest`, `SamplingPolicyDecision`, `SamplingPolicyDecisionType`, `SamplingPolicyReason`, `ParentContextState`, `RouteRatioPrefix`, `SamplingPolicySnapshot` | только `api` (`Versioned`, `PlatformSamplingReasons`) |
| `policy` | `SamplingPolicyRule` (pkg-private), 7 правил (pkg-private), `TraceIdRatioDecision` (pkg-private), `SamplingPolicyRuleNames` (pkg-private), `ProductionSamplingPolicyChain` (public fixed-chain facade) | `model`, `api` |
| `engine` | `SamplingPolicyEngine` | `policy`, `model` |
| `config` | `SamplingPolicyConfig`, `SamplingPolicyConfigValidator`, `SamplingPolicySnapshotFactory` | `model` |

Разрешённые рёбра зависимостей: `policy → model`, `engine → policy`, `engine → model`, `config → model`. Циклов нет. Правила закреплены ArchUnit (`ModuleTaxonomyArchRules`).

### 2. Единый источник нормализации/валидации (`config`)

- `SamplingPolicySnapshotFactory.create(SamplingPolicyConfig)` — **единственная** точка компиляции «сырой» конфигурации в снимок. Путь **LENIENT** (silent-skip невалидных route-записей), как и прежний `fromConfiguration`.
- `SamplingPolicyConfigValidator.validate(SamplingPolicyConfig)` — **fail-fast** доменная валидация (границы ratio, drop/force, blank-префиксы, платформенные max-count лимиты).
- `SamplingPolicyConfig` — сырой логический pure-Java input-model (не нормализован, без логики).
- `SamplingPolicySnapshot.fromConfiguration(...)` **удалён**. `SamplerState` (otel-extension) теперь строит снимок через фабрику и берёт нормализованные коллекции из снимка; локальные `normalize*` удалены.
- `SamplerPolicyUpdate.validateDomain(...)` оставляет у себя только wire/update-shape проверку (parity параллельных массивов prefix/value), а доменные инварианты делегирует `SamplingPolicyConfigValidator`. Сообщения об ошибках сохранены.

### 3. Порядок цепочки и видимость движка

- `ProductionSamplingPolicyChain` — единственный сборщик и исполнитель fixed chains: `production()` (нормативный порядок 7 правил) и `foundation()` (минимум kill-switch + hard-drop). Rule arrays и rule instances не покидают `policy`.
- Concrete rule-классы (`KillSwitchPolicyRule`, `HardDropPolicyRule`, `ForceHeaderPolicyRule`, `QaTracePolicyRule`, `ParentSampledPolicyRule`, `RouteRatioPolicyRule`, `DefaultRatioPolicyRule`) и интерфейс `SamplingPolicyRule` — **package-private** в `policy`. Приложение не может реализовать, зарегистрировать или передать собственное правило.
- `SamplingPolicyEngine`: `productionEngine()` — единственная публичная factory создания движка. Constructor — **private**, `foundationEngine()` — **package-private**. Engine делегирует fixed chain и не принимает rule type в своих сигнатурах.

### 4. `ProductionSamplingPolicyChain` — намеренный компромисс

Класс `public`, потому что движок живёт в соседнем пакете `engine` и должен компилироваться против него. Но это **не** публичный extension API: он создаёт только фиксированные platform-owned chains, не принимает custom rules и не раскрывает их тип/instances. «Железное правило ревью»: зависеть от него могут только пакеты `policy` и `engine` — это закреплено ArchUnit-правилом `PRODUCTION_CHAIN_ACCESS_RESTRICTED`. Путь выхода из компромисса (если в будущем движок и цепочка окажутся в одном пакете) — сделать класс package-private.

## Сохранённые инварианты (поведение не изменено)

- Порядок правил: `kill_switch → hard_drop → force_header → qa_trace → parent_decision → route_ratio → default_ratio`.
- Семантика `TraceIdRatioDecision` (паритет с OTel `TraceIdRatioBased`), reason-code маппинг, longest-prefix-first для route-ratio.
- LENIENT (config compile) vs FAIL-FAST (runtime update) — две разные семантики, явно разделены и **не** объединены.
- Hot-path `evaluate()` — без аллокаций, stateless, immutable-read, без синхронизации.

## Guardrails (ArchUnit)

В `ModuleTaxonomyArchRules`, подключены в `CorePolicyPackagePurityArchTest`:

| Правило | Смысл |
|---------|-------|
| `SAMPLING_MODEL_IS_PURE` | `model` не зависит от `policy/engine/config` |
| `SAMPLING_CONFIG_DEPENDS_ONLY_ON_MODEL` | `config` не зависит от `policy/engine` |
| `SAMPLING_POLICY_NO_ENGINE_OR_CONFIG` | `policy` не зависит от `engine/config` |
| `PRODUCTION_CHAIN_ACCESS_RESTRICTED` | `ProductionSamplingPolicyChain` доступен только из `policy/engine` |
| `SAMPLING_RULE_IMPLS_ONLY_IN_POLICY` | реализации `SamplingPolicyRule` допустимы только в `core.sampling.policy` (enforcement «not extension API», а не imports) |
| `CORE_POLICY_PACKAGES_NO_OTEL_OR_SPRING` | весь `core.sampling..` без OTel/Spring/JMX (без изменений) |

«Not extension API»-статус правил защищён тремя независимыми барьерами: (1) interface и concrete rules **package-private**; (2) fixed chain не принимает и не возвращает rule objects; (3) ArchUnit-правило `SAMPLING_RULE_IMPLS_ONLY_IN_POLICY` без empty-set waiver запрещает реализации вне `policy` даже внутри `core`. Public-surface/ABI test дополнительно подтверждает отсутствие rule type и `ServiceLoader` descriptor.

Паритет-тест `TraceIdRatioParityTest` (otel-extension) ходит через публичную `SamplingPolicyEngine.productionEngine()` со snapshot'ом (`defaultRatio`) и request'ом, который доходит до `default_ratio` — тест **не** конструирует правило/движок руками и не пересекает границу слоёв напрямую, уважая package-private видимость движка.

## Hot-path safety (JMH evidence)

JMH-прогон был частью hot-path safety gate (после переноса пакетов). Прогнаны три прицельных бенчмарка через `:platform-tracing-bench:jmh -PjmhInclude=CompositeSampler` (профиль `-PjmhDev`: 1 fork × 3 warmup × 5 iterations × 500 ms, gc-профайлер). Метрика аллокаций — `gc.alloc.rate.norm` (единственная допустимая alloc-метрика, B/op).

| Benchmark | Threads | latency (ns/op, avgt) | gc.alloc.rate.norm (B/op) |
|-----------|--------:|----------------------:|--------------------------:|
| `CompositeSamplerBenchmark.forceHeader` | 1 | 45.9 | 104 |
| `CompositeSamplerBenchmark.parentSampled` | 1 | 49.6 | 112 |
| `CompositeSamplerBenchmark.ratioDrop` | 1 | 43.8 | 96 |
| `CompositeSamplerBenchmark.forceHeaderContended` | 8 | 62.1 | 104 |
| `CompositeSamplerBenchmark.ratioDropContended` | 8 | 58.6 | 96 |
| `CompositeSamplerPolicyBranchesBenchmark.dropPath` | 1 | 40.2 | 56 |
| `CompositeSamplerPolicyBranchesBenchmark.routeRatioDrop` | 1 | 58.1 | 88 |
| `CompositeSamplerPolicyBranchesBenchmark.routeRatioSample` | 1 | 61.0 | 104 |
| `CompositeSamplerPolicyBranchesBenchmark.defaultRatioFullChain` | 1 | 60.0 | 96 |
| `CompositeSamplerPolicyBranchesBenchmark.defaultRatioFullChainContended` | 8 | 81.4 | 96 |
| `CompositeSamplerPolicyBranchesBenchmark.routeRatioSampleContended` | 8 | 81.0 | 104 |
| `CompositeSamplerConcurrentUpdateBenchmark.readWhileUpdate` | 8 | 166.4 | 119 |

Толкование:

- Latency в ожидаемом диапазоне десятков нс/op; контеншн (8 потоков) даёт ожидаемую деградацию без коллапса — lock-free atomic snapshot (инвариант P5) сохранён.
- Аллокации `56–119 B/op` локализованы в `CompositeSampler.shouldSample`/OTel-адаптере (конструирование `SamplingPolicyRequest` и `SamplingResult`), который рефакторингом **не** менялся. Ядровый путь `SamplingPolicyEngine.evaluate(...)` (обход массива правил + immutable-read) остаётся zero-alloc — он лишь сменил пакет/видимость, тело не тронуто.
- **Регрессия не ожидается и не наблюдается:** рефакторинг затронул структуру пакетов, config-time компиляцию/валидацию и видимость, но не hot-path-логику.

Ограничение / waiver: формальный `jmhCompareBaseline` (before/after delta) не выполнялся — committed baseline в репозитории отсутствует (`baselines/` пуст), а dev-профиль на dev-хосте по `performance-budgets.yaml` не является каноничным reference-lab источником. Цифры выше — индикативные (dev-host, `windows`, 20 cores). Официальный hard-gate baseline снимается на reference-лаборатории отдельно; риск отсутствия formal-baseline принят: hot-path-реализация не менялась.

## Consequences

- Чёткие, машинно-проверяемые границы слоёв; нормализация и валидация имеют единственный дом.
- Удалено дублирование нормализации между core и otel-extension.
- Ломающее изменение FQN классов `core.sampling.*` (допустимо в pre-production). Внешние ссылки обновлены только в `platform-tracing-otel-extension`.
- `ProductionSamplingPolicyChain` остаётся `public` fixed-chain фасадом как осознанный cross-package компромисс под контролем ArchUnit; sampling rule SPI закрыт решением CP-2.
