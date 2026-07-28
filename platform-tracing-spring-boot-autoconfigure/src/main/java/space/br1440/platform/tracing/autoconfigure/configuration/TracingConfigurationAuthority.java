package space.br1440.platform.tracing.autoconfigure.configuration;

/**
 * Единственный источник управления конфигурацией трассировки для процесса.
 */
public enum TracingConfigurationAuthority {
    HELM,
    CONFIG_SERVER,
    ACTUATOR
}
