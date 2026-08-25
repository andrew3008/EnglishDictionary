package space.br1440.platform.errorhandling.core.strategy.top;

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
import org.springframework.web.server.ResponseStatusException;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.order.TopExceptionStrategyOrder;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebFluxExceptionStrategy")
class WebFluxExceptionStrategyTest {

    private WebFluxExceptionStrategy strategy;
    private ErrorHandlingContext context;

    @BeforeEach
    void setUp() {
        strategy = new WebFluxExceptionStrategy(new CauseChainResolver(Collections.emptyList()));
        context = ErrorHandlingContext.of(
                new ErrorHandlingProperties(), "/api/test", new StackHintExtractor(false, List.of())
        );
    }

    @Nested
    @DisplayName("supports()")
    class Supports {

        @Test
        void supports_WebClientResponseException() {
            // given
            WebClientResponseException ex = WebClientResponseException.create(
                    500, "Error", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);

            // when
            boolean supported = strategy.supports(ex);

            // then
            assertThat(supported).isTrue();
        }

        @Test
        void supports_WebClientRequestException() {
            // given
            WebClientRequestException ex = new WebClientRequestException(
                    new IOException("fail"), HttpMethod.GET,
                    URI.create("http://svc/api"), HttpHeaders.EMPTY);

            // when
            boolean supported = strategy.supports(ex);

            // then
            assertThat(supported).isTrue();
        }

        @Test
        void does_not_support_RuntimeException() {
            // given
            RuntimeException ex = new RuntimeException("generic");

            // when
            boolean supported = strategy.supports(ex);

            // then
            assertThat(supported).isFalse();
        }

        @Test
        void does_not_support_ResponseStatusException() {
            // given
            ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND);

            // when
            boolean supported = strategy.supports(ex);

