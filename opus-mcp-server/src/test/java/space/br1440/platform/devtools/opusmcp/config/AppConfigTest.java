package space.br1440.platform.devtools.opusmcp.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigTest {

    @Test
    void readsValuesFromMap() {
        Map<String, String> env = new HashMap<>();
        env.put(AppConfig.ENV_BASE_URL, "https://example-endpoint");
        env.put(AppConfig.ENV_MODEL, "claude-opus-4-8");
        env.put(AppConfig.ENV_API_KEY, "secret-key-value");
        env.put(AppConfig.ENV_MAX_TOKENS, "2048");
        env.put(AppConfig.ENV_MAX_CONTEXT_CHARS, "1000");

        AppConfig config = new AppConfig(env);

        assertThat(config.baseUrl()).contains("https://example-endpoint");
        assertThat(config.model()).isEqualTo("claude-opus-4-8");
        assertThat(config.hasApiKey()).isTrue();
        assertThat(config.maxTokens()).isEqualTo(2048);
        assertThat(config.maxContextChars()).isEqualTo(1000);
    }

    @Test
    void appliesDefaultModelWhenMissing() {
        AppConfig config = new AppConfig(Map.of());

        assertThat(config.model()).isEqualTo(AppConfig.DEFAULT_MODEL);
        assertThat(config.baseUrl()).isEmpty();
        assertThat(config.hasApiKey()).isFalse();
    }

    @Test
    void handlesNullEnvSafely() {
        AppConfig config = new AppConfig(null);

        assertThat(config.hasApiKey()).isFalse();
        assertThat(config.baseUrl()).isEmpty();
        assertThat(config.model()).isEqualTo(AppConfig.DEFAULT_MODEL);
    }

    @Test
    void blankValuesTreatedAsAbsent() {
        Map<String, String> env = new HashMap<>();
        env.put(AppConfig.ENV_BASE_URL, "   ");
        env.put(AppConfig.ENV_API_KEY, "");

        AppConfig config = new AppConfig(env);

        assertThat(config.baseUrl()).isEmpty();
        assertThat(config.hasApiKey()).isFalse();
    }

    @Test
    void toStringNeverExposesApiKeyValue() {
        AppConfig config = new AppConfig(Map.of(AppConfig.ENV_API_KEY, "super-secret-123"));

        assertThat(config.toString())
                .doesNotContain("super-secret-123")
                .contains("apiKey=<present");
    }

    @Test
    void maskedApiKeyDoesNotRevealValue() {
        AppConfig config = new AppConfig(Map.of(AppConfig.ENV_API_KEY, "super-secret-123"));

        assertThat(config.maskedApiKey())
                .doesNotContain("super-secret-123")
                .startsWith("<present:len=");
    }
}
