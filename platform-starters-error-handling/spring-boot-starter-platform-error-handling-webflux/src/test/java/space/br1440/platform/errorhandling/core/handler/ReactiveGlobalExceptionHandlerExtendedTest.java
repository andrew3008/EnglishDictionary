package space.br1440.platform.errorhandling.core.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;
import space.br1440.platform.errorhandling.extension.ErrorEntryEnricher;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricSnapshot;
import space.br1440.platform.errorhandling.webflux.properties.ErrorHandlingEnricherProperties;
import space.br1440.platform.errorhandling.webflux.properties.ErrorHandlingSafetyNetProperties;
import space.br1440.platform.errorhandling.metrics.NoopExceptionMetricRecorder;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.properties.LoggingStrategy;
import space.br1440.platform.errorhandling.webflux.testing.handler.ReactiveGlobalExceptionHandlerTestFixture;
import space.br1440.platform.errorhandling.webflux.testing.support.TestUnknownExceptionHandler;
import space.br1440.platform.errorhandling.model.ErrorEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReactiveGlobalExceptionHandler — extended")
class ReactiveGlobalExceptionHandlerExtendedTest {

    @BeforeEach
    void clearMdc() {
        MDC.clear();
    }

    private static ReactiveGlobalExceptionHandler createHandler(List<TopExceptionHandlingStrategy> strategies) {
        return createHandler(strategies, Collections.emptyList(), new NoopExceptionMetricRecorder());
    }

