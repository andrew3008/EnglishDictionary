# platform-common-util

Внутренняя JDK-библиотека null-безопасных проверок пустоты. Это не Spring Boot starter:
автоконфигурации, Spring, OpenTelemetry и JMX в модуле нет.

## Координаты

| | Значение |
|---|---|
| Group | `space.br1440.platform.starters` |
| Artifact | `platform-common-util` |
| Gradle | `:platform-starters-common:platform-common-util` |
| Пакет | `space.br1440.platform.common.util` |

```groovy
implementation 'space.br1440.platform.starters:platform-common-util:{версия}'
```

Версия выравнивается platform BOM:

```groovy
api platform('space.br1440.platform:spring-boot-starter-platform-bom:{версия}')
```

Единственная production-зависимость — `jakarta.annotation:jakarta.annotation-api` (`api`-scope, для `@Nullable` в публичных сигнатурах). `commons-lang3` и Lombok не подключаются.

## Публичный API

| Класс | Методы |
|---|---|
| `StringUtils` | `isNullOrEmpty(String)`, `isNullOrBlank(String)` |
| `ArrayUtils` | `isNullOrEmpty(T[])` |
| `ListUtils` | `isNullOrEmpty(List<?>)` |
| `SetUtils` | `isNullOrEmpty(Set<?>)` |
| `MapUtils` | `isNullOrEmpty(Map<?,?>)`, `containsKey(Map<K,V>, K)`, `getOrDefault(Map<K,V>, K, V)`, `size(Map<?,?>)` |

FQCN: `space.br1440.platform.common.util.StringUtils` (и аналогично для остальных классов).

Имя `isNullOrEmpty` / `isNullOrBlank` называет заменяемое выражение:

```java
if (StringUtils.isNullOrEmpty(value)) {
    // value == null || value.isEmpty()
}
```

Положительной формы нет: вызывающий пишет `!isNullOrEmpty(value)`.

## Контракт

`null` — валидный вход. Методы не бросают `NullPointerException` из-за `null`-аргумента.

| Вход | `isNullOrEmpty` | `isNullOrBlank` |
|---|---|---|
| `null` | `true` | `true` |
| `""` / пустая коллекция / `{}` | `true` | `true` |
| `"   "` | `false` | `true` |
| `"x"` / непустая коллекция | `false` | `false` |

`isNullOrBlank` делегирует `String.isBlank()` (whitespace по `Character.isWhitespace`). Имя `isNullOrWhiteSpace` не вводится: это тот же предикат.

`ArrayUtils` принимает только `T[]`. Примитивные массивы (`int[]`, `long[]` и остальные) вне v1.

`MapUtils`:

- `containsKey(null, key)` → `false`
- `size(null)` → `0`
- `getOrDefault(null или empty, key, default)` → `default`; метод не подменяет переданный default на `null`
- если ключ есть и хранит `null`, возвращается это хранимое `null`

## Граница семейства `isNullOrX`

Семейство закрыто на emptiness и blankness перечисленных типов. Методы вида `isNullOrZero`, `isNullOrDefault`, `isNullOrEmptyOrBlank` и любые хелперы, склеивающие произвольные условия, в API не принимаются. Композицию условий пишет вызывающий. Расширение семейства — только отдельным решением, не по месту в PR.

## Имена классов и коллизии

Суффикс `Utils` намеренный. Короткие имена `Strings` / `Arrays` / `Lists` / `Sets` / `Maps` отклонены: `Arrays` сталкивается с `java.util.Arrays`, а остальные — с Guava у потребителей модуля.

Простые имена `StringUtils` и `ArrayUtils` совпадают с Apache Commons Lang. В одном исходном файле допускается только один `StringUtils` (или `ArrayUtils`); второй импортируется по FQCN. Методы платформы (`isNullOrEmpty`) не совпадают с Commons (`isEmpty`), поэтому ошибочный импорт `org.apache.commons.lang3.StringUtils` не компилируется на вызове `isNullOrEmpty`.
