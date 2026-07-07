package space.br1440.platform.tracing.api.control.protocol.schema;

public enum TracingControlProtocolOperation {

    /**
     * Применяет изменения runtime policy к конфигурации управления трейсингом.
     */
    APPLY_RUNTIME_POLICY,

    /**
     * Валидирует payload runtime policy без применения (dry-run).
     */
    VALIDATE_RUNTIME_POLICY,

    /**
     * Читает текущее применённое состояние runtime policy.
     */
    READ_APPLIED_STATE,

    /**
     * Читает метаданные схемы протокола (introspection).
     */
    READ_SCHEMA

}