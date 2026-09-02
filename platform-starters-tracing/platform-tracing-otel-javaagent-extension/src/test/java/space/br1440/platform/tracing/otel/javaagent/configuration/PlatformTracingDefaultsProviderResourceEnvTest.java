package space.br1440.platform.tracing.otel.javaagent.configuration;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.resource.PlatformResourceCustomizer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformTracingDefaultsProviderResourceEnvTest {

    @Test
    void env_service_name_is_propagated_to_property() {
        // given
        Map<String, String> env = Map.of(ExtensionEnvironmentVariables.SERVICE_NAME, "via-env");

        // when
        Map<String, String> defaults = new PlatformTracingDefaultsProvider(env::get).supply();

        // then
        assertThat(defaults).containsEntry(PlatformResourceCustomizer.PROP_SERVICE_NAME, "via-env");
    }

    @Test
    void all_resource_env_variables_are_mapped() {
        // given
        Map<String, String> env = Map.of(
                ExtensionEnvironmentVariables.SERVICE_VERSION, "1.2.3",
                ExtensionEnvironmentVariables.SERVICE_ENVIRONMENT, "production");

        // when
        Map<String, String> defaults = new PlatformTracingDefaultsProvider(env::get).supply();

        // then
        assertThat(defaults)
                .containsEntry(PlatformResourceCustomizer.PROP_SERVICE_VERSION, "1.2.3")
                .containsEntry(PlatformResourceCustomizer.PROP_ENVIRONMENT, "production");
    }

    @Test
    void empty_env_does_not_add_resource_keys() {
        // given
        // when
        Map<String, String> defaults = new PlatformTracingDefaultsProvider(name -> null).supply();

        // then
        assertThat(defaults).doesNotContainKey(PlatformResourceCustomizer.PROP_SERVICE_NAME);
    }
}
