package space.br1440.platform.tracing.otel.javaagent.configuration;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.resource.PlatformResourceCustomizer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Env-bridge resource-идентичности: {@code PLATFORM_TRACING_*} → платформенные свойства (Фаза 9, PR-5).
 * <p>
 * Тест живёт в пакете {@code configuration} для доступа к package-private
 * {@link ExtensionEnvironmentVariables} без расширения его видимости.
 */
class PlatformTracingDefaultsProviderResourceEnvTest {

    @Test
    void env_service_name_is_propagated_to_property() {
        Map<String, String> env = Map.of(ExtensionEnvironmentVariables.SERVICE_NAME, "via-env");

        Map<String, String> defaults = new PlatformTracingDefaultsProvider(env::get).supply();

        assertThat(defaults).containsEntry(PlatformResourceCustomizer.PROP_SERVICE_NAME, "via-env");
    }

    @Test
    void all_resource_env_variables_are_mapped() {
        Map<String, String> env = Map.of(
                ExtensionEnvironmentVariables.SERVICE_VERSION,     "1.2.3",
                ExtensionEnvironmentVariables.SERVICE_ENVIRONMENT, "production",
                ExtensionEnvironmentVariables.SERVICE_C_GROUP,     "payments");

        Map<String, String> defaults = new PlatformTracingDefaultsProvider(env::get).supply();

        assertThat(defaults)
                .containsEntry(PlatformResourceCustomizer.PROP_SERVICE_VERSION, "1.2.3")
                .containsEntry(PlatformResourceCustomizer.PROP_ENVIRONMENT, "production")
                .containsEntry(PlatformResourceCustomizer.PROP_C_GROUP, "payments");
    }

    @Test
    void empty_env_does_not_add_resource_keys() {
        Map<String, String> defaults = new PlatformTracingDefaultsProvider(name -> null).supply();

        assertThat(defaults).doesNotContainKey(PlatformResourceCustomizer.PROP_SERVICE_NAME);
    }
}
