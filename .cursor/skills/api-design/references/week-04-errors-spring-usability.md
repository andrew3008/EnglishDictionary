# Неделя 04 — Errors, feedback, Spring Boot usability

## Errors как основной интерфейс

**Must not:** polished success API + `IllegalStateException: invalid configuration` на failure path.

### Actionable error message must отвечать

- что не удалось
- какое значение/состояние наблюдалось
- какое правило нарушено
- где источник настройки
- какие варианты допустимы
- что сделать дальше
- безопен ли retry

### Локальность ошибки

**Must обнаруживать близко к причине:**

| Ошибка | Где |
|---|---|
| Неверный property | startup validation |
| Конфликт beans | context creation + имена candidates |
| Builder invariant | `build()` |
| **Must not** | первый production request |

### Exception taxonomy → recovery

| Тип | Действие потребителя |
|---|---|
| Programming error | исправить код |
| Invalid configuration | исправить deployment input |
| Transient dependency | retry/degrade |
| Permanent remote rejection | изменить request |
| Lifecycle violation | исправить sequencing |
| Unsupported capability | другой mode |

**Must not:** все failures в `PlatformException` с парсингом message.

**Must:** переводить internal cause (`No qualifying bean OtelSdkBridge`) на язык публичного контракта; cause для сопровождающего.

## Feedback и доверие

**Must:** после конфигурации, регистрации, async export, fallback — наблюдаемый результат (return, event, log, metric, health, actuator).

**Must not:** разные классификации в health (`PARTIAL`) vs runtime (`DEGRADED`) vs metrics (`fallback`).

## Effective configuration

**Must предоставлять** для сложных starter:

```text
export.mode = http                 source: application.yml
export.timeout = 3s                source: library default
export.compression = gzip          source: mode default
export.endpoint = https://...      source: environment
```

**Must not:** раскрывать secrets.

## Spring Boot configuration usability

### Prefix и иерархия

**Must:** tree отражает conceptual structure, не классы реализации.

```yaml
platform:
  tracing:
    export:
      mode: otlp
      endpoint: https://collector
      timeout: 3s
```

**Must not:** плоский `enable-export`, `otlp-url`, `sdk-timeout-ms` — смешение capability, vendor, representation.

### Configuration metadata = IDE API

**Must в description property:**

- когда применяется
- default
- единица/формат
- конфликтующие и зависимые properties
- влияние на runtime
- security/operational consequences

**Must not:** неполная metadata — скрывает supported configuration.

### Boolean vs mode

**Must not:** несколько `enabled`-флагов с комбinatorics.

**Must:** один `mode: disabled | auto | agent | starter` когда capability имеет режимы.

Boolean — только для независимой бинарной возможности.

### Deprecation properties

**Must:** replacement, reason, conflict policy, детерминированная диагностика при old+new.

**Must not:** молчаливый legacy alias до первого конфликта.

## Auto-configuration usability

Auto-configuration — **система решений** (classpath, properties, beans, ordering).

**Must для каждой auto-configuration:**

- предполагаемый user intent
- evidence intent
- что создаётся
- что считается override
- поведение при конфликте
- где увидеть принятое решение

### Back-off semantics

`@ConditionalOnMissingBean` — **must** документировать:

- какой type = замена
- нужен ли qualifier
- какие side effects исчезнут
- кто отвечает за lifecycle

Back-off = публичный extension contract.

### Fail-fast vs back-off

**Must fail-fast:** неоднозначность, частичная замена.

**Must back-off:** пользовательская конфигурация — полноценная альтернатива.

**Must not:** молчаливый выбор bean ради успешного startup.

### Condition diagnostics

**Must:** доменный summary для критичных capabilities:

```text
Tracing export: NOT ACTIVE
Reason: mode=otlp, but endpoint is absent
Resolution: set platform.tracing.export.endpoint or choose mode=disabled
```

**Must not:** raw condition report как единственный UX для application developer.

## SPI usability

**Must разделять персон:**

- application developer — использование capability
- extension author — реализация части capability

**Must not:** смешивать lifecycle callbacks в public interface для app dev.

### Minimal complete SPI must определять

- lifecycle, threading, ownership
- error propagation, ordering
- optional capabilities
- compatibility expectations
- testing support (contract tests / test kit)

### Context object

**Must:** только информация для callback, role-expressive accessors, defined lifetime.

**Must not:** context как service locator всего container.

## Анти-patterns usability

| Anti-pattern | Must not |
|---|---|
| God facade | autocomplete каталог несвязанных операций |
| Options bag | `Map<String,Object>` без schema |
| Magic by classpath | поведение меняется transitively без property confirmation |
| Convenience overloads без канона | расходящиеся defaults и docs paths |
| Compile-time puzzle | generics/staged builder без diagnosability |
| Silent fallback | no-op без сигнала при нарушении intent |
