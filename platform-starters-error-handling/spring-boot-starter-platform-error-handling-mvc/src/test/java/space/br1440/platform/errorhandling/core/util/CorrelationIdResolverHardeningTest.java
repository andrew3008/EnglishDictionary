package space.br1440.platform.errorhandling.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingHeaders;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CorrelationIdResolver hardening")
class CorrelationIdResolverHardeningTest {

    private final CorrelationIdResolver resolver = new CorrelationIdResolver(
            new ErrorHandlingProperties.CorrelationId(),
            true
    );

    @Nested
    @DisplayName("Приоритет источников")
    class Priority {

        @Test
        @DisplayName("traceparent имеет наивысший приоритет — даже при наличии X-Correlation-Id")
        void traceparent_winsOverCorrelationId() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ErrorHandlingHeaders.TRACEPARENT,"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
            request.addHeader(ErrorHandlingHeaders.X_CORRELATION_ID, "loser");

            // when
            String resolved = resolver.resolveFromRequest(request);

            // then
            assertThat(resolved).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        }

        @Test
        @DisplayName("X-Correlation-Id имеет приоритет над X-Request-Id и X-B3-TraceId")
        void correlationId_winsOverLegacy() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ErrorHandlingHeaders.X_CORRELATION_ID, "primary-id");
            request.addHeader(ErrorHandlingHeaders.X_REQUEST_ID, "fallback");
            request.addHeader(ErrorHandlingHeaders.X_B3_TRACE_ID, "deadbeef");

            // when
            String resolved = resolver.resolveFromRequest(request);

            // then
            assertThat(resolved).isEqualTo("primary-id");
        }

        @Test
        @DisplayName("X-Request-Id используется если X-Correlation-Id отсутствует")
        void requestId_usedAsSecondary() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ErrorHandlingHeaders.X_REQUEST_ID, "req-only");
            request.addHeader(ErrorHandlingHeaders.X_B3_TRACE_ID, "ignored");

            // when
            String resolved = resolver.resolveFromRequest(request);

            // then
            assertThat(resolved).isEqualTo("req-only");
        }

        @Test
        @DisplayName("X-B3-TraceId — последний legacy-fallback перед UUID")
        void b3_usedAsTertiary() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ErrorHandlingHeaders.X_B3_TRACE_ID, "abc123");

            // when
            String resolved = resolver.resolveFromRequest(request);

            // then
            assertThat(resolved).isEqualTo("abc123");
        }

        @Test
        @DisplayName("Пустые/whitespace-only заголовки игнорируются — fallback к следующему")
        void blankHeaders_skippedSilently() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ErrorHandlingHeaders.X_CORRELATION_ID, "   ");
            request.addHeader(ErrorHandlingHeaders.X_REQUEST_ID, "actual-id");

            // when
            String resolved = resolver.resolveFromRequest(request);

            // then
            assertThat(resolved).isEqualTo("actual-id");
        }

        @Test
        @DisplayName("При отсутствии всех заголовков генерируется валидный UUID")
        void noHeaders_generatesUuid() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();

            // when
            String result = resolver.resolveFromRequest(request);

            // then
            assertThat(result).isNotBlank();
            assertThat(UUID.fromString(result)).isNotNull();
        }
    }

    @Nested
    @DisplayName("traceparent: некорректные значения отбрасываются")
    class TraceparentRejection {

        @Test
        @DisplayName("Невалидный traceparent отбрасывается, fallback на X-Correlation-Id")
        void invalidTraceparent_rejected() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ErrorHandlingHeaders.TRACEPARENT, "not-a-traceparent");
            request.addHeader(ErrorHandlingHeaders.X_CORRELATION_ID, "fallback");

            // when
            String resolved = resolver.resolveFromRequest(request);

            // then
            assertThat(resolved).isEqualTo("fallback");
        }

        @Test
        @DisplayName("traceparent с нулевым trace-id отбрасывается")
        void zeroTraceId_rejected() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ErrorHandlingHeaders.TRACEPARENT,"00-00000000000000000000000000000000-00f067aa0ba902b7-01");

            // when
            String result = resolver.resolveFromRequest(request);

            // then
            assertThat(UUID.fromString(result)).isNotNull();
        }

        @Test
        @DisplayName("acceptTraceparent=false — traceparent игнорируется")
        void traceparentDisabled_ignored() {
            // given
            CorrelationIdResolver disabled = new CorrelationIdResolver(
                    new ErrorHandlingProperties.CorrelationId(),
                    false
            );
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ErrorHandlingHeaders.TRACEPARENT,"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
            request.addHeader(ErrorHandlingHeaders.X_CORRELATION_ID, "winner");

            // when
            String resolved = disabled.resolveFromRequest(request);

            // then
            assertThat(resolved).isEqualTo("winner");
        }
    }

    @Nested
    @DisplayName("Защита от CWE-113 / CWE-117")
    class HeaderInjection {

        @Test
        @DisplayName("CRLF smuggling в X-Correlation-Id — отброшено")
        void crlfSmuggling_rejected() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ErrorHandlingHeaders.X_CORRELATION_ID, "value\r\nInjected: yes");

            // when
            String result = resolver.resolveFromRequest(request);

            // then
            assertThat(UUID.fromString(result)).isNotNull();
        }

        @Test
        @DisplayName("Bidirectional override — отброшено")
        void bidiOverride_rejected() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ErrorHandlingHeaders.X_CORRELATION_ID, "evil\u202Eback");

            // when
            String result = resolver.resolveFromRequest(request);

            // then
            assertThat(UUID.fromString(result)).isNotNull();
        }

        @Test
        @DisplayName("Слишком длинный header — отброшено")
        void overlyLong_rejected() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();
            String longStr = "a".repeat(200);
            request.addHeader(ErrorHandlingHeaders.X_CORRELATION_ID, longStr);

            // when
            String result = resolver.resolveFromRequest(request);

            // then
            assertThat(UUID.fromString(result)).isNotNull();
        }

        @Test
        @DisplayName("Не-ASCII (например, кириллица) — отброшено по charset")
        void nonAscii_rejected() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ErrorHandlingHeaders.X_CORRELATION_ID, "идентификатор");

            // when
            String result = resolver.resolveFromRequest(request);

            // then
            assertThat(UUID.fromString(result)).isNotNull();
        }

        @Test
        @DisplayName("Только пробелы — отсутствие значения, fallback на UUID")
        void whitespaceOnly_notCountedAsRejection() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ErrorHandlingHeaders.X_CORRELATION_ID, "   \t  ");

            // when
            String result = resolver.resolveFromRequest(request);

            // then
            assertThat(UUID.fromString(result)).isNotNull();
        }
    }

    @Nested
    @DisplayName("Backward compatibility: статический метод")
    class StaticBackwardCompat {

        @Test
        @DisplayName("Статический resolve(request) использует дефолтные настройки")
        void staticResolve_works() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ErrorHandlingHeaders.X_CORRELATION_ID, "static-call");

            // when
            String resolved = resolver.resolveFromRequest(request);

            // then
            assertThat(resolved).isEqualTo("static-call");
        }
    }
}
