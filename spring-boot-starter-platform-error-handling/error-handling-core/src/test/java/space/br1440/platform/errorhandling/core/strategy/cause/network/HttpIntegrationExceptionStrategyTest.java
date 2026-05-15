package space.br1440.platform.errorhandling.core.strategy.cause.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HttpIntegrationExceptionStrategy")
class HttpIntegrationExceptionStrategyTest {

    private HttpIntegrationExceptionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new HttpIntegrationExceptionStrategy(new StackHintExtractor(false, List.of()));
    }

    @Nested
    @DisplayName("supportsCause")
    class SupportsCause {

        @ParameterizedTest(name = "{0}")
        @MethodSource("supportedExceptions")
        void acceptsSupportedTypes(String label, Throwable cause) {
            assertThat(strategy.supportsCause(cause)).isTrue();
        }

        static Stream<Arguments> supportedExceptions() {
            return Stream.of(
                    Arguments.of("SocketTimeoutException",
                            new SocketTimeoutException("Read timed out")),
                    Arguments.of("TimeoutException",
                            new TimeoutException("request timeout")),
                    Arguments.of("ConnectException",
                            new ConnectException("Connection refused")),
                    Arguments.of("UnknownHostException",
                            new UnknownHostException("upstream.internal")),
                    Arguments.of("ResourceAccessException",
                            new ResourceAccessException("I/O error", new ConnectException("refused"))),
                    Arguments.of("HttpClientErrorException (4xx)",
                            HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                                    HttpHeaders.EMPTY, new byte[0], null)),
                    Arguments.of("HttpServerErrorException (5xx)",
                            HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "ISE",
                                    HttpHeaders.EMPTY, new byte[0], null)),
                    Arguments.of("WebClientResponseException",
                            WebClientResponseException.create(429, "Too Many Requests",
                                    HttpHeaders.EMPTY, new byte[0], null)),
                    Arguments.of("WebClientRequestException",
                            new WebClientRequestException(new ConnectException("refused"),
                                    HttpMethod.GET, URI.create("http://upstream/api"), HttpHeaders.EMPTY))
            );
        }

        @Test
        void rejectsNonNetworkExceptions() {
            assertThat(strategy.supportsCause(new RuntimeException("not network"))).isFalse();
            assertThat(strategy.supportsCause(new IllegalStateException("nope"))).isFalse();
        }
    }

    @Nested
    @DisplayName("classification — гранулярные категории")
    class GranularClassification {

        @ParameterizedTest(name = "{0} → reason={1} status={2} retryable={3}")
        @MethodSource("classificationCases")
        void classifiesCorrectly(String label,
                                 String expectedReason,
                                 HttpStatus expectedStatus,
                                 boolean expectedRetryable,
                                 Throwable cause) {
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            assertThat(extractReason(resolution)).isEqualTo(expectedReason);
            assertThat(resolution.httpStatus()).isEqualTo(expectedStatus);

            boolean hasRetryInfo = resolution.details().stream()
                    .anyMatch(d -> d instanceof RetryInfoDetail);
            assertThat(hasRetryInfo).as("retryable").isEqualTo(expectedRetryable);
        }

        static Stream<Arguments> classificationCases() {
            return Stream.of(
                    Arguments.of("SocketTimeoutException", "SOCKET_TIMEOUT",
                            HttpStatus.GATEWAY_TIMEOUT, true,
                            new SocketTimeoutException("Read timed out")),

                    Arguments.of("TimeoutException", "TIMEOUT",
                            HttpStatus.GATEWAY_TIMEOUT, true,
                            new TimeoutException("request timeout")),

                    Arguments.of("ConnectException", "CONNECT_REFUSED",
                            HttpStatus.SERVICE_UNAVAILABLE, true,
                            new ConnectException("Connection refused: upstream:8080")),

                    Arguments.of("UnknownHostException", "DNS_FAILURE",
                            HttpStatus.SERVICE_UNAVAILABLE, true,
                            new UnknownHostException("upstream.internal")),

                    Arguments.of("HTTP 404 → CLIENT_ERROR (not retryable)", "CLIENT_ERROR",
                            HttpStatus.BAD_GATEWAY, false,
                            HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                                    HttpHeaders.EMPTY, new byte[0], null)),

                    Arguments.of("HTTP 500 → SERVER_ERROR (not retryable)", "SERVER_ERROR",
                            HttpStatus.BAD_GATEWAY, false,
                            HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "ISE",
                                    HttpHeaders.EMPTY, new byte[0], null)),

                    Arguments.of("HTTP 502 → OVERLOADED (retryable)", "OVERLOADED",
                            HttpStatus.BAD_GATEWAY, true,
                            HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway",
                                    HttpHeaders.EMPTY, new byte[0], null)),

                    Arguments.of("HTTP 503 → OVERLOADED (retryable)", "OVERLOADED",
                            HttpStatus.BAD_GATEWAY, true,
                            HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Unavailable",
                                    HttpHeaders.EMPTY, new byte[0], null)),

                    Arguments.of("HTTP 504 → OVERLOADED (retryable)", "OVERLOADED",
                            HttpStatus.BAD_GATEWAY, true,
                            HttpServerErrorException.create(HttpStatus.GATEWAY_TIMEOUT, "Gateway Timeout",
                                    HttpHeaders.EMPTY, new byte[0], null)),

                    Arguments.of("HTTP 429 без Retry-After → RATE_LIMITED (not retryable)",
                            "RATE_LIMITED", HttpStatus.BAD_GATEWAY, false,
                            HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many",
                                    HttpHeaders.EMPTY, new byte[0], null)),

                    Arguments.of("ResourceAccessException(ConnectException) → CONNECT_REFUSED",
                            "CONNECT_REFUSED", HttpStatus.SERVICE_UNAVAILABLE, true,
                            new ResourceAccessException("I/O error on GET",
                                    new ConnectException("Connection refused"))),

                    Arguments.of("ResourceAccessException(SocketTimeout) → SOCKET_TIMEOUT",
                            "SOCKET_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, true,
                            new ResourceAccessException("I/O error on GET",
                                    new SocketTimeoutException("Read timed out")))
            );
        }
    }

    @Nested
    @DisplayName("Retry-After (RFC 9110 §10.2.3)")
    class RetryAfterHandling {

        @Test
        void rateLimited_withRetryAfterSeconds_isRetryable() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, "30");

            HttpClientErrorException ex = HttpClientErrorException.create(
                    HttpStatus.TOO_MANY_REQUESTS, "Too Many", headers, new byte[0], null);

            RootCauseResolution resolution = strategy.extractCauseDetails(ex);

            assertThat(resolution.retryability()).isEqualTo(Retryability.RETRYABLE);

            boolean hasRetryInfo = resolution.details().stream()
                    .anyMatch(d -> d instanceof RetryInfoDetail);
            assertThat(hasRetryInfo).isTrue();

            Map<String, String> metadata = extractDiagnosticMetadata(resolution);
            assertThat(metadata).containsEntry("retryAfterMs", "30000");
            assertThat(metadata).containsEntry("retryAfterSource", "header");
        }

        @Test
        void rateLimited_withoutRetryAfter_isNotRetryable() {
            HttpClientErrorException ex = HttpClientErrorException.create(
                    HttpStatus.TOO_MANY_REQUESTS, "Too Many",
                    HttpHeaders.EMPTY, new byte[0], null);

            RootCauseResolution resolution = strategy.extractCauseDetails(ex);

            assertThat(resolution.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);

            Map<String, String> metadata = extractDiagnosticMetadata(resolution);
            assertThat(metadata).doesNotContainKey("retryAfterMs");
        }

        @Test
        void overloaded_withRetryAfter_metadataPresent() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, "120");

            HttpServerErrorException ex = HttpServerErrorException.create(
                    HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", headers, new byte[0], null);

            RootCauseResolution resolution = strategy.extractCauseDetails(ex);

            Map<String, String> metadata = extractDiagnosticMetadata(resolution);
            assertThat(metadata).containsEntry("retryAfterMs", "120000");
            assertThat(metadata).containsEntry("retryAfterSource", "header");
        }

        @Test
        void invalidRetryAfter_silentlyIgnored() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, "not-a-number");

            HttpClientErrorException ex = HttpClientErrorException.create(
                    HttpStatus.TOO_MANY_REQUESTS, "Too Many", headers, new byte[0], null);

            RootCauseResolution resolution = strategy.extractCauseDetails(ex);

            assertThat(resolution.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
            Map<String, String> metadata = extractDiagnosticMetadata(resolution);
            assertThat(metadata).doesNotContainKey("retryAfterMs");
        }
    }

    @Nested
    @DisplayName("WebClient support (reactive)")
    class WebClientSupport {

        @Test
        void webClientResponseException_classifiedAsServerError() {
            WebClientResponseException ex = WebClientResponseException.create(
                    500, "Internal Server Error", HttpHeaders.EMPTY, new byte[0], null);

            RootCauseResolution resolution = strategy.extractCauseDetails(ex);

            assertThat(extractReason(resolution)).isEqualTo("SERVER_ERROR");

            Map<String, String> metadata = extractDiagnosticMetadata(resolution);
            assertThat(metadata).containsEntry("restClientType", "WEBCLIENT");
            assertThat(metadata).containsEntry("upstreamStatus", "500");
        }

        @Test
        void webClientRequestException_classifiedAsConnectRefused_viaCauseChain() {
            WebClientRequestException ex = new WebClientRequestException(
                    new ConnectException("Connection refused"),
                    HttpMethod.POST,
                    URI.create("http://api.upstream.svc:8080/v1/payments/abc-123"),
                    HttpHeaders.EMPTY);

            RootCauseResolution resolution = strategy.extractCauseDetails(ex);

            assertThat(extractReason(resolution)).isEqualTo("CONNECT_REFUSED");

            Map<String, String> metadata = extractDiagnosticMetadata(resolution);
            assertThat(metadata).containsEntry("restClientType", "WEBCLIENT");
            assertThat(metadata).containsEntry("httpMethod", "POST");
            assertThat(metadata).containsEntry("upstreamServiceName", "api.upstream.svc");
            assertThat(metadata).containsKey("upstreamUrlPath");
            // PathSanitizer должен замаскировать abc-123 (нет — это не UUID/numeric, остаётся как есть);
            // но числовых UUID-сегментов нет → проверим что path начинается с /v1/
            assertThat(metadata.get("upstreamUrlPath")).startsWith("/v1/payments");
        }

        @Test
        void webClientRequestException_doesNotIncludeCauseMessage_butExposesNested() {
            WebClientRequestException ex = new WebClientRequestException(
                    new ConnectException("Connection refused: secret-host"),
                    HttpMethod.GET,
                    URI.create("http://secret-host:8080/api"),
                    HttpHeaders.EMPTY);

            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            assertThat(metadata).doesNotContainKey("causeMessage");
            assertThat(metadata).containsKey("nestedCauseClass");
            assertThat(metadata.get("nestedCauseClass"))
                    .isEqualTo(ConnectException.class.getName());
        }
    }

    @Nested
    @DisplayName("upstream coordinates extraction")
    class UpstreamCoordinates {

        @Test
        void webClientRequestException_extractsHostPathPort() {
            WebClientRequestException ex = new WebClientRequestException(
                    new ConnectException("refused"),
                    HttpMethod.GET,
                    URI.create("http://payment-service:9090/v1/transactions/12345"),
                    HttpHeaders.EMPTY);

            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            assertThat(metadata).containsEntry("upstreamServiceName", "payment-service");
            assertThat(metadata).containsEntry("upstreamPort", "9090");
            // PathSanitizer заменяет numeric "12345" на {id}.
            assertThat(metadata.get("upstreamUrlPath")).contains("{id}");
        }

        @Test
        void httpStatusCodeException_doesNotExposeRequestUri() {
            // RestTemplate exceptions не дают доступа к request — coordinates отсутствуют.
            HttpClientErrorException ex = HttpClientErrorException.create(
                    HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null);

            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            assertThat(metadata).doesNotContainKey("upstreamServiceName");
            assertThat(metadata).doesNotContainKey("upstreamUrlPath");
        }
    }

    @Nested
    @DisplayName("upstream request id (correlation/trace)")
    class UpstreamRequestId {

        @Test
        void extractsXRequestId_fromResponseHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("x-request-id", "req-abc-123");

            HttpServerErrorException ex = HttpServerErrorException.create(
                    HttpStatus.BAD_GATEWAY, "Bad Gateway", headers, new byte[0], null);

            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            assertThat(metadata).containsEntry("upstreamRequestId", "req-abc-123");
        }

        @Test
        void extractsTraceparent_fromResponseHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("traceparent",
                    "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

            HttpServerErrorException ex = HttpServerErrorException.create(
                    HttpStatus.BAD_GATEWAY, "Bad Gateway", headers, new byte[0], null);

            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            assertThat(metadata).containsKey("upstreamRequestId");
            assertThat(metadata.get("upstreamRequestId")).startsWith("00-");
        }

        @Test
        void prefersXRequestId_overTraceparent() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("x-request-id", "primary-id");
            headers.set("traceparent",
                    "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

            HttpServerErrorException ex = HttpServerErrorException.create(
                    HttpStatus.BAD_GATEWAY, "Bad Gateway", headers, new byte[0], null);

            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            assertThat(metadata).containsEntry("upstreamRequestId", "primary-id");
        }

        @Test
        void sanitizesRequestId_removingControlChars() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("x-request-id", "abc\r\nLOG-INJECT");

            HttpServerErrorException ex = HttpServerErrorException.create(
                    HttpStatus.BAD_GATEWAY, "Bad Gateway", headers, new byte[0], null);

            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            assertThat(metadata).containsKey("upstreamRequestId");
            assertThat(metadata.get("upstreamRequestId"))
                    .doesNotContain("\r")
                    .doesNotContain("\n");
        }
    }

    @Nested
    @DisplayName("CWE-209 — secure metadata")
    class SecurityCompliance {

        @Test
        void responseBody_neverInMetadata() {
            byte[] sensitiveBody = "{\"password\": \"s3cret\"}".getBytes(StandardCharsets.UTF_8);
            HttpServerErrorException ex = HttpServerErrorException.create(
                    HttpStatus.INTERNAL_SERVER_ERROR, "ISE",
                    HttpHeaders.EMPTY, sensitiveBody, null);

            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            assertThat(metadata.values().stream().noneMatch(v -> v.contains("s3cret")))
                    .as("response body must not leak").isTrue();
            assertThat(metadata).containsKey("responseBodyLength");
        }

        @Test
        void publicMessage_usesStandardReasonPhrase_notUpstreamReason() {
            HttpClientErrorException ex = HttpClientErrorException.create(
                    HttpStatus.NOT_FOUND, "Custom Reason From Upstream Containing PII",
                    HttpHeaders.EMPTY, new byte[0], null);

            RootCauseResolution resolution = strategy.extractCauseDetails(ex);

            assertThat(resolution.message())
                    .doesNotContain("Custom Reason From Upstream Containing PII");
            assertThat(resolution.message()).contains("404");
            assertThat(resolution.message()).contains("Not Found");
        }

        @Test
        void resourceAccessException_hidesUrlInCauseMessage() {
            ResourceAccessException ex = new ResourceAccessException(
                    "I/O error on GET request for \"http://secret-host:8080/api/v1/users\": Connection refused",
                    new ConnectException("Connection refused"));

            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            assertThat(metadata).doesNotContainKey("causeMessage");
            // secret-host не должен попадать в metadata через ResourceAccessException.
            assertThat(metadata.values().stream().noneMatch(v -> v.contains("secret-host")))
                    .as("URL from wrapper message must not leak").isTrue();
        }

        @Test
        void httpStatusCodeException_truncatesBodyFromCauseMessage() {
            // Spring форматирует .getMessage() как: "404 Not Found: \"<body>\""
            byte[] bodyWithSecret = "{\"apiKey\": \"sk_live_VERY_SECRET\"}"
                    .getBytes(StandardCharsets.UTF_8);

            HttpClientErrorException ex = HttpClientErrorException.create(
                    HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, bodyWithSecret, null);

            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            // causeMessage может присутствовать, но БЕЗ тела ответа (sk_live_...).
            String causeMessage = metadata.getOrDefault("causeMessage", "");
            assertThat(causeMessage).doesNotContain("sk_live_VERY_SECRET");
            assertThat(causeMessage).doesNotContain("apiKey");
        }

        @Test
        void pathSanitizer_masksUuidSegmentInUpstreamUrl() {
            WebClientRequestException ex = new WebClientRequestException(
                    new ConnectException("refused"),
                    HttpMethod.GET,
                    URI.create("http://api/v1/users/550e8400-e29b-41d4-a716-446655440000/profile"),
                    HttpHeaders.EMPTY);

            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            String path = metadata.get("upstreamUrlPath");
            assertThat(path).isNotNull();
            assertThat(path).doesNotContain("550e8400");
            assertThat(path).contains("{id}");
        }

        @Test
        void pathSanitizer_masksLongHexSegmentInUpstreamUrl() {
            // JWT-like / session-id: длинный hex/alphanumeric → должен маскироваться.
            WebClientRequestException ex = new WebClientRequestException(
                    new ConnectException("refused"),
                    HttpMethod.GET,
                    URI.create("http://api/sessions/a1b2c3d4e5f6a7b8c9d0e1f2"),
                    HttpHeaders.EMPTY);

            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            String path = metadata.get("upstreamUrlPath");
            assertThat(path).isNotNull();
            assertThat(path).doesNotContain("a1b2c3d4e5f6a7b8c9d0e1f2");
            assertThat(path).contains("{id}");
        }
    }

    @Nested
    @DisplayName("diagnosticMetadata structure")
    class MetadataStructure {

        @Test
        void containsExceptionClassAndNetworkErrorType() {
            ConnectException ex = new ConnectException("Connection refused");

            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            assertThat(metadata).containsKey("exceptionClass");
            assertThat(metadata).containsEntry("networkErrorType", "CONNECT_REFUSED");
        }

        @Test
        void httpError_containsUpstreamErrorCategory() {
            HttpServerErrorException ex = HttpServerErrorException.create(
                    HttpStatus.BAD_GATEWAY, "Bad Gateway", HttpHeaders.EMPTY, new byte[0], null);

            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            assertThat(metadata).containsEntry("upstreamStatus", "502");
            assertThat(metadata).containsEntry("upstreamErrorCategory", "SERVER_ERROR");
        }

        @Test
        void clientError_categorizedAsClientError() {
            HttpClientErrorException ex = HttpClientErrorException.create(
                    HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null);

            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            assertThat(metadata).containsEntry("upstreamErrorCategory", "CLIENT_ERROR");
        }

        @Test
        void restClientType_detectedForRestTemplate() {
            HttpClientErrorException ex = HttpClientErrorException.create(
                    HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null);

            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            assertThat(metadata).containsEntry("restClientType", "REST_TEMPLATE");
        }

        @Test
        void restClientType_detectedForWebClient() {
            WebClientResponseException ex = WebClientResponseException.create(
                    503, "Unavailable", HttpHeaders.EMPTY, new byte[0], null);

            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            assertThat(metadata).containsEntry("restClientType", "WEBCLIENT");
        }

        @Test
        void contentType_extractedFromResponseHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_TYPE, "application/json");

            HttpServerErrorException ex = HttpServerErrorException.create(
                    HttpStatus.INTERNAL_SERVER_ERROR, "ISE",
                    headers, "{\"e\":1}".getBytes(StandardCharsets.UTF_8), null);

            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            assertThat(metadata).containsKey("responseContentType");
            assertThat(metadata.get("responseContentType")).contains("application/json");
        }

        @Test
        void responseBodyLength_recorded() {
            byte[] body = "abcdef".getBytes(StandardCharsets.UTF_8);
            HttpServerErrorException ex = HttpServerErrorException.create(
                    HttpStatus.INTERNAL_SERVER_ERROR, "ISE",
                    HttpHeaders.EMPTY, body, null);

            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            assertThat(metadata).containsEntry("responseBodyLength", "6");
        }

        @Test
        void domainIsHttpIntegration() {
            RootCauseResolution resolution = strategy.extractCauseDetails(
                    new ConnectException("refused"));

            ErrorInfoDetail info = resolution.details().stream()
                    .filter(d -> d instanceof ErrorInfoDetail)
                    .map(d -> (ErrorInfoDetail) d)
                    .findFirst()
                    .orElseThrow();
            assertThat(info.getDomain()).isEqualTo("http-integration");
        }
    }

    private static String extractReason(RootCauseResolution resolution) {
        return resolution.details().stream()
                .filter(d -> d instanceof ErrorInfoDetail)
                .map(d -> (ErrorInfoDetail) d)
                .map(ErrorInfoDetail::getReason)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No ErrorInfoDetail found"));
    }

    private static Map<String, String> extractDiagnosticMetadata(RootCauseResolution resolution) {
        return resolution.details().stream()
                .filter(d -> d instanceof ErrorInfoDetail)
                .map(d -> (ErrorInfoDetail) d)
                .map(ErrorInfoDetail::getDiagnosticMetadata)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No diagnosticMetadata found"));
    }
}
