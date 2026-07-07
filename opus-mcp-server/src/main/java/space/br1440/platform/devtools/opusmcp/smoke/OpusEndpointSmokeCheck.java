package space.br1440.platform.devtools.opusmcp.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import space.br1440.platform.devtools.opusmcp.config.AppConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Phase 0B: ISOLATED Opus endpoint compatibility smoke check.
 *
 * <p>This is intentionally NOT an MCP tool and is NOT wired into the MCP server. It only proves that
 * the configured Opus model endpoint (e.g. {@code claude-opus-4-8}) is compatible with the Anthropic-style
 * {@code /v1/messages} contract:
 * <ul>
 *   <li>{@code /v1/messages} is reachable;</li>
 *   <li>the model id can be passed as a plain string;</li>
 *   <li>the base URL is configurable;</li>
 *   <li>the response body can be parsed enough to extract the text.</li>
 * </ul>
 *
 * <p>Hard boundaries:
 * <ul>
 *   <li>NO repository context is ever sent — only a fixed synthetic prompt.</li>
 *   <li>The API key is read from the environment and never logged.</li>
 *   <li>Run manually only; it is never invoked automatically and never in CI with a real key.</li>
 * </ul>
 */
public final class OpusEndpointSmokeCheck {

    /** The ONLY prompt this check ever sends. No repository content, ever. */
    public static final String SYNTHETIC_PROMPT = "Reply with exactly: OK";

    public static final String MESSAGES_PATH = "/v1/messages";
    public static final String ANTHROPIC_VERSION = "2023-06-01";
    public static final int MAX_TOKENS = 16;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper objectMapper;

    public OpusEndpointSmokeCheck() {
        this(new ObjectMapper());
    }

    public OpusEndpointSmokeCheck(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Result of the smoke check. Phase 7A.1 added diagnostics fields (status description, safe error
     * body preview, classification category, and selected safe headers). All additive — existing
     * accessors ({@code ok/statusCode/extractedText/message}) are unchanged.
     */
    public record SmokeResult(
            boolean ok,
            int statusCode,
            String extractedText,
            String message,
            String statusDescription,
            String errorBodyPreview,
            ProviderDiagnosticCategory diagnosticCategory,
            String server,
            String cfRay) {

        /** Backward-compatible constructor used by config/network failure paths. */
        public SmokeResult(boolean ok, int statusCode, String extractedText, String message) {
            this(ok, statusCode, extractedText, message,
                    statusCode >= 0 ? ProviderDiagnostics.statusDescription(statusCode) : "",
                    null, null, null, null);
        }
    }

    /**
     * Validates that the configuration is sufficient to run the smoke check.
     *
     * @return an error message if configuration is insufficient, otherwise empty
     */
    public Optional<String> validate(AppConfig config) {
        if (config.baseUrl().isEmpty()) {
            return Optional.of("Missing " + AppConfig.ENV_BASE_URL + " (e.g. https://your-compatible-endpoint)");
        }
        if (!config.hasApiKey()) {
            return Optional.of("Missing " + AppConfig.ENV_API_KEY + " (set it in your OS environment / secret store)");
        }
        return Optional.empty();
    }

    /** Builds the request body using ONLY the synthetic prompt and the configured model id. */
    public String buildRequestBody(String model, String prompt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", MAX_TOKENS);
        ArrayNode messages = root.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize smoke request body", e);
        }
    }

