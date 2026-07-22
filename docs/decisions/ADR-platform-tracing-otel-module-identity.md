# ADR: identity модуля platform-tracing-otel

| Поле | Значение |
|---|---|
| Статус | **Accepted** |
| Основание | Slice J rename, Slice K CP-3 |

## Решение

Артефакт implementation plane называется `platform-tracing-otel`. Имя отражает зависимость реализации от OpenTelemetry API и не обозначает technology-neutral core.

Slice K утвердил `CP-3 CLOSED - KEEP space.br1440.platform.tracing.core.*`. Сохранённые Java packages являются исторической внутренней taxonomy. Они не обещают reusable neutral-core contract, будущий extraction или соответствие имени package имени Gradle module.

Java Agent module и artifact называются `platform-tracing-otel-javaagent-extension`; root package extension - `space.br1440.platform.tracing.otel.javaagent.*`. Это именно extension OTel Java Agent, а не extension модуля `platform-tracing-otel`.

## Отклонённые альтернативы

- Mechanical repackaging `core.*` в `otel.*`: не дало production-hardening value и создавало большой риск churn.
- Split на `otel-runtime` и `platform-tracing-policy`: не выбран и отсутствует в master.

## Verification

Gradle settings, published metadata fixture, module taxonomy gates, package scans, extension manifest/SPI checks и [Slice K evidence](../analysis/platform-tracing-slice-k-evidence.md).
