package space.br1440.platform.devtools.opusmcp.perplexity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 8C offline contract tests for the production {@link PerplexityResearchClient}, using a mocked
 * {@link HttpClient}. Covers the OpenAI-compatible success path plus malformed/empty provider bodies
 * and HTTP error classifications. No real network and no API key are used.
 */
class PerplexityResearchClientTest {

    private static final String SYS = "system prompt";
    private static final String USER = "user prompt";

    private PerplexityConfig config() {
        return new PerplexityConfig(Map.of(
                PerplexityConfig.ENV_BASE_URL, "https://api.perplexity.ai",
                PerplexityConfig.ENV_MODEL, "sonar-deep-research",
                PerplexityConfig.ENV_API_KEY, "k"));
    }

    @SuppressWarnings("unchecked")
    private HttpClient mockedHttp(int status, String body) throws IOException, InterruptedException {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        return httpClient;
    }

    private PerplexityResearchClient client(HttpClient httpClient) {
        return new PerplexityResearchClient(config(), new PerplexityHttpClient(), httpClient);
    }

    @Test
    void successExtractsTextModelAndRequestId() throws Exception {
        String body = "{\"id\":\"cmpl_1\",\"model\":\"sonar-deep-research\","
                + "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ANSWER:\\nHello\"}}]}";
        ResearchResponse response = client(mockedHttp(200, body)).research(SYS, USER);

        assertThat(response.text()).contains("Hello");
        assertThat(response.model()).isEqualTo("sonar-deep-research");
        assertThat(response.requestId()).isEqualTo("cmpl_1");
        assertThat(response.inputTokenEstimate()).isPositive();
    }

    @Test
    void emptyChoicesMapsToParseError() throws Exception {
        ResearchClientException ex = catchClientException(client(mockedHttp(200, "{\"choices\":[]}")));
        assertThat(ex.category()).isEqualTo(PerplexityDiagnosticCategory.RESPONSE_PARSE_ERROR);
    }

    @Test
    void missingMessageContentMapsToParseError() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\"}}]}";
        ResearchClientException ex = catchClientException(client(mockedHttp(200, body)));
        assertThat(ex.category()).isEqualTo(PerplexityDiagnosticCategory.RESPONSE_PARSE_ERROR);
    }

    @Test
    void malformedJsonBodyMapsToParseError() throws Exception {
        ResearchClientException ex = catchClientException(client(mockedHttp(200, "not-json-at-all")));
        assertThat(ex.category()).isEqualTo(PerplexityDiagnosticCategory.RESPONSE_PARSE_ERROR);
    }

    @Test
    void authErrorIsClassified() throws Exception {
        ResearchClientException ex = catchClientException(
                client(mockedHttp(401, "{\"error\":{\"message\":\"unauthorized\"}}")));
        assertThat(ex.category()).isEqualTo(PerplexityDiagnosticCategory.AUTH_ERROR);
        assertThat(ex.getMessage()).doesNotContain("unauthorized");
    }

    @Test
    void rateLimitIsClassified() throws Exception {
        ResearchClientException ex = catchClientException(client(mockedHttp(429, "slow down")));
        assertThat(ex.category()).isEqualTo(PerplexityDiagnosticCategory.RATE_LIMIT_OR_QUOTA);
    }

    @Test
    void providerDownIsClassified() throws Exception {
        ResearchClientException ex = catchClientException(client(mockedHttp(503, "unavailable")));
        assertThat(ex.category()).isEqualTo(PerplexityDiagnosticCategory.PROVIDER_DOWN);
    }

    private static ResearchClientException catchClientException(PerplexityResearchClient client) {
        ResearchClientException[] holder = new ResearchClientException[1];
        assertThatThrownBy(() -> client.research(SYS, USER))
                .isInstanceOfSatisfying(ResearchClientException.class, e -> holder[0] = e);
        return holder[0];
    }
}
