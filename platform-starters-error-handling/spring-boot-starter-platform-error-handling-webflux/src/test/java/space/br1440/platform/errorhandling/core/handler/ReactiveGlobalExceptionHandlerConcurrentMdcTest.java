package space.br1440.platform.errorhandling.core.handler;

import io.micrometer.context.ContextRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Hooks;
import space.br1440.platform.errorhandling.autoconfigure.MdcThreadLocalAccessor;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.properties.LoggingStrategy;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.webflux.testing.handler.ReactiveGlobalExceptionHandlerTestFixture;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReactiveGlobalExceptionHandler — concurrent MDC isolation")
class ReactiveGlobalExceptionHandlerConcurrentMdcTest {

    private static final int THREADS = 16;
    private static final int REQUESTS_PER_THREAD = 200;

    private ReactiveGlobalExceptionHandler handler;
    private ThreadLocalCapturingStrategy capturingStrategy;

    @BeforeEach
    void setUp() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new MdcThreadLocalAccessor());
        Hooks.enableAutomaticContextPropagation();

        MDC.clear();

        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);

        capturingStrategy = new ThreadLocalCapturingStrategy();

        handler = ReactiveGlobalExceptionHandlerTestFixture.builder()
                .properties(properties)
                .strategies(List.of(capturingStrategy))
                .unknownHandler(new TestUnknownHandler())
                .applicationName("concurrent-mdc-test-app")
                .build();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("16 потоков × 200 запросов — нулевая утечка MDC между запросами")
    void mdc_isolated_under_concurrent_load() throws InterruptedException {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(THREADS);
        AtomicInteger crossTalk = new AtomicInteger();
        AtomicInteger leakedAfter = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(THREADS)) {
            for (int t = 0; t < THREADS; t++) {
                final int threadIdx = t;
                executor.submit(() -> {
                    try {
                        startGate.await();
                        for (int requestId = 0; requestId < REQUESTS_PER_THREAD; requestId++) {
                            String expectedCorrelationId = "thread-" + threadIdx + "-req-" + requestId;

                            MockServerWebExchange exchange = MockServerWebExchange.from(
                                    MockServerHttpRequest.get("/api/concurrent")
                                            .header("X-Correlation-Id", expectedCorrelationId)
                                            .build()
                            );

                            handler.handle(exchange, new IllegalStateException("err-" + requestId))
                                    .block();

                            // Проверка 1: стратегия видела свой correlationId, не чужой.
                            // Используем ThreadLocal — каждый поток читает только своё значение,
                            // что исключает race между observer и параллельным writer.
                            String observed = capturingStrategy.lastObserved();
                            if (observed != null && !expectedCorrelationId.equals(observed)) {
                                crossTalk.incrementAndGet();
                            }

                            // Проверка 2: после block() MDC текущего worker-потока чист.
                            if (MDC.get("correlationId") != null
                                    || MDC.get("error_status") != null
                                    || MDC.get("error_code") != null) {
                                leakedAfter.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }

            startGate.countDown();
            assertThat(finishLatch.await(60, TimeUnit.SECONDS))
                    .as("Все потоки должны завершиться за 60 секунд")
                    .isTrue();
        }

        assertThat(crossTalk.get())
                .as("Cross-talk MDC между параллельными запросами недопустим")
                .isZero();
        assertThat(leakedAfter.get())
                .as("Утечка MDC в worker-thread после завершения handle() недопустима")
                .isZero();
    }

    private static class ThreadLocalCapturingStrategy implements TopExceptionHandlingStrategy {

        private final ThreadLocal<String> lastObservedPerThread = new ThreadLocal<>();

        String lastObserved() {
            return lastObservedPerThread.get();
        }

        @Override
        public boolean supports(@NonNull Exception exception) {
            return true;
        }

        @Override
        @NonNull
        public ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
            lastObservedPerThread.set(MDC.get("correlationId"));
            return ErrorEntry.builder()
                    .httpStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                    .errorCode("CONCURRENT_TEST")
                    .message(exception.getMessage())
                    .build();
        }

        @Override
        @NonNull
        public LogPolicy logPolicy(@NonNull Exception exception, @NonNull ErrorEntry entry) {
            return LogPolicy.WARN;
        }

        @Override
        public int getOrder() {
            return 0;
        }
    }

    private static class TestUnknownHandler implements UnknownExceptionHandler {

        @Override
        @NonNull
        public @NotNull ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
            return ErrorEntry.builder()
                    .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .errorCode("UNKNOWN")
                    .message("unknown")
                    .build();
        }
    }
}
