package space.br1440.platform.tracing.otel.javaagent.bootstrap;

/**
 * Замкнутый набор кодов отказа аттестации контролируемого дистрибутива. Имя константы
 * является wire-токеном readiness-снимка, поэтому второго представления кода нет.
 */
enum AttestationFailureCode {

    /** Обязательное доказательство дистрибутива отсутствует или нечитаемо. */
    DISTRIBUTION_EVIDENCE_MISSING,

    /** Дистрибутив измерен, но не совпадает с ожидаемой identity или дайджестом. */
    DISTRIBUTION_MISMATCH,

    /** Профиль production не допускает версию расширения development. */
    DEVELOPMENT_VERSION_FORBIDDEN,

    /**
     * Настоящий {@link LinkageError} при активации: разрыв ABI между агентом и расширением.
     * Гейта, штампующего этот код без измерения, не существует.
     */
    EXTENSION_LINKAGE_ERROR
}
