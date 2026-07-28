# Week 13 — Ordering, back-off granularity, state model

## Контекст

Ordering выражает реальную bootstrap dependency. Гранулярность back-off совпадает с ownership boundary. Каждый несовпадение condition классифицируется: silent no-match, documented back-off или fail-fast.

## Ordering tools

| Механизм | Заметка |
|---|---|
| `@AutoConfiguration(before/after/beforeName/afterName)` | class-valued = compile dependency; string names = optional без dep |
| `@AutoConfigureBefore` / `@AutoConfigureAfter` | то же |
| `@AutoConfigureOrder` | числовой порядок |

Имена auto-configuration classes, `afterName`, exclusion — **effective Spring-facing API**, даже если developer не инстанцирует их.

## Ordering ≠ architecture fix

`after = X` чинит visibility относительно X, но **не** решает: unknown user configs; third-party auto-configs; циклы; parent/child; широкий replacement type; конфликт providers.

Предпочтительные coordination points:

- один owner auto-configuration для role;
- marker / capability bean;
- разделение defaults и adapters;
- type-safe SPI;
- ordered collection нескольких implementations;
- ambiguity как явное состояние;
- slices вокруг feature, не вокруг технических классов.

## Приоритет пользовательской конфигурации

Типичная иерархия владения:

```text
explicit disable
  > user-provided implementation
    > corporate/platform integration
      > starter default
```

Документация замещения **must** отвечать: какой type; qualifier/name; один candidate?; parent context?; связанные beans; частичная замена (transport без facade); несколько replacements; customizers к user bean.

«Define your own bean to override» без деталей — не API description.

## Гранулярность back-off

| Contract | Эффект |
|---|---|
| User client | подавляет client default; service строится поверх |
| User service | подавляет service default; operations продолжают |
| User service = full ownership | подавляет весь feature slice |
| Marker annotation | подавляет конкретный flavor |

Back-off graph:

```text
User AcmeClient -| DefaultAcmeClient -> DefaultAcmeService
User AcmeService -| DefaultAcmeService -> Metrics, Health
```

`-|` = подавление; `->` = dependent adapter. Граф выявляет class-level condition, удаляющий все downstream nodes.

## Default provider choice

Несколько transports (HTTP/gRPC) при отсутствии user bean:

- явное property `transport=http`;
- единственный доступный по classpath;
- фиксированный platform default;
- fail при нескольких;
- routing / multi-transport abstraction.

**Must-not:** «кто зарегистрировался первым». Default provider = behavioral + dependency contract → ADR, assessment, migration.

## Optional integrations

Micrometer, Actuator, tracing, cache, messaging — независимые slices с собственными classpath gate и bean prerequisite. Отсутствие optional class **must-not** выключать core.

Operations beans тоже требуют replacement policy: type / name / qualifier; допускаются ли оба indicators. Collection-oriented extension часто лучше single-owner back-off.

## Ошибка или back-off

| Ситуация | Реакция |
|---|---|
| Optional SDK отсутствует, feature не запрошена | normal no-match |
| Feature явно выключена | normal no-match |
| User bean предоставлен | documented back-off |
| Feature явно включена, обязательный class отсутствует | startup failure + remediation |
| Два candidates без selection | ambiguity failure или документированный no-match |
| Недопустимое property | binding/validation failure |
| Remote недоступна | runtime/health failure, **не** condition no-match |

Чем явнее намерение пользователя, тем менее допустимо молчаливое отступление. Для explicit opt-in без classpath — validation config или failure analyzer.

## Конечная модель состояний

| Состояние | Входы | Bean graph | Обработка |
|---|---|---|---|
| Unsupported | prerequisite нет | нет | inactive; failure при явном opt-in |
| Disabled | enabled=false | нет | inactive |
| Defaulted | enabled, нет replacement | platform default | active |
| Overridden | enabled, user replacement | user, default отсутствует | active |
| Ambiguous | enabled, N без winner | нет usable graph | failure / selection policy |
| Invalid | enabled, config недопустима | нет | validation failure |

Значимые переходы:

```text
Defaulted --add user bean--> Overridden
Overridden --add second bean--> Ambiguous
Disabled --enable--> Defaulted | Overridden
Unsupported --add dependency--> Defaulted
Defaulted --remove dependency--> Unsupported
Any active --invalid property--> Invalid
```

Classpath-only intent опасен: upgrade dependency может перевести Unsupported → Defaulted без смены properties.

## ConditionEvaluationReport

Positive/negative matches; debug report; `/actuator/conditions` (если разрешён).

**Must-not:** report — единственный способ объяснить fatal error (endpoint может быть закрыт; startup failure не успеет открыть).

Domain-oriented имена configurations и actionable messages превращают report в объяснение архитектуры. `Config1` / «did not find required beans» почти бесполезен.

## Ключевые тезисы

- Имена и порядок auto-configurations — Spring-facing contract.
- Ordering выражает dependency, не чинит composability.
- Гранулярность back-off = ownership boundary; рисуйте граф.
- Default provider — продуктовая policy, не registration race.
- Explicit intent → fail-fast, не silent skip.
- Observable state model обязателен для каждой feature.
