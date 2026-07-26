# Week 14 — Types, validation, diagnostics

## Контекст

Type conversion отвечает за синтаксис; validation — за семантику; diagnostics сохраняют Origin. Properties bean — чистая граница данных до runtime graph.

## Closed enum vs open string

| Характер | Тип |
|---|---|
| Небольшой набор modes владельца starter | enum |
| Extensible tokens внешней системы | String / value object |
| Bean identifier | String + metadata provider + runtime resolution |
| Class name | `Class<?>` или String + class-reference hint |
| Несколько параметров | structured type |

Enum: IDE + ранняя ошибка; add/rename/remove = compat analysis. String: open vocabulary → validation/hints обязанность автора.

**Must-not:** два boolean для взаимоисключающих modes. `AUTO|ENABLED|DISABLED` — enum.

## Domain scalars

Предпочтительны: `Duration`, `Period`, `DataSize`, `URI`, `Resource`, enum/value object вместо magic string / bare long.

Type = grammar свойства. Conversion = синтаксис; validation = допустимая семантика (отрицательный Duration, relative URI).

**Must-not:** публиковать SDK-specific types (`okhttp3.ConnectionSpec`) — configuration model стабильнее implementation.

## Units

Bare number неоднозначен. Новое API: `timeout=5s`, `max-size=256MB`. `@DurationUnit` / `@DataSizeUnit` — для legacy bare numbers при миграции.

Смена unit без смены key = silent semantic breaking (`5000` ms → s). Нужен старый unit или новый property name.

## Value objects и converters

Converter: детерминированный; locale-independent; без сети/файлов/beans; безопасный raw в ошибке; unit tests boundaries/malformed.

Предпочтительно `@ConfigurationPropertiesBinding`, не глобальный `conversionService`.

**Must-not:** converter как runtime bean lookup. Hint подсказывает имена; lookup — interpretation phase.

## Ранний lifecycle

Converter/validator создаются рано. **Must:** pure/stateless; static bean где нужно; не внедрять properties в свой converter; не remote validation при binding.

Unit test converter недостаточен — нужен context test с полным bootstrap.

## Архитектура validation

| Слой | Проверяет | Пример |
|---|---|---|
| Conversion | lexical form | `5xs` |
| Field constraint | локальный диапазон | batch > 0 |
| Nested | группа | TLS trust store |
| Cross-field | согласованность | min ≤ max |
| Mode-specific | обязательность при режиме | endpoint при REMOTE |
| Runtime capability | внешнее состояние | server up → health, не binding |

Первые слои могут останавливать startup. Remote readiness — не configuration binding.

## Jakarta Validation

`@Validated` + field/constructor constraints; nested — `@Valid` для cascade.

Messages: canonical property path; правило; без secret; все relevant violations; missing vs malformed.

`@NotNull` на primitive бессмысленен — zero value скрывает отсутствие.

## Cross-field / mode-specific

Механизмы: class-level constraint; Spring Validator; explicit validation method; validated internal specification.

Сообщение описывает отношение: «при mode=REMOTE требуется endpoint».

**Must-not:** каждое mode-specific поле `@NotNull` — ломает другие modes.

## Invalid vs unknown fields

| | Смысл | Default Boot |
|---|---|---|
| Invalid | key известен, value плохой | fail (`ignoreInvalidFields=false`) |
| Unknown | key под prefix, schema не связывает | ignore (`ignoreUnknownFields=true`) |

`ignoreInvalidFields=true` почти всегда разрушает контракт.

Unknown: leaf namespace одного starter → может быть строгим; shared prefix / dynamic map / multi-version config server → tolerance. Смена strictness = compatibility analysis.

`NoUnboundElementsBindHandler` — lint/tests/изолированные namespaces, не универсальный runtime режим без evolution protocol.

## Binding failure diagnostics

Сохранять: bean type, annotation, property, rejected value, conversion/validation problem, origin.

Обёртка допустима, если: capability + remediation; cause сохранена; no credential; canonical key; не схлопывает несколько violations.

Binding/validation **до** side effects (ports, schedulers, external resources).

## Регистрация properties

Explicit `@EnableConfigurationProperties({...})` на slice. Broad scan в starter может захватить unintended types.

Conventional bean name (prefix + FQCN) — не опираться без обещания; типизированная injection устойчивее. Java visibility ≠ configuration contract (keys публичны).

## Properties bean = data boundary

```text
Environment -> bind + validate -> immutable configuration
  -> adapter/factory -> runtime component
```

**Must-not** в properties type: выбирать DataSource; DNS; строить SDK client; tenant; metrics; менять system property.

## Ключевые тезисы

- Conversion ≠ validation ≠ runtime health.
- Enum для closed modes; Duration/DataSize вместо bare numbers.
- Origin-aware failures до side effects.
- Properties bean не владеет interpretation.
