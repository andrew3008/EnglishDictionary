package space.br1440.platform.tracing.autoconfigure.configuration;

/**
 * Technological authority for runtime tracing policy management.
 * <p>
 * Exactly one authority is selected at application startup.
 * Authority fallback is forbidden.
 * <p>
 * HELM: startup-only; Agent bootstrap parser builds initial Category A policy
 *       from env/system properties; no Spring runtime write; no Actuator write surface.
 * CONFIG_SERVER: non-optional Config Client; complete eight-leaf manifest delivery;
 *                no Actuator write surface.
 * ACTUATOR: typed Category A writes via Spring Boot Actuator; allowed only when
 *           {@link PlatformEnvironmentPosture#LOCAL} is confirmed by the deployment trust signal.
 */
public enum TracingConfigurationAuthority {
    HELM,
    CONFIG_SERVER,
    ACTUATOR
}
