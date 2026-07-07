package space.br1440.platform.tracing.api.control.protocol.validation;

/**
 * Стабильные machine-readable коды violation, возвращаемые {@code TracingControlProtocolValidator}.
 * Каждый {@code TracingControlProtocolViolation} несёт ровно один code плюс diagnostic-поля:
 * ({@code key}, {@code reason}, {@code expectedType}, {@code actualType}).
 * Code — числовое значение для программной обработки результата валидации;
 * {@code reason} — человеко-читаемое сообщение для человека.
 */
public enum TracingControlProtocolViolationCode {

    /**
     * Значение {@code contractVersion} было успешно распарсено как integer major,
     * но этот major не поддерживается текущим protocol registry.
     */
    UNSUPPORTED_VERSION,

    /**
     * {@code contractVersion} присутствует в payload, но не может быть распарсен в integer major
     * (malformed wire value — {@code null}, произвольная строка, тип вне поддерживаемого набора).
     * Применяется <b>только</b> к {@code contractVersion}; для всех прочих известных полей
     * несоответствие типа или значения — это {@link #TYPE_MISMATCH}, а не этот code.
     */
    INVALID_VALUE,

    /**
     * Payload содержит ключ, который не зарегистрирован в схеме.
     */
    UNKNOWN_KEY,

    /**
     * Required-ключ для данного validation entrypoint отсутствует в payload.
     */
    MISSING_REQUIRED_KEY,

    /**
     * Известный ключ присутствует, но его wire-значение не соответствует ожидаемой форме,
     * типу или field-local семантическому ограничению.
     */
    TYPE_MISMATCH,

    /**
     * Поле или значение операции несовместимо с текущим validation entrypoint.
     * Покрывает два разных сценария:
     * <ul>
     *    <li>(1) category policy — поле категории {@code STARTUP_TOPOLOGY} отклоняется
     *        на любом wire-control path, поле категории {@code RUNTIME_POLICY} отклоняется на read path;
     *    </li>
     *    <li>(2) operation allowlist — значение {@code operation} не входит в допустимый набор для
     *        данного entry method (например, {@code READ_SCHEMA} на runtime-mutation entrypoint).
     *    </li>
     * </ul>
     */
    OPERATION_NOT_ALLOWED

}