package space.br1440.platform.devtools.opusmcp.perplexity;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PerplexityConfigTest {

    @Test
    void readsEnvVarNamesAndAppliesDefaults() {
        PerplexityConfig config = new PerplexityConfig(Map.of(
                PerplexityConfig.ENV_API_KEY, "secret-key-value"));

        assertThat(config.hasApiKey()).isTrue();
        assertThat(config.baseUrl()).isEqualTo(PerplexityConfig.DEFAULT_BASE_URL);
        assertThat(config.model()).isEqualTo(PerplexityConfig.DEFAULT_MODEL);
    }

    @Test
    void overridesBaseUrlAndModelFromEnv() {
        PerplexityConfig config = new PerplexityConfig(Map.of(
                PerplexityConfig.ENV_API_KEY, "k",
                PerplexityConfig.ENV_BASE_URL, "https://proxy.example",
                PerplexityConfig.ENV_MODEL, "sonar-pro"));

        assertThat(config.baseUrl()).isEqualTo("https://proxy.example");
        assertThat(config.model()).isEqualTo("sonar-pro");
    }

    @Test
    void missingApiKeyIsHandledSafely() {
        PerplexityConfig config = new PerplexityConfig(Map.of());

        assertThat(config.hasApiKey()).isFalse();
        assertThat(config.apiKey()).isEmpty();
        assertThat(config.validate()).isPresent();
        assertThat(config.validate().orElseThrow()).contains(PerplexityConfig.ENV_API_KEY);
    }

    @Test
    void invalidBaseUrlFailsSafely() {
        PerplexityConfig config = new PerplexityConfig(Map.of(
                PerplexityConfig.ENV_API_KEY, "k",
                PerplexityConfig.ENV_BASE_URL, "not-a-url"));

        assertThat(config.validate()).isPresent();
        assertThat(config.validate().orElseThrow()).contains(PerplexityConfig.ENV_BASE_URL);
    }

    @Test
    void validConfigPassesValidation() {
        PerplexityConfig config = new PerplexityConfig(Map.of(
                PerplexityConfig.ENV_API_KEY, "k",
                PerplexityConfig.ENV_BASE_URL, "https://api.perplexity.ai"));

        assertThat(config.validate()).isEmpty();
    }

    @Test
    void toStringAndMaskNeverRevealApiKey() {
        PerplexityConfig config = new PerplexityConfig(Map.of(
                PerplexityConfig.ENV_API_KEY, "super-secret-key-123456"));

        assertThat(config.toString())
                .doesNotContain("super-secret-key-123456")
                .contains("apiKey=<present:len=");
        assertThat(config.maskedApiKey()).doesNotContain("super-secret-key-123456");
    }

    @Test
    void blankValuesFallBackToDefaults() {
        PerplexityConfig config = new PerplexityConfig(Map.of(
                PerplexityConfig.ENV_API_KEY, "k",
                PerplexityConfig.ENV_BASE_URL, "   ",
                PerplexityConfig.ENV_MODEL, "  "));

        assertThat(config.baseUrl()).isEqualTo(PerplexityConfig.DEFAULT_BASE_URL);
        assertThat(config.model()).isEqualTo(PerplexityConfig.DEFAULT_MODEL);
    }
}
