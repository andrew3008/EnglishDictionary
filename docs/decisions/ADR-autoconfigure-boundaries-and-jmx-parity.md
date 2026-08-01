# ADR: границы Spring autoconfigure и JMX parity

## Статус

Принято.

## Решение

Control-plane остаётся в `platform-tracing-spring-boot-autoconfigure`, но разделяется package-boundaries и blocking tests. Read-only `tracingStatus` принадлежит отдельному status-слою. Startup intent разрешается один раз в immutable `TracingBootstrapSettings`; ранний BFPP принадлежит `control.authority`.

`enabled` входит в immutable startup snapshot. При `enabled=true` readiness Agent проходит
обязательную reconciliation; при `enabled=false` Agent должен отсутствовать в startup validation
window и создаётся NoOp facade. Этот флаг не является hot-switch: изменение требует
restart/redeploy; dynamic Agent attach после Spring startup не поддерживается.

Публичность делится на user-facing API, Spring registry types и internal cross-module contracts. К последним относятся bootstrap property names и минимальные JMX transport-типы с поимённым allowlist потребителей. Codec разрешён JMH и perf fixture, поскольку первый измеряет реальный механизм, а второй проверяет канонический `payloadDigest`. Они не являются application SPI.

JMX wire-константы и OpenType schema намеренно существуют в application и Agent classloader planes отдельно. Общий Java-тип запрещён из-за изоляции classloader. Эквивалентность ObjectName, CompositeType и полей проверяется parity-тестами; граница передаёт только JDK OpenType.

Servlet management surface принадлежит webmvc-модулю, WebFlux surface — webflux-модулю. Нейтральный autoconfigure не зависит от Spring Web или Servlet API.

## Последствия

- `schema()`-подобная публичная интроспекция и новые SPI не вводятся.
- Пакетные перемещения являются breaking change до production и не получают deprecated aliases.
- Любой новый cross-module consumer internal contract требует изменения allowlist и архитектурного review.
