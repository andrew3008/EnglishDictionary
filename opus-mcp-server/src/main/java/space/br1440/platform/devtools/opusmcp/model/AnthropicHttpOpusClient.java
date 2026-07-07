package space.br1440.platform.devtools.opusmcp.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import space.br1440.platform.devtools.opusmcp.config.AppConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Optional;

public final class AnthropicHttpOpusClient implements OpusClient {

    public static final String MESSAGES_PATH = "/v1/messages";
    public static final String ANTHROPIC_VERSION = "2023-06-01";
    static final String REQUEST_ID_HEADER = "request-id";

    private final AppConfig config;
    private final ModelRegistry modelRegistry;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;

    public AnthropicHttpOpusClient(AppConfig config, ModelRegistry modelRegistry) {
        this(config, modelRegistry, HttpClient.newBuilder()
                        .connectTimeout(config.requestTimeout())
                        .build(), new ObjectMapper(),
                new RetryPolicy(
                        config.retryMaxAttempts(),
                        config.retryBaseDelayMs(),
                        config.retryMaxDelayMs()));
    }

    AnthropicHttpOpusClient(
            AppConfig config,
            ModelRegistry modelRegistry,
            HttpClient httpClient,
            ObjectMapper objectMapper) {
        this(config, modelRegistry, httpClient, objectMapper,
                new RetryPolicy(
                        config.retryMaxAttempts(),
                        config.retryBaseDelayMs(),
                        config.retryMaxDelayMs()));
    }

    AnthropicHttpOpusClient(
            AppConfig config,
            ModelRegistry modelRegistry,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            RetryPolicy retryPolicy) {
        this.config = config;
        this.modelRegistry = modelRegistry;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.retryPolicy = retryPolicy;
    }

    @Override
    public OpusResponse generate(OpusRequest request) throws OpusClientException {
        validateConfig(request.model());
        return retryPolicy.execute(() -> singleAttempt(request));
    }

    private OpusResponse singleAttempt(OpusRequest request) throws OpusClientException {
        try {
            HttpRequest httpRequest = buildHttpRequest(request);
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw buildHttpErrorException(status, response.body(), response.headers());
            }
            return parseResponse(response.body(), request, response.headers());
        } catch (HttpTimeoutException e) {
            throw new OpusClientException(OpusClientException.Reason.TIMEOUT, "Request timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpusClientException(OpusClientException.Reason.NETWORK_ERROR, "Request interrupted");
        } catch (IOException e) {
            throw new OpusClientException(OpusClientException.Reason.NETWORK_ERROR, "Network error");
        }
    }

    private void validateConfig(String model) throws OpusClientException {
        if (!config.hasApiKey()) {
            throw new OpusClientException(OpusClientException.Reason.MISSING_API_KEY,
                    "Missing OPUS_API_KEY");
        }
        if (config.baseUrl().isEmpty()) {
            throw new OpusClientException(OpusClientException.Reason.MISSING_BASE_URL,
                    "Missing OPUS_BASE_URL");
        }
        if (!modelRegistry.isAllowed(model)) {
            throw new OpusClientException(OpusClientException.Reason.MODEL_NOT_ALLOWED,
                    "Model not allowlisted: " + model);
        }
    }

