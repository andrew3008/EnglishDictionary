package space.br1440.platform.tracing.autoconfigure.sampling;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationRuntimeConfigTest {

    @Test
    void from_извлекает_enabled_и_strict() {
        TracingProperties.Validation validation = new TracingProperties.Validation()
                .setEnabled(false)
                .setStrict(true);

        ValidationRuntimeConfig config = ValidationRuntimeConfig.from(validation);

        assertThat(config.enabled()).isFalse();
        assertThat(config.strict()).isTrue();
    }

    @Test
    void from_сохраняет_defaults() {
        TracingProperties.Validation validation = new TracingProperties.Validation();

        ValidationRuntimeConfig config = ValidationRuntimeConfig.from(validation);

        assertThat(config.enabled()).isTrue();
        assertThat(config.strict()).isFalse();
    }

    @Test
    void source_константа_spring_runtime_config() {
        assertThat(ValidationRuntimeConfig.SOURCE).isEqualTo("spring-runtime-config");
    }
}
