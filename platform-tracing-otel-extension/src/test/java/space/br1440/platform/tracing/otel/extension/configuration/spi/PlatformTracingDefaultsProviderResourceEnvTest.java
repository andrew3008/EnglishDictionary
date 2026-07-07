package space.br1440.platform.tracing.otel.extension.configuration.spi;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.extension.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.extension.resource.PlatformResourceProvider;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Env-bridge resource-идентичности: {@code PLATFORM_TRACING_*} → платформенные свойства (Фаза 9, PR-5).
 * <p>
 * Тест живёт в пакете {@code configuration.spi} для доступа к package-private
 * {@link ExtensionEnvironmentVariables} без расширения его видимости.
 */
class PlatformTracingDefaultsProviderResourceEnvTest {

    @Test
    void env_service_name_пробрасывается_в_property() {
        Map<String, String> env = Map.of(ExtensionEnvironmentVariables.SERVICE_NAME, "via-env");

        Map<String, String> defaults = new PlatformTracingDefaultsProvider(env::get).supply();

        assertThat(defaults).containsEntry(PlatformResourceProvider.PROP_SERVICE_NAME, "via-env");
    }

    @Test
    void все_resource_env_переменные_маппятся() {
        Map<String, String> env = Map.of(
                ExtensionEnvironmentVariables.SERVICE_VERSION,     "1.2.3",
                ExtensionEnvironmentVariables.SERVICE_ENVIRONMENT, "production",
                ExtensionEnvironmentVariables.SERVICE_C_GROUP,     "payments",
                ExtensionEnvironmentVariables.RESOURCE_POLICY_VERSION, "2026.06.08");

        Map<String, String> defaults = new PlatformTracingDefaultsProvider(env::get).supply();

        assertThat(defaults)
                .containsEntry(PlatformResourceProvider.PROP_SERVICE_VERSION, "1.2.3")
                .containsEntry(PlatformResourceProvider.PROP_ENVIRONMENT, "production")
                .containsEntry(PlatformResourceProvider.PROP_C_GROUP, "payments")
                .containsEntry(ExtensionPropertyNames.RESOURCE_POLICY_VERSION, "2026.06.08");
    }

    @Test
    void пустой_env_не_добавляет_resource_ключи() {
        Map<String, String> defaults = new PlatformTracingDefaultsProvider(name -> null).supply();

        assertThat(defaults).doesNotContainKey(PlatformResourceProvider.PROP_SERVICE_NAME);
    }
}
