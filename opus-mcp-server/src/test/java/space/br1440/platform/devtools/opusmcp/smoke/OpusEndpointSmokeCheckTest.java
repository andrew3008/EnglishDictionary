package space.br1440.platform.devtools.opusmcp.smoke;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.config.AppConfig;

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

class OpusEndpointSmokeCheckTest {

    private final OpusEndpointSmokeCheck check = new OpusEndpointSmokeCheck();

    private AppConfig configWith(String baseUrl, String model, String apiKey) {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, baseUrl,
                AppConfig.ENV_MODEL, model,
                AppConfig.ENV_API_KEY, apiKey));
    }

    @Test
    void buildsRequestWithConfigurableBaseUrlAndStringModelId() {
        AppConfig config = configWith("https://my-gateway.example", "custom-opus-4-8", "k");

        HttpRequest request = check.buildHttpRequest(config);

        assertThat(request.uri().toString()).isEqualTo("https://my-gateway.example/v1/messages");
        assertThat(request.method()).isEqualTo("POST");
        // Model id is passed as a plain string in the body.
        String body = check.buildRequestBody(config.model(), OpusEndpointSmokeCheck.SYNTHETIC_PROMPT);
        assertThat(body).contains("\"model\":\"custom-opus-4-8\"");
    }

    @Test
    void normalizesTrailingSlashInBaseUrl() {
        AppConfig config = configWith("https://my-gateway.example/", "custom-opus-4-8", "k");

        HttpRequest request = check.buildHttpRequest(config);

        assertThat(request.uri().toString()).isEqualTo("https://my-gateway.example/v1/messages");
    }

    @Test
    void requestBodySendsOnlySyntheticPromptAndNoRepositoryContext() {
        String body = check.buildRequestBody("custom-opus-4-8", OpusEndpointSmokeCheck.SYNTHETIC_PROMPT);

        assertThat(body)
                .contains("Reply with exactly: OK")
                .contains("\"role\":\"user\"");
        // Guard: nothing resembling repository/file context is ever included.
        assertThat(body)
                .doesNotContain("package ")
                .doesNotContain("import ")
                .doesNotContain("/src/")
                .doesNotContain(".java");
    }

    @Test
    void parsesMinimalMessagesResponse() {
        String json = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"OK\"}]}";

        Optional<String> text = check.parseResponseText(json);

        assertThat(text).contains("OK");
    }

    @Test
    void parseReturnsEmptyForUnexpectedShape() {
        assertThat(check.parseResponseText("{\"unexpected\":true}")).isEmpty();
        assertThat(check.parseResponseText("not-json")).isEmpty();
    }

    @Test
    void validateReportsMissingBaseUrl() {
        AppConfig config = new AppConfig(Map.of(AppConfig.ENV_API_KEY, "k"));

        assertThat(check.validate(config)).isPresent();
        assertThat(check.validate(config).orElseThrow()).contains(AppConfig.ENV_BASE_URL);
    }

    @Test
    void validateReportsMissingApiKey() {
        AppConfig config = new AppConfig(Map.of(AppConfig.ENV_BASE_URL, "https://x"));

        assertThat(check.validate(config)).isPresent();
        assertThat(check.validate(config).orElseThrow()).contains(AppConfig.ENV_API_KEY);
    }

    @Test
    void runFailsSafelyWhenEnvMissing() {
        AppConfig config = new AppConfig(Map.of());
        HttpClient httpClient = mock(HttpClient.class);

        OpusEndpointSmokeCheck.SmokeResult result = check.run(config, httpClient);

        assertThat(result.ok()).isFalse();
        assertThat(result.statusCode()).isEqualTo(-1);
        assertThat(result.message()).isNotBlank();
    }

    @Test
    @SuppressWarnings("unchecked")
    void runSucceedsWithMockedHttpClient() throws IOException, InterruptedException {
        AppConfig config = configWith("https://my-gateway.example", "custom-opus-4-8", "k");

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(
                "{\"content\":[{\"type\":\"text\",\"text\":\"OK\"}]}");

        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        OpusEndpointSmokeCheck.SmokeResult result = check.run(config, httpClient);

        assertThat(result.ok()).isTrue();
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.extractedText()).isEqualTo("OK");
    }

    @Test
    @SuppressWarnings("unchecked")
    void runReportsNon2xxStatus() throws IOException, InterruptedException {
        AppConfig config = configWith("https://my-gateway.example", "custom-opus-4-8", "k");

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(401);
        when(response.body()).thenReturn("{\"error\":\"unauthorized\"}");

        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        OpusEndpointSmokeCheck.SmokeResult result = check.run(config, httpClient);

        assertThat(result.ok()).isFalse();
        assertThat(result.statusCode()).isEqualTo(401);
        assertThat(result.diagnosticCategory()).isEqualTo(ProviderDiagnosticCategory.AUTH_ERROR);
    }

    @Test
    @SuppressWarnings("unchecked")
    void runClassifiesCloudflare502AsProviderDown() throws IOException, InterruptedException {
        AppConfig config = configWith("https://my-gateway.example", "custom-opus-4-8", "k");

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(502);
        when(response.body()).thenReturn("error code: 502");

        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        OpusEndpointSmokeCheck.SmokeResult result = check.run(config, httpClient);

        assertThat(result.ok()).isFalse();
        assertThat(result.statusCode()).isEqualTo(502);
        assertThat(result.statusDescription()).isEqualTo("Bad Gateway");
        assertThat(result.errorBodyPreview()).isEqualTo("error code: 502");
        assertThat(result.diagnosticCategory()).isEqualTo(ProviderDiagnosticCategory.PROVIDER_DOWN);
    }
}
