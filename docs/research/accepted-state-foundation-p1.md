# P1 Accepted-State Foundation

> Дата: 2026-07-28  
> Ветка: `refactor/config-accepted-state-p1`  
> Базовый commit: `cfd1af161351257957ed9f5bd9bc44e59fc154e9`  
> Статус: `IMPLEMENTATION_COMPLETE / VERIFICATION_GREEN`

## 1. Основание

Архитектурный комитет закрыл gates E1-E9 и предоставил Implementation GO без
дополнительных требований. Решение зафиксировано в
`docs/research/evidence/implementation-readiness/committee-approval.json`.

P1 реализует только accepted-state foundation. Действующие runtime readers, JMX writers,
Spring delivery paths и legacy holders не переключались. Новый target path недостижим из
поддерживаемой production posture до следующих phases плана.

## 2. Реализованный контракт

В `platform-tracing-otel-javaagent-extension` добавлен внутренний package
`control.state`:

- `EffectiveTracingPolicy` содержит ровно восемь typed Category A leaves;
- route ratios копируются, нормализуются, сортируются и публикуются как unmodifiable map;
- `AcceptedPolicyEnvelope` содержит только `runtimeInstanceId`, `acceptedSequence` и policy;
- `AcceptedPolicyStateStore` владеет одной
  `AtomicReference<AcceptedPolicyEnvelope>`;
- `current()` выполняет один volatile read без allocation, lock и JMX;
- `commit(...)` выполняет не более одной expected-base CAS-попытки;
- store возвращает только `APPLIED` или `STALE` и не поддерживает retry/rebase;
- coordinator формирует `UNCHANGED`, `REJECTED` и `FAILED` до или вокруг store boundary;
- canonical SHA-256 digest использует versioned domain separator, фиксированные field ids,
  big-endian primitives и UTF-8 byte ordering для routes;
- apply diagnostics и metadata-only audit ring находятся вне accepted-state CAS payload;
- audit ring имеет фиксированную ёмкость 32 записи.

Все новые production types package-private. Публичный API и publication metadata не изменены.

## 3. Сохранённые границы

- Category B отсутствует в accepted policy.
- `ScrubbingPolicyHolder` остаётся startup-migration concern и не переносится в P1 state.
- `SamplerStateHolder`, `ValidationPolicyHolder`, exporter, propagation и diagnostics owners
  пока остаются действующими.
- Удаление прежних holders/version owners допускается только после reader equivalence proof и
  coherent cutover по последующим phases.
- Ни один новый public DTO, generic map API, module или compatibility shim не добавлен.

## 4. Доказательства

Новый production candidate покрыт 20 focused tests, 0 failures, 0 skipped:

- exact eight-leaf shape и отсутствие Category B;
- defensive copy, deterministic order, unmodifiable representation;
- ratio, route-count и route-prefix bounds;
- negative-zero normalization;
- 100 rounds x 8 concurrent writers: один `APPLIED`, семь `STALE`;
- runtime-instance и sequence mismatch;
- `UNCHANGED` без CAS;
- strict-runtime envelope rejection;
- `FAILED` без accepted-state mutation;
- publication visibility: 20 000 commits и шесть concurrent readers;
- HotSpot allocation counter: 0 bytes на 2 000 000 вызовов `current()`;
- independent golden SHA-256 vector;
- bounded diagnostics/audit и отсутствие raw policy в audit entry;
- package-private surface и отсутствие generic/rebase API.

## 5. Verification

| Команда | Результат |
|---|---|
| `:platform-tracing-otel-javaagent-extension:compileJava` | PASS |
| `:platform-tracing-otel-javaagent-extension:compileTestJava` | PASS |
| `:platform-tracing-otel-javaagent-extension:test --tests "*control.state.*"` | PASS |
| `:platform-tracing-otel-javaagent-extension:test` | PASS |
| `acceptedStatePrimitiveVerify` | PASS |
| `tracingConfigurationInventoryVerify` | PASS после механической regeneration ссылок |
| `pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify` | PASS |
| `build` | PASS |

`platform-tracing-e2e-tests:test` в обычном build был `SKIPPED`. Docker-backed collector
validation попытался обратиться к `tcp://192.168.100.70:2375`, получил timeout, а Gradle task
завершился успешно по собственной fallback-семантике. Внешняя Docker-проверка поэтому имеет
статус `NOT_RUN`, а не PASS.

## 6. Exit

P1 accepted-state foundation реализован и проверен. Production writer switch не выполнен.
Следующий phase может начинать private control boundary только сохраняя C-05: target и legacy
writers не должны одновременно становиться достижимыми из supported runtime posture.

Production rollout остаётся запрещён до coherent cutover и release gates, предусмотренных планом.
