# ADR: PlatformResourceProvider перекрывает OTel Agent resource detectors

## Статус

Принято (Wave 2, PR-GAP-2). **Superseded Фазой 9** (см.
[ADR-resource-merge-precedence.md](ADR-resource-merge-precedence.md)): `order()=0` заменён на
`order()=100` + `ConditionalResourceProvider` + per-key omit; явный `OTEL_*` больше не перетирается.

## Контекст

`PlatformResourceProvider` регистрируется с `order() = 0` — наивысший приоритет merge
resource-атрибутов в OTel SDK. Это означает, что значения платформенного provider'а
**перекрывают** auto-detect OTel Java Agent (`HostResource`, `OsResource`, `ContainerResource` и др.).

Wave 2 мигрирует платформенные ключи на stable OTel semconv:

| Было | Станет |
|------|--------|
| `platform.environment` | `deployment.environment.name` |
| `platform.host` | `host.name` |
| `platform.type` | `platform.trace.type` (span) |
| `platform.result` | `platform.trace.result` (span) |
| `platform.timeout` | `platform.trace.timeout` (span) |

## Решение

Приоритет источников для platform-managed resource attrs:

1. **Explicit platform config** (`platform.tracing.service.*`, env vars) — всегда побеждает
2. **OTel Agent auto-detect** — используется, если platform provider **omit** (не пишет ключ)
3. **Omit-if-unconfigured** — для `container.id`: provider не пишет атрибут, если нет explicit value;
   merge оставляет значение Agent Container detector

`order() = 0` — **намеренное** решение: платформа управляет каноническими значениями
`deployment.environment.name`, `host.name`, `service.name`, `platform.c_group`, `platform.id`
независимо от окружения Agent.

## Последствия

- Helm/ConfigProperties — единственный источник truth для environment/host в managed deployments
- Agent detectors остаются fallback для dev/local без platform config
- E2E smoke после Wave 2 assert merged resource attrs с новыми semconv-ключами

## Альтернативы

- `order() = Integer.MAX_VALUE` (Agent побеждает) — отклонено: platform attrs теряют предсказуемость
- Dual-write старых и новых ключей — отклонено: prod нет, BC не требуется
