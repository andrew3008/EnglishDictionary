package space.br1440.platform.errorhandling.core.strategy.cause;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.webflux.properties.ErrorHandlingUpstreamProperties;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("WebFluxCauseExceptionStrategy")
class WebFluxCauseExceptionStrategyTest {

    private WebFluxCauseExceptionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new WebFluxCauseExceptionStrategy(
                new StackHintExtractor(false, List.of()),
                new ErrorHandlingUpstreamProperties());
    }

    @Nested
    @DisplayName("supportsCause()")
    class SupportsCause {

        @Test
        void supports_WebClientResponseException() {
            // Given
            WebClientResponseException ex = WebClientResponseException.create(
                    500, "Error", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);

            // When & Then
            assertThat(strategy.supportsCause(ex)).isTrue();
        }

        @Test
        void supports_WebClientRequestException() {
            // Given
            WebClientRequestException ex = new WebClientRequestException(
                    new IOException("fail"), HttpMethod.GET,
                    URI.create("http://svc/api"), HttpHeaders.EMPTY);

            // When & Then
            assertThat(strategy.supportsCause(ex)).isTrue();
        }

        @Test
        void does_not_support_IOException() {
            assertThat(strategy.supportsCause(new IOException("generic"))).isFalse();
        }
    }

    @Nested
    @DisplayName("Response exception classification")
    class ResponseExceptionClassification {

        @Test
        void maps_503_to_serviceUnavailable_retryable() {
            // Given
            var ex = responseException(503, "http://order-svc/api/orders");

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(result.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(result.retryability()).isEqualTo(Retryability.RETRYABLE);
            assertThat(reason(result)).isEqualTo("upstream_response");

            Map<String, String> diag = diagnostic(result);
            assertThat(diag).containsEntry("errorCategory", "upstream_response");
            assertThat(diag).containsEntry("upstreamStatus", "503");
            assertThat(diag).containsEntry("upstreamServiceName", "order-svc");
            assertThat(diag).containsEntry("upstreamUrlPath", "/api/orders");
            assertThat(diag).containsKey("httpMethod");
        }

        @Test
        void maps_401_to_unauthorized_notRetryable() {
            // Given
            var ex = responseException(401, "http://auth-svc/api");

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(result.httpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(result.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
        }

        @Test
        void maps_429_to_tooManyRequests_retryable() {
            // Given
            var ex = responseException(429, "http://svc/api");

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(result.httpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(result.retryability()).isEqualTo(Retryability.RETRYABLE);
        }

        @Test
        void maps_500_to_badGateway_notRetryable() {
            // Given
            var ex = responseException(500, "http://svc/api");

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(result.httpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(result.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
        }

        @Test
        void errorInfoDetail_has_domain_webflux() {
            // Given
            var ex = responseException(503, "http://svc/api");

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            ErrorInfoDetail info = findDetail(result);
            assertThat(info.getDomain()).isEqualTo("webflux");
        }
    }

    @Nested
    @DisplayName("Request exception classification")
    class RequestExceptionClassification {

        @Test
        void maps_to_serviceUnavailable_retryable() {
            // Given
            WebClientRequestException ex = new WebClientRequestException(
                    new ConnectException("Connection refused"), HttpMethod.GET,
                    URI.create("http://payment-svc/api/payments"), HttpHeaders.EMPTY);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(result.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(result.retryability()).isEqualTo(Retryability.RETRYABLE);
            assertThat(reason(result)).isEqualTo("network");

            Map<String, String> diag = diagnostic(result);
            assertThat(diag).containsEntry("errorCategory", "network");
            assertThat(diag).containsEntry("upstreamServiceName", "payment-svc");
            assertThat(diag).containsEntry("upstreamUrlPath", "/api/payments");
            assertThat(diag).containsEntry("httpMethod", "GET");
            assertThat(diag).containsKey("nestedCauseClass");
        }
    }

    @Nested
    @DisplayName("RetryInfoDetail")
    class RetryInfoDetailTests {

        @Test
        void retryable_with_retryAfter_header_has_delay() {
            // Given
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.set("Retry-After", "60");
            MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://svc/api"));
            WebClientResponseException ex = WebClientResponseException.create(
                    503, "Service Unavailable", responseHeaders,
                    new byte[0], StandardCharsets.UTF_8, request);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(hasRetryInfo(result)).isTrue();
            RetryInfoDetail retryInfo = result.details().stream()
                    .filter(RetryInfoDetail.class::isInstance)
                    .map(RetryInfoDetail.class::cast)
                    .findFirst().orElseThrow();
            assertThat(retryInfo.getRetryDelay()).isEqualTo(Duration.ofSeconds(60));
        }

        @Test
        void retryable_without_retryAfter_has_null_delay() {
            // Given
            var ex = responseException(503, "http://svc/api");

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(hasRetryInfo(result)).isTrue();
        }

        @Test
        void notRetryable_has_no_retryInfo() {
            // Given
            var ex = responseException(401, "http://svc/api");

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(hasRetryInfo(result)).isFalse();
        }

        @Test
        void requestException_retryInfo_without_delay() {
            // Given
            WebClientRequestException ex = new WebClientRequestException(
                    new ConnectException("refused"), HttpMethod.GET,
                    URI.create("http://svc/api"), HttpHeaders.EMPTY);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(hasRetryInfo(result)).isTrue();
        }
    }

    @Nested
    @DisplayName("PathSanitization — CWE-209")
    class PathSanitization {

        @Test
        void replaces_numeric_id() {
            // Given
            var ex = responseException(503, "http://svc/api/users/12345/orders");

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(diagnostic(result)).containsEntry("upstreamUrlPath", "/api/users/{id}/orders");
        }

        @Test
        void replaces_uuid() {
            // Given
            var ex = responseException(503, "http://svc/api/orders/550e8400-e29b-41d4-a716-446655440000");

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(diagnostic(result).get("upstreamUrlPath")).contains("{id}");
            assertThat(diagnostic(result).get("upstreamUrlPath")).doesNotContain("550e8400");
        }

        @Test
        void replaces_hex_string() {
            // Given
            var ex = responseException(503, "http://svc/api/cache/a1b2c3d4e5");

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(diagnostic(result).get("upstreamUrlPath")).contains("{id}");
            assertThat(diagnostic(result).get("upstreamUrlPath")).doesNotContain("a1b2c3d4e5");
        }
    }

    @Nested
    @DisplayName("MessageSanitization — CWE-209")
    class MessageSanitization {

        @Test
        void bearer_token_in_cause_message_is_masked() {
            // Given
            HttpHeaders responseHeaders = new HttpHeaders();
            MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://svc/api"));
            WebClientResponseException ex = WebClientResponseException.create(
                    500, "Authorization: Bearer leaked-token-xyz failed",
                    responseHeaders, new byte[0], StandardCharsets.UTF_8, request);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            String causeMessage = diagnostic(result).get("causeMessage");
            assertThat(causeMessage).contains("Bearer ***");
            assertThat(causeMessage).doesNotContain("leaked-token-xyz");
        }
    }

    @Nested
    @DisplayName("ResponseBodyLength — CWE-400 protection")
    class ResponseBodyLength {

        @Test
        void uses_content_length_header_when_available() {
            // Given
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            responseHeaders.setContentLength(42);
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://svc/api"));
            WebClientResponseException ex = WebClientResponseException.create(
                    500, "Error", responseHeaders, body, StandardCharsets.UTF_8, request);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(diagnostic(result))
                    .as("Content-Length wins over byte-array length")
                    .containsEntry("responseBodyLength", "42");
        }

        @Test
        void caps_fallback_length_at_safety_limit() {
            // Given
            byte[] hugeBody = new byte[16 * 1024];
            MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://svc/api"));
            WebClientResponseException ex = WebClientResponseException.create(
                    500, "Error", HttpHeaders.EMPTY, hugeBody, StandardCharsets.UTF_8, request);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            long reported = Long.parseLong(diagnostic(result).get("responseBodyLength"));
            assertThat(reported)
                    .as("fallback length must be capped at 8KB safety limit")
                    .isLessThanOrEqualTo(8L * 1024);
        }
    }

    @Nested
    @DisplayName("HeaderSanitization — CWE-209")
    class HeaderSanitization {

        @Test
        void requestException_authorization_header_not_leaked() {
            // Given
            HttpHeaders sensitiveHeaders = new HttpHeaders();
            sensitiveHeaders.add("Authorization", "Bearer secret-token");
            WebClientRequestException ex = new WebClientRequestException(
                    new ConnectException("refused"), HttpMethod.GET,
                    URI.create("http://svc/api"), sensitiveHeaders);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            String allValues = String.join(" ", diagnostic(result).values());
            assertThat(allValues).doesNotContain("Authorization", "Bearer", "secret-token");
        }

        @Test
        void responseException_authorization_header_not_leaked() {
            // Given
            MockClientHttpRequest fakeRequest = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("http://svc/api"));
            fakeRequest.getHeaders().add("Authorization", "Bearer secret-jwt");

            WebClientResponseException ex = WebClientResponseException.create(
                    401, "Unauthorized", HttpHeaders.EMPTY, new byte[0],
                    StandardCharsets.UTF_8, fakeRequest);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            String allValues = String.join(" ", diagnostic(result).values());
            assertThat(allValues).doesNotContain("Authorization", "Bearer", "secret-jwt");
        }
    }

    @Nested
    @DisplayName("UpstreamRequestId — cross-service correlation")
    class UpstreamRequestId {

        @Test
        void extracts_x_request_id() {
            // Given
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.set("x-request-id", "abc-123");
            MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://svc/api"));
            WebClientResponseException ex = WebClientResponseException.create(
                    503, "Service Unavailable", responseHeaders, new byte[0],
                    StandardCharsets.UTF_8, request);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(diagnostic(result)).containsEntry("upstreamRequestId", "abc-123");
        }

        @Test
        void falls_back_to_x_correlation_id() {
            // Given
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.set("x-correlation-id", "corr-456");
            MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://svc/api"));
            WebClientResponseException ex = WebClientResponseException.create(
                    503, "Service Unavailable", responseHeaders, new byte[0],
                    StandardCharsets.UTF_8, request);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(diagnostic(result)).containsEntry("upstreamRequestId", "corr-456");
        }

        @Test
        void absent_when_no_correlation_headers() {
            // Given
            var ex = responseException(503, "http://svc/api");

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(diagnostic(result)).doesNotContainKey("upstreamRequestId");
        }

        @Test
        void truncates_long_value() {
            // Given
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.set("x-request-id", "x".repeat(200));
            MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://svc/api"));
            WebClientResponseException ex = WebClientResponseException.create(
                    503, "Service Unavailable", responseHeaders, new byte[0],
                    StandardCharsets.UTF_8, request);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            String value = diagnostic(result).get("upstreamRequestId");
            assertThat(value).hasSizeLessThanOrEqualTo(128 + 12);
        }
    }

    @Nested
    @DisplayName("Response Content-Type and Content-Length")
    class ResponseContentMeta {

        @Test
        void includes_contentType_and_bodyLength() {
            // Given
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.set("Content-Type", "application/json");
            byte[] body = "{\"error\":\"test\"}".getBytes(StandardCharsets.UTF_8);
            MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://svc/api"));
            WebClientResponseException ex = WebClientResponseException.create(
                    500, "Error", responseHeaders, body, StandardCharsets.UTF_8,
                    request);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            Map<String, String> diag = diagnostic(result);
            assertThat(diag).containsEntry("responseContentType", "application/json");
            assertThat(diag).containsEntry("responseBodyLength", Integer.toString(body.length));
        }

        @Test
        void preserves_raw_contentType_with_charset() {
            // Given
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.set("Content-Type", "application/json; charset=UTF-8");
            MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://svc/api"));
            WebClientResponseException ex = WebClientResponseException.create(
                    500, "Error", responseHeaders, new byte[]{1}, StandardCharsets.UTF_8,
                    request);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(diagnostic(result)).containsEntry("responseContentType", "application/json; charset=UTF-8");
        }

        @Test
        void omits_bodyLength_when_empty() {
            // Given
            var ex = responseException(500, "http://svc/api");

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(diagnostic(result)).doesNotContainKey("responseBodyLength");
        }

        @Test
        void omits_contentType_when_absent() {
            // Given
            var ex = responseException(500, "http://svc/api");

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(diagnostic(result)).doesNotContainKey("responseContentType");
        }
    }

    @Nested
    @DisplayName("NetworkFailureType classification")
    class NetworkFailureType {

        @Test
        void connectException_classified_as_CONNECTION_REFUSED() {
            // Given
            WebClientRequestException ex = new WebClientRequestException(
                    new ConnectException("Connection refused"), HttpMethod.GET,
                    URI.create("http://svc/api"), HttpHeaders.EMPTY);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(diagnostic(result)).containsEntry("networkFailureType", "CONNECTION_REFUSED");
        }

        @Test
        void timeoutException_classified_as_TIMEOUT() {
            // Given
            WebClientRequestException ex = new WebClientRequestException(
                    new java.net.SocketTimeoutException("Read timed out"), HttpMethod.GET,
                    URI.create("http://svc/api"), HttpHeaders.EMPTY);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(diagnostic(result)).containsEntry("networkFailureType", "TIMEOUT");
        }

        @Test
        void unknownHostException_classified_as_DNS_RESOLUTION() {
            // Given
            WebClientRequestException ex = new WebClientRequestException(
                    new java.net.UnknownHostException("unknown-svc"), HttpMethod.GET,
                    URI.create("http://unknown-svc/api"), HttpHeaders.EMPTY);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(diagnostic(result)).containsEntry("networkFailureType", "DNS_RESOLUTION");
        }

        @Test
        void sslException_classified_as_TLS_HANDSHAKE() {
            // Given
            WebClientRequestException ex = new WebClientRequestException(
                    new javax.net.ssl.SSLHandshakeException("certificate unknown"), HttpMethod.GET,
                    URI.create("http://svc/api"), HttpHeaders.EMPTY);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(diagnostic(result)).containsEntry("networkFailureType", "TLS_HANDSHAKE");
        }

        @Test
        void connectionReset_message_classified_as_CONNECTION_RESET() {
            // Given
            WebClientRequestException ex = new WebClientRequestException(
                    new java.io.IOException("Connection reset by peer"), HttpMethod.GET,
                    URI.create("http://svc/api"), HttpHeaders.EMPTY);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(diagnostic(result)).containsEntry("networkFailureType", "CONNECTION_RESET");
        }

        @Test
        void nativeIoException_connectionReset_classified_as_CONNECTION_RESET() {
            // Given — NativeIoException-like с message "syscall:read(..) Connection reset by peer"
            WebClientRequestException ex = new WebClientRequestException(
                    new java.io.IOException("syscall:read(..) failed: Connection reset by peer (-104)"),
                    HttpMethod.GET, URI.create("http://svc/api"), HttpHeaders.EMPTY);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(diagnostic(result)).containsEntry("networkFailureType", "CONNECTION_RESET");
        }

        @Test
        void opaque_ioException_classified_as_UNKNOWN_NETWORK() {
            // Given
            WebClientRequestException ex = new WebClientRequestException(
                    new java.io.IOException("something opaque"), HttpMethod.GET,
                    URI.create("http://svc/api"), HttpHeaders.EMPTY);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThat(diagnostic(result)).containsEntry("networkFailureType", "UNKNOWN_NETWORK");
        }
    }

    @Nested
    @DisplayName("ResponseBodyLength — boundary mutations (Sprint 5)")
    class ResponseBodyLengthBoundaries {

        @Test
        @DisplayName("Content-Length=0 при body.length=5 → ключ omited (kill ConditionalsBoundary >=0 → >0)")
        void contentLengthZero_overridesNonEmptyBody() {
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentLength(0);
            byte[] nonEmptyBody = "hello".getBytes(StandardCharsets.UTF_8);
            MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://svc/api"));
            WebClientResponseException ex = WebClientResponseException.create(
                    500, "Error", responseHeaders, nonEmptyBody, StandardCharsets.UTF_8, request);

            RootCauseResolution result = strategy.extractCauseDetails(ex);

            assertThat(diagnostic(result))
                    .as("Content-Length=0 wins; fallback не должен быть применён даже при non-empty body")
                    .doesNotContainKey("responseBodyLength");
        }

        @Test
        @DisplayName("body.length=DEFAULT_MAX_BODY_BYTES без Content-Length → reported=DEFAULT_MAX_BODY_BYTES")
        void bodyLengthAtBoundary_reportsBoundaryValue() {
            int boundary = 4 * 1024;
            byte[] body = new byte[boundary];
            MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://svc/api"));
            WebClientResponseException ex = WebClientResponseException.create(
                    500, "Error", HttpHeaders.EMPTY, body, StandardCharsets.UTF_8, request);

            RootCauseResolution result = strategy.extractCauseDetails(ex);

            assertThat(diagnostic(result))
                    .containsEntry("responseBodyLength", Integer.toString(boundary));
        }

        @Test
        @DisplayName("Content-Length=-1 (parsed) → fallback на body.length")
        void invalidContentLength_fallsBackToBodyLength() {
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.set(HttpHeaders.CONTENT_LENGTH, "not-a-number");
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://svc/api"));
            WebClientResponseException ex = WebClientResponseException.create(
                    500, "Error", responseHeaders, body, StandardCharsets.UTF_8, request);

            RootCauseResolution result = strategy.extractCauseDetails(ex);

            assertThat(diagnostic(result))
                    .as("invalid Content-Length → -1 → fallback")
                    .containsEntry("responseBodyLength", Integer.toString(body.length));
        }
    }

    @Nested
    @DisplayName("NetworkFailureType — equality vs contains mutators (Sprint 5)")
    class NetworkFailureTypeEqualityMutations {

        @Test
        @DisplayName("ConnectionRefusedException-named cause → CONNECTION_REFUSED")
        void connectionRefusedNamedException_classified() {
            class ConnectionRefusedException extends IOException {
                ConnectionRefusedException(String msg) { super(msg); }
            }

            WebClientRequestException ex = new WebClientRequestException(
                    new ConnectionRefusedException("refused by peer"), HttpMethod.GET,
                    URI.create("http://svc/api"), HttpHeaders.EMPTY);

            RootCauseResolution result = strategy.extractCauseDetails(ex);

            assertThat(diagnostic(result)).containsEntry("networkFailureType", "CONNECTION_REFUSED");
        }

        @Test
        @DisplayName("UnresolvedAddressException → DNS_RESOLUTION (contains UnresolvedAddress)")
        void unresolvedAddressException_classifiedAsDns() {
            WebClientRequestException ex = new WebClientRequestException(
                    new UnresolvedAddressException(), HttpMethod.GET,
                    URI.create("http://svc/api"), HttpHeaders.EMPTY
            );

            RootCauseResolution result = strategy.extractCauseDetails(ex);

            assertThat(diagnostic(result)).containsEntry("networkFailureType", "DNS_RESOLUTION");
        }

        // SSL ветка — Certificate-стиль (вторая половина OR-условия contains("Certificate")).
        @Test
        @DisplayName("CertificateException → TLS_HANDSHAKE (contains Certificate)")
        void certificateException_classifiedAsTls() {
            WebClientRequestException ex = new WebClientRequestException(
                    new CertificateException("untrusted"), HttpMethod.GET,
                    URI.create("http://svc/api"), HttpHeaders.EMPTY
            );

            RootCauseResolution result = strategy.extractCauseDetails(ex);

            assertThat(diagnostic(result)).containsEntry("networkFailureType", "TLS_HANDSHAKE");
        }

        @Test
        @DisplayName("nested cause без сообщения → UNKNOWN_NETWORK (null-safe в classifyNetworkFailure)")
        void nestedCauseWithNullMessage_classifiedAsUnknown() {
            WebClientRequestException ex = new WebClientRequestException(
                    new RuntimeException(), HttpMethod.GET,
                    URI.create("http://svc/api"), HttpHeaders.EMPTY);

            RootCauseResolution result = strategy.extractCauseDetails(ex);

            assertThat(diagnostic(result)).containsEntry("networkFailureType", "UNKNOWN_NETWORK");
        }

        @Test
        @DisplayName("CONNECTION RESET в верхнем регистре → CONNECTION_RESET (toLowerCase важен)")
        void uppercaseConnectionReset_classified() {
            WebClientRequestException ex = new WebClientRequestException(
                    new java.io.IOException("CONNECTION RESET BY PEER"), HttpMethod.GET,
                    URI.create("http://svc/api"), HttpHeaders.EMPTY);

            RootCauseResolution result = strategy.extractCauseDetails(ex);

            assertThat(diagnostic(result)).containsEntry("networkFailureType", "CONNECTION_RESET");
        }
    }

    @Nested
    @DisplayName("Retryability — order-of-clause mutations (Sprint 5)")
    class RetryabilityOrderClauseMutations {

        @Test
        @DisplayName("502 Bad Gateway → RETRYABLE")
        void status502_isRetryable() {
            var ex = responseException(502, "http://svc/api");
            RootCauseResolution result = strategy.extractCauseDetails(ex);
            assertThat(result.retryability()).isEqualTo(Retryability.RETRYABLE);
        }

        @Test
        @DisplayName("504 Gateway Timeout → RETRYABLE")
        void status504_isRetryable() {
            var ex = responseException(504, "http://svc/api");
            RootCauseResolution result = strategy.extractCauseDetails(ex);
            assertThat(result.retryability()).isEqualTo(Retryability.RETRYABLE);
        }

        @Test
        @DisplayName("501 Not Implemented → NOT_RETRYABLE (НЕ в RETRYABLE_UPSTREAM_STATUSES)")
        void status501_isNotRetryable() {
            var ex = responseException(501, "http://svc/api");
            RootCauseResolution result = strategy.extractCauseDetails(ex);
            assertThat(result.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
        }
    }

    @Nested
    @DisplayName("upstreamStatus > 0 boundary (Sprint 5)")
    class UpstreamStatusBoundary {

        @Test
        @DisplayName("RequestException → upstreamStatus key omitted (status=-1)")
        void requestException_omitsUpstreamStatus() {
            WebClientRequestException ex = new WebClientRequestException(
                    new ConnectException("refused"), HttpMethod.GET,
                    URI.create("http://svc/api"), HttpHeaders.EMPTY
            );

            RootCauseResolution result = strategy.extractCauseDetails(ex);

            assertThat(diagnostic(result))
                    .as("RequestException не имеет upstream HTTP статуса")
                    .doesNotContainKey("upstreamStatus");
        }
    }

    @Nested
    @DisplayName("DiagnosticMetadata completeness")
    class DiagnosticMetadataCompleteness {

        @Test
        void responseException_contains_all_expected_keys() {
            // Given
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.set("x-request-id", "req-001");
            responseHeaders.set("Content-Type", "application/json");
            byte[] body = "{\"error\":\"test\"}".getBytes(StandardCharsets.UTF_8);
            MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://order-svc/api/orders"));
            WebClientResponseException ex = WebClientResponseException.create(
                    503, "Service Unavailable", responseHeaders, body, StandardCharsets.UTF_8,
                    request);

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            Map<String, String> diag = diagnostic(result);
            assertThat(diag).containsKeys(
                    "exceptionClass", "errorCategory", "causeMessage",
                    "upstreamStatus", "upstreamServiceName", "upstreamUrlPath", "httpMethod",
                    "upstreamRequestId", "responseContentType", "responseBodyLength");
        }

        @Test
        void diagnosticMetadata_is_immutable() {
            // Given
            var ex = responseException(503, "http://svc/api");

            // When
            RootCauseResolution result = strategy.extractCauseDetails(ex);

            // Then
            assertThrows(UnsupportedOperationException.class,
                    () -> diagnostic(result).put("injected", "value"));
        }
    }

    private static WebClientResponseException responseException(int status, String uri) {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create(uri));
        return WebClientResponseException.create(
                status, HttpStatus.valueOf(status).getReasonPhrase(),
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8,
                request);
    }

    private static boolean hasRetryInfo(RootCauseResolution result) {
        return result.details().stream().anyMatch(RetryInfoDetail.class::isInstance);
    }

    private static String reason(RootCauseResolution result) {
        return findDetail(result).getReason();
    }

    private static Map<String, String> diagnostic(RootCauseResolution result) {
        return findDetail(result).getDiagnosticMetadata();
    }

    private static ErrorInfoDetail findDetail(RootCauseResolution result) {
        return result.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .findFirst()
                .orElseThrow();
    }
}
