package space.br1440.platform.errorhandling.integration.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.strategy.cause.network.DnsResolutionExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.network.SslExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.network.TimeoutExceptionStrategy;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import javax.net.ssl.SSLHandshakeException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static space.br1440.platform.errorhandling.testing.assertions.ErrorHandlingAssertions.assertThat;

/**
 * Доменный интеграционный тест для NETWORK root-cause стратегий без Spring-контекста.
 *
 * <p>Проверяет корректность работы стратегий {@link TimeoutExceptionStrategy},
 * {@link SslExceptionStrategy} и {@link DnsResolutionExceptionStrategy} —
 * распознавание исключений ({@code supportsCause}) и формирование
 * {@link RootCauseResolution} ({@code extractCauseDetails}).
 */
@DisplayName("Сетевые root-cause стратегии — доменный интеграционный тест")
class NetworkStrategyIntegrationTest {

    // ── TimeoutExceptionStrategy ─────────────────────────────────────

    @Nested
    @DisplayName("TimeoutExceptionStrategy — обработка timeout-исключений")
    class TimeoutExceptionStrategyTests {

        private final TimeoutExceptionStrategy strategy =
                new TimeoutExceptionStrategy(new StackHintExtractor(false, List.of()));

        @Test
        @DisplayName("supportsCause возвращает true для java.util.concurrent.TimeoutException")
        void supportsCause_returns_true_for_TimeoutException() {
            // given
            var cause = new TimeoutException("Connection timed out");

            // when / then
            assertThat(strategy.supportsCause(cause)).isTrue();
        }

        @Test
        @DisplayName("supportsCause возвращает false для неподдерживаемого исключения")
        void supportsCause_returns_false_for_unsupported_exception() {
            // given
            var cause = new IllegalArgumentException("not a timeout");

            // when / then
            assertThat(strategy.supportsCause(cause)).isFalse();
        }

        @Test
        @DisplayName("extractCauseDetails возвращает 504 GATEWAY_TIMEOUT для TimeoutException")
        void extractCauseDetails_returns_gateway_timeout_status() {
            // given
            var cause = new TimeoutException("Connection timed out");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            assertThat(resolution)
                    .hasHttpStatus(HttpStatus.GATEWAY_TIMEOUT);
        }

        @Test
        @DisplayName("Сообщение содержит описание timeout-проблемы")
        void extractCauseDetails_message_describes_timeout() {
            // given
            var cause = new TimeoutException("Connection timed out");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            assertThat(resolution).hasMessageContaining("timeout");
        }

        @Test
        @DisplayName("Результат содержит RetryInfoDetail — timeout повторяем")
        void extractCauseDetails_contains_retry_info() {
            // given
            var cause = new TimeoutException("Connection timed out");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            assertThat(resolution)
                    .containsRetryInfo();
        }

        @Test
        @DisplayName("diagnosticMetadata содержит causeMessage (sanitized) и stackHint, без redundant exceptionClass")
        void extractCauseDetails_diagnostic_metadata_has_cause_message() {
            // given
            var cause = new TimeoutException("Connection timed out");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            ErrorInfoDetail info = extractErrorInfoDetail(resolution);

            // exceptionClass удалён как избыточный с reason/rootCauseClass:
            // тип исключения уже идентифицирован через reason=ASYNC_TIMEOUT.
            // stackHint не проверяем здесь — экстрактор инициализирован с emit=false.
            assertThat(info.getDiagnosticMetadata()).doesNotContainKey("exceptionClass");
            assertThat(info.getDiagnosticMetadata()).containsKey("causeMessage");
        }

        @Test
        @DisplayName("reason идентифицирует тип timeout (timeoutType дублировался — удалён)")
        void extractCauseDetails_reason_identifies_timeout_type() {
            // given
            var cause = new TimeoutException("Connection timed out");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            ErrorInfoDetail info = extractErrorInfoDetail(resolution);

            assertThat(info).hasReason("ASYNC_TIMEOUT");
            // timeoutType удалён из metadata — он тождественен reason
            assertThat(info.getDiagnosticMetadata()).doesNotContainKey("timeoutType");
        }

