package space.br1440.platform.tracing.api.control.protocol.schema;

/**
 * Классифицирует каждое поле schema по lifecycle и entrypoint-политике control валидации.
 */
public enum TracingControlProtocolFieldCategory {

    /**
     * Поля control envelope, общие для всех operation. Всегда разрешены на любом validation entrypoint
     * ({@code validateRuntimePolicy} и {@code validateReadRequest}).
     */
    ENVELOPE,

    /**
     * Runtime-mutable поля policy. Разрешены при применении или валидации runtime policy
     * ({@code APPLY_RUNTIME_POLICY}, {@code VALIDATE_RUNTIME_POLICY}), но отклоняются с кодом
     * {@code OPERATION_NOT_ALLOWED} на read-request path — эти поля описывают, что можно
     * изменить в runtime, а не то, что можно прочитать обратно как applied state.
     */
    RUNTIME_POLICY,

    /**
     * Startup-only поля topology, которые фиксируются при старте процесса и
     * никогда не являются mutable или readable через control protocol.
     * Отклоняются с кодом {@code OPERATION_NOT_ALLOWED}.
     */
    STARTUP_TOPOLOGY,

    /**
     * Correlation- и diagnostic-метаданные. Разрешены как на runtime-policy, так и на read-request entrypoint;
     * в отличие от {@link #RUNTIME_POLICY}, diagnostic-поля не являются policy state, поэтому read-запросы не ограничены в их передаче.
     */
    DIAGNOSTIC

}
