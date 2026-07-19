# Platform Tracing Slice B Evidence

Дата: 2026-07-19  
Ветка: `feature/runtime-control-hardening`  
Исходный HEAD: `f852f7b`

## Результат

Slice B завершён. Публичная поверхность `platform-tracing-core` зафиксирована положительным allowlist из 63 верхнеуровневых типов. Любое добавление или удаление публичного типа теперь нарушает `PublicSurfaceAllowlistTest.PUBLIC_SURFACE_IS_EXACT`.

Пять accidental-public utility-классов удалены:

- `ArrayUtils`;
- `ListUtils`;
- `MapUtils`;
- `SetUtils`;
- `StringUtils`.

Их внутренние потребители переведены на эквивалентные прямые null/empty-проверки. Поиск по всем Java-исходникам не обнаружил оставшихся импортов или вызовов удалённых типов.

## ABI

Сравнение утверждённого snapshot с фактическим отчётом показало единственный intentional ABI delta: удаление пяти utility-типов и 14 их публичных методов. Остальные типы и сигнатуры не изменились.

Полный сигнатурный snapshot остаётся в `platform-tracing-api-core.txt`; отдельный список верхнеуровневых типов хранится в `platform-tracing-core-public-types.txt`.

## Верификация

- `gradlew.bat :platform-tracing-core:test --tests "*PublicSurfaceAllowlistTest" --no-daemon` — PASS.
- `gradlew.bat :platform-tracing-core:test --no-daemon` — PASS.
- `gradlew.bat build pr1ModuleTaxonomyVerify --no-daemon` — PASS, 116 actionable tasks.
- Поиск `core.utils|ArrayUtils|ListUtils|MapUtils|SetUtils|StringUtils` по `*.java` — 0 совпадений.
- Проверка UTF-8 BOM по `*.java` — 0 файлов.

Общий e2e test task остался `SKIPPED` из-за существующего opt-in gate; Slice B не меняет e2e-поведение.
