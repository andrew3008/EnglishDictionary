package space.br1440.platform.devtools.opusmcp.model;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.config.AppConfig;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnthropicHttpOpusClientTest {

    private AppConfig config() {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "test-key"));
    }

    @Test
    void buildsRequestWithStringModelAndConfigurableBaseUrl() throws OpusClientException {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        OpusRequest request = new OpusRequest("claude-opus-4-8", 16, "system", "user");

        HttpRequest httpRequest = client.buildHttpRequest(request);

        assertThat(httpRequest.uri().toString()).isEqualTo("https://api.cheat-ai.shop/v1/messages");
        assertThat(client.buildRequestBody(request)).contains("\"model\":\"claude-opus-4-8\"");
    }

    @Test
    void parsesAnthropicStyleResponse() throws OpusClientException {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        String json = "{\"content\":[{\"type\":\"text\",\"text\":\"public int add(){}\"}],"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
        OpusRequest request = new OpusRequest("claude-opus-4-8", 16, "sys", "user");

        OpusResponse response = client.parseResponse(json, request);

        assertThat(response.text()).contains("public int add");
        assertThat(response.inputTokenEstimate()).isEqualTo(10);
        assertThat(response.outputTokenEstimate()).isEqualTo(5);
        assertThat(response.providerMetadata().envelopeKind()).isEqualTo("anthropic_messages");
        assertThat(response.providerMetadata().providerCallAttempted()).isTrue();
        assertThat(response.providerMetadata().diagnosticCategory()).isEqualTo("none");
    }

    @Test
    void parsesOpenAiStyleChatCompletionsResponse() throws OpusClientException {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"public int add(){}\"}}],"
                + "\"usage\":{\"input_tokens\":7,\"output_tokens\":3}}";
        OpusResponse response = client.parseResponse(json, new OpusRequest("claude-opus-4-8", 16, "s", "u"));
        assertThat(response.text()).contains("public int add");
        assertThat(response.providerMetadata().envelopeKind()).isEqualTo("openai_chat_string");
    }

    @Test
    void parsesContentAsPlainStringResponse() throws OpusClientException {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        String json = "{\"content\":\"package p; class T {}\"}";
        OpusResponse response = client.parseResponse(json, new OpusRequest("claude-opus-4-8", 16, "s", "u"));
        assertThat(response.text()).contains("class T");
        assertThat(response.providerMetadata().envelopeKind()).isEqualTo("gateway_content_string");
    }

    @Test
    void extractTextFromOpenAiMessageContentBlockArray() {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"package p; class BlockArray {}\"}]}}]}";
        Optional<String> text = client.extractText(json);
        assertThat(text).isPresent();
        assertThat(text.orElseThrow()).contains("class BlockArray");
    }

    @Test
    void extractTextJoinsMultipleAnthropicTextBlocksInOrder() {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        String json = "{\"content\":["
                + "{\"type\":\"text\",\"text\":\"SUMMARY:\\nBaseline.\"},"
                + "{\"type\":\"text\",\"text\":\"TEST_CODE:\\n```java\\nclass T {}\\n```\"}"
                + "]}";
        Optional<String> text = client.extractText(json);
        assertThat(text).isPresent();
        assertThat(text.orElseThrow())
                .startsWith("SUMMARY:\nBaseline.")
                .contains("TEST_CODE:")
                .contains("class T {}");
    }

    @Test
    void extractTextFromOpenAiChoicesTextCompletion() {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        String json = "{\"choices\":[{\"text\":\"package p; class ChoiceText {}\"}]}";
        Optional<String> text = client.extractText(json);
        assertThat(text).isPresent();
        assertThat(text.orElseThrow()).contains("class ChoiceText");
    }

    @Test
    void extractTextFromLegacyCompletionField() {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        String json = "{\"completion\":\"package p; class Legacy {}\"}";
        Optional<String> text = client.extractText(json);
        assertThat(text).isPresent();
        assertThat(text.orElseThrow()).contains("class Legacy");
    }

    @Test
    void parseResponseExtractsOpenAiUsageFieldNames() throws OpusClientException {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        String json = "{\"choices\":[{\"message\":{\"content\":\"Hello world\"}}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":4,\"total_tokens\":15}}";
        OpusResponse response = client.parseResponse(json, new OpusRequest("claude-opus-4-8", 16, "s", "u"));
        assertThat(response.text()).isEqualTo("Hello world");
        assertThat(response.inputTokenEstimate()).isEqualTo(11);
        assertThat(response.outputTokenEstimate()).isEqualTo(4);
    }

    @Test
    void parseResponseInvalidJsonThrowsParseErrorWithDiagnostic() {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        assertThatThrownBy(() -> client.parseResponse("not valid json", new OpusRequest("claude-opus-4-8", 16, "s", "u")))
                .isInstanceOf(OpusClientException.class)
                .satisfies(ex -> {
                    OpusClientException oce = (OpusClientException) ex;
                    assertThat(oce.reason()).isEqualTo(OpusClientException.Reason.PARSE_ERROR);
                    assertThat(oce.getMessage()).contains("invalid JSON");
                    assertThat(oce.providerMetadata().diagnosticCategory()).isEqualTo("invalid_json");
                    assertThat(oce.providerMetadata().providerCallAttempted()).isTrue();
                });
    }

    @Test
    void parseResponseProviderErrorEnvelopeThrowsSafeSummaryWithoutRawMessage() {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        String json = "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\","
                + "\"message\":\"model: claude-opus-secret-model not found\"},"
                + "\"request_id\":\"req_secret123\"}";
        assertThatThrownBy(() -> client.parseResponse(json, new OpusRequest("claude-opus-4-8", 16, "s", "u")))
                .isInstanceOf(OpusClientException.class)
                .satisfies(ex -> {
                    OpusClientException oce = (OpusClientException) ex;
                    assertThat(oce.reason()).isEqualTo(OpusClientException.Reason.PARSE_ERROR);
                    assertThat(oce.getMessage()).contains("error envelope");
                    assertThat(oce.getMessage()).contains("not_found_error");
                    assertThat(oce.getMessage()).doesNotContain("claude-opus-secret-model");
                    assertThat(oce.getMessage()).doesNotContain("req_secret123");
                    assertThat(oce.providerMetadata().providerRequestId()).isEqualTo("req_secret123");
                    assertThat(oce.providerMetadata().envelopeKind()).isEqualTo("error_envelope");
                    assertThat(oce.providerMetadata().diagnosticCategory()).isEqualTo("error_envelope");
                });
    }

    @Test
    void extractTextReturnsEmptyForMalformedJson() {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        assertThat(client.extractText("not valid json {{{")).isEmpty();
    }

    @Test
    void extractTextReturnsEmptyForEmptyContent() {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        assertThat(client.extractText("{\"content\":[]}")).isEmpty();
        assertThat(client.extractText("{\"content\":\"\"}")).isEmpty();
        assertThat(client.extractText("{\"content\":[{\"type\":\"text\",\"text\":\"\"}]}")).isEmpty();
    }

    @Test
    void unparseableEnvelopeStillThrowsParseError() {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        String json = "{\"unexpected\":true}";
        assertThatThrownBy(() -> client.parseResponse(json, new OpusRequest("claude-opus-4-8", 16, "s", "u")))
                .isInstanceOf(OpusClientException.class)
                .satisfies(ex -> {
                    OpusClientException oce = (OpusClientException) ex;
                    assertThat(oce.reason()).isEqualTo(OpusClientException.Reason.PARSE_ERROR);
                    assertThat(oce.getMessage()).contains("no supported text payload found");
                    assertThat(oce.providerMetadata().diagnosticCategory()).isEqualTo("no_text_found");
                });
    }

    @Test
    void parseResponseExtractsProviderRequestIdFromHeader() throws OpusClientException {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        String json = "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}";
        HttpHeaders headers = HttpHeaders.of(
                Map.of(AnthropicHttpOpusClient.REQUEST_ID_HEADER, List.of("hdr-req-42")),
                (a, b) -> true);

        OpusResponse response = client.parseResponse(json, new OpusRequest("claude-opus-4-8", 16, "s", "u"), headers);

        assertThat(response.providerMetadata().providerRequestId()).isEqualTo("hdr-req-42");
        assertThat(response.providerMetadata().envelopeKind()).isEqualTo("anthropic_messages");
    }

    @Test
    void parseResponsePrefersHeaderRequestIdOverBodyField() throws OpusClientException {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        String json = "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"request_id\":\"body-req-1\"}";
        HttpHeaders headers = HttpHeaders.of(
                Map.of(AnthropicHttpOpusClient.REQUEST_ID_HEADER, List.of("hdr-req-99")),
                (a, b) -> true);

        OpusResponse response = client.parseResponse(json, new OpusRequest("claude-opus-4-8", 16, "s", "u"), headers);

        assertThat(response.providerMetadata().providerRequestId()).isEqualTo("hdr-req-99");
    }

    @Test
    void parseResponseOpenAiChatBlocksSetsEnvelopeKind() throws OpusClientException {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"block one\"},"
                + "{\"type\":\"text\",\"text\":\"block two\"}]}}],"
                + "\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}";
        OpusResponse response = client.parseResponse(json, new OpusRequest("claude-opus-4-8", 16, "s", "u"));
        assertThat(response.text()).isEqualTo("block one\n\nblock two");
        assertThat(response.providerMetadata().envelopeKind()).isEqualTo("openai_chat_blocks");
    }

    @Test
    void parseResponseExtractsAnthropicUsageFieldNames() throws OpusClientException {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        String json = "{\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}],"
                + "\"usage\":{\"input_tokens\":12,\"output_tokens\":6}}";
        OpusResponse response = client.parseResponse(json, new OpusRequest("claude-opus-4-8", 16, "s", "u"));
        assertThat(response.inputTokenEstimate()).isEqualTo(12);
        assertThat(response.outputTokenEstimate()).isEqualTo(6);
    }

    @Test
    void parseResponseSanitizesRequestIdFromHeaderCrLf() throws OpusClientException {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        String json = "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}";
        HttpHeaders headers = HttpHeaders.of(
                Map.of(AnthropicHttpOpusClient.REQUEST_ID_HEADER, List.of("hdr-req\r\ninjected")),
                (a, b) -> true);
        OpusResponse response = client.parseResponse(json, new OpusRequest("claude-opus-4-8", 16, "s", "u"), headers);
        assertThat(response.providerMetadata().providerRequestId()).isEqualTo("hdr-req injected");
    }

    @Test
    void parseResponseExceptionMessageNeverContainsRawBodyOrApiKey() {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        String json = "{\"type\":\"error\",\"error\":{\"type\":\"secret_type\","
                + "\"message\":\"Bearer sk-live-super-secret-key leaked\"},"
                + "\"request_id\":\"req_secret123\"}";
        assertThatThrownBy(() -> client.parseResponse(json, new OpusRequest("claude-opus-4-8", 16, "s", "u")))
                .satisfies(ex -> {
                    OpusClientException oce = (OpusClientException) ex;
                    assertThat(oce.getMessage()).doesNotContain("sk-live-super-secret-key");
                    assertThat(oce.getMessage()).doesNotContain("req_secret123");
                    assertThat(oce.getMessage()).doesNotContain("Bearer");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void maps401ToHttpError() throws IOException, InterruptedException {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(401);
        when(response.body()).thenReturn("{\"error\":\"unauthorized\"}");

        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(
                config(), new ModelRegistry(), httpClient, new com.fasterxml.jackson.databind.ObjectMapper());

        assertThatThrownBy(() -> client.generate(new OpusRequest("claude-opus-4-8", 16, "s", "u")))
                .isInstanceOf(OpusClientException.class)
                .satisfies(ex -> {
                    OpusClientException oce = (OpusClientException) ex;
                    assertThat(oce.httpStatus()).isEqualTo(401);
                    assertThat(oce.providerMetadata().providerCallAttempted()).isTrue();
                    assertThat(oce.providerMetadata().envelopeKind()).isEqualTo("none");
                    assertThat(oce.providerMetadata().diagnosticCategory()).isEqualTo("none");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void non2xxWithAnthropicErrorEnvelopePreservesHttpErrorWithMetadata() throws IOException, InterruptedException {
        String body = "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\","
                + "\"message\":\"model: claude-opus-secret-model not found\"},"
                + "\"request_id\":\"req_body_404\"}";
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(404);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(
                Map.of(AnthropicHttpOpusClient.REQUEST_ID_HEADER, List.of("req_hdr_404")),
                (a, b) -> true));

        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(
                config(), new ModelRegistry(), httpClient, new com.fasterxml.jackson.databind.ObjectMapper());

        assertThatThrownBy(() -> client.generate(new OpusRequest("claude-opus-4-8", 16, "s", "u")))
                .isInstanceOf(OpusClientException.class)
                .satisfies(ex -> {
                    OpusClientException oce = (OpusClientException) ex;
                    assertThat(oce.reason()).isEqualTo(OpusClientException.Reason.HTTP_ERROR);
                    assertThat(oce.httpStatus()).isEqualTo(404);
                    assertThat(oce.getMessage()).isEqualTo("Provider returned HTTP 404");
                    assertThat(oce.getMessage()).doesNotContain("claude-opus-secret-model");
                    assertThat(oce.getMessage()).doesNotContain("req_body_404");
                    assertThat(oce.providerMetadata().providerRequestId()).isEqualTo("req_hdr_404");
                    assertThat(oce.providerMetadata().envelopeKind()).isEqualTo("error_envelope");
                    assertThat(oce.providerMetadata().diagnosticCategory()).isEqualTo("error_envelope");
                    assertThat(oce.providerMetadata().providerCallAttempted()).isTrue();
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void non2xxWithInvalidJsonBodyKeepsHeaderRequestIdMetadata() throws IOException, InterruptedException {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(502);
        when(response.body()).thenReturn("Bad Gateway HTML");
        when(response.headers()).thenReturn(HttpHeaders.of(
                Map.of(AnthropicHttpOpusClient.REQUEST_ID_HEADER, List.of("req_hdr_502")),
                (a, b) -> true));

        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(
                config(), new ModelRegistry(), httpClient, new com.fasterxml.jackson.databind.ObjectMapper());

        assertThatThrownBy(() -> client.generate(new OpusRequest("claude-opus-4-8", 16, "s", "u")))
                .satisfies(ex -> {
                    OpusClientException oce = (OpusClientException) ex;
                    assertThat(oce.reason()).isEqualTo(OpusClientException.Reason.HTTP_ERROR);
                    assertThat(oce.httpStatus()).isEqualTo(502);
                    assertThat(oce.getMessage()).doesNotContain("Bad Gateway HTML");
                    assertThat(oce.providerMetadata().providerRequestId()).isEqualTo("req_hdr_502");
                    assertThat(oce.providerMetadata().envelopeKind()).isEqualTo("none");
                    assertThat(oce.providerMetadata().diagnosticCategory()).isEqualTo("none");
                });
    }

    @Test
    void rejectsNonAllowlistedModel() {
        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config(), new ModelRegistry());

        assertThatThrownBy(() -> client.generate(new OpusRequest("unknown-model", 16, "s", "u")))
                .isInstanceOf(OpusClientException.class)
                .extracting(ex -> ((OpusClientException) ex).reason())
                .isEqualTo(OpusClientException.Reason.MODEL_NOT_ALLOWED);
    }
}
