package space.br1440.platform.tracing.api.spi;

/**
 * Действие, которое {@code ScrubbingSpanProcessor} применяет к значению атрибута, когда
 * сработало {@link SensitiveDataRule}.
 */
public enum ScrubbingAction {

    /** Значение безопасно — оставить как есть. */
    KEEP,

    /** Заменить значение на универсальную маску {@code ***} (для строк). */
    MASK,

    /**
     * Удалить значение. У {@code ReadWriteSpan} нет remove-API, поэтому реализуется как
     * overwrite пустой строкой {@code ""} (STRING) или type-neutral sentinel
     * ({@code 0}/{@code 0.0}/{@code false}) для нестроковых типов.
     */
    DROP,

    /**
     * Заменить значение на HMAC-SHA256 hex от исходного. Применяется к идентификаторам, для
     * которых нужна корреляция в backend без раскрытия исходного значения (email, логин).
     */
    HASH,

    /**
     * Усечь значение до {@link ScrubbingDecision#maxLength()} символов (character-based).
     * Для IP-адресов используется как prefix-grouping (подсеть).
     */
    TRUNCATE

}
