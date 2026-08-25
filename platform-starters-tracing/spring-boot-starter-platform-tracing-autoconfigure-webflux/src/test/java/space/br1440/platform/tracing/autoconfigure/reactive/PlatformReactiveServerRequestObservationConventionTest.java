package space.br1440.platform.tracing.autoconfigure.reactive;

import io.micrometer.common.KeyValues;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import java.util.HashMap;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тесты реактивной серверной {@link PlatformReactiveServerRequestObservationConvention}.
 */
class PlatformReactiveServerRequestObservationConventionTest {

    private final PlatformReactiveServerRequestObservationConvention convention =
            new PlatformReactiveServerRequestObservationConvention();

    @Test
    void successWhenStatus2xxAndNoError() {
        ServerRequestObservationContext ctx = newContext(HttpStatus.OK, null);
        KeyValues kv = convention.getLowCardinalityKeyValues(ctx);
        assertThat(asMap(kv)).containsEntry(PlatformAttributes.PLATFORM_TYPE, SpanCategory.HTTP_SERVER.value());
        assertThat(asMap(kv)).containsEntry(PlatformAttributes.PLATFORM_RESULT, SpanResult.SUCCESS.value());
    }

    @Test
    void failureWhenStatus5xx() {
        ServerRequestObservationContext ctx = newContext(HttpStatus.INTERNAL_SERVER_ERROR, null);
        KeyValues kv = convention.getLowCardinalityKeyValues(ctx);
        assertThat(asMap(kv)).containsEntry(PlatformAttributes.PLATFORM_RESULT, SpanResult.FAILURE.value());
    }

    @Test
    void failureWhenErrorPresentEvenIfStatus2xx() {
        ServerRequestObservationContext ctx = newContext(HttpStatus.OK, new RuntimeException("boom"));
        KeyValues kv = convention.getLowCardinalityKeyValues(ctx);
        assertThat(asMap(kv)).containsEntry(PlatformAttributes.PLATFORM_RESULT, SpanResult.FAILURE.value());
    }

    private static ServerRequestObservationContext newContext(HttpStatus status, Throwable error) {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders").build();
        MockServerHttpResponse response = new MockServerHttpResponse();
        response.setStatusCode(status);
        // Конструктор reactive-контекста требует Map атрибутов (Spring Framework 6.x / SB 3.5).
        ServerRequestObservationContext ctx = new ServerRequestObservationContext(request, response, new HashMap<>());
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
