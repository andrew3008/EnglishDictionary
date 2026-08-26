# Platform tracing JMX contract

Модуль содержит build-time source of truth для JMX wire-контракта. Он не является общим
runtime API: extension и Spring application получают разные Java-классы с одинаковыми
значениями и Open Type descriptors.

Основные задачи:

- `generateJmxWireContract` — генерирует обе плоскости;
- `validateJmxWireContract` — проверяет manifest и positive/negative golden-векторы;
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

Runtime-модули должны зависеть от задачи генерации и подключать только собственную
плоскость. Подключать JAR этого модуля к runtime classpath запрещено.

## Границы модуля

Это одноразовый станок на один манифест, а не фреймворк генерации схем. Запрещено:

- обобщать генератор «на любые схемы», добавлять plugin SPI или registry;
- добавлять второй манифест в этот модуль;
- публиковать модуль или включать его в BOM;
- выносить SnakeYAML на runtime classpath агента или стартера.

## Что манифест не описывает

В контракт не входят поля, которые сравниваются с константой сборки: такое поле пересекает
границу классов, чтобы быть сравненным само с собой, и ничего не проверяет. Секция
`readinessEvidence` перечисляет только измеряемые в рантайме факты.
