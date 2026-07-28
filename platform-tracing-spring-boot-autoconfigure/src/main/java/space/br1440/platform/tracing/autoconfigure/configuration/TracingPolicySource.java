package space.br1440.platform.tracing.autoconfigure.configuration;

/**
 * Identifies the source of a policy apply attempt in delivery diagnostics.
 * This is a diagnostics/audit label, NOT an authority ordering token.
 */
public enum TracingPolicySource {
    CONFIG_SERVER,
    ACTUATOR
}
