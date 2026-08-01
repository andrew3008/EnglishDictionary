package space.br1440.platform.tracing.autoconfigure.bootstrap;

/**
 * Имена свойств, определяющих неизменяемые настройки bootstrap-фазы.
 *
 * <p>Тип является внутренним межмодульным контрактом: константа режима используется
 * условиями auto-configuration в нейтральном, Servlet- и WebFlux-модулях.</p>
 */
public final class TracingBootstrapPropertyNames {

    public static final String MODE = "platform.tracing.mode";
    public static final String CONFIGURATION_AUTHORITY = "platform.tracing.configuration.authority";
    public static final String CONFIGURATION_ENABLED = "platform.tracing.configuration.enabled";
    public static final String ENVIRONMENT_POSTURE = "platform.environment";

    public static final String SETTINGS_BEAN_NAME = "tracingBootstrapSettings";

    private TracingBootstrapPropertyNames() {
    }
}
