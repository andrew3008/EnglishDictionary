package space.br1440.platform.devtools.opusmcp.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigValidationTest {

    private Map<String, String> base() {
        Map<String, String> env = new HashMap<>();
        env.put(AppConfig.ENV_API_KEY, "test-key");
        env.put(AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop");
        return env;
    }

    @Test
    void validConfigPassesValidation() {
        assertThat(new AppConfig(base()).validateForGeneration()).isEmpty();
    }

    @Test
    void missingApiKeyFails() {
        Map<String, String> env = base();
        env.remove(AppConfig.ENV_API_KEY);
        assertThat(new AppConfig(env).validateForGeneration()).get()
                .asString().contains("OPUS_API_KEY");
    }

    @Test
    void missingBaseUrlFails() {
        Map<String, String> env = base();
        env.remove(AppConfig.ENV_BASE_URL);
        assertThat(new AppConfig(env).validateForGeneration()).get()
                .asString().contains("OPUS_BASE_URL");
    }

    @Test
    void invalidBaseUrlSchemeFails() {
        Map<String, String> env = base();
        env.put(AppConfig.ENV_BASE_URL, "ftp://example.com");
        assertThat(new AppConfig(env).validateForGeneration()).isPresent();
    }

    @Test
    void maxTokensIsClampedToCap() {
        Map<String, String> env = base();
        env.put(AppConfig.ENV_MAX_TOKENS, String.valueOf(Integer.MAX_VALUE));
        assertThat(new AppConfig(env).maxTokens()).isEqualTo(AppConfig.CAP_MAX_TOKENS);
    }

    @Test
    void invalidNumericFallsBackToDefault() {
        Map<String, String> env = base();
        env.put(AppConfig.ENV_MAX_TOKENS, "not-a-number");
        env.put(AppConfig.ENV_REQUEST_TIMEOUT_SECONDS, "-5");
        AppConfig config = new AppConfig(env);
        assertThat(config.maxTokens()).isEqualTo(AppConfig.DEFAULT_MAX_TOKENS);
        assertThat(config.requestTimeout().toSeconds())
                .isEqualTo(AppConfig.DEFAULT_REQUEST_TIMEOUT_SECONDS);
    }

    @Test
    void retryAndRateValuesParsedAndSane() {
        Map<String, String> env = base();
        env.put(AppConfig.ENV_RETRY_MAX_ATTEMPTS, "5");
        env.put(AppConfig.ENV_REQUESTS_PER_MINUTE, "30");
        env.put(AppConfig.ENV_DAILY_REQUEST_LIMIT, "100");
        AppConfig config = new AppConfig(env);
        assertThat(config.retryMaxAttempts()).isEqualTo(5);
        assertThat(config.requestsPerMinute()).isEqualTo(30);
        assertThat(config.dailyRequestLimit()).isEqualTo(100);
        assertThat(config.retryMaxDelayMs()).isGreaterThanOrEqualTo(config.retryBaseDelayMs());
    }

    @Test
    void auditIncludeContentDefaultsFalse() {
        assertThat(new AppConfig(base()).auditIncludeContent()).isFalse();
    }
}
