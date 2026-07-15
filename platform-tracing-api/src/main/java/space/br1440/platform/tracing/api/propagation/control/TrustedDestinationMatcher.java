package space.br1440.platform.tracing.api.propagation.control;

/**
 * Определяет, является ли destination доверенным для получения платформенных заголовков.
 * <p>
 * Реализация: {@code space.br1440.platform.tracing.core.propagation.control.GlobTrustedDestinationMatcher}
 * (создаётся через {@code space.br1440.platform.tracing.core.propagation.control.TrustedDestinationMatchers}).
 */
public interface TrustedDestinationMatcher {

    /**
     * @param destination HTTP-хост или Kafka-топик
     * @return {@code true} если destination доверен и должен получить платформенные заголовки
     */
    boolean isTrusted(String destination);
}
