package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

public enum JmxDiagnosticKind {

    // -- ContractViolation --------------------------------------------------

    /** Обязательный член наблюдения или атрибут вернул {@code null}. */
    NULL_VALUE_FOR_REQUIRED_MEMBER,

    /** Скалярное значение имеет тип, отличный от ожидаемого. */
    VALUE_TYPE_MISMATCH,

    /** Ключ динамической карты имеет тип, отличный от {@code String}. */
    MAP_KEY_TYPE_MISMATCH,

    /** Значение элемента динамической карты имеет тип, отличный от ожидаемого. */
    MAP_VALUE_TYPE_MISMATCH,

    /** Значение синтаксически корректного типа не проходит семантический валидатор (например, {@code ratio ∉ [0,1]}). */
    VALUE_SEMANTICS_VIOLATION,

    /** Ожидаемый атрибут отсутствует в {@code MBeanInfo}. */
    MISSING_ATTRIBUTE,

    /** Ожидаемая операция отсутствует или сигнатура отличается от заявленной. */
    OPERATION_SIGNATURE_DRIFT,

    /** Имя полученного {@code CompositeType} не совпадает с нормативным. */
    COMPOSITE_TYPE_NAME_MISMATCH,

    /** В observation отсутствует обязательный item. */
    MISSING_OBSERVATION_ITEM,

    /** {@code ownerGeneration = 0} при {@code ownerPresent = true} — нарушение схемы. */
    OWNER_GENERATION_INVALID,

    /** Полученная JMX-нагрузка не является ограниченным графом, нейтральным к загрузчикам. */
    JMX_WIRE_TYPE_VIOLATION,

    /** Метаданные MBean не совпадают с точной формой сгенерированного OpenType-контракта. */
    MBEAN_INFO_SHAPE_VIOLATION,


    // -- ExecutionFailure -----------------------------------------------------

    /** {@code MBeanException} / {@code RuntimeMBeanException} — отказ внутри реализации MBean. */
    MBEAN_EXECUTION_FAILURE,

    /** {@code RuntimeErrorException} — {@code Error} внутри реализации MBean. */
    MBEAN_RUNTIME_ERROR,

    /** {@code SecurityException} на вызове transport. */
    SECURITY_FAILURE,

    /** {@code IOException} соединения {@code MBeanServerConnection}. */
    CONNECTOR_FAILURE,

    /** {@code ReflectionException} с cause, не входящим в классифицированный набор. */
    REFLECTION_UNCLASSIFIED,

    /** {@code RuntimeOperationsException} с cause, отличным от {@code IllegalArgumentException}. */
    OPERATIONS_EXCEPTION_UNCLASSIFIED,

    /** Получение {@code MBeanInfo} завершилось ошибкой introspection на стороне provider. */
    METADATA_RETRIEVAL_FAILURE

}
