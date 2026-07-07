package space.br1440.platform.devtools.opusmcp.config;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only configuration for the Opus MCP server.
 *
 * <p>Security: API key is read only from the environment and never exposed via {@link #toString()}.
 */
public final class AppConfig {

    public static final String ENV_API_KEY = "OPUS_API_KEY";
    public static final String ENV_BASE_URL = "OPUS_BASE_URL";
    public static final String ENV_MODEL = "OPUS_MODEL";
    public static final String ENV_MAX_TOKENS = "OPUS_MAX_TOKENS";
    public static final String ENV_REQUEST_TIMEOUT_SECONDS = "OPUS_REQUEST_TIMEOUT_SECONDS";
    public static final String ENV_MAX_CONTEXT_CHARS = "OPUS_MAX_CONTEXT_CHARS";
    public static final String ENV_MAX_CONSTRAINTS_CHARS = "OPUS_MAX_CONSTRAINTS_CHARS";
    public static final String ENV_MAX_OUTPUT_CHARS = "OPUS_MAX_OUTPUT_CHARS";

    // Phase 3: budget / rate / retry / audit configuration.
    public static final String ENV_DAILY_REQUEST_LIMIT = "OPUS_DAILY_REQUEST_LIMIT";
    public static final String ENV_DAILY_INPUT_CHAR_LIMIT = "OPUS_DAILY_INPUT_CHAR_LIMIT";
    public static final String ENV_DAILY_ESTIMATED_TOKEN_LIMIT = "OPUS_DAILY_ESTIMATED_TOKEN_LIMIT";
    public static final String ENV_DAILY_COST_LIMIT = "OPUS_DAILY_COST_LIMIT";
    public static final String ENV_REQUESTS_PER_MINUTE = "OPUS_REQUESTS_PER_MINUTE";
    public static final String ENV_RETRY_MAX_ATTEMPTS = "OPUS_RETRY_MAX_ATTEMPTS";
    public static final String ENV_RETRY_BASE_DELAY_MS = "OPUS_RETRY_BASE_DELAY_MS";
    public static final String ENV_RETRY_MAX_DELAY_MS = "OPUS_RETRY_MAX_DELAY_MS";
    public static final String ENV_PRICE_PER_1K_INPUT_TOKENS = "OPUS_PRICE_PER_1K_INPUT_TOKENS";
    public static final String ENV_PRICE_PER_1K_OUTPUT_TOKENS = "OPUS_PRICE_PER_1K_OUTPUT_TOKENS";
    public static final String ENV_AUDIT_INCLUDE_CONTENT = "OPUS_AUDIT_INCLUDE_CONTENT";

    public static final String DEFAULT_MODEL = "claude-opus-4-8";
    public static final int DEFAULT_MAX_TOKENS = 4096;
    public static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 60;
    public static final int DEFAULT_MAX_CONTEXT_CHARS = 32_000;
    public static final int DEFAULT_MAX_CONSTRAINTS_CHARS = 8_000;
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 64_000;

    public static final int DEFAULT_RETRY_MAX_ATTEMPTS = 3;
    public static final long DEFAULT_RETRY_BASE_DELAY_MS = 200L;
    public static final long DEFAULT_RETRY_MAX_DELAY_MS = 2_000L;

    // Hard safety caps to prevent runaway values from env.
    public static final int CAP_MAX_TOKENS = 200_000;
    public static final int CAP_REQUEST_TIMEOUT_SECONDS = 600;
    public static final int CAP_MAX_CONTEXT_CHARS = 2_000_000;
    public static final int CAP_MAX_CONSTRAINTS_CHARS = 1_000_000;
    public static final int CAP_MAX_OUTPUT_CHARS = 4_000_000;
    public static final int CAP_RETRY_MAX_ATTEMPTS = 10;
    public static final long CAP_RETRY_DELAY_MS = 120_000L;
    public static final int CAP_REQUESTS_PER_MINUTE = 100_000;

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final int maxTokens;
    private final Duration requestTimeout;
    private final int maxContextChars;
    private final int maxConstraintsChars;
    private final int maxOutputChars;

    private final long dailyRequestLimit;
    private final long dailyInputCharLimit;
    private final long dailyEstimatedTokenLimit;
    private final double dailyCostLimit;
    private final int requestsPerMinute;
    private final int retryMaxAttempts;
    private final long retryBaseDelayMs;
    private final long retryMaxDelayMs;
    private final double pricePer1kInputTokens;
    private final double pricePer1kOutputTokens;
    private final boolean auditIncludeContent;

    public AppConfig(Map<String, String> env) {
        Map<String, String> source = env == null ? Map.of() : env;
        this.baseUrl = trimToNull(source.get(ENV_BASE_URL));
        String rawModel = trimToNull(source.get(ENV_MODEL));
        this.model = rawModel != null ? rawModel : DEFAULT_MODEL;
        this.apiKey = trimToNull(source.get(ENV_API_KEY));
        this.maxTokens = parsePositiveIntCapped(source.get(ENV_MAX_TOKENS), DEFAULT_MAX_TOKENS, CAP_MAX_TOKENS);
        this.requestTimeout = Duration.ofSeconds(
                parsePositiveIntCapped(source.get(ENV_REQUEST_TIMEOUT_SECONDS),
                        DEFAULT_REQUEST_TIMEOUT_SECONDS, CAP_REQUEST_TIMEOUT_SECONDS));
        this.maxContextChars = parsePositiveIntCapped(
                source.get(ENV_MAX_CONTEXT_CHARS), DEFAULT_MAX_CONTEXT_CHARS, CAP_MAX_CONTEXT_CHARS);
        this.maxConstraintsChars = parsePositiveIntCapped(
                source.get(ENV_MAX_CONSTRAINTS_CHARS), DEFAULT_MAX_CONSTRAINTS_CHARS, CAP_MAX_CONSTRAINTS_CHARS);
        this.maxOutputChars = parsePositiveIntCapped(
                source.get(ENV_MAX_OUTPUT_CHARS), DEFAULT_MAX_OUTPUT_CHARS, CAP_MAX_OUTPUT_CHARS);

        // Limits default to 0 (disabled) unless explicitly configured.
        this.dailyRequestLimit = parseNonNegativeLong(source.get(ENV_DAILY_REQUEST_LIMIT), 0L);
        this.dailyInputCharLimit = parseNonNegativeLong(source.get(ENV_DAILY_INPUT_CHAR_LIMIT), 0L);
        this.dailyEstimatedTokenLimit = parseNonNegativeLong(source.get(ENV_DAILY_ESTIMATED_TOKEN_LIMIT), 0L);
        this.dailyCostLimit = parseNonNegativeDouble(source.get(ENV_DAILY_COST_LIMIT), 0d);
        this.requestsPerMinute = (int) Math.min(CAP_REQUESTS_PER_MINUTE,
                parseNonNegativeLong(source.get(ENV_REQUESTS_PER_MINUTE), 0L));
        this.retryMaxAttempts = clampInt(
                parsePositiveIntCapped(source.get(ENV_RETRY_MAX_ATTEMPTS),
                        DEFAULT_RETRY_MAX_ATTEMPTS, CAP_RETRY_MAX_ATTEMPTS), 1, CAP_RETRY_MAX_ATTEMPTS);
        this.retryBaseDelayMs = Math.min(CAP_RETRY_DELAY_MS,
                parseNonNegativeLong(source.get(ENV_RETRY_BASE_DELAY_MS), DEFAULT_RETRY_BASE_DELAY_MS));
        this.retryMaxDelayMs = Math.max(this.retryBaseDelayMs, Math.min(CAP_RETRY_DELAY_MS,
                parseNonNegativeLong(source.get(ENV_RETRY_MAX_DELAY_MS), DEFAULT_RETRY_MAX_DELAY_MS)));
        this.pricePer1kInputTokens = parseNonNegativeDouble(source.get(ENV_PRICE_PER_1K_INPUT_TOKENS), 0d);
        this.pricePer1kOutputTokens = parseNonNegativeDouble(source.get(ENV_PRICE_PER_1K_OUTPUT_TOKENS), 0d);
        this.auditIncludeContent = parseBoolean(source.get(ENV_AUDIT_INCLUDE_CONTENT), false);
    }

    public static AppConfig fromEnv() {
        return new AppConfig(System.getenv());
    }

    public Optional<String> baseUrl() {
        return Optional.ofNullable(baseUrl);
    }

    public String model() {
        return model;
    }

    public int maxTokens() {
        return maxTokens;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public int maxContextChars() {
        return maxContextChars;
    }

    public int maxConstraintsChars() {
        return maxConstraintsChars;
    }

    public int maxOutputChars() {
        return maxOutputChars;
    }

    public long dailyRequestLimit() {
        return dailyRequestLimit;
    }

    public long dailyInputCharLimit() {
        return dailyInputCharLimit;
    }

    public long dailyEstimatedTokenLimit() {
        return dailyEstimatedTokenLimit;
    }

    public double dailyCostLimit() {
        return dailyCostLimit;
    }

    public int requestsPerMinute() {
        return requestsPerMinute;
    }

    public int retryMaxAttempts() {
        return retryMaxAttempts;
    }

    public long retryBaseDelayMs() {
        return retryBaseDelayMs;
    }

    public long retryMaxDelayMs() {
        return retryMaxDelayMs;
    }

    public double pricePer1kInputTokens() {
        return pricePer1kInputTokens;
    }

    public double pricePer1kOutputTokens() {
        return pricePer1kOutputTokens;
    }

    public boolean auditIncludeContent() {
        return auditIncludeContent;
    }

    /**
     * Request-time validation for {@code generate_code_with_opus}. Returns a safe, secret-free
     * error message if configuration is unusable, otherwise empty. Numeric values are already
     * clamped to sane ranges in the constructor, so this focuses on presence/URI checks.
     */
    public Optional<String> validateForGeneration() {
        if (apiKey == null) {
            return Optional.of("Missing OPUS_API_KEY environment variable");
        }
        if (baseUrl == null) {
            return Optional.of("Missing OPUS_BASE_URL environment variable");
        }
        try {
            URI uri = URI.create(baseUrl);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                return Optional.of("OPUS_BASE_URL must be a valid http(s) URL");
            }
        } catch (IllegalArgumentException e) {
            return Optional.of("OPUS_BASE_URL is not a valid URI");
        }
        return Optional.empty();
    }

    public boolean hasApiKey() {
        return apiKey != null;
    }

    public Optional<String> apiKey() {
        return Optional.ofNullable(apiKey);
    }

    public String maskedApiKey() {
        return apiKey == null ? "<absent>" : "<present:len=" + apiKey.length() + ">";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int parsePositiveIntCapped(String raw, int defaultValue, int cap) {
        if (raw == null || raw.isBlank()) {
            return Math.min(defaultValue, cap);
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed <= 0) {
                return Math.min(defaultValue, cap);
            }
            return Math.min(parsed, cap);
        } catch (NumberFormatException e) {
            return Math.min(defaultValue, cap);
        }
    }

    private static long parseNonNegativeLong(String raw, long defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed >= 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double parseNonNegativeDouble(String raw, double defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            return parsed >= 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean parseBoolean(String raw, boolean defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String v = raw.trim();
        if (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes")) {
            return true;
        }
        if (v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("no")) {
            return false;
        }
        return defaultValue;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public String toString() {
        return "AppConfig{baseUrl=" + (baseUrl == null ? "<absent>" : baseUrl)
                + ", model=" + model
                + ", maxTokens=" + maxTokens
                + ", requestTimeoutSeconds=" + requestTimeout.toSeconds()
                + ", maxContextChars=" + maxContextChars
                + ", maxConstraintsChars=" + maxConstraintsChars
                + ", maxOutputChars=" + maxOutputChars
                + ", apiKey=" + maskedApiKey()
                + '}';
    }
}
