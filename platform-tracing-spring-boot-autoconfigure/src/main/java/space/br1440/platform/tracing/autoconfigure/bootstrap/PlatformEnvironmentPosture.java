package space.br1440.platform.tracing.autoconfigure.bootstrap;

/**
 * Trusted deployment posture derived from {@code platform.environment}.
 * <p>
 * This is a separate axis from {@link TracingConfigurationAuthority}.
 * Posture is NOT determined by Spring profile names, hostname, localhost heuristics,
 * IDE detection, absence of Kubernetes variables, or absence of Config Server.
 * <p>
 * LOCAL  – explicit local developer environment; Actuator mutations allowed when
 *          authority = ACTUATOR.
 * SHARED – shared/integration/staging environment; Actuator mutations cause fail-fast.
 * PRODUCTION – production environment; Actuator mutations cause fail-fast.
 */
public enum PlatformEnvironmentPosture {
    LOCAL,
    SHARED,
    PRODUCTION
}
