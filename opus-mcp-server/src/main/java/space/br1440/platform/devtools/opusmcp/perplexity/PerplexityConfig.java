package space.br1440.platform.devtools.opusmcp.perplexity;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 8A: ISOLATED read-only configuration for the Perplexity provider compatibility spike.
 *
 * <p>This is intentionally separate from {@link space.br1440.platform.devtools.opusmcp.config.AppConfig}
 * (the Anthropic/Opus config) so the spike never affects existing tool behavior. It is NOT wired into
 * the MCP server and exposes NO MCP tool.
 *
 * <p>Security: the API key is read only from the environment and never exposed via {@link #toString()}.
 */
public final class PerplexityConfig {

    public static final String ENV_API_KEY = "PERPLEXITY_API_KEY";
    public static final String ENV_BASE_URL = "PERPLEXITY_BASE_URL";
    public static final String ENV_MODEL = "PERPLEXITY_MODEL";

    public static final String DEFAULT_BASE_URL = "https://api.perplexity.ai";
    public static final String DEFAULT_MODEL = "sonar-deep-research";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public PerplexityConfig(Map<String, String> env) {
        Map<String, String> source = env == null ? Map.of() : env;
        this.apiKey = trimToNull(source.get(ENV_API_KEY));
        String rawBase = trimToNull(source.get(ENV_BASE_URL));
        this.baseUrl = rawBase != null ? rawBase : DEFAULT_BASE_URL;
        String rawModel = trimToNull(source.get(ENV_MODEL));
        this.model = rawModel != null ? rawModel : DEFAULT_MODEL;
    }

    public static PerplexityConfig fromEnv() {
        return new PerplexityConfig(System.getenv());
    }

    /** Base URL always has a value (configured or {@link #DEFAULT_BASE_URL}). */
    public String baseUrl() {
        return baseUrl;
    }

    /** Model always has a value (configured or {@link #DEFAULT_MODEL}). Not hard-failed if it differs. */
    public String model() {
        return model;
    }

    public Duration requestTimeout() {
        return REQUEST_TIMEOUT;
    }

    public boolean hasApiKey() {
        return apiKey != null;
    }

    public Optional<String> apiKey() {
        return Optional.ofNullable(apiKey);
    }

    /** Length-only, content-free description of the key for safe diagnostics. */
    public String maskedApiKey() {
        return apiKey == null ? "<absent>" : "<present:len=" + apiKey.length() + ">";
    }

    /**
     * Validates that the spike can run. Returns a safe, secret-free message if unusable, otherwise
     * empty. Mirrors the presence/URI style of {@code AppConfig.validateForGeneration()}.
     */
    public Optional<String> validate() {
        if (apiKey == null) {
            return Optional.of("Missing " + ENV_API_KEY + " (set it in your OS environment / secret store)");
        }
        try {
            URI uri = URI.create(baseUrl);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                return Optional.of(ENV_BASE_URL + " must be a valid http(s) URL");
            }
        } catch (IllegalArgumentException e) {
            return Optional.of(ENV_BASE_URL + " is not a valid URI");
        }
        return Optional.empty();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public String toString() {
        return "PerplexityConfig{baseUrl=" + baseUrl
                + ", model=" + model
                + ", apiKey=" + maskedApiKey()
                + '}';
    }
}