        @Test
        @DisplayName("clientMetadata содержит только timeoutLayer (без избыточного timeoutType/retryable)")
        void extractCauseDetails_client_metadata_has_only_timeout_layer() {
            // given
            var cause = new TimeoutException("Connection timed out");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            ErrorInfoDetail info = extractErrorInfoDetail(resolution);

            // timeoutType избыточен с reason; retryable передаётся через RetryInfoDetail
            assertThat(info)
                    .clientMetadataContains("timeoutLayer", "ASYNC");
            assertThat(info.getClientMetadata()).doesNotContainKey("timeoutType");
            assertThat(info.getClientMetadata()).doesNotContainKey("retryable");
        }

        @Test
        @DisplayName("ErrorInfoDetail имеет domain = timeout")
        void extractCauseDetails_error_info_has_timeout_domain() {
            // given
            var cause = new TimeoutException("Connection timed out");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            ErrorInfoDetail info = extractErrorInfoDetail(resolution);

            assertThat(info)
                    .hasDomain("timeout")
                    .hasReason("ASYNC_TIMEOUT");
        }
    }

    // ── SslExceptionStrategy ─────────────────────────────────────────

    @Nested
    @DisplayName("SslExceptionStrategy — обработка TLS/SSL-исключений")
    class SslExceptionStrategyTests {

        private final SslExceptionStrategy strategy =
                new SslExceptionStrategy(new StackHintExtractor(false, List.of()));

        @Test
        @DisplayName("supportsCause возвращает true для SSLHandshakeException")
        void supportsCause_returns_true_for_SSLHandshakeException() {
            // given
            var cause = new SSLHandshakeException("Remote host terminated the handshake");

            // when / then
            assertThat(strategy.supportsCause(cause)).isTrue();
        }

        @Test
        @DisplayName("supportsCause возвращает false для неподдерживаемого исключения")
        void supportsCause_returns_false_for_unsupported_exception() {
            // given
            var cause = new RuntimeException("not an SSL error");

            // when / then
            assertThat(strategy.supportsCause(cause)).isFalse();
        }

