package space.br1440.platform.tracing.autoconfigure.reactive;

import io.micrometer.common.KeyValues;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;
import org.springframework.web.reactive.function.client.ClientResponse;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты реактивной клиентской {@link PlatformReactiveClientRequestObservationConvention}.
 * <p>
 * Проверяет, что convention расширяет WebFlux-flavor {@code DefaultClientRequestObservationConvention}
 * и проставляет {@code platform.type=http_client} и {@code platform.result}.
 */
class PlatformReactiveClientRequestObservationConventionTest {

    private final PlatformReactiveClientRequestObservationConvention convention =
            new PlatformReactiveClientRequestObservationConvention();

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
    void successWhenResponseAbsentAndNoError() {
        ClientRequestObservationContext ctx = new ClientRequestObservationContext(
                ClientRequest.create(org.springframework.http.HttpMethod.GET,
                        URI.create("http://example/test")));

        KeyValues kv = convention.getLowCardinalityKeyValues(ctx);
        assertThat(asMap(kv)).containsEntry(PlatformAttributes.PLATFORM_RESULT, SpanResult.SUCCESS.value());
    }

    private static ClientRequestObservationContext newContext(HttpStatus status, Throwable error) {
        ClientRequestObservationContext ctx = new ClientRequestObservationContext(
                ClientRequest.create(org.springframework.http.HttpMethod.GET, URI.create("http://example/test")));
        if (status != null) {
            ClientResponse response = mock(ClientResponse.class);
            when(response.statusCode()).thenReturn(HttpStatusCode.valueOf(status.value()));
            ctx.setResponse(response);
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
