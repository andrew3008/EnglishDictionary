package space.br1440.platform.tracing.api.propagation.control;

/**
 * Allowlist-matcher для доверенных HTTP-хостов или Kafka-топиков.
 * <p>
 * Создание экземпляров — через {@code TrustedDestinationMatchers} в {@code platform-tracing-core}.
 * Реализации обязаны быть thread-safe и immutable.
 */
public interface TrustedDestinationMatcher {

    /**
     * Проверяет, является ли {@code destination} доверенным.
     *
     * <p>Для HTTP-режима: {@code destination} — hostname (без порта, без схемы).
     * Для Kafka-режима: {@code destination} — имя топика.
     *
     * @param destination hostname или имя топика; {@code null} безопасен — всегда возвращает {@code false}
     * @return {@code true} если destination входит в allowlist
     */
    boolean isTrusted(String destination);
}
