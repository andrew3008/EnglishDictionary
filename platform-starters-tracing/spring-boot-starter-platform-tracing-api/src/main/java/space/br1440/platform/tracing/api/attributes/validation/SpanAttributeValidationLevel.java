package space.br1440.platform.tracing.api.attributes.validation;

/**
 * Уровень проверки атрибутов span'а, создаваемых платформенными builders.
 * <p>
 * Значения — взаимоисключающие режимы окружений, а не градация строгости одной оси:
 * <ul>
 *   <li>{@link #ERROR} — fail-fast для CI/test: при нарушении контракта бросается
 *       {@link SpanAttributeViolationException} <b>до</b> {@code startSpan()} (на build-фазе), что
 *       ломает тест, но не бизнес-логику в runtime. Это прикладной контракт builders, а не
 *       агентский onEnding: агентский {@code ERROR} пишет диагностику и не запрещает экспорт.</li>
 *   <li>{@link #WARNING} — production default: span создаётся всегда; для безопасных
 *       platform-required полей подставляются safe-defaults (сам факт подстановки считается
 *       нарушением), пишется rate-limited лог и метрика нарушений. Соответствует OTel Error Handling spec
 *       («MUST NOT throw unhandled exceptions at runtime»).</li>
 *   <li>{@link #DISABLED} — явный аварийный opt-out из runtime-валидации:
 *       атрибуты передаются as-is, дефолты не подставляются, per-span логов/метрик нарушений нет.</li>
 * </ul>
 */
public enum SpanAttributeValidationLevel {

    /** Fail-fast для CI/test: бросает {@link SpanAttributeViolationException} до старта span'а. */
    ERROR,

    /** Production default: safe-defaults + лог + метрика, span создаётся всегда. */
    WARNING,

    /** Явный аварийный opt-out: атрибуты as-is, без дефолтов/логов/метрик. */
    DISABLED

}
