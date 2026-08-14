package space.br1440.platform.tracing.otel.javaagent.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentNormalizerTest {

    @ParameterizedTest
    @CsvSource({
            "production,production",
            "prod,production",
            "prd,production",
            "stage,staging",
            "stg,staging",
            "staging,staging",
            "dev,development",
            "development,development",
            "test,test",
            "qa1,test",
            "qa,test",
            "weird-env,weird-env"
    })
    void normalize_enabled(String raw, String expected) {
        assertThat(EnvironmentNormalizer.normalize(raw, true)).isEqualTo(expected);
    }

    @Test
    void normalize_disabled_не_подменяет_алиасы() {
        assertThat(EnvironmentNormalizer.normalize("prod", false)).isEqualTo("prod");
    }

    @Test
    void null_и_blank_дают_unknown() {
        assertThat(EnvironmentNormalizer.normalize(null, true)).isEqualTo(EnvironmentNormalizer.UNKNOWN);
        assertThat(EnvironmentNormalizer.normalize("  ", true)).isEqualTo(EnvironmentNormalizer.UNKNOWN);
    }
}
