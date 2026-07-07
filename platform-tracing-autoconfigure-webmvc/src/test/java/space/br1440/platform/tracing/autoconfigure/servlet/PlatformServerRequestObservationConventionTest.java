package space.br1440.platform.tracing.autoconfigure.servlet;

import io.micrometer.common.KeyValues;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тесты Servlet-серверной {@link PlatformServerRequestObservationConvention}.
 * <p>
 * Проверяет, что обязательные платформенные атрибуты {@code platform.type=http_server}
 * и {@code platform.result} вычисляются корректно по сочетанию HTTP-кода и наличия исключения.
 */
class PlatformServerRequestObservationConventionTest {

    private final PlatformServerRequestObservationConvention convention =
            new PlatformServerRequestObservationConvention();

    @Test
    void successWhenStatus2xxAndNoError() {
        ServerRequestObservationContext ctx = newContext(200, null);

        KeyValues kv = convention.getLowCardinalityKeyValues(ctx);

        assertThat(asMap(kv)).containsEntry(PlatformAttributes.PLATFORM_TYPE, SpanCategory.HTTP_SERVER.value());
        assertThat(asMap(kv)).containsEntry(PlatformAttributes.PLATFORM_RESULT, SpanResult.SUCCESS.value());
    }

    @Test
    void failureWhenStatusGte400() {
        ServerRequestObservationContext ctx = newContext(500, null);

        KeyValues kv = convention.getLowCardinalityKeyValues(ctx);
        assertThat(asMap(kv)).containsEntry(PlatformAttributes.PLATFORM_RESULT, SpanResult.FAILURE.value());
    }

    @Test
    void failureWhenErrorPresentEvenIfStatus2xx() {
        ServerRequestObservationContext ctx = newContext(200, new RuntimeException("boom"));

        KeyValues kv = convention.getLowCardinalityKeyValues(ctx);
        assertThat(asMap(kv)).containsEntry(PlatformAttributes.PLATFORM_RESULT, SpanResult.FAILURE.value());
    }

    @Test
    void successWhenStatus3xx() {
        ServerRequestObservationContext ctx = newContext(302, null);
        KeyValues kv = convention.getLowCardinalityKeyValues(ctx);
        assertThat(asMap(kv)).containsEntry(PlatformAttributes.PLATFORM_RESULT, SpanResult.SUCCESS.value());
    }

    private static ServerRequestObservationContext newContext(int status, Throwable error) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        ServerRequestObservationContext ctx = new ServerRequestObservationContext(request, response);
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
