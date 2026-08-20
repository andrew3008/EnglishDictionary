package space.br1440.platform.errorhandling.core.advice;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.DynamicType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.mock.web.MockHttpServletRequest;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorEntryEnricher;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricSnapshot;
import space.br1440.platform.errorhandling.metrics.NoopExceptionMetricRecorder;
import space.br1440.platform.errorhandling.mvc.internal.ExceptionLogger;
import space.br1440.platform.errorhandling.mvc.internal.ProblemResponseFactory;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.properties.LoggingStrategy;
import space.br1440.platform.errorhandling.testing.support.TestCorrelationIdResolver;
import space.br1440.platform.errorhandling.testing.support.TestUnknownExceptionHandler;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.mapping.ErrorEntryToDtoMapper;
import space.br1440.platform.errorhandling.model.dto.ErrorEntryDTO;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private static final String CORRELATION_ID = "test-corr-id";
    private static final String APP_NAME = "test-service";
    private static final String REQUEST_URI = "/api/resource";

    @BeforeEach
    void clearMdc() {
        MDC.clear();
    }

    private static MockHttpServletRequest mockRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", REQUEST_URI);
        request.addHeader("X-Correlation-Id", CORRELATION_ID);
        return request;
    }

    private static GlobalExceptionHandler createHandler(List<TopExceptionHandlingStrategy> strategies) {
        return createHandler(strategies, Collections.emptyList(), new NoopExceptionMetricRecorder());
    }

    private static GlobalExceptionHandler createHandler(List<TopExceptionHandlingStrategy> strategies,
                                                        List<ErrorEntryEnricher> enrichers,
                                                        ExceptionMetricRecorder metricRecorder) {
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);
        return new GlobalExceptionHandler(
                properties,
                strategies,
                new TestUnknownExceptionHandler(),
                metricRecorder,
                new ProblemResponseFactory(new ErrorEntryToDtoMapper(), APP_NAME, properties),
                new ExceptionLogger(properties),
                TestCorrelationIdResolver.defaultInstance(),
                enrichers,
                new StackHintExtractor(false, List.of()),
                List.of(),
                null
        );
    }

    @Nested
    @DisplayName("Dispatch стратегий")
    class StrategyDispatch {

        @Test
        @DisplayName("Первая стратегия с supports=true побеждает")
        void firstSupportingStrategy_wins() {
            // given
            var called = new AtomicBoolean(false);
            TopExceptionHandlingStrategy first = stubStrategy(true, HttpStatus.BAD_REQUEST, "FIRST");
            TopExceptionHandlingStrategy second = new TopExceptionHandlingStrategy() {
                @Override
                public boolean supports(@NonNull Exception e) {
                    return true;
                }

                @Override
                @NonNull
                public ErrorEntry handle(@NonNull Exception e, @NonNull ErrorHandlingContext ctx) {
                    called.set(true);
                    return ErrorEntry.builder().httpStatus(HttpStatus.CONFLICT).errorCode("SECOND").message("s").build();
                }

                @Override
                public int getOrder() {
                    return 1;
                }
            };
            GlobalExceptionHandler handler = createHandler(List.of(first, second));

            // when
            ResponseEntity<ErrorEntryDTO> response = handler.handleException(new RuntimeException("test"), mockRequest());

            // then
            assertThat(extractReason(response.getBody())).isEqualTo("FIRST");
            assertThat(called.get()).isFalse();
        }

        @Test
        @DisplayName("При отсутствии стратегий вызывается UnknownExceptionHandler")
        void noStrategies_fallsBackToUnknownHandler() {
            // given
            GlobalExceptionHandler handler = createHandler(Collections.emptyList());

            // when
            ResponseEntity<ErrorEntryDTO> response = handler.handleException(new RuntimeException("test"), mockRequest());

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(extractReason(response.getBody())).isEqualTo("UNKNOWN_INTERNAL_ERROR");
        }

        @Test
        @DisplayName("Стратегия с supports=false пропускается")
        void nonSupportingStrategy_skipped() {
            // given
            TopExceptionHandlingStrategy nonSupporting = stubStrategy(false, HttpStatus.OK, "SKIP");
            TopExceptionHandlingStrategy supporting = stubStrategy(true, HttpStatus.BAD_REQUEST, "HIT");
            GlobalExceptionHandler handler = createHandler(List.of(nonSupporting, supporting));

            // when
            ResponseEntity<ErrorEntryDTO> response = handler.handleException(new RuntimeException("test"), mockRequest());

            // then
            assertThat(extractReason(response.getBody())).isEqualTo("HIT");
        }

        @Test
        @DisplayName("Две стратегии с supports=true -- побеждает первая по порядку в списке")
        void firstByListOrder_wins_whenBothSupport() {
            // given
            var secondHandleCalled = new AtomicBoolean(false);
            TopExceptionHandlingStrategy high = stubStrategy(true, HttpStatus.BAD_REQUEST, "HIGH_PRIORITY");
            TopExceptionHandlingStrategy low = new TopExceptionHandlingStrategy() {
                @Override
                public boolean supports(@NonNull Exception e) {
                    return true;
                }

                @Override
                @NonNull
                public ErrorEntry handle(@NonNull Exception e, @NonNull ErrorHandlingContext ctx) {
                    secondHandleCalled.set(true);
                    return ErrorEntry.builder().httpStatus(HttpStatus.CONFLICT).errorCode("LOW").message("l").build();
                }

                @Override
                public int getOrder() {
                    return 100;
                }
            };
            GlobalExceptionHandler handler = createHandler(List.of(high, low));

            // when
            ResponseEntity<ErrorEntryDTO> response = handler.handleException(new RuntimeException("test"), mockRequest());

            // then
            assertThat(extractReason(response.getBody())).isEqualTo("HIGH_PRIORITY");
            assertThat(secondHandleCalled.get()).isFalse();
        }
    }

    @Nested
    @DisplayName("Кэш стратегий")
    class StrategyCache {

        @Test
        @DisplayName("Повторный вызов с тем же типом использует кэш -- supports() вызывается только один раз")
        void repeatedCall_usesCache() {
            // given
            var supportsCalls = new AtomicInteger(0);
            TopExceptionHandlingStrategy strategy = new TopExceptionHandlingStrategy() {
                @Override
                public boolean supports(@NonNull Exception e) {
                    supportsCalls.incrementAndGet();
                    return true;
                }

                @Override
                @NonNull
                public ErrorEntry handle(@NonNull Exception e, @NonNull ErrorHandlingContext ctx) {
                    return ErrorEntry.builder().httpStatus(HttpStatus.BAD_REQUEST).errorCode("TEST").message("t").build();
                }

                @Override
                public int getOrder() {
                    return 0;
                }
            };
            GlobalExceptionHandler handler = createHandler(List.of(strategy));

            // when
            handler.handleException(new IllegalArgumentException("first"), mockRequest());
            handler.handleException(new IllegalArgumentException("second"), mockRequest());
            handler.handleException(new IllegalArgumentException("third"), mockRequest());

            // then
            assertThat(supportsCalls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("После заполнения кэша handler продолжает корректно маршрутизировать исключения")
        void afterCacheFull_handlerStillRoutes() {
            // given
            TopExceptionHandlingStrategy strategy = stubStrategy(true, HttpStatus.BAD_REQUEST, "CACHED");
            GlobalExceptionHandler handler = createHandler(List.of(strategy));

            // when / then
            for (int i = 0; i < 260; i++) {
                Exception ex = createDistinctException(i);
                ResponseEntity<ErrorEntryDTO> response = handler.handleException(ex, mockRequest());
                assertThat(extractReason(response.getBody())).isEqualTo("CACHED");
            }
        }

        private Exception createDistinctException(int index) {
            try (
                    DynamicType.Unloaded<? extends RuntimeException> unloaded =
                            new ByteBuddy()
                                    .subclass(RuntimeException.class)
                                    .name("DynException" + index)
                                    .make()
            ) {
                Class<? extends Exception> cls = unloaded.load(getClass().getClassLoader()).getLoaded();
                return cls.getConstructor(String.class).newInstance("test-" + index);
            } catch (Exception e) {
                throw new AssertionError("Failed to create DynException" + index, e);
            }
        }
    }

    @Nested
    @DisplayName("Формат ответа")
    class ResponseFormat {

        @Test
        @DisplayName("Content-Type = application/problem+json;version=1")
        void response_hasCorrectContentType() {
            // given
            GlobalExceptionHandler handler = createHandler(
                    List.of(stubStrategy(true, HttpStatus.BAD_REQUEST, "TEST"))
            );

            // when
            ResponseEntity<ErrorEntryDTO> response = handler.handleException(new RuntimeException("test"), mockRequest());

            // then
            assertThat(response.getHeaders().getContentType())
                    .isEqualTo(MediaType.parseMediaType("application/problem+json;version=1"));
        }

        @Test
        @DisplayName("X-Correlation-Id заголовок из запроса")
        void response_containsCorrelationIdHeader() {
            // given
            GlobalExceptionHandler handler = createHandler(
                    List.of(stubStrategy(true, HttpStatus.BAD_REQUEST, "TEST"))
            );

            // when
            ResponseEntity<ErrorEntryDTO> response = handler.handleException(new RuntimeException("test"), mockRequest());

            // then
            assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
        }

        @Test
        @DisplayName("domain = applicationName при пустом domain в ErrorEntry")
        void response_setsDomainFromApplicationName() {
            // given
            GlobalExceptionHandler handler = createHandler(
                    List.of(stubStrategy(true, HttpStatus.BAD_REQUEST, "TEST"))
            );

            // when
            ResponseEntity<ErrorEntryDTO> response = handler.handleException(new RuntimeException("test"), mockRequest());

            // then
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getDomain()).isEqualTo(APP_NAME);
        }
    }

    @Nested
    @DisplayName("Safety-net ответ")
    class SafetyNetResponse {

        @Test
        @DisplayName("При критической ошибке обработчика -- 500 с фиксированным ответом")
        void handlerError_returnsSafetyNetResponse() {
            // given
            TopExceptionHandlingStrategy failingStrategy = new TopExceptionHandlingStrategy() {
                @Override
                public boolean supports(@NonNull Exception e) {
                    return true;
                }

                @Override
                @NonNull
                public ErrorEntry handle(@NonNull Exception e, @NonNull ErrorHandlingContext ctx) {
                    throw new RuntimeException("handler crash");
                }

                @Override
                public int getOrder() {
                    return 0;
                }
            };
            GlobalExceptionHandler handler = createHandler(List.of(failingStrategy));

            // when
            ResponseEntity<ErrorEntryDTO> response = handler.handleException(
                    new RuntimeException("original"), mockRequest()
            );

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(extractReason(response.getBody())).isEqualTo("INTERNAL");
            assertThat(response.getBody().getMessage()).isEqualTo("An unexpected error occurred");
            assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
        }

        @Test
        @DisplayName("CWE-209: safety-net domain по умолчанию — generic 'internal', не имя сервиса")
        void safetyNet_doesNotExposeApplicationNameByDefault() {
            // given
            TopExceptionHandlingStrategy failingStrategy = new TopExceptionHandlingStrategy() {
                @Override
                public boolean supports(@NonNull Exception e) {
                    return true;
                }

                @Override
                @NonNull
                public ErrorEntry handle(@NonNull Exception e, @NonNull ErrorHandlingContext ctx) {
                    throw new RuntimeException("handler crash");
                }

                @Override
                public int getOrder() {
                    return 0;
                }
            };
            GlobalExceptionHandler handler = createHandler(List.of(failingStrategy));

            // when
            ResponseEntity<ErrorEntryDTO> response = handler.handleException(
                    new RuntimeException("original"), mockRequest()
            );

            // then
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getDomain())
                    .as("safety-net domain должен быть generic при дефолте expose-application-name=false")
                    .isEqualTo("internal")
                    .isNotEqualTo(APP_NAME);
        }
    }

    @Nested
    @DisplayName("Цепочка enricher'ов")
    class EnricherChain {

        @Test
        @DisplayName("Enricher'ы вызываются по порядку и модифицируют ErrorEntry")
        void enrichers_calledInOrderAndModifyEntry() {
            // given
            ErrorEntryEnricher first = (entry, ctx) ->
                    entry.toBuilder().reason("enriched-by-first").build();

            ErrorEntryEnricher second = (entry, ctx) ->
                    entry.toBuilder().domain("enriched-domain").build();

            GlobalExceptionHandler handler = createHandler(
                    List.of(stubStrategy(true, HttpStatus.BAD_REQUEST, "TEST")),
                    List.of(first, second),
                    new NoopExceptionMetricRecorder()
            );

            // when
            ResponseEntity<ErrorEntryDTO> response = handler.handleException(
                    new RuntimeException("test"), mockRequest()
            );

            // then
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getDomain()).isEqualTo("enriched-domain");
        }
    }

    @Nested
    @DisplayName("Запись метрик")
    class MetricsRecording {

        @Test
        @DisplayName("Классифицированное исключение записывается как handled")
        void classifiedException_recordedAsHandled() {
            // given
            var snapshots = new ArrayList<ExceptionMetricSnapshot>();
            ExceptionMetricRecorder recorder = new ExceptionMetricRecorder() {
                @Override
                public void recordHandled(@NonNull ExceptionMetricSnapshot s) {
                    snapshots.add(s);
                }

                @Override
                public void recordUnhandled(@NonNull ExceptionMetricSnapshot s) {
                }
            };
            GlobalExceptionHandler handler = createHandler(
                    List.of(stubStrategy(true, HttpStatus.BAD_REQUEST, "TEST")),
                    Collections.emptyList(), recorder);

            // when
            handler.handleException(new RuntimeException("test"), mockRequest());

            // then
            assertThat(snapshots).hasSize(1);
            assertThat(snapshots.getFirst().strategyName()).isNotEqualTo("unknown");
        }

        @Test
        @DisplayName("Неклассифицированное исключение записывается как unhandled")
        void unclassifiedException_recordedAsUnhandled() {
            // given
            var snapshots = new ArrayList<ExceptionMetricSnapshot>();
            ExceptionMetricRecorder recorder = new ExceptionMetricRecorder() {
                @Override
                public void recordHandled(@NonNull ExceptionMetricSnapshot s) {
                }

                @Override
                public void recordUnhandled(@NonNull ExceptionMetricSnapshot s) {
                    snapshots.add(s);
                }
            };
            GlobalExceptionHandler handler = createHandler(
                    Collections.emptyList(), Collections.emptyList(), recorder
            );

            // when
            handler.handleException(new RuntimeException("test"), mockRequest());

            // then
            assertThat(snapshots).hasSize(1);
        }
    }

    private static String extractReason(ErrorEntryDTO dto) {
        if (dto == null) {
            return null;
        }

        return dto.getErrorCode();
    }

    private static TopExceptionHandlingStrategy stubStrategy(boolean supports, HttpStatus status, String errorCode) {
        return new TopExceptionHandlingStrategy() {
            @Override
            public boolean supports(@NonNull Exception e) {
                return supports;
            }

            @Override
            @NonNull
            public ErrorEntry handle(@NonNull Exception e, @NonNull ErrorHandlingContext ctx) {
                ErrorInfoDetail info = ErrorInfoDetail.builder()
                        .reason(errorCode)
                        .build();

                return ErrorEntry.builder()
                        .httpStatus(status)
                        .errorCode(errorCode)
                        .message("test message")
                        .details(List.of(info))
                        .build();
            }

            @Override
            public int getOrder() {
                return 0;
            }
        };
    }

}
