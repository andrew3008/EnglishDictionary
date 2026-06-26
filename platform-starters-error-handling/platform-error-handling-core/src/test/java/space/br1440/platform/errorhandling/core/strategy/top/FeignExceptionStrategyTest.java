package space.br1440.platform.errorhandling.core.strategy.top;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FeignExceptionStrategy")
class FeignExceptionStrategyTest {

    private FeignExceptionStrategy strategy;

    private static ErrorHandlingContext context(String path) {
        return ErrorHandlingContext.of(
                new ErrorHandlingProperties(), path, new StackHintExtractor(false, List.of()));
    }

    private static Request dummyRequest() {
        return Request.create(Request.HttpMethod.GET, "http://upstream/api/resource",
                Collections.emptyMap(), null, new RequestTemplate());
    }

    private static FeignException feignException(int status) {
        return FeignException.errorStatus("GET /api/resource",
                feign.Response.builder()
                        .status(status)
                        .reason("error")
                        .headers(Collections.emptyMap())
                        .request(dummyRequest())
                        .build());
    }

    @BeforeEach
    void setUp() {
        CauseChainResolver resolver = new CauseChainResolver(Collections.emptyList());
        strategy = new FeignExceptionStrategy(resolver);
    }

    @Nested
    @DisplayName("supports")
    class Supports {

        @Test
        void acceptsFeignException() {
            // when / then
            assertThat(strategy.supports(feignException(500))).isTrue();
        }

        @Test
        void acceptsRetryableException() {
            // given
            RetryableException retryable = new RetryableException(
                    503, "Service Unavailable", Request.HttpMethod.GET, (java.util.Date) null, dummyRequest());

            // when / then
            assertThat(strategy.supports(retryable)).isTrue();
        }

        @Test
        void rejectsNonFeignExceptions() {
            // when / then
            assertThat(strategy.supports(new RuntimeException("not feign"))).isFalse();
        }
    }

    @Nested
    @DisplayName("HTTP status mapping")
    class StatusMapping {

        @ParameterizedTest(name = "feign {0} -> http {1}")
        @MethodSource("statusMappingCases")
        void mapsUpstreamStatusCorrectly(int feignStatus, HttpStatus expectedHttpStatus) {
            // when
            ErrorEntry entry = strategy.handle(feignException(feignStatus), context("/api/v1/x"));

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(expectedHttpStatus);
        }

        static Stream<Arguments> statusMappingCases() {
            return Stream.of(
                    Arguments.of(401, HttpStatus.UNAUTHORIZED),
                    Arguments.of(403, HttpStatus.FORBIDDEN),
                    Arguments.of(404, HttpStatus.NOT_FOUND),
                    Arguments.of(410, HttpStatus.BAD_GATEWAY),
                    Arguments.of(429, HttpStatus.TOO_MANY_REQUESTS),
                    Arguments.of(500, HttpStatus.BAD_GATEWAY),
                    Arguments.of(502, HttpStatus.BAD_GATEWAY),
                    Arguments.of(503, HttpStatus.SERVICE_UNAVAILABLE),
                    Arguments.of(504, HttpStatus.GATEWAY_TIMEOUT),
                    Arguments.of(400, HttpStatus.BAD_GATEWAY),
                    Arguments.of(422, HttpStatus.BAD_GATEWAY)
            );
        }
    }

    @Nested
    @DisplayName("CWE-209 — sensitive data exclusion")
    class SecurityCompliance {

        @Test
        void publicMessage_neverContainsUpstreamUrl() {
            // when
            ErrorEntry entry = strategy.handle(feignException(500), context("/"));

            // then
            assertThat(entry.getMessage()).doesNotContain("http://upstream");
            assertThat(entry.getMessage()).doesNotContain("/api/resource");
        }

        @Test
        void publicMessage_neverContainsFeignExceptionMessage() {
            // given
            FeignException ex = feignException(500);

            // when
            ErrorEntry entry = strategy.handle(ex, context("/"));

            // then
            assertThat(entry.getMessage()).doesNotContain("GET /api/resource");
        }
    }

    @Nested
    @DisplayName("clientMetadata")
    class ClientMetadata {

        @Test
        void containsUpstreamStatus() {
            // when
            ErrorEntry entry = strategy.handle(feignException(503), context("/"));

            // then
            ErrorInfoDetail info = extractFirstInfoDetail(entry);
            assertThat(info.getClientMetadata()).containsEntry("upstreamStatus", "503");
        }
    }

    @Nested
    @DisplayName("diagnosticMetadata")
    class DiagnosticMetadata {

        @Test
        void containsExceptionClass() {
            // when
            ErrorEntry entry = strategy.handle(feignException(500), context("/"));

            // then
            ErrorInfoDetail info = extractFirstInfoDetail(entry);
            assertThat(info.getDiagnosticMetadata()).containsKey("exceptionClass");
        }

        @Test
        void domainIsHttpIntegration() {
            // when
            ErrorEntry entry = strategy.handle(feignException(500), context("/"));

            // then
            ErrorInfoDetail info = extractFirstInfoDetail(entry);
            assertThat(info.getDomain()).isEqualTo("http-integration");
        }
    }

    @Nested
    @DisplayName("retryability")
    class Retryability {

        @Test
        void retryableException_hasRetryInfoDetail() {
            // given
            RetryableException retryable = new RetryableException(
                    503, "Service Unavailable", Request.HttpMethod.GET, (java.util.Date) null, dummyRequest());

            // when
            ErrorEntry entry = strategy.handle(retryable, context("/"));

            // then
            boolean hasRetryInfo = entry.getDetails().stream()
                    .anyMatch(d -> d instanceof RetryInfoDetail);
            assertThat(hasRetryInfo).isTrue();
        }

        @Test
        void normalFeignException_noRetryInfoDetail() {
            // when
            ErrorEntry entry = strategy.handle(feignException(500), context("/"));

            // then
            boolean hasRetryInfo = entry.getDetails().stream()
                    .anyMatch(d -> d instanceof RetryInfoDetail);
            assertThat(hasRetryInfo).isFalse();
        }
    }

    @Nested
    @DisplayName("logPolicy")
    class LogPolicyTests {

        @Test
        void serverError_returnsError() {
            // given
            ErrorEntry entry = strategy.handle(feignException(503), context("/"));

            // when / then
            assertThat(strategy.logPolicy(feignException(503), entry)).isEqualTo(LogPolicy.ERROR);
        }

        @Test
        void clientError_returnsWarn() {
            // given
            ErrorEntry entry = strategy.handle(feignException(404), context("/"));

            // when / then
            assertThat(strategy.logPolicy(feignException(404), entry)).isEqualTo(LogPolicy.WARN);
        }
    }

    @Nested
    @DisplayName("requestPath")
    class RequestPath {

        @Test
        void preservesRequestPath() {
            // when
            ErrorEntry entry = strategy.handle(feignException(500), context("/api/v2/orders"));

            // then
            assertThat(entry.getRequestURI()).isEqualTo("/api/v2/orders");
        }
    }

    private static ErrorInfoDetail extractFirstInfoDetail(ErrorEntry entry) {
        return entry.getDetails().stream()
                .filter(d -> d instanceof ErrorInfoDetail)
                .map(d -> (ErrorInfoDetail) d)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No ErrorInfoDetail found"));
    }
}
