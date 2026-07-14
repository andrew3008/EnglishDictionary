package space.br1440.platform.tracing.api.semconv;

/**
 * Режим валидации платформенного semantic-контракта при создании/обогащении span'ов.
 * <p>
 * Поддерживаемые режимы:
 * <ul>
 *   <li>{@link #STRICT} — fail-fast для CI/test: при нарушении контракта бросается
 *       {@link SemconvViolationException} <b>до</b> {@code startSpan()} (на build-фазе), что
 *       ломает тест, но не бизнес-логику в runtime. В production runtime НЕ поддерживается.</li>
 *   <li>{@link #WARN} — production default: span создаётся всегда; для безопасных
 *       platform-required полей подставляются safe-defaults (сам факт подстановки считается
 *       нарушением), пишется rate-limited лог и метрика нарушений. Соответствует OTel Error Handling spec
 *       («MUST NOT throw unhandled exceptions at runtime»).</li>
 *   <li>{@link #DISABLED} — явный аварийный opt-out из runtime-валидации:
 *       атрибуты передаются as-is, дефолты не подставляются, per-span логов/метрик нарушений нет.</li>
 * </ul>
 */
public enum SemconvValidationMode {

    /** Fail-fast для CI/test: бросает {@link SemconvViolationException} до старта span'а. */
    STRICT,

    /** Production default: safe-defaults + лог + метрика, span создаётся всегда. */
    WARN,

    /** Явный аварийный opt-out: атрибуты as-is, без дефолтов/логов/метрик. */
    DISABLED

}
