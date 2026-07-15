package space.br1440.platform.tracing.core.propagation.control;

import space.br1440.platform.tracing.api.propagation.control.TrustedDestinationMatcher;

import java.util.List;

/**
 * Фабрика для создания {@link TrustedDestinationMatcher} реализаций.
 * <p>
 * Единственная точка создания matcher'ов для wiring-слоя (autoconfigure).
 * Тип реализации ({@link GlobTrustedDestinationMatcher}) не экспортируется.
 *
 * <p>Пример:
 * <pre>{@code
 * TrustedDestinationMatcher matcher = TrustedDestinationMatchers.forHttpHosts(
 *         List.of("*.internal.example.com"), false);
 * }</pre>
 */
public final class TrustedDestinationMatchers {

    private TrustedDestinationMatchers() {
    }

    /**
     * Создаёт matcher для HTTP-хостов по glob-паттернам.
     *
     * @param hostPatterns glob-паттерны хостов ({@code *.internal.example.com}, {@code api.example.com})
     * @param allowIpLiterals разрешить IPv4-литералы (рекомендуется {@code false} в production)
     */
    public static TrustedDestinationMatcher forHttpHosts(
            List<String> hostPatterns, boolean allowIpLiterals) {
        return new GlobTrustedDestinationMatcher(hostPatterns, true, allowIpLiterals);
    }

    /**
     * Создаёт matcher для Kafka-топиков по glob-паттернам.
     *
     * @param topicPatterns glob-паттерны топиков ({@code platform.*}, {@code events.user.*})
     */
    public static TrustedDestinationMatcher forKafkaTopics(List<String> topicPatterns) {
        return new GlobTrustedDestinationMatcher(topicPatterns, false, true);
    }
}
