package space.br1440.platform.tracing.api.propagation.control;

/**
 * Allowlist matcher для доверенных HTTP-хостов или Kafka-топиков.
 * <p>
 * Создание экземпляров — через {@code TrustedDestinationMatchers} в {@code platform-tracing-core}.
 */
public interface TrustedDestinationMatcher {

    boolean isTrusted(String destination);
}
