package space.br1440.platform.errorhandling.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebFluxExceptionUtils")
class WebFluxExceptionUtilsTest {

    @Nested
    @DisplayName("StatusClassification")
    class StatusClassification {

        @Test
        void responseException_isWebClientException() {
            // Given
            WebClientResponseException ex = WebClientResponseException.create(
                    500, "Internal Server Error", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);

            // When & Then
            assertThat(WebFluxExceptionUtils.isWebClientException(ex)).isTrue();
            assertThat(WebFluxExceptionUtils.isResponseException(ex)).isTrue();
            assertThat(WebFluxExceptionUtils.isRequestException(ex)).isFalse();
        }

        @Test
        void requestException_isWebClientException() {
            // Given
            WebClientRequestException ex = new WebClientRequestException(
                    new ConnectException("Connection refused"),
                    HttpMethod.GET, URI.create("http://svc/api"), HttpHeaders.EMPTY);

            // When & Then
            assertThat(WebFluxExceptionUtils.isWebClientException(ex)).isTrue();
            assertThat(WebFluxExceptionUtils.isResponseException(ex)).isFalse();
            assertThat(WebFluxExceptionUtils.isRequestException(ex)).isTrue();
        }

        @Test
        void runtimeException_isNotWebClientException() {
            // Given
            RuntimeException ex = new RuntimeException("not a webclient error");

            // When & Then
            assertThat(WebFluxExceptionUtils.isWebClientException(ex)).isFalse();
            assertThat(WebFluxExceptionUtils.isResponseException(ex)).isFalse();
            assertThat(WebFluxExceptionUtils.isRequestException(ex)).isFalse();
        }
    }

    @Nested
    @DisplayName("ExtractStatusCode")
    class ExtractStatusCode {

        @ParameterizedTest
        @CsvSource({"401", "403", "404", "410", "429", "500", "502", "503", "504"})
        void extracts_known_status_codes(int status) {
            // Given
            WebClientResponseException ex = WebClientResponseException.create(
                    status, "status", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);

            // When
            int result = WebFluxExceptionUtils.extractStatusCode(ex);

            // Then
            assertThat(result).isEqualTo(status);
        }

        @Test
        void returns_minus1_for_non_response_exception() {
            // Given
            RuntimeException ex = new RuntimeException("not a response");

            // When
            int result = WebFluxExceptionUtils.extractStatusCode(ex);

            // Then
            assertThat(result).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("MapToHttpStatus")
    class MapToHttpStatusTests {

        @ParameterizedTest
        @CsvSource({
                "401, UNAUTHORIZED",
                "403, FORBIDDEN",
                "404, NOT_FOUND",
                "410, GONE",
                "429, TOO_MANY_REQUESTS",
                "503, SERVICE_UNAVAILABLE",
                "504, GATEWAY_TIMEOUT"
        })
        void maps_known_response_status_codes(int upstream, String expected) {
            // When
            HttpStatus result = WebFluxExceptionUtils.mapToHttpStatus(true, false, upstream);

            // Then
            assertThat(result).isEqualTo(HttpStatus.valueOf(expected));
        }

        @ParameterizedTest
        @CsvSource({"400", "405", "409", "422", "500", "501", "502"})
        void maps_other_4xx_5xx_to_badGateway(int upstream) {
            // When
            HttpStatus result = WebFluxExceptionUtils.mapToHttpStatus(true, false, upstream);

            // Then
            assertThat(result).isEqualTo(HttpStatus.BAD_GATEWAY);
        }

        @Test
        void request_exception_maps_to_serviceUnavailable() {
            // When
            HttpStatus result = WebFluxExceptionUtils.mapToHttpStatus(false, true, -1);

            // Then
            assertThat(result).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        void generic_exception_maps_to_serviceUnavailable() {
            // When
            HttpStatus result = WebFluxExceptionUtils.mapToHttpStatus(false, false, -1);

            // Then
            assertThat(result).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Nested
    @DisplayName("ResolveErrorCategory")
    class ResolveErrorCategoryTests {

        @Test
        void request_returns_network() {
            assertThat(WebFluxExceptionUtils.resolveErrorCategory(false, true)).isEqualTo("network");
        }

        @Test
        void response_returns_upstream_response() {
            assertThat(WebFluxExceptionUtils.resolveErrorCategory(true, false)).isEqualTo("upstream_response");
        }

        @Test
        void generic_returns_unknown() {
            assertThat(WebFluxExceptionUtils.resolveErrorCategory(false, false)).isEqualTo("unknown");
        }
    }

    @Nested
    @DisplayName("DetermineErrorCode")
    class DetermineErrorCodeTests {

        @ParameterizedTest
        @CsvSource({
                "UNAUTHORIZED, UNAUTHENTICATED",
                "FORBIDDEN, PERMISSION_DENIED",
                "NOT_FOUND, NOT_FOUND",
                "TOO_MANY_REQUESTS, RESOURCE_EXHAUSTED",
                "BAD_GATEWAY, BAD_GATEWAY",
                "SERVICE_UNAVAILABLE, UNAVAILABLE",
                "GATEWAY_TIMEOUT, DEADLINE_EXCEEDED"
        })
        void determines_correct_error_code(String httpStatus, String expectedCode) {
            // When
            String result = WebFluxExceptionUtils.determineErrorCode(HttpStatus.valueOf(httpStatus));

            // Then
            assertThat(result).isEqualTo(expectedCode);
        }
    }

    @Nested
    @DisplayName("ExtractRetryAfterDelay")
    class ExtractRetryAfterDelayTests {

        @Test
        void extracts_delay_seconds() {
            // Given
            HttpHeaders headers = new HttpHeaders();
            headers.set("Retry-After", "120");
            WebClientResponseException ex = WebClientResponseException.create(
                    503, "Service Unavailable", headers, new byte[0], StandardCharsets.UTF_8);

            // When
            Duration result = WebFluxExceptionUtils.extractRetryAfterDelay(ex);

            // Then
            assertThat(result).isEqualTo(Duration.ofSeconds(120));
        }

        @Test
        void returns_null_when_header_absent() {
            // Given
            WebClientResponseException ex = WebClientResponseException.create(
                    503, "Service Unavailable", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);

            // When
            Duration result = WebFluxExceptionUtils.extractRetryAfterDelay(ex);

            // Then
            assertThat(result).isNull();
        }

        @Test
        void returns_null_for_invalid_header() {
            // Given
            HttpHeaders headers = new HttpHeaders();
            headers.set("Retry-After", "abc");
            WebClientResponseException ex = WebClientResponseException.create(
                    503, "Service Unavailable", headers, new byte[0], StandardCharsets.UTF_8);

            // When
            Duration result = WebFluxExceptionUtils.extractRetryAfterDelay(ex);

            // Then
            assertThat(result).isNull();
        }

        @Test
        void returns_null_for_request_exception() {
            // Given
            WebClientRequestException ex = new WebClientRequestException(
                    new IOException("timeout"), HttpMethod.GET,
                    URI.create("http://svc/api"), HttpHeaders.EMPTY);

            // When
            Duration result = WebFluxExceptionUtils.extractRetryAfterDelay(ex);

            // Then
            assertThat(result).isNull();
        }
    }

}
