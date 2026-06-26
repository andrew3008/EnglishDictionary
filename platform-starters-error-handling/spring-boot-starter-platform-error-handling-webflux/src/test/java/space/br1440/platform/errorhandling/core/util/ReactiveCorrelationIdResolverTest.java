package space.br1440.platform.errorhandling.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingHeaders;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReactiveCorrelationIdResolver")
class ReactiveCorrelationIdResolverTest {

    private static ReactiveCorrelationIdResolver resolver() {
        return new ReactiveCorrelationIdResolver(
                new ErrorHandlingProperties.CorrelationId(),
                true);
    }

    private static ReactiveCorrelationIdResolver resolver(int maxLength,
                                                          String allowedPattern,
                                                          boolean acceptTraceparent) {
        ErrorHandlingProperties.CorrelationId cfg = new ErrorHandlingProperties.CorrelationId()
                .setMaxLength(maxLength)
                .setAllowedPattern(allowedPattern);
        return new ReactiveCorrelationIdResolver(cfg, acceptTraceparent);
    }

    @Nested
    @DisplayName("Приоритет источников")
    class Priority {

        @Test
        @DisplayName("traceparent имеет наивысший приоритет (выше X-Correlation-Id)")
        void traceparent_winsOverCorrelationId() {
            // given
            String validTraceId = "0af7651916cd43dd8448eb211c80319c";
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.TRACEPARENT,
                            "00-" + validTraceId + "-b7ad6b7169203331-01")
                    .header(ErrorHandlingHeaders.X_CORRELATION_ID, "from-correlation-header")
                    .build();

            // when
            String result = resolver().resolveFromRequest(request);

            // then
            assertThat(result).isEqualTo(validTraceId);
        }

