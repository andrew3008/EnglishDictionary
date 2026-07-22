# ADR: identity модуля platform-tracing-otel

| Поле | Значение |
|---|---|
| Статус | **Accepted** |
| Основание | Slice J rename; CP-3 R2 по явному указанию владельца проекта |

## Решение

Артефакт implementation plane называется `platform-tracing-otel`. Имя отражает зависимость реализации от OpenTelemetry API и не обозначает technology-neutral core.

CP-3 R2 утверждает атомарную миграцию implementation namespace в
`space.br1440.platform.tracing.otel.*`. Предыдущее решение Slice K сохранить
`space.br1440.platform.tracing.core.*` superseded этой ревизией. Старый namespace ошибочно
подразумевал technology-neutral reusable core, хотя реализация намеренно зависит от OpenTelemetry.

Compatibility package, aliases и forwarding classes не сохраняются: кодовая база pre-production,
а миграция является intentional source/binary ABI break implementation-типов. Публичный
`platform-tracing-api` остаётся technology-neutral и OTel-free.

Java Agent module и artifact называются `platform-tracing-otel-javaagent-extension`; root package extension - `space.br1440.platform.tracing.otel.javaagent.*`. Это именно extension OTel Java Agent, а не extension модуля `platform-tracing-otel`.

## Отклонённые альтернативы

- KEEP `core.*`: superseded, потому что больше не соответствует утверждённой identity OTel-specific implementation module.
- Compatibility aliases/dual packages: отклонены как лишняя pre-production поверхность и источник неоднозначного ownership.
- Split на `otel-runtime` и `platform-tracing-policy`: не выбран и отсутствует в master.

## Verification

Gradle settings, published metadata fixture, module taxonomy gates, package scans, ABI snapshots,
extension manifest/SPI checks, mandatory packaged Agent E2E и
[CP-3 R2 migration evidence](../analysis/platform-tracing-package-rename-evidence.md).

Историческое основание предыдущего KEEP-решения сохранено в
[Slice K evidence](../analysis/platform-tracing-slice-k-evidence.md).
