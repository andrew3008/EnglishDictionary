# Week 14 — Configuration API: language and namespace

## Контекст

Configuration API — опубликованный язык управления стартером. `@ConfigurationProperties` — типизированная проекция, не весь контракт.

## Формальная модель

```text
resolve(sources, profiles, imports, aliases) -> effective raw values + origins
bind(raw, schema, converters)                 -> typed config | binding failure
validate(typed, invariants)                   -> valid config | validation failure
interpret(valid, runtime context)             -> starter behavior
```

Каждый переход — свои ошибки и правила совместимости. Смешение в одном setter / auto-config method → неявный контракт.

## Потребители

| Потребитель | Использует | Ожидает |
|---|---|---|
| App developer | YAML/properties, IDE | имена, defaults, validation |
| Deployment | env vars, secrets | однозначные ключи, безопасность |
| Spring container | Environment, binder | детерминированный binding |
| Другой starter | prefix, properties bean | стабильная семантика |
| SRE | /env, /configprops | effective value, origin, sanitization |
| IDE/build | metadata JSON | descriptions, hints, deprecations |

Публичны: canonical names, типы/форматы, defaults, precedence/merge, связь с activation/back-off, validation texts + origin, metadata/deprecation, operational exposure.

Review только Java-класса properties пропускает большую часть поверхности.

## Key space и prefix

Starter публикует ограниченный участок глобального key space.

Хороший prefix: устойчивый владелец + capability (`platform.audit`, `acme.messaging`). Плохой: implementation detail (`kafka-helper`, имя внутреннего bean).

Структура: `<organization-or-platform>.<capability>[.<integration>]`

**Must-not:** автоматически `spring.*`; конкурировать с `app.*`.

Перед публикацией: владелец корня; соседние модули; platform vs integration; коллизии с Boot namespaces; расширяемость дерева; читаемость в YAML/properties/env.

Изменение prefix ≈ always breaking → alias/deprecation или migration mechanism. Ownership — в архитектурном реестре платформы.

## Семантическая иерархия ≠ Java graph

Дерево ключей = mental model потребителя (`transport → timeouts → retry`), не SDK builder tree (`client-builder.http-client-configurer.socket-timeout-millis`).

Одинокий leaf не требует nested level. Группа заслуживает имя при общем lifecycle / activation / invariant.

## Каноническое имя

Lower-case kebab-case: `platform.audit.batch-size=500`.

Та же форма: `@ConfigurationProperties(prefix)`, docs, placeholders, deprecation replacement, logs, test fixtures.

Camel/underscore/upper — relaxed variants, не равноправные documented forms.

Placeholders тоже kebab-case — иначе source-dependent behavior.

## Relaxed binding

Effective API шире документированных имён.

**Must:**

- не публиковать два канонических ключа, неразличимых после нормализации (`client-id` / `clientId`);
- проверять коллизии в env representation;
- не использовать alternate form как migration mechanism.

Документировать одну форму; тестировать основные внешние представления; эволюцию — явно.

## Environment variables

`platform.audit.batch-size` → `PLATFORM_AUDIT_BATCHSIZE` (точки→`_`, dashes удаляются, UPPER).

Преобразование теряет информацию → injectivity может нарушаться.

Review каждой property: итоговое env-имя; коллизии; limits deployment platform; списки/indexed; case-sensitive map keys; secrets / accidental logging.

K8s ConfigMap/Secret/Helm — отдельный слой API, тестировать отдельно.

## Collision table

```text
k1 != k2  =>  C(k1, source) != C(k2, source)
```

При нарушении — менять каноническую схему **до** публикации. Docs не чинят техническую коллизию.

## Источники и приоритет

Типичные уровни (точный порядок — у Boot; starter читает через Environment/Binder):

| Уровень | Пример |
|---|---|
| Library default | значение в properties object |
| Packaged config | application.yaml в artifact |
| External config | mounted / imported |
| Environment/system | env, system property |
| Command line/test | `--key=`, test properties |

**Must-not:** воспроизводить precedence собственной логикой. Смена источника/момента чтения без смены имени = behavioral breaking.

## Config Data, imports, profiles

Property принадлежит логическому Environment, не конкретному файлу.

**Must-not** предполагать: значение только в application.yaml; один YAML = полный объект; profile только дополняет; optional import = ошибка; YAML+properties без определённого порядка.

Review **must** включать сценарий «одна логическая конфигурация из нескольких источников».

## Effective value и Origin

Диагностика: канонический ключ; raw value (если безопасно); target type; origin победившего значения.

**Must-not:** перепаковывать binder exceptions в общее `IllegalStateException("invalid config")`. Platform error code дополняет, не заменяет binder diagnostics.

## Ключевые тезисы

- Configuration API = язык (словарь + грамматика + семантика + прагматика).
- Namespace ownership глобален; prefix — почти навсегда.
- Canonical kebab-case + одна documented form.
- Env transport входит в review каждой property.
- Origin — часть error contract.