    public String buildRequestBody(OpusRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.model());
        root.put("max_tokens", request.maxTokens());
        root.put("system", request.systemPrompt());
        ArrayNode messages = root.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", request.userPrompt());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize request body", e);
        }
    }

    public HttpRequest buildHttpRequest(OpusRequest request) throws OpusClientException {
        validateConfig(request.model());
        String baseUrl = config.baseUrl().orElseThrow();
        String apiKey = config.apiKey().orElseThrow();
        String normalizedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        URI uri = URI.create(normalizedBase + MESSAGES_PATH);
        return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(config.requestTimeout())
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(request)))
                .build();
    }

    /**
     * Parses a provider HTTP response body into an {@link OpusResponse}. The JSON is read exactly
     * once; usage is extracted before deciding whether text extraction failed.
     */
    public OpusResponse parseResponse(String json, OpusRequest request) throws OpusClientException {
        return parseResponse(json, request, null);
    }

    /**
     * Parses a provider HTTP response body and optional response headers into an {@link OpusResponse}.
     * Response headers are used only for metadata (e.g. {@code request-id}); they are never logged
     * together with raw bodies.
     */
    public OpusResponse parseResponse(String json, OpusRequest request, HttpHeaders headers)
            throws OpusClientException {
        final JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            String providerRequestId = extractProviderRequestId(headers, null).orElse("");
            throw new OpusClientException(
                    OpusClientException.Reason.PARSE_ERROR,
                    "Provider returned invalid JSON",
                    ProviderCallMetadata.failure(
                            providerRequestId,
                            ProviderEnvelopeKind.NONE,
                            ProviderDiagnosticCategory.INVALID_JSON));
        }

        String providerRequestId = extractProviderRequestId(headers, root).orElse("");

        if (looksLikeProviderErrorEnvelope(root)) {
            throw new OpusClientException(
                    OpusClientException.Reason.PARSE_ERROR,
                    safeErrorSummary(root),
                    ProviderCallMetadata.failure(
                            providerRequestId,
                            ProviderEnvelopeKind.ERROR_ENVELOPE,
                            ProviderDiagnosticCategory.ERROR_ENVELOPE));
        }

        int inputTokens = extractInputTokens(root)
                .orElse(estimateTokens(request.systemPrompt() + request.userPrompt()));

        Optional<TextExtractionResult> extracted = extractTextResult(root);
        if (extracted.isEmpty()) {
            throw new OpusClientException(
                    OpusClientException.Reason.PARSE_ERROR,
                    "Could not extract text from provider response: no supported text payload found",
                    ProviderCallMetadata.failure(
                            providerRequestId,
                            ProviderEnvelopeKind.NONE,
                            ProviderDiagnosticCategory.NO_TEXT_FOUND));
        }

        TextExtractionResult result = extracted.get();
        int outputTokens = extractOutputTokens(root)
                .orElse(estimateTokens(result.text()));

        return new OpusResponse(
                result.text(),
                inputTokens,
                outputTokens,
                ProviderCallMetadata.success(providerRequestId, result.envelopeKind()));
    }

    /**
     * Extracts the model's text output from a provider response JSON string.
     *
     * <p>Malformed JSON yields {@link Optional#empty()} for backward compatibility with callers that
     * probe envelopes without throwing. Use {@link #parseResponse} when a definitive outcome is
     * required.
     */
    public Optional<String> extractText(String json) {
        try {
            return extractTextResult(objectMapper.readTree(json)).map(TextExtractionResult::text);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    Optional<String> extractText(JsonNode root) {
        return extractTextResult(root).map(TextExtractionResult::text);
    }

    /**
     * Extracts model text and the envelope kind that supplied it from a parsed provider response.
     */
    Optional<TextExtractionResult> extractTextResult(JsonNode root) {
        JsonNode content = root.path("content");
        if (content.isArray()) {
            Optional<String> fromBlocks = collectTextBlocks(content);
            if (fromBlocks.isPresent()) {
                return Optional.of(new TextExtractionResult(
                        fromBlocks.get(), ProviderEnvelopeKind.ANTHROPIC_MESSAGES));
            }
        }
        if (content.isTextual()) {
            String value = stripBom(content.asText());
            if (!value.isBlank()) {
                return Optional.of(new TextExtractionResult(
                        value, ProviderEnvelopeKind.GATEWAY_CONTENT_STRING));
            }
        }
        JsonNode choices = root.path("choices");
        if (choices.isArray()) {
            for (JsonNode choice : choices) {
                JsonNode message = choice.path("message");
                JsonNode messageContent = message.path("content");
                if (messageContent.isTextual()) {
                    String value = stripBom(messageContent.asText());
                    if (!value.isBlank()) {
                        return Optional.of(new TextExtractionResult(
                                value, ProviderEnvelopeKind.OPENAI_CHAT_STRING));
                    }
                }
                if (messageContent.isArray()) {
                    Optional<String> fromBlocks = collectTextBlocks(messageContent);
                    if (fromBlocks.isPresent()) {
                        return Optional.of(new TextExtractionResult(
                                fromBlocks.get(), ProviderEnvelopeKind.OPENAI_CHAT_BLOCKS));
                    }
                }
                JsonNode choiceText = choice.path("text");
                if (choiceText.isTextual()) {
                    String value = stripBom(choiceText.asText());
                    if (!value.isBlank()) {
                        return Optional.of(new TextExtractionResult(
                                value, ProviderEnvelopeKind.OPENAI_COMPLETION));
                    }
                }
            }
        }
        JsonNode completion = root.path("completion");
        if (completion.isTextual()) {
            String value = stripBom(completion.asText());
            if (!value.isBlank()) {
                return Optional.of(new TextExtractionResult(
                        value, ProviderEnvelopeKind.LEGACY_COMPLETION));
            }
        }
        return Optional.empty();
    }

    static Optional<String> extractProviderRequestId(HttpHeaders headers, JsonNode root) {
        if (headers != null) {
            Optional<String> fromHeader = headers.firstValue(REQUEST_ID_HEADER);
            if (fromHeader.isPresent() && !fromHeader.get().isBlank()) {
                return Optional.of(sanitizeForLog(fromHeader.get()));
            }
        }
        if (root != null) {
            JsonNode requestId = root.path("request_id");
            if (requestId.isTextual() && !requestId.asText().isBlank()) {
                return Optional.of(sanitizeForLog(requestId.asText()));
            }
        }
        return Optional.empty();
    }

    record TextExtractionResult(String text, ProviderEnvelopeKind envelopeKind) {
    }

    private static Optional<String> collectTextBlocks(JsonNode blocks) {
        if (!blocks.isArray()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : blocks) {
            JsonNode text = block.path("text");
            if (text.isTextual()) {
                String value = stripBom(text.asText());
                if (!value.isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append("\n\n");
                    }
                    sb.append(value);
                }
            }
        }
        return sb.isEmpty() ? Optional.empty() : Optional.of(sb.toString());
    }

    private static Optional<Integer> extractInputTokens(JsonNode root) {
        JsonNode usage = root.path("usage");
        return firstInt(usage.path("input_tokens"), usage.path("prompt_tokens"));
    }

    private static Optional<Integer> extractOutputTokens(JsonNode root) {
        JsonNode usage = root.path("usage");
        return firstInt(usage.path("output_tokens"), usage.path("completion_tokens"));
    }

    private static Optional<Integer> firstInt(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isIntegralNumber()) {
                long value = node.asLong();
                if (value < 0L) {
                    continue;
                }
                return Optional.of(value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value);
            }
        }
        return Optional.empty();
    }

    static boolean looksLikeProviderErrorEnvelope(JsonNode root) {
        if ("error".equals(root.path("type").asText())) {
            return root.has("error");
        }
        JsonNode error = root.path("error");
        return error.isObject()
                && error.path("type").isTextual()
                && error.path("message").isTextual();
    }

    static String safeErrorSummary(JsonNode root) {
        JsonNode error = root.path("error");
        String type = sanitizeForLog(error.path("type").asText(""));
        if (!type.isEmpty()) {
            return "Provider returned error envelope: type=" + type;
        }
        return "Provider returned error envelope";
    }

    private static String stripBom(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.charAt(0) == '\uFEFF' ? value.substring(1) : value;
    }

    static String sanitizeForLog(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').trim();
    }

    /**
     * Builds an {@link OpusClientException} for non-2xx HTTP responses. The raw body is never copied
     * into the exception message; only safe metadata (request-id, error envelope classification) is
     * extracted when the body is valid JSON.
     */
    OpusClientException buildHttpErrorException(int status, String body, HttpHeaders headers) {
        String providerRequestId = extractProviderRequestId(headers, null).orElse("");
        ProviderEnvelopeKind envelopeKind = ProviderEnvelopeKind.NONE;
        ProviderDiagnosticCategory diagnostic = ProviderDiagnosticCategory.NONE;

        if (body != null && !body.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(body);
                providerRequestId = extractProviderRequestId(headers, root).orElse(providerRequestId);
                if (looksLikeProviderErrorEnvelope(root)) {
                    envelopeKind = ProviderEnvelopeKind.ERROR_ENVELOPE;
                    diagnostic = ProviderDiagnosticCategory.ERROR_ENVELOPE;
                }
            } catch (Exception ignored) {
                // Body is not JSON: keep header-only request-id and default envelope/diagnostic.
            }
        }

        return new OpusClientException(
                OpusClientException.Reason.HTTP_ERROR,
                "Provider returned HTTP " + status,
                status,
                ProviderCallMetadata.failure(providerRequestId, envelopeKind, diagnostic));
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }
}