        @Test
        @DisplayName("extractCauseDetails возвращает 503 SERVICE_UNAVAILABLE для SSLHandshakeException")
        void extractCauseDetails_returns_service_unavailable() {
            // given
            var cause = new SSLHandshakeException("Remote host terminated the handshake");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            assertThat(resolution)
                    .hasHttpStatus(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("Сообщение содержит описание TLS/SSL-проблемы")
        void extractCauseDetails_message_describes_ssl_error() {
            // given
            var cause = new SSLHandshakeException("Remote host terminated the handshake");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            assertThat(resolution)
                    .hasMessageContaining("TLS");
        }

        @Test
        @DisplayName("HANDSHAKE_FAILURE — Retryability.UNSPECIFIED (политика клиента, а не библиотеки)")
        void extractCauseDetails_handshake_is_unspecified() {
            // given
            var cause = new SSLHandshakeException("Remote host terminated the handshake");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            assertThat(resolution)
                    .hasRetryability(Retryability.UNSPECIFIED);
        }

        @Test
        @DisplayName("diagnosticMetadata содержит sslErrorType = HANDSHAKE_FAILURE")
        void extractCauseDetails_diagnostic_metadata_has_ssl_error_type() {
            // given
            var cause = new SSLHandshakeException("Remote host terminated the handshake");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            ErrorInfoDetail info = extractErrorInfoDetail(resolution);

            assertThat(info)
                    .diagnosticMetadataContains("sslErrorType", "HANDSHAKE_FAILURE");
        }

        @Test
        @DisplayName("diagnosticMetadata содержит exceptionClass")
        void extractCauseDetails_diagnostic_metadata_has_exception_class() {
            // given
            var cause = new SSLHandshakeException("Remote host terminated the handshake");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            ErrorInfoDetail info = extractErrorInfoDetail(resolution);

            assertThat(info)
                    .diagnosticMetadataContains("exceptionClass",
                            "javax.net.ssl.SSLHandshakeException");
        }

        @Test
        @DisplayName("ErrorInfoDetail имеет domain = tls и reason = HANDSHAKE_FAILURE")
        void extractCauseDetails_error_info_has_tls_domain() {
            // given
            var cause = new SSLHandshakeException("Remote host terminated the handshake");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            ErrorInfoDetail info = extractErrorInfoDetail(resolution);

            assertThat(info)
                    .hasDomain("tls")
                    .hasReason("HANDSHAKE_FAILURE");
        }
    }

    // ── DnsResolutionExceptionStrategy ───────────────────────────────

    @Nested
    @DisplayName("DnsResolutionExceptionStrategy — обработка DNS-ошибок")
    class DnsResolutionExceptionStrategyTests {

        private final DnsResolutionExceptionStrategy strategy =
                new DnsResolutionExceptionStrategy(new StackHintExtractor(false, List.of()));

        @Test
        @DisplayName("supportsCause возвращает true для UnknownHostException")
        void supportsCause_returns_true_for_UnknownHostException() {
            // given
            var cause = new UnknownHostException("api.example.com");

            // when / then
            assertThat(strategy.supportsCause(cause)).isTrue();
        }

        @Test
        @DisplayName("supportsCause возвращает false для неподдерживаемого исключения")
        void supportsCause_returns_false_for_unsupported_exception() {
            // given
            var cause = new IllegalStateException("not a DNS error");

            // when / then
            assertThat(strategy.supportsCause(cause)).isFalse();
        }

        @Test
        @DisplayName("extractCauseDetails возвращает 503 SERVICE_UNAVAILABLE для UnknownHostException")
        void extractCauseDetails_returns_service_unavailable() {
            // given
            var cause = new UnknownHostException("api.example.com");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            assertThat(resolution)
                    .hasHttpStatus(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("Сообщение содержит описание DNS-проблемы")
        void extractCauseDetails_message_describes_dns_error() {
            // given
            var cause = new UnknownHostException("api.example.com");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            assertThat(resolution)
                    .hasMessageContaining("DNS");
        }

        @Test
        @DisplayName("DNS-ошибки являются повторяемыми — содержат RetryInfoDetail")
        void extractCauseDetails_contains_retry_info() {
            // given
            var cause = new UnknownHostException("api.example.com");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            assertThat(resolution)
                    .containsRetryInfo();
        }

        @Test
        @DisplayName("diagnosticMetadata содержит hostname из UnknownHostException")
        void extractCauseDetails_diagnostic_metadata_has_hostname() {
            // given
            var cause = new UnknownHostException("api.example.com");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            ErrorInfoDetail info = extractErrorInfoDetail(resolution);

            assertThat(info)
                    .diagnosticMetadataContains("hostname", "api.example.com");
        }

        @Test
        @DisplayName("diagnosticMetadata содержит exceptionClass = UnknownHostException")
        void extractCauseDetails_diagnostic_metadata_has_exception_class() {
            // given
            var cause = new UnknownHostException("api.example.com");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            // Поле infrastructureLayer = "DNS" удалено как избыточное —
            // оно дублировало domain = "dns" из ErrorInfoDetail.
            // resolverType проверяется в отдельном тесте ниже.
            ErrorInfoDetail info = extractErrorInfoDetail(resolution);

            assertThat(info)
                    .diagnosticMetadataContains("exceptionClass",
                            "java.net.UnknownHostException");
        }

        @Test
        @DisplayName("ErrorInfoDetail имеет domain = dns и reason = UNKNOWN_HOST")
        void extractCauseDetails_error_info_has_dns_domain() {
            // given
            var cause = new UnknownHostException("api.example.com");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            ErrorInfoDetail info = extractErrorInfoDetail(resolution);

            assertThat(info)
                    .hasDomain("dns")
                    .hasReason("UNKNOWN_HOST");
        }

        @Test
        @DisplayName("diagnosticMetadata содержит hostnameType = EXTERNAL для внешнего хоста")
        void extractCauseDetails_diagnostic_metadata_classifies_external_hostname() {
            // given
            var cause = new UnknownHostException("api.example.com");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            ErrorInfoDetail info = extractErrorInfoDetail(resolution);

            assertThat(info)
                    .diagnosticMetadataContains("hostnameType", "EXTERNAL");
        }

        @Test
        @DisplayName("diagnosticMetadata содержит resolverType = JDK для UnknownHostException")
        void extractCauseDetails_diagnostic_metadata_has_resolver_type() {
            // given
            var cause = new UnknownHostException("api.example.com");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            ErrorInfoDetail info = extractErrorInfoDetail(resolution);

            assertThat(info)
                    .diagnosticMetadataContains("resolverType", "JDK");
        }
    }

    private static ErrorInfoDetail extractErrorInfoDetail(RootCauseResolution resolution) {
        return resolution.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("RootCauseResolution не содержит ErrorInfoDetail"));
    }

}
