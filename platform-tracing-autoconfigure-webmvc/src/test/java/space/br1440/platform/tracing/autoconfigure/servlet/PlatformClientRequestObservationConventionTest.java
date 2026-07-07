package space.br1440.platform.tracing.autoconfigure.servlet;

import io.micrometer.common.KeyValues;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тесты Servlet-клиентской {@link PlatformClientRequestObservationConvention}.
 * <p>
 * Проверяется, что атрибуты {@code platform.type=http_client} и {@code platform.result}
 * проставляются по матрице (success/failure × ошибка/2xx/4xx/5xx/IOException при чтении статуса).
 */
class PlatformClientRequestObservationConventionTest {

    private final PlatformClientRequestObservationConvention convention =
            new PlatformClientRequestObservationConvention();

    @Test
    void successWhenStatus2xxAndNoError() {
        ClientRequestObservationContext ctx = newContext(HttpStatus.OK, null);

        KeyValues kv = convention.getLowCardinalityKeyValues(ctx);
        assertThat(asMap(kv)).containsEntry(PlatformAttributes.PLATFORM_TYPE, SpanCategory.HTTP_CLIENT.value());
        assertThat(asMap(kv)).containsEntry(PlatformAttributes.PLATFORM_RESULT, SpanResult.SUCCESS.value());
    }

    @Test
    void failureWhenStatus4xx() {
        ClientRequestObservationContext ctx = newContext(HttpStatus.NOT_FOUND, null);
        KeyValues kv = convention.getLowCardinalityKeyValues(ctx);
        assertThat(asMap(kv)).containsEntry(PlatformAttributes.PLATFORM_RESULT, SpanResult.FAILURE.value());
    }

    @Test
    void failureWhenStatus5xx() {
        ClientRequestObservationContext ctx = newContext(HttpStatus.BAD_GATEWAY, null);
        KeyValues kv = convention.getLowCardinalityKeyValues(ctx);
        assertThat(asMap(kv)).containsEntry(PlatformAttributes.PLATFORM_RESULT, SpanResult.FAILURE.value());
    }

    @Test
    void failureWhenErrorPresent() {
        ClientRequestObservationContext ctx = newContext(null, new java.net.ConnectException("refused"));
        KeyValues kv = convention.getLowCardinalityKeyValues(ctx);
        assertThat(asMap(kv)).containsEntry(PlatformAttributes.PLATFORM_RESULT, SpanResult.FAILURE.value());
    }

    @Test
    void failureWhenResponseStatusReadThrowsIOException() {
        // Контекст с response, который при getStatusCode() бросает IOException — реалистичный
        // сценарий проблем чтения чанка / закрытого соединения. Должно квалифицироваться как FAILURE.
        ClientHttpResponse exploding = new ClientHttpResponse() {
            @Override
            public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
                throw new IOException("stream closed");
            }

            @Override
            public String getStatusText() {
                return "OK";
            }

            @Override
            public org.springframework.http.HttpHeaders getHeaders() {
                return new org.springframework.http.HttpHeaders();
            }

            @Override
            public java.io.InputStream getBody() {
                return java.io.InputStream.nullInputStream();
            }

            @Override
            public void close() {
            }
        };
        MockClientHttpRequest request = new MockClientHttpRequest();
        request.setURI(URI.create("http://example/test"));
        ClientRequestObservationContext ctx = new ClientRequestObservationContext(request);
        ctx.setResponse(exploding);

        KeyValues kv = convention.getLowCardinalityKeyValues(ctx);
        assertThat(asMap(kv)).containsEntry(PlatformAttributes.PLATFORM_RESULT, SpanResult.FAILURE.value());
    }

    private static ClientRequestObservationContext newContext(HttpStatus status, Throwable error) {
        MockClientHttpRequest request = new MockClientHttpRequest();
        request.setURI(URI.create("http://example/test"));
        ClientRequestObservationContext ctx = new ClientRequestObservationContext(request);
        if (status != null) {
            ctx.setResponse(new MockClientHttpResponse(new byte[0], status));
        }
        if (error != null) {
            ctx.setError(error);
        }
        return ctx;
    }

    private static java.util.Map<String, String> asMap(KeyValues kv) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        kv.forEach(k -> result.put(k.getKey(), k.getValue()));
        return result;
    }
}
