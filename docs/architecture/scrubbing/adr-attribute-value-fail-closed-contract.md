# ADR: единый владелец контракта AttributeType.VALUE

Статус: ACCEPTED
Дата: 2026-08-23

## Решение

Единственный владелец контракта `AttributeType.VALUE` — **exporter boundary**
(`SpanDataBoundaryScrubber` + `AttributesScrubber`).

Процессорная плоскость (`ScrubbingSpanProcessor`, `ScrubbingRuntime`) не владеет
`AttributeType.VALUE` и ничего для него не пишет.

Внешняя гарантия: semantic key, у которого есть представление `AttributeType.VALUE`,
**отсутствует** в экспортируемом payload целиком — вместе со всеми типизированными
alias'ами того же имени. Ни sentinel, ни MASK/HASH/TRUNCATE-derivative не являются
частью внешнего wire-контракта для `VALUE_*`.

## Причина: процессор структурно слеп к VALUE

`SdkSpan.toSpanData()` разворачивает `Value` в примитивный тип, когда форма это
позволяет. Измерено на реальном `SdkTracerProvider`:

| Форма `Value` | Тип в снапшоте процессора | Java-класс значения |
|---|---|---|
| `Value.of(String)` | `STRING` | `String` |
| `Value.of(long)` | `LONG` | `Long` |
| `Value.of(boolean)` | `BOOLEAN` | `Boolean` |
| `Value.of(Value…)` (array) | `STRING_ARRAY` | `ArrayList` |
| `Value.of(byte[])` | `VALUE` | `ValueBytes` |
| `Value.of(Map)` | `VALUE` | `KeyValueList` |
| `Value.empty()` | `VALUE` | `ValueEmpty` |

Следствия для процессорной плоскости:

1. Для плоских форм проверка `key.getType() == AttributeType.VALUE` не срабатывает:
   атрибут неотличим от обычного `STRING`/`LONG`/`BOOLEAN`/`STRING_ARRAY`.
2. Процессор применяет к такому представлению обычное правило и пишет результат в
   одноимённый **типизированный** ключ.
3. Карта атрибутов живого span'а ключуется парой имя+тип, поэтому исходный VALUE не
   заменяется, а остаётся рядом с новым alias'ом.
4. В экспорт уходят две записи одного semantic key: безопасный alias и сырой VALUE.

Именно этот механизм давал утечку в qualification-прогоне `scrub-20260822-44`:
падали `TYPE_ACTION_009` (scalar string) и `TYPE_ACTION_010` (array) — плоские формы,
тогда как `TYPE_ACTION_011`/`023` (`VALUE_MAP`) проходили, потому что структурные
формы сохраняют тип `VALUE` и попадали под старую процессорную ветку.

Процессор не может отличить flattened VALUE от обычного атрибута и не может удалить
атрибут из живого span'а. Поэтому владение контрактом на этой плоскости
недостижимо, а его имитация создаёт ложную уверенность.

## Реализация

Процессорная плоскость:

- `ScrubbingRuntime.boundaryOwned(AttributeKey)` — единственная точка, задающая
  «VALUE принадлежит границе»; используется в `scrubSpanAttributes` и
  `applyFailClosedSnapshot`.
- VALUE пропускается без записи и без учёта в `maskedActions`.
- Sentinel-машинерия для VALUE в процессоре удалена: она была недостижима для
  плоских форм и создавала alias для структурных.

Exporter boundary:

- `AttributesScrubber.removeValueAttributeTrees` удаляет каждый semantic key с
  VALUE-представлением вместе с alias'ами того же имени.
- То же правило действует внутри `ScrubPass` до вызова `MergeEngine`: недостоверный
  `KEEP` старого `SpanAttributeScrubbingRule` SPI не может авторизовать VALUE.
- Удаление fail-closed и при пустом наборе attribute rules.
- При critical или непредвиденной ошибке границы `collapseBoundarySurfaces`
  схлопывает и span attributes: сырое представление не должно переживать сбой.
- Удаления наблюдаемы: `ScrubbingBoundaryMetrics.valueAttributesRemoved`.

## Принятые издержки

Удаляется весь semantic key, включая легитимный одноимённый атрибут другого типа.
Это осознанный fail-closed выбор: коллизия имени с VALUE-представлением встречается
только там, где alias создала сама процессорная плоскость, а приватность
приоритетнее полноты телеметрии. Величина эффекта наблюдаема через
`valueAttributesRemoved`.

## Верификация

`ValueAttributeFailClosedContractTest` собирает полный путь
«живой `SdkSpan` -> `ScrubbingSpanProcessor` -> exporter boundary» и проверяет для
11 форм `Value` × 5 действий (включая `KEEP`): probe key отсутствует, канарейка не
встречается ни в одном представлении, control-атрибут на месте (отрицательная
проверка не проходит vacuously), удаление отражено в метрике.

Контракт нельзя проверять на моке `ReadWriteSpan` с вручную заданным типом `VALUE`:
такой снапшот не воспроизводит flattening и потому не описывает production.

## Что запрещено

- Возвращать контракт `AttributeType.VALUE` в процессорную плоскость.
- Считать processor sentinel частью внешнего OTLP/Jaeger-контракта.
- Пропускать `AttributeType.VALUE` через legacy SPI на основании `KEEP`.
- Сериализовать произвольный `Value` через `toString()` для HASH/MASK/TRUNCATE.
- Проверять контракт `VALUE_*` только на моках или только на структурных формах.

## Если потребуется видимый derivative

Понадобится типизированный SPI (`StructuredValueScrubbingRule`) и ограниченный tree
sanitizer с лимитами depth/node count/bytes, явной политикой для map keys и byte
arrays, стабильной каноникализацией перед HMAC и fail-closed collapse при
неизвестном типе или превышении лимита. До появления такого SPI внешний контракт —
удаление всего semantic key на exporter boundary.