        @Test
        @DisplayName("X-Correlation-Id выше X-Request-Id")
        void correlationId_winsOverRequestId() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.X_CORRELATION_ID, "corr-1")
                    .header(ErrorHandlingHeaders.X_REQUEST_ID, "req-2")
                    .build();

            String result = resolver().resolveFromRequest(request);

            assertThat(result).isEqualTo("corr-1");
        }

        @Test
        @DisplayName("X-Request-Id выше X-B3-TraceId")
        void requestId_winsOverB3() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.X_REQUEST_ID, "req-2")
                    .header(ErrorHandlingHeaders.X_B3_TRACE_ID, "b3-trace-3")
                    .build();

            String result = resolver().resolveFromRequest(request);

            assertThat(result).isEqualTo("req-2");
        }

        @Test
        @DisplayName("X-B3-TraceId используется как последний fallback перед UUID")
        void b3_usedAsLastFallback() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.X_B3_TRACE_ID, "b3-trace-only")
                    .build();

            String result = resolver().resolveFromRequest(request);

            assertThat(result).isEqualTo("b3-trace-only");
        }

        @Test
        @DisplayName("Отсутствие всех источников → генерируется валидный UUID")
        void noHeaders_generatesUuid() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();

            String result = resolver().resolveFromRequest(request);

            assertThat(result).isNotBlank();
            assertThat(UUID.fromString(result)).isNotNull();
        }

        @Test
        @DisplayName("acceptTraceparent=false полностью игнорирует traceparent (даже валидный)")
        void traceparent_disabled_ignored() {
            String validTraceId = "0af7651916cd43dd8448eb211c80319c";
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.TRACEPARENT,
                            "00-" + validTraceId + "-b7ad6b7169203331-01")
                    .header(ErrorHandlingHeaders.X_CORRELATION_ID, "from-correlation")
                    .build();

            ReactiveCorrelationIdResolver subject = resolver(
                    ErrorHandlingProperties.CorrelationId.DEFAULT_MAX_LENGTH,
                    ErrorHandlingProperties.CorrelationId.DEFAULT_ALLOWED_PATTERN,
                    false);

            assertThat(subject.resolveFromRequest(request)).isEqualTo("from-correlation");
        }
    }

    @Nested
    @DisplayName("Length cap (CWE-400)")
    class LengthCap {

        @Test
        @DisplayName("Значение длиннее maxLength отклоняется (fallback на UUID)")
        void overlong_rejected_andFallsBackToUuid() {
            ReactiveCorrelationIdResolver subject = resolver();

            String overlong = "a".repeat(ErrorHandlingProperties.CorrelationId.DEFAULT_MAX_LENGTH + 1);

            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.X_CORRELATION_ID, overlong)
                    .build();

            String result = subject.resolveFromRequest(request);

            assertThat(result).isNotEqualTo(overlong);
            assertThat(UUID.fromString(result)).isNotNull();
        }

        @Test
        @DisplayName("Значение ровно maxLength принимается")
        void exactlyMaxLength_accepted() {
            ReactiveCorrelationIdResolver subject = resolver();

            String boundary = "a".repeat(ErrorHandlingProperties.CorrelationId.DEFAULT_MAX_LENGTH);

            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.X_CORRELATION_ID, boundary)
                    .build();

            String result = subject.resolveFromRequest(request);

            assertThat(result).isEqualTo(boundary);
        }

        @Test
        @DisplayName("Кастомный maxLength соблюдается")
        void customMaxLength_respected() {
            ReactiveCorrelationIdResolver subject = resolver(
                    16,
                    ErrorHandlingProperties.CorrelationId.DEFAULT_ALLOWED_PATTERN,
                    true);

            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.X_CORRELATION_ID, "a".repeat(17))
                    .build();

            String result = subject.resolveFromRequest(request);

            assertThat(UUID.fromString(result)).isNotNull();
        }

        @Test
        @DisplayName("После length-rejection cascade продолжается на следующем header'е")
        void rejection_cascadesToNextHeader() {
            ReactiveCorrelationIdResolver subject = resolver();

            String overlong = "a".repeat(ErrorHandlingProperties.CorrelationId.DEFAULT_MAX_LENGTH + 1);

            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.X_CORRELATION_ID, overlong)
                    .header(ErrorHandlingHeaders.X_REQUEST_ID, "valid-fallback")
                    .build();

            String result = subject.resolveFromRequest(request);

            assertThat(result).isEqualTo("valid-fallback");
        }
    }

    @Nested
    @DisplayName("Charset whitelist (CWE-113 / CWE-117)")
    class CharsetWhitelist {

        @ParameterizedTest(name = "Header injection отклоняется: {0}")
        @ValueSource(strings = {
                "valid\r\nX-Injected: bad",  // CR/LF injection
                "valid\nX-Injected: bad",
                "valid with spaces",
                "valid;rm -rf",
                "valid<script>",
                "valid\u202Eevil",            // RTL Override (Unicode)
                "valid|pipe",
                "valid\"quote"
        })
        void disallowedChars_rejected(String malicious) {
            ReactiveCorrelationIdResolver subject = resolver();

            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.X_CORRELATION_ID, malicious)
                    .build();

            String result = subject.resolveFromRequest(request);

            assertThat(result).doesNotContain("\r").doesNotContain("\n");
            assertThat(UUID.fromString(result)).isNotNull();
        }

        @ParameterizedTest(name = "Допустимое значение принимается: {0}")
        @ValueSource(strings = {
                "0af7651916cd43dd8448eb211c80319c",
                "550e8400-e29b-41d4-a716-446655440000",
                "tx-2024-01-15.12_30_45",
                "service:order:42",
                "ABCDEF0123456789"
        })
        void allowedChars_accepted(String valid) {
            ReactiveCorrelationIdResolver subject = resolver();

            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.X_CORRELATION_ID, valid)
                    .build();

            String result = subject.resolveFromRequest(request);

            assertThat(result).isEqualTo(valid);
        }

        @Test
        @DisplayName("Кастомный allowedPattern соблюдается")
        void customPattern_respected() {
            ReactiveCorrelationIdResolver subject = resolver(
                    ErrorHandlingProperties.CorrelationId.DEFAULT_MAX_LENGTH,
                    "[0-9]+",
                    true);

            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.X_CORRELATION_ID, "abc-123")
                    .build();

            String result = subject.resolveFromRequest(request);

            assertThat(UUID.fromString(result)).isNotNull();
        }
    }

    @Nested
    @DisplayName("traceparent (W3C Trace Context)")
    class Traceparent {

        @Test
        @DisplayName("Валидный traceparent даёт trace-id (32 hex)")
        void valid_traceparent_extracted() {
            String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.TRACEPARENT,
                            "00-" + traceId + "-00f067aa0ba902b7-01")
                    .build();

            String result = resolver().resolveFromRequest(request);

            assertThat(result).isEqualTo(traceId);
        }

        @ParameterizedTest(name = "Невалидный traceparent отклоняется: {0}")
        @ValueSource(strings = {
                "garbage",
                "00-not-hex-stuff-here-01",
                "00-00000000000000000000000000000000-00f067aa0ba902b7-01", // all-zero trace-id (W3C запрещён)
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7",    // missing flags (мало полей)
                "00-tooshort-00f067aa0ba902b7-01",                          // trace-id != 32 hex
                "00-XYZ92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"   // не-hex символы в trace-id
        })
        void invalid_traceparent_fallsBackToNextHeader(String badTraceparent) {
            ReactiveCorrelationIdResolver subject = resolver();

            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.TRACEPARENT, badTraceparent)
                    .header(ErrorHandlingHeaders.X_CORRELATION_ID, "fallback-corr")
                    .build();

            String result = subject.resolveFromRequest(request);

            assertThat(result).isEqualTo("fallback-corr");
        }

        @Test
        @DisplayName("Forward-compatibility: версия отличная от 00 (например, 01) принимается, " +
                "если структура валидна (W3C TraceContext §3.2.2.1 рекомендация)")
        void futureVersion_forwardCompatible() {
            String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.TRACEPARENT,
                            "01-" + traceId + "-00f067aa0ba902b7-01")
                    .build();

            String result = resolver().resolveFromRequest(request);

            assertThat(result).isEqualTo(traceId);
        }

        @Test
        @DisplayName("Whitespace в traceparent → trim, валидное значение принимается")
        void traceparent_trimmed() {
            String traceId = "0af7651916cd43dd8448eb211c80319c";
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.TRACEPARENT,
                            "  00-" + traceId + "-b7ad6b7169203331-01  ")
                    .build();

            assertThat(resolver().resolveFromRequest(request))
                    .isEqualTo(traceId);
        }

        @Test
        @DisplayName("Пустой traceparent игнорируется (к следующему источнику)")
        void blank_traceparent_silentlyIgnored() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.TRACEPARENT, "   ")
                    .header(ErrorHandlingHeaders.X_CORRELATION_ID, "corr-fallback")
                    .build();

            String result = resolver().resolveFromRequest(request);

            assertThat(result).isEqualTo("corr-fallback");
        }
    }

    @Nested
    @DisplayName("Blank / отсутствующие значения")
    class BlankHandling {

        @Test
        @DisplayName("Пустой X-Correlation-Id молча игнорируется")
        void emptyHeader_ignored() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.X_CORRELATION_ID, "")
                    .header(ErrorHandlingHeaders.X_REQUEST_ID, "req-789")
                    .build();

            String result = resolver().resolveFromRequest(request);

            assertThat(result).isEqualTo("req-789");
        }

        @Test
        @DisplayName("Whitespace-only X-Correlation-Id молча игнорируется")
        void whitespaceHeader_ignored() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.X_CORRELATION_ID, "   ")
                    .header(ErrorHandlingHeaders.X_REQUEST_ID, "req-111")
                    .build();

            String result = resolver().resolveFromRequest(request);

            assertThat(result).isEqualTo("req-111");
        }
    }

    @Nested
    @DisplayName("Контракт результата (SecureRandom UUID)")
    class ResultContract {

        @Test
        @DisplayName("Результат никогда не null и не blank")
        void result_neverNullOrBlank() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
            String result = resolver().resolveFromRequest(request);

            assertThat(result).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("При генерации UUID каждый вызов даёт уникальное значение (PRNG)")
        void uuidGeneration_producesUnique() {
            ReactiveCorrelationIdResolver subject = resolver();

            Set<String> uniques = new HashSet<>();
            for (int i = 0; i < 200; i++) {
                MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
                uniques.add(subject.resolveFromRequest(request));
            }

            // Коллизий быть не должно (UUID v4: ~5.3 × 10^36 значений)
            assertThat(uniques).hasSize(200);
        }

        @Test
        @DisplayName("Сгенерированный UUID имеет валидный version=4 формат")
        void generatedUuid_hasVersion4() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
            String result = resolver().resolveFromRequest(request);

            UUID parsed = UUID.fromString(result);
            assertThat(parsed.version()).isEqualTo(4);
            // RFC 4122 variant = 10x (big-endian, IETF)
            assertThat(parsed.variant()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Backward-compatible статический resolve()")
    class StaticFacade {

        @Test
        @DisplayName("Статический resolve() возвращает trace-id из traceparent")
        void staticResolve_traceparent() {
            String traceId = "0af7651916cd43dd8448eb211c80319c";
            MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                    .header(ErrorHandlingHeaders.TRACEPARENT,
                            "00-" + traceId + "-b7ad6b7169203331-01")
                    .build();

            assertThat(ReactiveCorrelationIdResolver.resolve(request)).isEqualTo(traceId);
        }

        @Test
        @DisplayName("Статический resolve() без header'ов генерирует UUID")
        void staticResolve_generatesUuid() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
            String result = ReactiveCorrelationIdResolver.resolve(request);

            assertThat(result).isNotBlank();
            assertThat(UUID.fromString(result)).isNotNull();
        }
    }
}
