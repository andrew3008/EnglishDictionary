package space.br1440.platform.devtools.opusmcp.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.config.AppConfig;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnthropicHttpOpusClientRetryTest {

    private AppConfig config() {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "test-key"));
    }

    private RetryPolicy fastRetry(int attempts) {
        return new RetryPolicy(attempts, 1, 2, millis -> { }, new Random(1));
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> resp(int status, String body) {
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);
        when(r.body()).thenReturn(body);
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void retriesOn503ThenSucceeds() throws IOException, InterruptedException, OpusClientException {
        HttpClient httpClient = mock(HttpClient.class);
        String okBody = "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                + "\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}";
        HttpResponse<String> busy = resp(503, "busy");
        HttpResponse<String> ok = resp(200, okBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(busy)
                .thenReturn(ok);

        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(
                config(), new ModelRegistry(), httpClient, new ObjectMapper(), fastRetry(3));

        OpusResponse response = client.generate(new OpusRequest("claude-opus-4-8", 16, "s", "u"));

        assertThat(response.text()).isEqualTo("ok");
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exhaustsRetriesOn5xxThenThrows() throws IOException, InterruptedException {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> err = resp(500, "err");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(err);

        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(
                config(), new ModelRegistry(), httpClient, new ObjectMapper(), fastRetry(3));

        assertThatThrownBy(() -> client.generate(new OpusRequest("claude-opus-4-8", 16, "s", "u")))
                .isInstanceOf(OpusClientException.class)
                .extracting(ex -> ((OpusClientException) ex).httpStatus())
                .isEqualTo(500);
        verify(httpClient, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotRetryOn400() throws IOException, InterruptedException {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> bad = resp(400, "bad");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(bad);

        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(
                config(), new ModelRegistry(), httpClient, new ObjectMapper(), fastRetry(3));

        assertThatThrownBy(() -> client.generate(new OpusRequest("claude-opus-4-8", 16, "s", "u")))
                .isInstanceOf(OpusClientException.class);
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
}