    /**
     * Builds the HTTP request against {@code <baseUrl>/v1/messages}. Requires base URL and API key.
     */
    public HttpRequest buildHttpRequest(AppConfig config) {
        String baseUrl = config.baseUrl()
                .orElseThrow(() -> new IllegalStateException("Base URL is required"));
        String apiKey = config.apiKey()
                .orElseThrow(() -> new IllegalStateException("API key is required"));

        String normalizedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        URI uri = URI.create(normalizedBase + MESSAGES_PATH);
        String body = buildRequestBody(config.model(), SYNTHETIC_PROMPT);

        return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    /**
     * Extracts the first text block from an Anthropic-style {@code /v1/messages} response, e.g.
     * {@code {"content":[{"type":"text","text":"OK"}]}}.
     */
    public Optional<String> parseResponseText(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode content = root.path("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    JsonNode text = block.path("text");
                    if (text.isTextual()) {
                        return Optional.of(text.asText());
                    }
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Executes the smoke check using the supplied HTTP client (mockable in tests). */
    public SmokeResult run(AppConfig config, HttpClient httpClient) {
        Optional<String> configError = validate(config);
        if (configError.isPresent()) {
            return new SmokeResult(false, -1, null, configError.get());
        }
        try {
            HttpRequest request = buildHttpRequest(config);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String server = safeHeader(response, "server");
            String cfRay = safeHeader(response, "cf-ray");
            String body = response.body();

            if (status < 200 || status >= 300) {
                ProviderDiagnosticCategory category = ProviderDiagnostics.classify(status, false, body);
                return new SmokeResult(false, status, null,
                        "Endpoint returned non-2xx status " + status,
                        ProviderDiagnostics.statusDescription(status),
                        ProviderDiagnostics.previewBody(body),
                        category, server, cfRay);
            }
            Optional<String> text = parseResponseText(body);
            if (text.isEmpty()) {
                return new SmokeResult(false, status, null,
                        "Could not extract text from response body",
                        ProviderDiagnostics.statusDescription(status),
                        ProviderDiagnostics.previewBody(body),
                        ProviderDiagnostics.classify(status, false, body), server, cfRay);
            }
            return new SmokeResult(true, status, text.get(),
                    "Endpoint compatible with " + MESSAGES_PATH + "; extracted text length="
                            + text.get().length(),
                    ProviderDiagnostics.statusDescription(status),
                    null,
                    ProviderDiagnostics.classify(status, true, body), server, cfRay);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new SmokeResult(false, -1, null, "Request failed: " + e.getClass().getSimpleName(),
                    "", null, ProviderDiagnostics.classifyNetworkFailure(), null, null);
        }
    }

    /** Reads a single header safely; tolerant of null headers (e.g. mocked responses). */
    private static String safeHeader(HttpResponse<String> response, String name) {
        try {
            if (response.headers() == null) {
                return null;
            }
            return response.headers().firstValue(name).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnv();
        OpusEndpointSmokeCheck check = new OpusEndpointSmokeCheck();

        Optional<String> configError = check.validate(config);
        if (configError.isPresent()) {
            // Fail safely with a clear message; never print the API key.
            System.err.println("[smoke] Cannot run Phase 0B endpoint check: " + configError.get());
            System.err.println("[smoke] Config: " + config);
            System.exit(2);
            return;
        }

        System.out.println("[smoke] Running Phase 0B endpoint compatibility check");
        System.out.println("[smoke] Model: " + config.model());
        System.out.println("[smoke] Synthetic prompt only (no repository context): \"" + SYNTHETIC_PROMPT + "\"");

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
        SmokeResult result = check.run(config, httpClient);

        System.out.println("[smoke] ok=" + result.ok()
                + " status=" + result.statusCode()
                + " statusDescription=" + emptyIfNull(result.statusDescription())
                + " text=" + (result.extractedText() == null ? "<none>" : '"' + result.extractedText() + '"'));
        if (!result.ok()) {
            System.out.println("[smoke] errorBodyPreview="
                    + (result.errorBodyPreview() == null ? ProviderDiagnostics.EMPTY_BODY : result.errorBodyPreview()));
            System.out.println("[smoke] server=" + emptyIfNull(result.server())
                    + " cfRay=" + emptyIfNull(result.cfRay()));
        }
        System.out.println("[smoke] diagnosticCategory="
                + (result.diagnosticCategory() == null ? ProviderDiagnosticCategory.UNKNOWN_PROVIDER_ERROR : result.diagnosticCategory()));
        System.out.println("[smoke] " + result.message());
        System.exit(result.ok() ? 0 : 1);
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
