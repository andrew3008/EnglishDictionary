package space.br1440.platform.tracing.otel.propagation.control;

import java.util.List;

import space.br1440.platform.tracing.api.propagation.control.TrustedDestinationMatcher;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class TrustedDestinationMatchers {

    public static TrustedDestinationMatcher forHttpHosts(List<String> hostPatterns, boolean allowIpLiterals) {
        return new GlobTrustedDestinationMatcher(hostPatterns, true, allowIpLiterals);
    }

    public static TrustedDestinationMatcher forKafkaTopics(List<String> topicPatterns) {
        return new GlobTrustedDestinationMatcher(topicPatterns, false, true);
    }
}
