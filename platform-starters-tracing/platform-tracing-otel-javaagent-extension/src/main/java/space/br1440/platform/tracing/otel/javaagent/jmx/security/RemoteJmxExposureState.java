package space.br1440.platform.tracing.otel.javaagent.jmx.security;

/**
 * Состояние обнаруженной поверхности удалённого JMX на момент проверки безопасности.
 */
public enum RemoteJmxExposureState {
    ABSENT,
    CONFIGURED_AT_STARTUP,
    INSECURE_CONFIGURATION,
    POSSIBLE_DYNAMIC_OR_PROGRAMMATIC,
    CONFIRMED_LISTENER,
    UNKNOWN
}
