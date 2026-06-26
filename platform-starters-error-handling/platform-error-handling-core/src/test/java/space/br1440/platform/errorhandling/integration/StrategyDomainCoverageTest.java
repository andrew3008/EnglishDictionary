package space.br1440.platform.errorhandling.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.strategy.cause.network.DnsResolutionExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.network.SslExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.network.TimeoutExceptionStrategy;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.testing.StrategyTestExtension;
import space.br1440.platform.errorhandling.testing.annotation.WithStrategy;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Верификация поведения {@code supportsCause()} реальных экземпляров сетевых стратегий
 * без Spring-контекста.
 *
 * <p>Каждая стратегия проверяется на корректное распознавание «своих» исключений
 * и отклонение исключений из чужих доменов (cross-domain negative testing).
 */
@Tag("coverage")
@Tag("no-spring")
@DisplayName("Покрытие доменов: supportsCause() сетевых стратегий")
class StrategyDomainCoverageTest {

    private final TimeoutExceptionStrategy timeoutStrategy =
            new TimeoutExceptionStrategy(new StackHintExtractor(false, List.of()));
    private final SslExceptionStrategy sslStrategy =
            new SslExceptionStrategy(new StackHintExtractor(false, List.of()));
    private final DnsResolutionExceptionStrategy dnsStrategy =
            new DnsResolutionExceptionStrategy(new StackHintExtractor(false, List.of()));

    @Nested
    @DisplayName("TimeoutExceptionStrategy — распознавание timeout-исключений")
    class TimeoutStrategyTests {

        @Test
        @DisplayName("supportsCause → true для java.util.concurrent.TimeoutException")
        void supportsTimeoutException() {
            // when / then
            assertThat(timeoutStrategy.supportsCause(new TimeoutException("timeout")))
                    .isTrue();
        }

        @Test
        @DisplayName("supportsCause → false для IOException (чужой домен)")
        void doesNotSupportIOException() {
            // when / then
            assertThat(timeoutStrategy.supportsCause(new IOException("io")))
                    .isFalse();
        }

        @Test
        @DisplayName("supportsCause → false для RuntimeException (чужой домен)")
        void doesNotSupportRuntimeException() {
            // when / then
            assertThat(timeoutStrategy.supportsCause(new RuntimeException("runtime")))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("SslExceptionStrategy — распознавание TLS/SSL-исключений")
    class SslStrategyTests {

        @Test
        @DisplayName("supportsCause → true для SSLHandshakeException")
        void supportsSslHandshakeException() {
            // when / then
            assertThat(sslStrategy.supportsCause(new SSLHandshakeException("handshake failed")))
                    .isTrue();
        }

        @Test
        @DisplayName("supportsCause → true для SSLException (базовый тип)")
        void supportsSslException() {
            // when / then
            assertThat(sslStrategy.supportsCause(new SSLException("ssl error")))
                    .isTrue();
        }

        @Test
        @DisplayName("supportsCause → false для TimeoutException (чужой домен)")
        void doesNotSupportTimeoutException() {
            // when / then
            assertThat(sslStrategy.supportsCause(new TimeoutException("timeout")))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("DnsResolutionExceptionStrategy — распознавание DNS-исключений")
    class DnsStrategyTests {

        @Test
        @DisplayName("supportsCause → true для UnknownHostException")
        void supportsUnknownHostException() {
            // when / then
            assertThat(dnsStrategy.supportsCause(new UnknownHostException("host.invalid")))
                    .isTrue();
        }

        @Test
        @DisplayName("supportsCause → false для ConnectException (чужой домен)")
        void doesNotSupportConnectException() {
            // when / then
            assertThat(dnsStrategy.supportsCause(new ConnectException("connection refused")))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Кросс-доменная изоляция: стратегии НЕ перехватывают чужие исключения")
    class CrossDomainNegativeTests {

        @Test
        @DisplayName("TimeoutExceptionStrategy НЕ поддерживает SSLException")
        void timeoutDoesNotSupportSsl() {
            // when / then
            assertThat(timeoutStrategy.supportsCause(new SSLException("ssl error")))
                    .isFalse();
        }

        @Test
        @DisplayName("SslExceptionStrategy НЕ поддерживает TimeoutException")
        void sslDoesNotSupportTimeout() {
            // when / then
            assertThat(sslStrategy.supportsCause(new TimeoutException("timeout")))
                    .isFalse();
        }

        @Test
        @DisplayName("DnsResolutionExceptionStrategy НЕ поддерживает SSLException")
        void dnsDoesNotSupportSsl() {
            // when / then
            assertThat(dnsStrategy.supportsCause(new SSLException("ssl error")))
                    .isFalse();
        }
    }

    // ── StrategyTestExtension — матричная проверка общих контрактов ────

    @TestTemplate
    @ExtendWith(StrategyTestExtension.class)
    @WithStrategy(TimeoutExceptionStrategy.class)
    @WithStrategy(SslExceptionStrategy.class)
    @WithStrategy(DnsResolutionExceptionStrategy.class)
    @DisplayName("supportsCause → false для RuntimeException, не относящегося к домену стратегии")
    void rejectsGenericRuntimeException(RootCauseExceptionHandlingStrategy strategy) {
        // given
        var unrelated = new RuntimeException("completely unrelated");

        // when / then
        assertThat(strategy.supportsCause(unrelated)).isFalse();
    }

    @TestTemplate
    @ExtendWith(StrategyTestExtension.class)
    @WithStrategy(TimeoutExceptionStrategy.class)
    @WithStrategy(SslExceptionStrategy.class)
    @WithStrategy(DnsResolutionExceptionStrategy.class)
    @DisplayName("getOrder() возвращает неотрицательное значение")
    void orderIsNonNegative(RootCauseExceptionHandlingStrategy strategy) {
        // when / then
        assertThat(strategy.getOrder()).isGreaterThanOrEqualTo(0);
    }

}
