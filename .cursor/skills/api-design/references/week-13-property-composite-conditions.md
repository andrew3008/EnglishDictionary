# Week 13 — Property and composite conditions

## Контекст

Property conditions выражают явное намерение. Activation и replacement — разные оси. Composite/custom conditions допустимы только при явной domain semantics и предсказуемом lifecycle.

## Property conditions

Подходит для: feature toggle, coarse mode, opt-in к дорогой/рискованной функции, legacy switch, явного отключения default.

**Must-not:** заменять binding/validation `@ConfigurationProperties`. Condition = активировать ли slice; properties = значения внутри активного slice.

### Default rule без havingValue

Без `havingValue` совпадение зависит от существования property и того, что значение ≠ `"false"`. Произвольные строки ведут себя неочевидно.

Для boolean **must**: явный `havingValue = "true"` или `@ConditionalOnBooleanProperty`; тестировать missing / true / false / invalid.

## Таблица истинности @ConditionalOnProperty

Сравнение с `havingValue` (упрощённо):

| Property value | havingValue="" | "true" | "false" | "foo" |
|---|---|---|---|---|
| `"true"` | match | match | no | no |
| `"false"` | no | no | match | no |
| `"foo"` | match | no | no | match |
| absent | по `matchIfMissing` (default false → no) | | | |

Несколько имён в `name` — **AND**, не OR. Альтернативы → composite или нормализованная model.

Properties в `name` — dashed notation. Prefix + name = стабильный namespace (`acme.observability.enabled`).

## matchIfMissing и владение default

| | Semantics | Риск |
|---|---|---|
| `matchIfMissing = true` | opt-out (включено по умолчанию) | новый opt-out slice активируется у всех consumers после upgrade |
| `matchIfMissing = false` | opt-in | — |

Влияет на: startup cost, side effects, security, operational signals, совместимость подключения starter, безопасное добавление feature в minor.

**Must:** один источник default между condition, `@ConfigurationProperties`, metadata и docs.

**Must-not:** `matchIfMissing = true` при `AcmeProperties.enabled = false` — condition и bound object расходятся.

## Boolean properties

`@ConditionalOnBooleanProperty` явно фиксирует boolean semantics.

Всё равно ответить: отсутствие property; динамическое изменение; slice vs bean; нормальность отключения; закрытие resources.

Conditions вычисляются при построении context. Изменение property после startup **не** пересобирает graph.

**Must-not:** документировать conditional property как runtime toggle без refresh/reconfiguration. Для динамики — stable bean + runtime policy.

## Collections

`@ConditionalOnProperty` **не** надёжна для collection / indexed keys (`acme.targets` vs `acme.targets[0]`).

Предпочтительные модели:

1. Отдельный boolean `enabled` + collection через binding/validation (обычно лучшее).
2. Custom Condition с Binder и явной semantics.
3. Всегда регистрировать coordinator; пустая collection = валидное runtime state.

## Activation vs replacement

| Feature enabled | User bean | Результат |
|---|---|---|
| нет | нет | slice выключен |
| нет | есть | по опубликованной policy |
| да | нет | platform default |
| да | есть | user impl, default backs off |

Трудная клетка: `enabled=false` + user bean. Либо property контролирует всю feature (bean не реактивирует), либо user bean = opt-in (property не нужен). Одна модель, документирована.

Предпочтительный порядок владения (если явный):

```text
hard prerequisite -> explicit disable -> explicit user replacement
  -> platform default eligibility -> validation
```

**Must-not:** двойное управление без приоритета (скрытый state machine).

## Resource / environment / web

Evidence среды, не бизнес-намерение: `@ConditionalOnWebApplication`, `@ConditionalOnResource`, `@ConditionalOnNotWebApplication`.

Вопросы: необходимый vs коррелирующий признак; стабильность packaging; AOT/native; объяснимость mismatch; более явный property/API.

## SpEL hazards

`@ConditionalOnExpression` с bean reference → ранняя инициализация; объект без полного post-processing / binding.

Альтернативы: property conditions; custom Condition без создания beans; разделение configurations; runtime strategy после binding; validation для допустимости значений.

Простота = предсказуемость lifecycle, не число строк SpEL.

## Composite conditions

`AllNestedConditions` / `AnyNestedCondition` / `NoneNestedConditions` + явная `ConfigurationPhase`.

```java
final class OnAcmeTransportCondition extends AllNestedConditions {
    OnAcmeTransportCondition() {
        super(ConfigurationPhase.REGISTER_BEAN);
    }
    @ConditionalOnProperty(prefix = "acme.transport", name = "enabled",
            havingValue = "true")
    static final class Enabled { }
    @ConditionalOnBean(AcmeClient.class)
    static final class ClientAvailable { }
}
```

**Must:** domain name + reusable semantics. Decision table до реализации.

**Must-not:** `Any(All(None(...)))` maze.

## Custom Condition

Оправдан: structured bind; domain rule по нескольким properties; platform capability via metadata; специальный diagnostic; classpath compatibility policy.

**Must:** side-effect free; быстрый; детерминированный; безопасен при частичном registry; не создаёт beans; без network/filesystem I/O; точный outcome message; протестирован по ветвям.

**Must-not:** remote readiness как condition.

## ConfigurationPhase

| Phase | Когда |
|---|---|
| PARSE_CONFIGURATION | class разбирать? Classpath/property metadata |
| REGISTER_BEAN | регистрация definitions; bean conditions |

Самая ранняя фаза, где доступны все данные. Composite phase **must** соответствовать вложенным условиям.

## Diagnostic outcome

`ConditionOutcome` + `ConditionMessage`: feature, проверенный input, observed state, remediation.

Плохо: `condition failed`. Хорошо: property `'acme.transport.enabled' was 'false'` или `no single candidate of type 'AcmeClient'; found 'euClient', 'usClient'`.

Actionable semantics собственных messages — часть supportability. Дословная стабильность framework messages не обещается.

## Class-level vs method-level

| Placement | При mismatch | Подходит для |
|---|---|---|
| Auto-config class | весь slice | общий classpath/context prerequisite |
| Nested configuration | группа beans | optional integration / mode |
| `@Bean` method | одна definition | точечный replacement |

Слишком широкий class-level `@ConditionalOnMissingBean` отключает metrics/health/customizers вместе с runtime bean.

Группируйте beans по общей причине существования. Operational adapters к user-provided service **must-not** жить под missing-bean default implementation.

## Декомпозиция slices

```text
AcmeAutoConfiguration
├── common properties and customizers
├── ClientDefaultConfiguration (classpath + missing AcmeClient)
├── ServiceConfiguration (enabled + single AcmeClient)
├── MetricsConfiguration (Micrometer + AcmeService)
└── HealthConfiguration (Actuator + AcmeService)
```

User `AcmeClient` подавляет только client default. User `AcmeService` подавляет service default; metrics/health могут адаптировать user service, если контракт это заявляет.

Каждый slice: purpose, inputs, outputs, ownership, diagnostics, evolution note.

## Ключевые тезисы

- Property = intent; MissingBean = replacement; оси независимы.
- Один источник default активации.
- Conditional property ≠ runtime toggle.
- SpEL с bean reference — lifecycle hazard.
- Custom Condition без side effects и remote I/O.
- Slice topology задаёт, что отступает вместе.
