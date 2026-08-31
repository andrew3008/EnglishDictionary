# Platform tracing JMX contract

Модуль содержит build-time source of truth для JMX wire-контракта. Он не является общим
runtime API: extension и Spring application получают разные Java-классы с одинаковыми
значениями и Open Type descriptors.

Основные задачи:

- `generateJmxWireContract` — генерирует обе плоскости;
- `validateJmxWireContract` — проверяет manifest;
- `verifyJmxWireContract` — проверяет воспроизводимость, schema identity и parity;
- `compileGeneratedJmxWireContract` — компилирует оба независимых дерева generated sources;
- `verifyNoManualJmxWireLiterals` — отвергает рукописные wire-литералы в production-исходниках
  обеих плоскостей.

Все четыре verification-задачи навешены на `check` модуля: пайплайн CI не меняется, поэтому
задача, доступная только по имени, гейтом не является.

Generated output:

- `build/generated/jmx-wire-contract/extension/java`;
- `build/generated/jmx-wire-contract/extension/resources`;
- `build/generated/jmx-wire-contract/application/java`;
- `build/generated/jmx-wire-contract/application/resources`.

Каждая плоскость получает `JmxWireContract`, `JmxOpenTypes`, `JmxTracingSettingsDigest`
и `JmxWireUtf8Meter`. Измерение UTF-8 живёт рядом с лимитами бюджета, а не двумя
рукописными копиями.

Runtime-модули должны зависеть от задачи генерации и подключать только собственную
плоскость. Подключать JAR этого модуля к runtime classpath запрещено.
