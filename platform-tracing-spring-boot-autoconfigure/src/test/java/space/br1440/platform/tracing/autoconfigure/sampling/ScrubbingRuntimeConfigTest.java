package space.br1440.platform.tracing.autoconfigure.sampling;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScrubbingRuntimeConfigTest {

    @Test
    void from_извлекает_enabled_и_ruleNames() {
        TracingProperties.Scrubbing scrubbing = new TracingProperties.Scrubbing()
                .setEnabled(false)
                .setBuiltInRules(List.of("password", "jwt", "email"));

        ScrubbingRuntimeConfig config = ScrubbingRuntimeConfig.from(scrubbing);

        assertThat(config.enabled()).isFalse();
        assertThat(config.ruleNames()).containsExactly("password", "jwt", "email");
    }

    @Test
    void from_сохраняет_порядок_ruleNames() {
        List<String> rules = new ArrayList<>();
        rules.add("email");
        rules.add("password");
        rules.add("jwt");
        TracingProperties.Scrubbing scrubbing = new TracingProperties.Scrubbing()
                .setBuiltInRules(rules);

        ScrubbingRuntimeConfig config = ScrubbingRuntimeConfig.from(scrubbing);

        assertThat(config.ruleNames()).containsExactly("email", "password", "jwt");
    }

    @Test
    void from_null_и_empty_builtInRules() {
        TracingProperties.Scrubbing nullList = new TracingProperties.Scrubbing().setBuiltInRules(null);
        assertThat(ScrubbingRuntimeConfig.from(nullList).ruleNames()).isEmpty();

        TracingProperties.Scrubbing emptyList = new TracingProperties.Scrubbing().setBuiltInRules(List.of());
        assertThat(ScrubbingRuntimeConfig.from(emptyList).ruleNames()).isEmpty();
    }

    @Test
    void source_константа_spring_runtime_config() {
        assertThat(ScrubbingRuntimeConfig.SOURCE).isEqualTo("spring-runtime-config");
    }
}