    private static ReactiveGlobalExceptionHandler createHandler(List<TopExceptionHandlingStrategy> strategies,
                                                                List<ErrorEntryEnricher> enrichers,
                                                                ExceptionMetricRecorder metricRecorder) {
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);
        return ReactiveGlobalExceptionHandlerTestFixture.builder()
                .properties(properties)
                .strategies(strategies)
                .recorder(metricRecorder)
                .entryEnrichers(enrichers)
                .applicationName("test-app")
                .unknownHandler(new TestUnknownExceptionHandler())
                .build();
    }

    private static ReactiveGlobalExceptionHandler createHandlerWithLogging(LoggingStrategy strategy) {
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(strategy);
        return ReactiveGlobalExceptionHandlerTestFixture.builder()
                .properties(properties)
                .strategies(List.of(stubStrategy(true, HttpStatus.BAD_REQUEST, "TEST")))
                .applicationName("test-app")
                .unknownHandler(new TestUnknownExceptionHandler())
                .build();
    }

    private static MockServerWebExchange mockExchange() {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Correlation-Id", "test-corr-id")
                        .build());
    }

    @Nested
    @DisplayName("Strategy dispatch")
    class StrategyDispatch {

        @Test
        @DisplayName("Первая стратегия с supports=true побеждает")
        void firstSupportingStrategy_wins() {
            // given
            var secondCalled = new AtomicBoolean(false);
            TopExceptionHandlingStrategy first = stubStrategy(true, HttpStatus.BAD_REQUEST, "FIRST");
            TopExceptionHandlingStrategy second = new TopExceptionHandlingStrategy() {
                @Override
                public boolean supports(@NonNull Exception e) {
                    return true;
                }

                @Override
                @NonNull
                public ErrorEntry handle(@NonNull Exception e, @NonNull ErrorHandlingContext ctx) {
                    secondCalled.set(true);
                    return ErrorEntry.builder().httpStatus(HttpStatus.CONFLICT).errorCode("SECOND").message("s").build();
                }

                @Override
                public int getOrder() {
                    return 1;
                }
            };
            ReactiveGlobalExceptionHandler handler = createHandler(List.of(first, second));
            MockServerWebExchange exchange = mockExchange();

            // when
            StepVerifier.create(handler.handle(exchange, new RuntimeException("test")))
                    .verifyComplete();

            // then
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(secondCalled.get()).isFalse();
        }

        @Test
        @DisplayName("Две стратегии с supports=true — побеждает первая по порядку в списке")
        void firstByOrder_wins_whenBothSupport() {
            // given
            AtomicBoolean lowPriorityCalled = new AtomicBoolean(false);
            TopExceptionHandlingStrategy highPriority = stubStrategy(true, HttpStatus.BAD_REQUEST, "HIGH");
            TopExceptionHandlingStrategy lowPriority = new TopExceptionHandlingStrategy() {
                @Override
                public boolean supports(@NonNull Exception e) {
                    return true;
                }

                @Override
                @NonNull
                public ErrorEntry handle(@NonNull Exception e, @NonNull ErrorHandlingContext ctx) {
                    lowPriorityCalled.set(true);
                    return ErrorEntry.builder().httpStatus(HttpStatus.CONFLICT).errorCode("LOW").message("l").build();
                }

                @Override
                public int getOrder() {
                    return 10;
                }
            };
            ReactiveGlobalExceptionHandler handler = createHandler(List.of(highPriority, lowPriority));
            MockServerWebExchange exchange = mockExchange();

            // when
            StepVerifier.create(handler.handle(exchange, new RuntimeException("test")))
                    .verifyComplete();

            // then
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(lowPriorityCalled.get()).isFalse();
        }

        @Test
        @DisplayName("Нет подходящей стратегии — вызывается UnknownExceptionHandler, HTTP 500")
        void noStrategy_unknownHandler() {
            // given
            ReactiveGlobalExceptionHandler handler = createHandler(Collections.emptyList());
            MockServerWebExchange exchange = mockExchange();

            // when
            StepVerifier.create(handler.handle(exchange, new RuntimeException("test")))
                    .verifyComplete();

            // then
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("Повторный вызов с тем же типом использует кеш — supports() один раз")
        void cachedStrategy_usedOnSecondCall() {
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
                    return ErrorEntry.builder().httpStatus(HttpStatus.BAD_REQUEST).errorCode("CACHED").message("c").build();
                }

                @Override
                public int getOrder() {
                    return 0;
                }
            };
            ReactiveGlobalExceptionHandler handler = createHandler(List.of(strategy));

            // when
            for (int i = 0; i < 3; i++) {
                MockServerWebExchange exchange = mockExchange();
                StepVerifier.create(handler.handle(exchange, new IllegalArgumentException("call-" + i)))
                        .verifyComplete();
            }

            // then
            assertThat(supportsCalls.get())
                    .as("supports() вызывается один раз, далее кеш")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Enrichers")
    class Enrichers {

        @Test
        @DisplayName("Цепочка enrichers применяется по порядку и модифицирует entry")
        void enrichers_appliedInOrder() {
            // given
            ErrorEntryEnricher first = (entry, ctx) ->
                    entry.toBuilder().reason("enriched-by-first").build();

            ErrorEntryEnricher second = (entry, ctx) ->
                    entry.toBuilder().domain("enriched-domain").build();

            ReactiveGlobalExceptionHandler handler = createHandler(
                    List.of(stubStrategy(true, HttpStatus.BAD_REQUEST, "TEST")),
                    List.of(first, second),
                    new NoopExceptionMetricRecorder()
            );
            MockServerWebExchange exchange = mockExchange();

            // when
            StepVerifier.create(handler.handle(exchange, new RuntimeException("test")))
                    .verifyComplete();

            // then
            String body = exchange.getResponse().getBodyAsString().block();
            assertThat(body).contains("enriched-domain");
        }

        @Test
        @DisplayName("Пустой список enrichers не ломает handler")
        void noEnrichers_entryUnchanged() {
            // given
            ReactiveGlobalExceptionHandler handler = createHandler(
                    List.of(stubStrategy(true, HttpStatus.BAD_REQUEST, "ORIGINAL")),
                    Collections.emptyList(),
                    new NoopExceptionMetricRecorder()
            );
            MockServerWebExchange exchange = mockExchange();

            // when
            StepVerifier.create(handler.handle(exchange, new RuntimeException("test")))
                    .verifyComplete();

            // then
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("Metrics")
    class Metrics {

        @Test
        @DisplayName("Классифицированное исключение записывается как handled")
        void handledException_recordsHandled() {
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
            ReactiveGlobalExceptionHandler handler = createHandler(
                    List.of(stubStrategy(true, HttpStatus.BAD_REQUEST, "TEST")),
                    Collections.emptyList(), recorder);
            MockServerWebExchange exchange = mockExchange();

            // when
            StepVerifier.create(handler.handle(exchange, new RuntimeException("test")))
                    .verifyComplete();

            // then
            assertThat(snapshots).hasSize(1);
            assertThat(snapshots.getFirst().strategyName()).isNotEqualTo("unknown");
        }

        @Test
        @DisplayName("Неклассифицированное исключение записывается как unhandled")
        void unhandledException_recordsUnhandled() {
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
            ReactiveGlobalExceptionHandler handler = createHandler(
                    Collections.emptyList(), Collections.emptyList(), recorder
            );
            MockServerWebExchange exchange = mockExchange();

            // when
            StepVerifier.create(handler.handle(exchange, new RuntimeException("test")))
                    .verifyComplete();

            // then
            assertThat(snapshots).hasSize(1);
        }

        @Test
        @DisplayName("rootCauseType отличается от exceptionType при cause chain > 1")
        void snapshot_rootCauseType_differs() {
            // given — исключение с cause chain
            var snapshots = new ArrayList<ExceptionMetricSnapshot>();
            ExceptionMetricRecorder recorder = new ExceptionMetricRecorder() {
                @Override
                public void recordHandled(@NonNull ExceptionMetricSnapshot s) {
                    snapshots.add(s);
                }

                @Override
                public void recordUnhandled(@NonNull ExceptionMetricSnapshot s) {
                    snapshots.add(s);
                }
            };
            ReactiveGlobalExceptionHandler handler = createHandler(
                    List.of(stubStrategy(true, HttpStatus.BAD_REQUEST, "TEST")),
                    Collections.emptyList(), recorder
            );
            MockServerWebExchange exchange = mockExchange();
            Exception nested = new RuntimeException("top", new IllegalStateException("root cause"));

            // when
            StepVerifier.create(handler.handle(exchange, nested))
                    .verifyComplete();

            // then
            assertThat(snapshots).hasSize(1);
            assertThat(snapshots.getFirst().rootCauseType())
                    .as("rootCauseType — FQCN глубинной причины")
                    .isEqualTo(IllegalStateException.class.getName());
        }
    }

    @Nested
    @DisplayName("Safety-net")
    class SafetyNet {

        @Test
        @DisplayName("Критическая ошибка в стратегии — 500 с фиксированным ответом")
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
            ReactiveGlobalExceptionHandler handler = createHandler(List.of(failingStrategy));
            MockServerWebExchange exchange = mockExchange();

            // when
            StepVerifier.create(handler.handle(exchange, new RuntimeException("original")))
                    .verifyComplete();

            // then
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(exchange.getResponse().getHeaders().getFirst("X-Correlation-Id"))
                    .isEqualTo("test-corr-id");

            String body = exchange.getResponse().getBodyAsString().block();
            assertThat(body).contains("An unexpected error occurred");
        }

        @Test
        @DisplayName("expose-application-name=false (default) — domain не утекает (CWE-209)")
        void safetyNet_hidesApplicationName_byDefault() {
            // given
            TopExceptionHandlingStrategy failing = new TopExceptionHandlingStrategy() {
                @Override
                public boolean supports(@NonNull Exception e) {
                    return true;
                }

                @Override
                @NonNull
                public ErrorEntry handle(@NonNull Exception e, @NonNull ErrorHandlingContext ctx) {
                    throw new RuntimeException("crash");
                }

                @Override
                public int getOrder() {
                    return 0;
                }
            };
            ReactiveGlobalExceptionHandler handler = createHandler(List.of(failing));
            MockServerWebExchange exchange = mockExchange();

            // when
            StepVerifier.create(handler.handle(exchange, new RuntimeException("orig"))).verifyComplete();

            // then
            String body = exchange.getResponse().getBodyAsString().block();
            assertThat(body)
                    .as("application name must not leak in safety-net domain by default")
                    .doesNotContain("test-app");
            assertThat(body).contains("\"domain\":\"internal\"");
        }

        @Test
        @DisplayName("expose-application-name=true — domain содержит имя приложения")
        void safetyNet_exposesApplicationName_whenFlagEnabled() {
            // given
            ErrorHandlingProperties properties = new ErrorHandlingProperties();
            properties.setLoggingStrategy(LoggingStrategy.CATEGORY);
            ErrorHandlingSafetyNetProperties safetyNetProps = new ErrorHandlingSafetyNetProperties().setExposeApplicationName(true);

            TopExceptionHandlingStrategy failing = new TopExceptionHandlingStrategy() {
                @Override
                public boolean supports(@NonNull Exception e) {
                    return true;
                }

                @Override
                @NonNull
                public ErrorEntry handle(@NonNull Exception e, @NonNull ErrorHandlingContext ctx) {
                    throw new RuntimeException("crash");
                }

                @Override
                public int getOrder() {
                    return 0;
                }
            };

            ReactiveGlobalExceptionHandler handler = ReactiveGlobalExceptionHandlerTestFixture.builder()
                    .properties(properties)
                    .enricherProperties(new ErrorHandlingEnricherProperties())
                    .safetyNetProperties(safetyNetProps)
                    .strategies(List.of(failing))
                    .applicationName("test-app")
                    .unknownHandler(new TestUnknownExceptionHandler())
                    .build();
            MockServerWebExchange exchange = mockExchange();

            // when
            StepVerifier.create(handler.handle(exchange, new RuntimeException("orig"))).verifyComplete();

            // then
            String body = exchange.getResponse().getBodyAsString().block();
            assertThat(body).contains("\"domain\":\"test-app\"");
        }
    }

    @Nested
    @DisplayName("Logging")
    class Logging {

        @Test
        @DisplayName("CATEGORY — logByPolicy не бросает исключение")
        void categoryStrategy_logsByPolicy() {
            // given
            ReactiveGlobalExceptionHandler handler = createHandlerWithLogging(LoggingStrategy.CATEGORY);
            MockServerWebExchange exchange = mockExchange();

            // when
            StepVerifier.create(handler.handle(exchange, new RuntimeException("test")))
                    .verifyComplete();

            // then
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("ALL_DEBUG — логирование не бросает исключение")
        void allDebug_logsByDebug() {
            // given
            ReactiveGlobalExceptionHandler handler = createHandlerWithLogging(LoggingStrategy.ALL_DEBUG);
            MockServerWebExchange exchange = mockExchange();

            // when / then
            StepVerifier.create(handler.handle(exchange, new RuntimeException("test-debug")))
                    .verifyComplete();
        }

        @Test
        @DisplayName("ALL_ERROR — логирование не бросает исключение")
        void allError_logsByError() {
            // given
            ReactiveGlobalExceptionHandler handler = createHandlerWithLogging(LoggingStrategy.ALL_ERROR);
            MockServerWebExchange exchange = mockExchange();

            // when / then
            StepVerifier.create(handler.handle(exchange, new RuntimeException("test-error")))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Concurrent cache access")
    class ConcurrentCacheAccess {

        @Test
        @DisplayName("Параллельный доступ к кешу стратегий не теряет ответы")
        void concurrentAccess_cacheRemainsConsistent() throws Exception {
            // given
            TopExceptionHandlingStrategy strategy = stubStrategy(true, HttpStatus.BAD_REQUEST, "CONCURRENT");
            ReactiveGlobalExceptionHandler handler = createHandler(List.of(strategy));

            int threadCount = 8;
            int iterationsPerThread = 50;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            boolean finished;
            try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
                // when — все потоки стартуют одновременно через барьер
                for (int t = 0; t < threadCount; t++) {
                    final int threadIdx = t;
                    executor.submit(() -> {
                        try {
                            startLatch.await();

                            for (int i = 0; i < iterationsPerThread; i++) {
                                MockServerWebExchange exchange = MockServerWebExchange.from(
                                        MockServerHttpRequest.get("/concurrent-" + threadIdx + "-" + i).build()
                                );

                                StepVerifier.create(handler.handle(exchange, new IllegalArgumentException("t" + threadIdx)))
                                        .verifyComplete();

                                if (exchange.getResponse().getStatusCode() != null) {
                                    successCount.incrementAndGet();
                                }
                            }
                        } catch (Exception ignored) {
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                finished = doneLatch.await(10, TimeUnit.SECONDS);
            }

            // then
            assertThat(finished).as("Все потоки должны завершиться за 10 секунд").isTrue();
            assertThat(successCount.get()).isEqualTo(threadCount * iterationsPerThread);
        }
    }

    private static TopExceptionHandlingStrategy stubStrategy(boolean supports,
                                                             HttpStatus status,
                                                             String errorCode) {
        return new TopExceptionHandlingStrategy() {
            @Override
            public boolean supports(@NonNull Exception e) {
                return supports;
            }

            @Override
            @NonNull
            public ErrorEntry handle(@NonNull Exception e, @NonNull ErrorHandlingContext ctx) {
                return ErrorEntry.builder()
                        .httpStatus(status)
                        .errorCode(errorCode)
                        .message("test message")
                        .build();
            }

            @Override
            public int getOrder() {
                return 0;
            }
        };
    }
}
