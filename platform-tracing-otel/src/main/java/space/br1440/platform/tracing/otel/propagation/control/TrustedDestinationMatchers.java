package space.br1440.platform.tracing.otel.propagation.control;

import space.br1440.platform.tracing.api.propagation.control.TrustedDestinationMatcher;

import java.util.List;

/**
 * Фабрика {@link TrustedDestinationMatcher} для wiring-слоя (autoconfigure).
 */
public final class TrustedDestinationMatchers {

    private TrustedDestinationMatchers() {
    }

    public static TrustedDestinationMatcher forHttpHosts(List<String> hostPatterns, boolean allowIpLiterals) {
        return new GlobTrustedDestinationMatcher(hostPatterns, true, allowIpLiterals);
    }

    public static TrustedDestinationMatcher forKafkaTopics(List<String> topicPatterns) {
        return new GlobTrustedDestinationMatcher(topicPatterns, false, true);
    }
}
