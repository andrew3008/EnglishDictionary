package space.br1440.platform.tracing.api.spi;

import jakarta.annotation.Nonnull;

/**
 * Результат вычисления {@link SensitiveDataRule#evaluate}: какое действие применить к значению атрибута и почему.
 * <p>
 * Несёт <b>только</b> {@code action}, {@code reason} (для метрик/диагностики) и {@code maxLength}
 * (для {@link ScrubbingAction#TRUNCATE}). Конкретное новое значение атрибута вычисляет процессор
 * type-aware способом — решение намеренно не содержит {@code Object replacementValue},
 * чтобы не пропагировать type-aware логику в каждое правило.
 */
public record ScrubbingDecision(@Nonnull ScrubbingAction action, @Nonnull String reason, int maxLength, boolean terminal) {

    /**
     * Reason-маркер fail-closed, когда валидация по правилу не прошла.
     * Процессор по этому reason применяет type-safe collapse значения вместо обычной маски.
     */
    public static final String SCRUBBING_FAILED_REASON = "<SCRUBBING_FAILED>";

    /**
     * Кэшированное решение «оставить как есть» — самый горячий путь (большинство атрибутов безопасны).
     */
    private static final ScrubbingDecision KEEP = new ScrubbingDecision(ScrubbingAction.KEEP, "no-match", -1, false);

    /** Singleton fail-closed решения. */
    private static final ScrubbingDecision SCRUBBING_FAILED =
            new ScrubbingDecision(ScrubbingAction.MASK, SCRUBBING_FAILED_REASON, -1, false);

    /** Значение безопасно, изменений не требуется. */
    @Nonnull
    public static ScrubbingDecision keep() {
        return KEEP;
    }

    /** Заменить значение на маску {@code ***} (нетерминальное). */
    @Nonnull
    public static ScrubbingDecision mask(@Nonnull String reason) {
        return new ScrubbingDecision(ScrubbingAction.MASK, reason, -1, false);
    }

    /** Удалить значение (overwrite пустой строкой / type-neutral sentinel); нетерминальное. */
    @Nonnull
    public static ScrubbingDecision drop(@Nonnull String reason) {
        return new ScrubbingDecision(ScrubbingAction.DROP, reason, -1, false);
    }

    /** Заменить значение на HMAC-SHA256 hex (нетерминальное). */
    @Nonnull
    public static ScrubbingDecision hash(@Nonnull String reason) {
        return new ScrubbingDecision(ScrubbingAction.HASH, reason, -1, false);
    }

    /** Усечь значение до {@code maxLength} символов (для IP — prefix-grouping); нетерминальное. */
    @Nonnull
    public static ScrubbingDecision truncate(@Nonnull String reason, int maxLength) {
        return new ScrubbingDecision(ScrubbingAction.TRUNCATE, reason, maxLength, false);
    }

    /**
     * Терминальная маска: решение, которое нельзя ослабить последующими правилами. Предназначено
     * для critical built-in правил, защищающих секреты (например, заголовки авторизации).
     */
    @Nonnull
    public static ScrubbingDecision maskTerminal(@Nonnull String reason) {
        return new ScrubbingDecision(ScrubbingAction.MASK, reason, -1, true);
    }

    /**
     * Терминальное удаление: самое строгое решение, не ослабляемое последующими правилами.
     * Предназначено для critical built-in правил.
     */
    @Nonnull
    public static ScrubbingDecision dropTerminal(@Nonnull String reason) {
        return new ScrubbingDecision(ScrubbingAction.DROP, reason, -1, true);
    }

    /**
     * Fail-closed решение (валидация по правилу не прошла / circuit breaker открыт).
     * Нетерминальное: чтобы более строгое решение critical built-in могло его перекрыть в merge.
     */
    @Nonnull
    public static ScrubbingDecision scrubbingFailed() {
        return SCRUBBING_FAILED;
    }
}