            // then
            assertThat(supported).isFalse();
        }
    }

    @Nested
    @DisplayName("handle() — response exceptions")
    class HandleResponseException {

        @Test
        void maps_503_to_serviceUnavailable() {
            // given
            var ex = responseException(503, "http://order-svc/api/orders");

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(entry.getErrorCode()).isEqualTo("UNAVAILABLE");

            ErrorInfoDetail info = findDetail(entry.getDetails(), ErrorInfoDetail.class);
            assertThat(info.getClientMetadata()).containsEntry("clientType", "WebClient");
            assertThat(info.getClientMetadata()).containsEntry("errorCategory", "upstream_response");
            assertThat(info.getClientMetadata())
                    .as("upstreamStatus is a plain HTTP-response fact, safe to expose unconditionally")
                    .containsEntry("upstreamStatus", "503");
            assertThat(info.getDiagnosticMetadata()).containsKey("exceptionClass");
            assertThat(info.getDiagnosticMetadata()).containsEntry("upstreamStatus", "503");
            assertThat(info.getDiagnosticMetadata()).containsEntry("upstreamHost", "order-svc");
            assertThat(info.getDiagnosticMetadata()).containsEntry("upstreamUrlPath", "/api/orders");
        }

        @Test
        void maps_401_to_unauthorized() {
            // given
            var ex = responseException(401, "http://auth-svc/api");

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void maps_500_to_badGateway() {
            // given
            var ex = responseException(500, "http://svc/api");

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        }
    }

    @Nested
    @DisplayName("handle() — request exceptions")
    class HandleRequestException {

        @Test
        void maps_to_serviceUnavailable() {
            // given
            WebClientRequestException ex = new WebClientRequestException(
                    new ConnectException("Connection refused"), HttpMethod.GET,
                    URI.create("http://payment-svc/api"), HttpHeaders.EMPTY);

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

            ErrorInfoDetail info = findDetail(entry.getDetails(), ErrorInfoDetail.class);
            assertThat(info.getClientMetadata()).containsEntry("errorCategory", "network");
        }
    }

    @Nested
    @DisplayName("logPolicy()")
    class LogPolicyTests {

        @Test
        void server_5xx_uses_ERROR() {
            // given
            var ex = responseException(503, "http://svc/api");
            ErrorEntry entry = strategy.handle(ex, context);

            // when
            LogPolicy policy = strategy.logPolicy(ex, entry);

            // then
            assertThat(policy).isEqualTo(LogPolicy.ERROR);
        }

        @Test
        void client_4xx_uses_WARN() {
            // given
            var ex = responseException(401, "http://svc/api");
            ErrorEntry entry = strategy.handle(ex, context);

            // when
            LogPolicy policy = strategy.logPolicy(ex, entry);

            // then
            assertThat(policy).isEqualTo(LogPolicy.WARN);
        }
    }

    @Nested
    @DisplayName("CWE-209 — Information Leak Prevention")
    class CWE209_InformationLeakPrevention {

        @Test
        void diagnosticMetadata_does_not_contain_query_params() {
            // given
            MockClientHttpRequest fakeRequest = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("http://svc/api?secret=token123&password=hunter2"));
            WebClientResponseException ex = WebClientResponseException.create(
                    500, "Error", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8,
                    fakeRequest);

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            ErrorInfoDetail info = findDetail(entry.getDetails(), ErrorInfoDetail.class);
            String allValues = String.join(" ", info.getDiagnosticMetadata().values());
            assertThat(allValues).doesNotContain("secret", "token123", "password", "hunter2");
        }

        @Test
        void diagnosticMetadata_does_not_contain_response_body() {
            // given
            byte[] sensitiveBody = "{\"password\":\"secret123\"}".getBytes(StandardCharsets.UTF_8);
            WebClientResponseException ex = WebClientResponseException.create(
                    500, "Error", HttpHeaders.EMPTY, sensitiveBody, StandardCharsets.UTF_8);

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            ErrorInfoDetail info = findDetail(entry.getDetails(), ErrorInfoDetail.class);
            String allValues = String.join(" ", info.getDiagnosticMetadata().values());
            assertThat(allValues).doesNotContain("password", "secret123");
        }

        @Test
        void causeMessage_is_sanitized_before_truncate() {
            // given
            MockClientHttpRequest fakeRequest = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://svc/api"));
            HttpHeaders headers = new HttpHeaders();
            headers.add("Authorization", "Bearer leaked-jwt-token");

            WebClientResponseException ex = WebClientResponseException.create(
                    500, "Authorization: Bearer leaked-jwt-token denied", headers, new byte[0],
                    StandardCharsets.UTF_8, fakeRequest);

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            ErrorInfoDetail info = findDetail(entry.getDetails(), ErrorInfoDetail.class);
            String causeMessage = info.getDiagnosticMetadata().get("causeMessage");
            assertThat(causeMessage)
                    .as("Bearer token in cause message must be masked")
                    .doesNotContain("leaked-jwt-token");
            assertThat(causeMessage).contains("Bearer ***");
        }

        @Test
        void upstreamUrlPath_is_sanitized() {
            // given
            MockClientHttpRequest fakeRequest = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("http://order-svc/api/users/12345/orders/550e8400-e29b-41d4-a716-446655440000"));
            WebClientResponseException ex = WebClientResponseException.create(
                    500, "Error", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8,
                    fakeRequest);

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            ErrorInfoDetail info = findDetail(entry.getDetails(), ErrorInfoDetail.class);
            assertThat(info.getDiagnosticMetadata())
                    .containsEntry("upstreamUrlPath", "/api/users/{id}/orders/{id}");
        }

        @Test
        void diagnosticMetadata_does_not_contain_authorization_header() {
            // given
            MockClientHttpRequest fakeRequest = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://svc/api"));
            fakeRequest.getHeaders().add("Authorization", "Bearer secret-jwt-token");

            WebClientResponseException ex = WebClientResponseException.create(
                    401, "Unauthorized", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8,
                    fakeRequest);

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            ErrorInfoDetail info = findDetail(entry.getDetails(), ErrorInfoDetail.class);
            String allValues = String.join(" ", info.getDiagnosticMetadata().values());
            assertThat(allValues).doesNotContain("Authorization", "Bearer", "secret-jwt-token");
        }
    }

    @Nested
    @DisplayName("Branch-targeted survivors (Sprint 5)")
    class BranchTargetedSurvivors {

        @Test
        @DisplayName("418 (4xx default) → message 'rejected the request' (upstream<500 ветка)")
        void status418_messageFor4xxDefault() {
            // given
            var ex = responseException(418, "http://svc/api");

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(entry.getMessage())
                    .as("4xx default → 'rejected the request', НЕ 'unexpected error'")
                    .isEqualTo("The upstream service rejected the request.");
        }

        @Test
        @DisplayName("505 HTTP Version Not Supported (5xx default) → 'unexpected error'")
        void status505_messageFor5xxDefault() {
            // given
            var ex = responseException(505, "http://svc/api");

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(entry.getMessage())
                    .as("5xx default → 'unexpected error'")
                    .isEqualTo("The upstream service returned an unexpected error.");
        }

        @Test
        @DisplayName("Exception без message → diagnostic не содержит causeMessage")
        void nullMessage_omitsCauseMessageKey() {
            // given
            HttpHeaders headers = new HttpHeaders();
            WebClientResponseException ex = WebClientResponseException.create(
                    404, "", headers, new byte[0], StandardCharsets.UTF_8);

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            ErrorInfoDetail info = findDetail(entry.getDetails(), ErrorInfoDetail.class);
            assertThat(info.getDiagnosticMetadata())
                    .as("ExceptionClass всегда есть как маркер, что diagnosticMetadata не пустой")
                    .containsKey("exceptionClass");
        }

        @Test
        @DisplayName("RequestException → diagnostic не содержит upstreamStatus")
        void requestException_diagnosticOmitsUpstreamStatus() {
            // given
            WebClientRequestException ex = new WebClientRequestException(
                    new ConnectException("refused"), HttpMethod.GET,
                    URI.create("http://svc/api"), HttpHeaders.EMPTY);

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            ErrorInfoDetail info = findDetail(entry.getDetails(), ErrorInfoDetail.class);
            assertThat(info.getDiagnosticMetadata())
                    .as("status=-1 не должен попадать в diagnostic")
                    .doesNotContainKey("upstreamStatus");
        }

        @Test
        @DisplayName("ResponseException без request → нет upstreamHost/upstreamUrlPath")
        void responseException_withoutRequest_omitsHostAndPath() {
            // given
            WebClientResponseException ex = WebClientResponseException.create(
                    500, "Error", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8
            );

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            ErrorInfoDetail info = findDetail(entry.getDetails(), ErrorInfoDetail.class);
            assertThat(info.getDiagnosticMetadata())
                    .as("без request — нет upstreamHost")
                    .doesNotContainKey("upstreamHost")
                    .as("без request — нет upstreamUrlPath")
                    .doesNotContainKey("upstreamUrlPath");
        }
    }

    @Nested
    @DisplayName("getOrder()")
    class Order {

        @Test
        void returns_webflux_order() {
            // when
            int order = strategy.getOrder();

            // then
            assertThat(order).isEqualTo(TopExceptionStrategyOrder.WEBFLUX);
        }
    }

    private static WebClientResponseException responseException(int status, String uri) {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create(uri));
        return WebClientResponseException.create(
                status, HttpStatus.valueOf(status).getReasonPhrase(),
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8,
                request);
    }

    @SuppressWarnings("unchecked")
    private static <T extends ErrorDetail> T findDetail(List<ErrorDetail> details, Class<T> type) {
        return (T) details.stream()
                .filter(type::isInstance)
                .findFirst()
                .orElseThrow();
    }
}
