package space.br1440.platform.errorhandling.core.strategy.cause.integration;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.RetryableException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeignRootCauseExceptionStrategyTest {

    private final FeignRootCauseExceptionStrategy strategy =
            new FeignRootCauseExceptionStrategy(new StackHintExtractor(true, List.of()));

    @Nested
    class SupportsCause {

        @Test
        void supports_feign_exception() {
            FeignException ex = buildFeignException(503, Map.of());

            assertTrue(strategy.supportsCause(ex));
        }

        @Test
        void supports_retryable_exception() {
            RetryableException ex = retryable(-1, "timeout", null, "http://svc:8080/api");

            assertTrue(strategy.supportsCause(ex));
        }

        @Test
        void does_not_support_plain_runtime_exception() {
            assertFalse(strategy.supportsCause(new RuntimeException("boom")));
        }
    }

    @Nested
    class HttpMapping {

        @Test
        void maps_401_to_unauthorized() {
            assertStatus(401, HttpStatus.UNAUTHORIZED);
        }

        @Test
        void maps_403_to_forbidden() {
            assertStatus(403, HttpStatus.FORBIDDEN);
        }

        @Test
        void maps_404_to_not_found() {
            assertStatus(404, HttpStatus.NOT_FOUND);
        }

        @Test
        void maps_410_to_gone() {
            assertStatus(410, HttpStatus.GONE);
        }

        @Test
        void maps_429_to_too_many_requests() {
            assertStatus(429, HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        void maps_503_to_service_unavailable() {
            assertStatus(503, HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        void maps_504_to_gateway_timeout() {
            assertStatus(504, HttpStatus.GATEWAY_TIMEOUT);
        }

        @Test
        void maps_408_upstream_timeout_to_gateway_timeout() {
            assertStatus(408, HttpStatus.GATEWAY_TIMEOUT);
        }

        @Test
        void maps_409_conflict_to_conflict() {
            assertStatus(409, HttpStatus.CONFLICT);
        }

        @Test
        void maps_400_to_internal_server_error() {
            assertStatus(400, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void maps_422_to_internal_server_error() {
            assertStatus(422, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void maps_500_to_bad_gateway() {
            assertStatus(500, HttpStatus.BAD_GATEWAY);
        }

        @Test
        void retryable_without_status_maps_to_service_unavailable() {
            RetryableException ex = retryable(-1, "connection timed out", null, "http://svc:8080/api");

            RootCauseResolution resolution = strategy.extractCauseDetails(ex);

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resolution.httpStatus());
            assertEquals(Retryability.RETRYABLE, resolution.retryability());
        }

        private void assertStatus(int feignStatus, HttpStatus expected) {
            FeignException ex = buildFeignException(feignStatus, Map.of());

            RootCauseResolution resolution = strategy.extractCauseDetails(ex);

            assertEquals(expected, resolution.httpStatus(),
                    () -> "Expected upstream " + feignStatus + " → " + expected);
        }
    }

    @Nested
    class ReasonAndRetryability {

        @Test
        void non_retryable_for_404() {
            FeignException ex = buildFeignException(404, Map.of());

            RootCauseResolution resolution = strategy.extractCauseDetails(ex);

            assertEquals(Retryability.NOT_RETRYABLE, resolution.retryability());
        }

        @Test
        void retryable_for_retryableException() {
            RetryableException ex = retryable(503, "upstream down", null, "http://svc:8080/api");

            RootCauseResolution resolution = strategy.extractCauseDetails(ex);

            assertEquals(Retryability.RETRYABLE, resolution.retryability());
        }

        @Test
        void reason_is_resource_exhausted_for_429() {
            FeignException ex = buildFeignException(429, Map.of());

            ErrorInfoDetail info = findInfo(strategy.extractCauseDetails(ex));

            assertEquals("RESOURCE_EXHAUSTED", info.getReason());
        }

        @Test
        void domain_is_http_integration() {
            FeignException ex = buildFeignException(503, Map.of());

            ErrorInfoDetail info = findInfo(strategy.extractCauseDetails(ex));

            assertEquals("http-integration", info.getDomain());
        }
    }

    @Nested
    class ClientMetadata {

        @Test
        void does_not_expose_upstreamStatus_in_clientMetadata() {
            FeignException ex = buildFeignException(503, Map.of());

            ErrorInfoDetail info = findInfo(strategy.extractCauseDetails(ex));

            // CWE-200: upstream HTTP status — деталь архитектуры, не должна
            // светить во внешнем DTO. См. Javadoc стратегии.
            Map<String, String> clientMetadata = info.getClientMetadata();
            assertTrue(clientMetadata.isEmpty(),"clientMetadata must not expose upstream HTTP details");
        }
    }

    @Nested
    class DiagnosticMetadata {

        @Test
        void includes_upstream_status_in_diagnostic() {
            FeignException ex = buildFeignException(503, Map.of());

            Map<String, String> diag = diagOf(ex);

            assertEquals("503", diag.get("upstreamStatus"));
        }

        @Test
        void does_not_include_legacy_upstreamErrorCategory() {
            FeignException ex = buildFeignException(503, Map.of());

            Map<String, String> diag = diagOf(ex);

            assertFalse(diag.containsKey("upstreamErrorCategory"),
                    "upstreamErrorCategory удалено как вычислимое из upstreamStatus");
        }

        @Test
        void includes_request_context() {
            FeignException ex = buildFeignException(503, Map.of(),
                    "http://payment-service:9090/api/v1/charge");

            Map<String, String> diag = diagOf(ex);

            assertEquals("GET", diag.get("httpMethod"));
            assertEquals("payment-service", diag.get("upstreamHost"));
            assertEquals("/api/v1/charge", diag.get("upstreamPath"));
            assertFalse(diag.containsKey("upstreamPort"),
                    "upstreamPort удалён — вычислимо из upstreamHost/URL при необходимости");
        }

        @Test
        void does_not_include_responseContentLength() {
            FeignException ex = buildFeignException(503, Map.of());

            Map<String, String> diag = diagOf(ex);

            assertFalse(diag.containsKey("responseContentLength"),
                    "responseContentLength удалён — содержимое body не включается (CWE-209)");
        }

        @Test
        void sanitizes_numeric_id_segments_in_upstreamPath() {
            // CWE-209: tenant/user-id в URL не должны попадать в diagnosticMetadata —
            // PathSanitizer заменяет числовые сегменты на {id}.
            FeignException ex = buildFeignException(503, Map.of(),
                    "http://payment-service:9090/api/v1/users/12345/orders/700");

            Map<String, String> diag = diagOf(ex);

            String path = diag.get("upstreamPath");
            assertNotNull(path);
            assertFalse(path.contains("12345"),
                    "числовой id-сегмент должен быть заменён на {id}: " + path);
            assertFalse(path.contains("700"));
            assertTrue(path.contains("{id}"),
                    "PathSanitizer должен подставить маркер {id}: " + path);
        }

        @Test
        void sanitizes_uuid_segment_in_upstreamPath() {
            FeignException ex = buildFeignException(503, Map.of(),
                    "http://svc/api/v1/sessions/550e8400-e29b-41d4-a716-446655440000");

            Map<String, String> diag = diagOf(ex);

            String path = diag.get("upstreamPath");
            assertNotNull(path);
            assertFalse(path.contains("550e8400"),
                    "UUID-сегмент должен быть заменён на {id}: " + path);
            assertTrue(path.contains("{id}"));
        }

        @Test
        void caps_long_upstreamPath() {
            // PathSanitizer.MAX_PATH_LENGTH = 120; убеждаемся, что результат не превышает разумный лимит.
            String longPath = "/api/" + "x".repeat(300);
            FeignException ex = buildFeignException(503, Map.of(),
                    "http://svc:9090" + longPath);

            Map<String, String> diag = diagOf(ex);

            String path = diag.get("upstreamPath");
            assertNotNull(path);
            assertTrue(path.length() <= 200,
                    "upstreamPath должен быть ограничен PathSanitizer.MAX_PATH_LENGTH + маркер: "
                            + path.length() + " chars");
        }
    }

    @Nested
    class RetryAfter {

        @Test
        void parses_retry_after_header_seconds() {
            FeignException ex = buildFeignException(429,
                    Map.of("Retry-After", List.of("120")));

            RootCauseResolution resolution = strategy.extractCauseDetails(ex);
            Map<String, String> diag = findInfo(resolution).getDiagnosticMetadata();

            assertEquals("120000", diag.get("retryAfterMs"));
            assertEquals("header", diag.get("retryAfterSource"));
        }

        @Test
        void sanitizes_crlf_in_retry_after_header() {
            // CWE-117 Log Injection: upstream возвращает заголовок с \r\n — не должен
            // повредить парсер и не должен попасть в raw-виде в metadata.
            FeignException ex = buildFeignException(429,
                    Map.of("Retry-After", List.of("60\r\nX-Evil: 1")));

            Map<String, String> diag = diagOf(ex);

            // Либо парсинг удался до \r\n → retryAfterMs присутствует без CRLF,
            // либо парсер не справился и значение не попало — но CRLF не должно
            // остаться в raw-виде.
            String retryAfter = diag.get("retryAfterMs");
            if (retryAfter != null) {
                assertFalse(retryAfter.contains("\r"));
                assertFalse(retryAfter.contains("\n"));
            }
        }

        @Test
        void falls_back_to_retryable_exception_retryAfter() {
            long futureEpoch = System.currentTimeMillis() + 30_000L;
            RetryableException ex = new RetryableException(
                    503, "throttled", Request.HttpMethod.POST,
                    futureEpoch, buildRequest("http://svc/api"));

            RootCauseResolution resolution = strategy.extractCauseDetails(ex);
            Map<String, String> diag = findInfo(resolution).getDiagnosticMetadata();

            assertEquals("exception", diag.get("retryAfterSource"));
            // допускается небольшой дрейф из-за системного времени
            long ms = Long.parseLong(diag.get("retryAfterMs"));
            assertTrue(ms > 0 && ms <= 30_000, "retryAfterMs должен быть положительным и ≤ 30s");
        }

        @Test
        void header_wins_over_exception_source() {
            long futureEpoch = System.currentTimeMillis() + 60_000L;
            Map<String, Collection<String>> responseHeaders = new LinkedHashMap<>();
            responseHeaders.put("Retry-After", List.of("5"));

            RetryableException ex = new RetryableException(
                    503, "throttled", Request.HttpMethod.POST,
                    futureEpoch, buildRequest("http://svc/api"),
                    new byte[0], responseHeaders);

            Map<String, String> diag = diagOf(ex);

            assertEquals("header", diag.get("retryAfterSource"),
                    "Header-based retry-after должен побеждать exception fallback");
            assertEquals("5000", diag.get("retryAfterMs"));
        }

        @Test
        void retry_info_detail_present_for_retryable_only() {
            RetryableException retryable = new RetryableException(
                    503, "throttled", Request.HttpMethod.GET,
                    System.currentTimeMillis() + 5_000L,
                    buildRequest("http://svc/api"));
            FeignException nonRetryable = buildFeignException(404, Map.of());

            assertTrue(hasRetryInfoDetail(strategy.extractCauseDetails(retryable)));
            assertFalse(hasRetryInfoDetail(strategy.extractCauseDetails(nonRetryable)));
        }
    }

    @Nested
    class RateLimitHeaders {

        @Test
        void enriches_only_on_429() {
            FeignException notFound = buildFeignException(404,
                    Map.of("X-RateLimit-Limit", List.of("100")));
            FeignException throttled = buildFeignException(429,
                    Map.of("X-RateLimit-Limit", List.of("100"),
                            "X-RateLimit-Remaining", List.of("0")));

            assertFalse(diagOf(notFound).containsKey("rateLimitMax"));
            assertEquals("100", diagOf(throttled).get("rateLimitMax"));
            assertEquals("0", diagOf(throttled).get("rateLimitRemaining"));
        }

        @Test
        void sanitizes_rate_limit_header_values() {
            // CWE-117
            FeignException ex = buildFeignException(429,
                    Map.of("X-RateLimit-Remaining", List.of("5\r\nX-Injected: yes")));

            String remaining = diagOf(ex).get("rateLimitRemaining");

            assertNotNull(remaining);
            assertFalse(remaining.contains("\r"));
            assertFalse(remaining.contains("\n"));
        }
    }

    @Nested
    class Message {

        @Test
        void gone_has_dedicated_message() {
            RootCauseResolution resolution = strategy.extractCauseDetails(
                    buildFeignException(410, Map.of()));

            assertTrue(resolution.message().toLowerCase().contains("no longer"),
                    "Сообщение для 410 должно отражать семантику GONE");
        }

        @Test
        void conflict_has_dedicated_message() {
            RootCauseResolution resolution = strategy.extractCauseDetails(
                    buildFeignException(409, Map.of()));

            assertTrue(resolution.message().toLowerCase().contains("conflict"));
        }

        @Test
        void internal_error_message_for_our_bad_request() {
            RootCauseResolution resolution = strategy.extractCauseDetails(
                    buildFeignException(400, Map.of()));

            // 400/422 от upstream — это наш баг; сообщение должно на это намекать
            assertTrue(resolution.message().toLowerCase().contains("malformed"));
        }
    }

    private static Request buildRequest(String url) {
        return Request.create(Request.HttpMethod.GET, url,
                new HashMap<>(), null, StandardCharsets.UTF_8, new RequestTemplate());
    }

    /**
     * Создаёт {@link RetryableException} через Long-overload (Feign 10+),
     * избегая неоднозначности {@code null}-аргумента между {@code Long} и {@code Date}.
     */
    private static RetryableException retryable(int status,
                                                String message,
                                                Long retryAfterEpochMillis,
                                                String url) {
        return new RetryableException(
                status, message, Request.HttpMethod.GET,
                retryAfterEpochMillis, buildRequest(url));
    }

    private static FeignException buildFeignException(int status,
                                                      Map<String, Collection<String>> headers) {
        return buildFeignException(status, headers, "http://svc/api");
    }

    private static FeignException buildFeignException(int status,
                                                      Map<String, Collection<String>> headers,
                                                      String url) {
        // Feign.errorStatus сам выберет корректный подкласс (NotFound, ServiceUnavailable, ...)
        Map<String, Collection<String>> requestHeaders = new HashMap<>();
        Request request = Request.create(Request.HttpMethod.GET, url,
                requestHeaders, null, StandardCharsets.UTF_8, new RequestTemplate());

        Response response = Response.builder()
                .status(status)
                .reason(HttpStatus.valueOf(status).getReasonPhrase())
                .request(request)
                .headers(new LinkedHashMap<>(headers))
                .build();

        return FeignException.errorStatus("SvcClient#call(Object)", response);
    }

    private static ErrorInfoDetail findInfo(RootCauseResolution resolution) {
        for (ErrorDetail detail : resolution.details()) {
            if (detail instanceof ErrorInfoDetail info) {
                return info;
            }
        }
        throw new AssertionError("ErrorInfoDetail not present in resolution.details()");
    }

    private Map<String, String> diagOf(Throwable cause) {
        return findInfo(strategy.extractCauseDetails(cause)).getDiagnosticMetadata();
    }

    private static boolean hasRetryInfoDetail(RootCauseResolution resolution) {
        for (ErrorDetail detail : resolution.details()) {
            if (detail instanceof RetryInfoDetail) {
                return true;
            }
        }

        return false;
    }
}
