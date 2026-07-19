# Platform Tracing Slice C1 Evidence

Дата: 2026-07-19  
Ветка: `feature/runtime-control-hardening`  
Исходный HEAD: `bc25242`

## Результат

Slice C1 завершён в соответствии с composition model, доказанной Spike A:

- удалён API holder `OtelTraceparentReaders` со static `ServiceLoader` lookup;
- удалён provider descriptor `META-INF/services/...OtelTraceparentReader`;
- удалён `SpanSpecBuilder.fromTraceparent(String...)`, поскольку static `SpanSpec.builder()` не имеет composition root;
- manual builders, получаемые из `SpanFactory`, сохранили `fromTraceparent(String...)`;
- `DefaultSpanFactory` выбирает application-side reader по runtime mode и передаёт его по всей builder-цепочке как instance dependency;
- enabled/test используют `OtelTraceparentReaderImpl.INSTANCE`;
- disabled/unavailable/noop используют no-op reader и не разбирают transport input;
- публичный `SpanFactory` не содержит `OtelTraceparentReader` в сигнатурах.

Spring+Agent продолжает использовать application-side reader; agent-side объект приложению не передаётся. Agent-only composition не создаёт facade, `SpanFactory` или reader.

## Architecture Gates

ArchUnit-исключение для `OtelTraceparentReaders` удалено. Правило `API_NO_SERVICE_LOADER` теперь запрещает любой API production-класс, зависящий от `java.util.ServiceLoader`.

Доступ к `OtelTraceparentReaderImpl` расширен ровно на утверждённого composition owner-а `DefaultSpanFactory`. Остальной allowlist не ослаблен.

`DefaultSpanFactoryReaderCompositionTest` доказывает использование подставленного reader-а и no-op semantics для disabled runtime.

## ABI

Фактический ABI diff содержит только два утверждённых удаления:

- тип `space.br1440.platform.tracing.api.propagation.OtelTraceparentReaders` и его `get()`;
- метод `SpanSpecBuilder.fromTraceparent(String[])`.

Публичные конструкторы и сигнатуры `SpanFactory`/manual builders не изменились.

## Верификация

- `gradlew.bat :platform-tracing-api:compileTestJava :platform-tracing-core:compileTestJava :platform-tracing-spring-boot-autoconfigure:compileTestJava :platform-tracing-samples:compileJava --no-daemon` — PASS.
- `gradlew.bat :platform-tracing-api:test :platform-tracing-core:test :platform-tracing-api:javadoc :platform-tracing-core:javadoc pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify --no-daemon` — PASS.
- `gradlew.bat build --no-daemon` — PASS, 115 actionable tasks.
- Поиск `OtelTraceparentReaders` по активным Java-исходникам — 0 совпадений.
- Поиск `java.util.ServiceLoader` в API main — 0 совпадений.
- API JAR не содержит holder или reader service descriptor.
- Проверка UTF-8 BOM по `*.java` — 0 файлов.

Общий e2e test task остался `SKIPPED` из-за существующего opt-in gate. Slice C1 не меняет agent packaging и не требует утверждения CP-C2.
