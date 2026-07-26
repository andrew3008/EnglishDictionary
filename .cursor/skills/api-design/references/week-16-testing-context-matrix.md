# Week 16 — Testing: context and auto-config matrix

## Контекст

Тесты подтверждают контракт, не последовательность приватных вызовов. Line coverage не доказывает Java/config/conditions/ops/AOT surfaces.

## Тестирование как executable spec

Покрывает: Java API; binding; conditions/back-off; bean graph; extensions; runtime behavior; operational signals; dependency metadata; compatibility; AOT/native.

**Must-not:** напрямую вызывать `@Bean` methods — обходят conditions, ordering, lifecycle, post-processing. Имя теста = rule (`userExporterBacksOffDefaultAndRemainsObservable`).

## Test portfolio

| Уровень | Проверяет | Не подменяет |
|---|---|---|
| Unit | domain policy | container wiring |
| Binding | mapping/validation | runtime topology |
| Context | conditions, beans, back-off | real transport |
| Integration | modules/infrastructure | весь consumer landscape |
| Contract | внешняя surface | internal details |
| Architecture | dependency/package rules | behavior |
| Native/AOT | closed-world | JVM coverage |
| Consumer smoke | реальное подключение | exhaustive matrix |

Дублирование happy path на всех уровнях малоценно. Риски — на самом дешёвом доказательстве.

## Unit domain policy

Без Spring: state transitions; selection; validation; aggregation; retry; redaction; exception→code; cardinality normalization. Быстрые parameterized / property-based. Critical policy только через `@SpringBootTest` → logic слишком связана с container.

## Configuration binding tests

Canonical keys; defaults; relaxed; nested; Duration/DataSize; enums; absent/blank/null; validation boundary; aliases; metadata; sanitized representation.

Различать: binding OK; fields valid; cross-field valid; effective mode resolved. Classpath-dependent invariant — в domain resolver, не обязательно в `@ConfigurationProperties` validation.

## ApplicationContextRunner

Компактные variants auto-configuration. Варьировать: properties; user configs; classpath; initializers; env; auto-configs.

Assertions: owner; отсутствие конкурирующего default; extensions; diagnostics; meters — не только `hasSingleBean`.

## Матрица auto-configuration

| Измерение | Варианты |
|---|---|
| Feature property | absent / enabled / disabled / invalid |
| Required class | present / absent |
| User bean | none / one / multiple / primary |
| Related infrastructure | present / absent |
| Web type | none / servlet / reactive |
| Actuator | present / absent |
| Observability registry | present / absent |
| Parent context | match parent / only child |

Risk-based pairwise + critical scenarios. Матрица записана явно.

## Conditions / back-off tests

Для каждого default: создаётся при prerequisites; не при disable; не при replacement; user injectable; dependents используют replacement; unrelated bean ≠ accidental back-off; multi-candidate → documented selection/failure; diagnostic message.

Ownership boundary целиком (exporter + dedicated scheduler). `@Primary` ≠ два владельца ресурса — различать injection selection и ownership.

## Classpath tests

Optional absent; min supported version; incompatible/partial; servlet↔reactive; Actuator absent; test fixture не скрывает publication error. `FilteredClassLoader` помогает, но не заменяет published dependency graph run.

## Limits of context tests

Не: real web server; packaged jar; все classloaders; native; все SpringApplication hooks; consumer-lean classpath.

Стратегия: широкая дешёвая context matrix + узкие vertical integration slices. Не переносить всю матрицу в `@SpringBootTest`.

## Full Spring Boot integration

Imports metadata; real SpringApplication; lifecycle; server/management ports; endpoint serialization; security/exposure; shutdown resources. Random ports. Evidence на публичной границе (HTTP/JMX, meters, spans, closed resources). **Must-not:** autowire internal implementation.

## Test slices

`@WebMvcTest` / `@WebFluxTest` и starter composition: нужен ли `@AutoConfigure...`; участие в slice; properties; excluded infrastructure. Не объединять несколько slices «почти полный context» — основной slice + explicit import.

## Ключевые тезисы

- Portfolio по рискам, не giant BootTest.
- Context matrix записана и покрывает ownership.
- Full smoke узкий; context matrix широкая.
- Public-boundary evidence, не internal autowiring.
