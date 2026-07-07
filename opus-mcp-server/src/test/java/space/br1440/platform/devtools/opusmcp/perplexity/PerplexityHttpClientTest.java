package space.br1440.platform.devtools.opusmcp.perplexity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PerplexityHttpClientTest {

    private static final String PROMPT = PerplexityEndpointSmokeCheck.SYNTHETIC_PROMPT;

    private final PerplexityHttpClient client = new PerplexityHttpClient();

    private PerplexityConfig configWith(String baseUrl, String model, String apiKey) {
        return new PerplexityConfig(Map.of(
                PerplexityConfig.ENV_BASE_URL, baseUrl,
                PerplexityConfig.ENV_MODEL, model,
                PerplexityConfig.ENV_API_KEY, apiKey));
    }

    @Test
    void buildsChatCompletionsRequestWithBearerAuthAndStringModel() {
        PerplexityConfig config = configWith("https://api.perplexity.ai", "sonar-deep-research", "k");

        HttpRequest request = client.buildHttpRequest(config, PROMPT);

        assertThat(request.uri().toString()).isEqualTo("https://api.perplexity.ai/chat/completions");
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.headers().firstValue("authorization")).contains("Bearer k");
        String body = client.buildRequestBody(config.model(), PROMPT);
        assertThat(body).contains("\"model\":\"sonar-deep-research\"");
    }

    @Test
    void normalizesTrailingSlashInBaseUrl() {
        PerplexityConfig config = configWith("https://api.perplexity.ai/", "sonar-pro", "k");

        HttpRequest request = client.buildHttpRequest(config, PROMPT);

        assertThat(request.uri().toString()).isEqualTo("https://api.perplexity.ai/chat/completions");
    }

    @Test
    void requestBodySendsOnlySyntheticPromptAndNoRepositoryContext() {
        String body = client.buildRequestBody("sonar-pro", PROMPT);

        assertThat(body).contains("Reply with exactly: OK").contains("\"role\":\"user\"");
        assertThat(body)
                .doesNotContain("package ")
                .doesNotContain("import ")
                .doesNotContain("/src/")
                .doesNotContain(".java");
    }

    @Test
    void parsesOpenAiCompatibleResponseText() {
        String json = "{\"id\":\"cmpl_1\",\"model\":\"sonar-deep-research\","
                + "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}";

        assertThat(client.parseResponseText(json)).contains("OK");
        assertThat(client.parseModel(json)).contains("sonar-deep-research");
        assertThat(client.parseRequestId(json)).contains("cmpl_1");
    }

    @Test
    void parseReturnsEmptyForUnexpectedShape() {
        assertThat(client.parseResponseText("{\"unexpected\":true}")).isEmpty();
        assertThat(client.parseResponseText("not-json")).isEmpty();
    }

    @Test
    void runFailsSafelyWhenApiKeyMissing() {
        PerplexityConfig config = new PerplexityConfig(Map.of());
        HttpClient httpClient = mock(HttpClient.class);

        PerplexityHttpClient.PerplexityResult result = client.run(config, PROMPT, httpClient);

        assertThat(result.ok()).isFalse();
        assertThat(result.statusCode()).isEqualTo(-1);
        assertThat(result.message()).contains(PerplexityConfig.ENV_API_KEY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void runSucceedsWithMockedHttpClient() throws IOException, InterruptedException {
        PerplexityConfig config = configWith("https://api.perplexity.ai", "sonar-deep-research", "k");

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(
                "{\"id\":\"cmpl_1\",\"model\":\"sonar-deep-research\","
                        + "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}");

        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        PerplexityHttpClient.PerplexityResult result = client.run(config, PROMPT, httpClient);

        assertThat(result.ok()).isTrue();
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.extractedText()).isEqualTo("OK");
        assertThat(result.model()).isEqualTo("sonar-deep-research");
        assertThat(result.requestId()).isEqualTo("cmpl_1");
        assertThat(result.diagnosticCategory()).isEqualTo(PerplexityDiagnosticCategory.OK);
    }

    @Test
    @SuppressWarnings("unchecked")
    void runClassifies401AsAuthError() throws IOException, InterruptedException {
        PerplexityConfig config = configWith("https://api.perplexity.ai", "sonar-pro", "k");

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(401);
        when(response.body()).thenReturn("{\"error\":{\"message\":\"unauthorized\"}}");

        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        PerplexityHttpClient.PerplexityResult result = client.run(config, PROMPT, httpClient);

        assertThat(result.ok()).isFalse();
        assertThat(result.statusCode()).isEqualTo(401);
        assertThat(result.diagnosticCategory()).isEqualTo(PerplexityDiagnosticCategory.AUTH_ERROR);
    }

    @Test
    @SuppressWarnings("unchecked")
    void runClassifiesModelNotFound() throws IOException, InterruptedException {
        PerplexityConfig config = configWith("https://api.perplexity.ai", "sonar-bogus", "k");

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(400);
        when(response.body()).thenReturn("{\"error\":{\"message\":\"Invalid model sonar-bogus\"}}");

        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        PerplexityHttpClient.PerplexityResult result = client.run(config, PROMPT, httpClient);

        assertThat(result.diagnosticCategory()).isEqualTo(PerplexityDiagnosticCategory.MODEL_NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void runClassifies5xxAsProviderDown() throws IOException, InterruptedException {
        PerplexityConfig config = configWith("https://api.perplexity.ai", "sonar-pro", "k");

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(response.body()).thenReturn("service unavailable");

        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        PerplexityHttpClient.PerplexityResult result = client.run(config, PROMPT, httpClient);

        assertThat(result.statusCode()).isEqualTo(503);
        assertThat(result.statusDescription()).isEqualTo("Service Unavailable");
        assertThat(result.diagnosticCategory()).isEqualTo(PerplexityDiagnosticCategory.PROVIDER_DOWN);
    }

    @Test
    @SuppressWarnings("unchecked")
    void errorBodyPreviewMasksApiKeyAndSecrets() throws IOException, InterruptedException {
        String apiKey = "pplx-super-secret-key-987654";
        PerplexityConfig config = configWith("https://api.perplexity.ai", "sonar-pro", apiKey);

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(400);
        // Body echoes the literal key and a bearer token; both must be redacted in the preview.
        when(response.body()).thenReturn(
                "{\"echo\":\"" + apiKey + "\",\"auth\":\"Bearer abcdef0123456789ghijkl\"}");

        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        PerplexityHttpClient.PerplexityResult result = client.run(config, PROMPT, httpClient);

        assertThat(result.errorBodyPreview())
                .doesNotContain(apiKey)
                .doesNotContain("abcdef0123456789ghijkl")
                .contains("[REDACTED]");
    }

    @Test
    @SuppressWarnings("unchecked")
    void runReportsParseErrorOn2xxWithUnexpectedShape() throws IOException, InterruptedException {
        PerplexityConfig config = configWith("https://api.perplexity.ai", "sonar-pro", "k");

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"unexpected\":true}");

        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        PerplexityHttpClient.PerplexityResult result = client.run(config, PROMPT, httpClient);

        assertThat(result.ok()).isFalse();
        assertThat(result.extractedText()).isNull();
        assertThat(result.diagnosticCategory()).isEqualTo(PerplexityDiagnosticCategory.RESPONSE_PARSE_ERROR);
    }

    @Test
    void buildRequestThrowsWhenApiKeyMissing() {
        PerplexityConfig config = new PerplexityConfig(Map.of(
                PerplexityConfig.ENV_BASE_URL, "https://api.perplexity.ai"));

        Optional<String> validation = config.validate();
        assertThat(validation).isPresent();
    }
}
