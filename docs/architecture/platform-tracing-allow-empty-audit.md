# Аудит `allowEmptyShould(true)`

Аудит выполнен по `platform-tracing-test/src/main/java/.../arch`. Классификация нормативная:

| Категория | Правила | Решение |
|---|---|---|
| `LEGITIMATELY_EMPTY` | Запреты удалённых/несуществующих типов и пакетов: fake OTel packages, local OTel contract names, public parser, request-id SPI, retired sanitizer/enrichment/context keys, SQL sanitizer, `RemoteServiceContextReaders` | Флаг оставлен: отсутствие совпадений является предметом правила. |
| `MUST_MATCH` | Границы существующих production-пакетов: autoconfigure, webmvc, webflux, otel policy, runtime versioned, enrichment, naming, semconv, javaagent MDC | Флаг удаляется; пустая выборка должна падать. |
| `FUTURE_SLICE` | Status/control/bootstrap правила данного плана | Реализованы одновременно с пакетами и не используют `allowEmptyShould(true)`. |

Точные `haveFullyQualifiedName(...)` правила оставлены допускающими пустую выборку, когда общий reusable ruleset запускается на модуле, не содержащем целевой тип. Новые package-boundary проверки находятся в `AutoconfigurePackageArchitectureTest` и дополнительно проверяют непустой production source set.

