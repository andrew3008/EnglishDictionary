package space.br1440.platform.devtools.opusmcp.perplexity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import space.br1440.platform.devtools.opusmcp.security.Masking;
import space.br1440.platform.devtools.opusmcp.smoke.ProviderDiagnostics;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

/**
 * Phase 8A: ISOLATED Perplexity (OpenAI-compatible {@code /chat/completions}) HTTP spike client.
 *
 * <p>This is intentionally NOT the Anthropic {@code /v1/messages}
 * {@link space.br1440.platform.devtools.opusmcp.model.AnthropicHttpOpusClient}; it never shares its
 * request/response shape. It is NOT an MCP tool and is NOT wired into the MCP server.
 *
 * <p>Hard boundaries:
 * <ul>
 *   <li>Only a fixed synthetic prompt is ever sent — no repository context, no code, no secrets.</li>
 *   <li>The API key is read from the environment and sent as a {@code Bearer} token; it is never
 *       logged. Error-body previews are masked (literal key + generic secret shapes).</li>
 *   <li>Run manually only; never invoked automatically and never in CI with a real key.</li>
 * </ul>
 */
public final class PerplexityHttpClient {

    /** OpenAI-compatible chat completions path. */
    public static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    public static final int MAX_TOKENS = 64;

    private final ObjectMapper objectMapper;

    public PerplexityHttpClient() {
        this(new ObjectMapper());
    }

    public PerplexityHttpClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Result of a Perplexity spike call. Mirrors the shape of the Opus smoke result but with a
     * Perplexity-specific diagnostic category and an OpenAI-style {@code model}/{@code requestId}.
     */
    public record PerplexityResult(
            boolean ok,
            int statusCode,
            String extractedText,
            String model,
            String requestId,
            String message,
            String statusDescription,
            String errorBodyPreview,
            PerplexityDiagnosticCategory diagnosticCategory) {
    }

    /** Builds an OpenAI-compatible chat-completions body from ONLY the model id and synthetic prompt. */
    public String buildRequestBody(String model, String prompt) {
        return buildRequestBody(model, prompt, MAX_TOKENS);
    }

    /** Builds an OpenAI-compatible chat-completions body with an explicit max-tokens budget. */
    public String buildRequestBody(String model, String prompt, int maxTokens) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", Math.max(1, maxTokens));
        ArrayNode messages = root.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Perplexity request body", e);
        }
    }

    /** Builds the HTTP request against {@code <baseUrl>/chat/completions} with Bearer auth. */
    public HttpRequest buildHttpRequest(PerplexityConfig config, String prompt) {
        return buildHttpRequest(config, prompt, MAX_TOKENS);
    }

    /** Builds the HTTP request with an explicit max-tokens budget. */
    public HttpRequest buildHttpRequest(PerplexityConfig config, String prompt, int maxTokens) {
        String apiKey = config.apiKey()
                .orElseThrow(() -> new IllegalStateException("API key is required"));
        String baseUrl = config.baseUrl();
        String normalizedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        URI uri = URI.create(normalizedBase + CHAT_COMPLETIONS_PATH);
        String body = buildRequestBody(config.model(), prompt, maxTokens);

        return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(config.requestTimeout())
                .header("content-type", "application/json")
                .header("authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    /**
     * Extracts the assistant text from an OpenAI-compatible response, e.g.
     * {@code {"choices":[{"message":{"role":"assistant","content":"OK"}}]}}.
     */
    public Optional<String> parseResponseText(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode choices = root.path("choices");
            if (choices.isArray()) {
                for (JsonNode choice : choices) {
                    JsonNode content = choice.path("message").path("content");
                    if (content.isTextual()) {
                        return Optional.of(content.asText());
                    }
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Best-effort extraction of the echoed model id from the response. */
    public Optional<String> parseModel(String json) {
        try {
            JsonNode model = objectMapper.readTree(json).path("model");
            return model.isTextual() ? Optional.of(model.asText()) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Best-effort extraction of the response id (used as a requestId in diagnostics). */
    public Optional<String> parseRequestId(String json) {
        try {
            JsonNode id = objectMapper.readTree(json).path("id");
            return id.isTextual() ? Optional.of(id.asText()) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Executes the spike using the supplied HTTP client (mockable in tests). */
    public PerplexityResult run(PerplexityConfig config, String prompt, HttpClient httpClient) {
        return run(config, prompt, MAX_TOKENS, httpClient);
    }

    /** Executes a call with an explicit max-tokens budget (used by the research tool). */
    public PerplexityResult run(PerplexityConfig config, String prompt, int maxTokens,
            HttpClient httpClient) {
        Optional<String> configError = config.validate();
        if (configError.isPresent()) {
            return new PerplexityResult(false, -1, null, config.model(), null, configError.get(),
                    "", null, null);
        }
        try {
            HttpRequest request = buildHttpRequest(config, prompt, maxTokens);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String body = response.body();
            String model = parseModel(body).orElse(config.model());
            String requestId = parseRequestId(body).orElse(null);

            if (status < 200 || status >= 300) {
                return new PerplexityResult(false, status, null, model, requestId,
                        "Endpoint returned non-2xx status " + status,
                        ProviderDiagnostics.statusDescription(status),
                        safePreview(config, body),
                        PerplexityProviderErrorClassifier.classify(status, false, body));
            }
            Optional<String> text = parseResponseText(body);
            if (text.isEmpty()) {
                return new PerplexityResult(false, status, null, model, requestId,
                        "Could not extract assistant text from response body",
                        ProviderDiagnostics.statusDescription(status),
                        safePreview(config, body),
                        PerplexityProviderErrorClassifier.classify(status, false, body));
            }
            return new PerplexityResult(true, status, text.get(), model, requestId,
                    "Endpoint compatible with " + CHAT_COMPLETIONS_PATH + "; extracted text length="
                            + text.get().length(),
                    ProviderDiagnostics.statusDescription(status),
                    null,
                    PerplexityProviderErrorClassifier.classify(status, true, body));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new PerplexityResult(false, -1, null, config.model(), null,
                    "Request failed: " + e.getClass().getSimpleName(),
                    "", null, PerplexityProviderErrorClassifier.classifyNetworkFailure());
        }
    }

    /** Masks the literal API key and generic secret shapes, then length-caps for a safe preview. */
    private String safePreview(PerplexityConfig config, String body) {
        String keyRedacted = Masking.maskSecret(body, config.apiKey().orElse(null));
        return ProviderDiagnostics.previewBody(keyRedacted);
    }
}
