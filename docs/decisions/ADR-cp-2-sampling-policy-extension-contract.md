# ADR CP-2: Sampling Policy Extension Contract

## Status

**ACCEPTED — CP-2 CLOSED / SEALED INTERNAL.**

Дата решения: 2026-07-22.

## Decision

Sampling policy принадлежит исключительно платформе.

- `SamplingPolicyRule` остаётся package-private контрактом пакета
  `core.sampling.policy` и не входит в публичный ABI.
- Приложения не могут регистрировать собственные sampling rules.
- Не вводятся public SPI, `ServiceLoader` registration, Spring bean override или
  произвольный выбор через `@Primary`.
- `SamplingPolicyEngine` создаётся только через platform-owned factory
  `SamplingPolicyEngine.productionEngine()`.
- `ProductionSamplingPolicyChain` является фиксированным platform-owned фасадом:
  наружу не передаются rule instances, массивы правил или тип `SamplingPolicyRule`.
- OTel adapter только преобразует вход/результат и делегирует решение платформенному
  engine. OTel `ConfigurableSamplerProvider` остаётся bootstrap SPI агента и не является
  SPI расширения sampling policy.

## Package Boundary

Структура `core.sampling.{engine,model,policy,properties}` не меняется. Прежнее
предположение, что package-private rule требует переноса engine, оказалось излишним:
итерация по rules остаётся внутри `ProductionSamplingPolicyChain`, а engine вызывает
его типизированный fixed-chain API. Публичные сигнатуры chain не содержат
`SamplingPolicyRule`.

Это не новый generic evaluator и не extension seam: consumer не может передать правило,
изменить состав цепочки или создать engine через произвольный constructor.

## Golden Contract

Нормативный порядок production chain сохраняется:

1. `kill_switch`;
2. `hard_drop`;
3. `force_header`;
4. `qa_trace`;
5. `parent_decision`;
6. `route_ratio`;
7. `default_ratio`.

`hard_drop` сохраняет утверждённый приоритет над force/QA. Parent-sampled behavior
сохраняет место перед route/default ratio согласно существующему golden contract.
Reason strings и mapping в OTel decision остаются детерминированными.

## Failure Semantics

- Невалидная runtime-конфигурация отклоняется существующим control protocol и не
  заменяет last-known-good snapshot.
- Исключение или `null` от sampler delegate не может увеличить sampling: `SafeSampler`
  возвращает `DROP`, регистрирует diagnostics и не выпускает исключение в application
  hot path.
- Открытый degraded-mode circuit breaker также возвращает `DROP`.
- Application code не получает composition point, позволяющий обойти kill switch,
  hard-drop или другие platform governance rules.

## Residual Defects Found by Slice G

Verification-first аудит обнаружил два расхождения с утверждённым решением:

1. `SamplingPolicyRule` был public и присутствовал в ABI snapshots; chain возвращал
   массивы этого типа.
2. `SafeSampler` при исключении делегата использовал permissive ratio-based fallback,
   который мог вернуть `RECORD_AND_SAMPLE`.

Оба дефекта исправлены без изменения нормативного rule order, reason contract,
runtime mutation protocol, package topology или модульной структуры.

## Verification

Обязательные gates:

- golden order, deterministic reason и branch-precedence tests;
- kill-switch/hard-drop/force/QA/parent/route/default characterization;
- invalid configuration и fail-closed sampler tests;
- concurrent snapshot/version tests;
- OTel adapter compilation и tests;
- public-surface/ABI tests, подтверждающие отсутствие `SamplingPolicyRule`;
- ArchUnit gates и полный build.

## Consequences

- Breaking ABI delta намеренный: случайно опубликованный `SamplingPolicyRule` удалён из
  public surface до production rollout; compatibility shim и `@Deprecated` alias не
  добавляются.
- Добавление application-defined policy rule в будущем требует нового архитектурного
  решения и отдельного versioned SPI design; CP-2 такого расширения не разрешает.
- После зелёной верификации: `CP-2 CLOSED`, `SLICE G CLOSED`,
  `SAMPLING SPI SEALED INTERNAL`.
