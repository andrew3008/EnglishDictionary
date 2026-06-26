package space.br1440.platform.errorhandling.core.handler;

import io.micrometer.context.ContextRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;
import space.br1440.platform.errorhandling.autoconfigure.MdcThreadLocalAccessor;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingMdcKeys;
import space.br1440.platform.errorhandling.extension.ErrorEntryEnricher;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricSnapshot;
import space.br1440.platform.errorhandling.metrics.NoopExceptionMetricRecorder;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.properties.LoggingStrategy;
import space.br1440.platform.errorhandling.webflux.properties.ErrorHandlingEnricherProperties;
import space.br1440.platform.errorhandling.webflux.testing.handler.ReactiveGlobalExceptionHandlerTestFixture;
import space.br1440.platform.errorhandling.model.ErrorEntry;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReactiveGlobalExceptionHandler — Enricher boundedElastic/timeout (C2)")
class ReactiveGlobalExceptionHandlerEnricherTimeoutTest {

    @BeforeAll
    static void registerAccessor() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new MdcThreadLocalAccessor());
        reactor.core.publisher.Hooks.enableAutomaticContextPropagation();
    }

    @AfterAll
    static void resetHooks() {
        reactor.core.publisher.Hooks.disableAutomaticContextPropagation();
    }

    @BeforeEach
    void clearMdc() {
        MDC.clear();
    }

    private static ReactiveGlobalExceptionHandler createHandler(@NonNull ErrorHandlingProperties properties,
                                                                @NonNull ErrorHandlingEnricherProperties enricherProperties,
                                                                @NonNull List<ErrorEntryEnricher> enrichers,
                                                                @NonNull ExceptionMetricRecorder recorder) {
        return ReactiveGlobalExceptionHandlerTestFixture.builder()
                .properties(properties)
                .enricherProperties(enricherProperties)
                .strategies(List.of(noopBadRequestStrategy()))
                .unknownHandler(fixedUnknownHandler())
                .recorder(recorder)
                .entryEnrichers(enrichers)
                .applicationName("test-app")
                .build();
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/c2")
                        .header("X-Correlation-Id", "c2-corr-id")
                        .build());
    }

    @Test
    @DisplayName("Enricher, превышающий timeout, обрабатывается как fail-open: возвращается необогащённый entry")
    void slowEnricher_timesOut_returnsUnenrichedEntry() {
        // given
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);
        ErrorHandlingEnricherProperties enricherProps = new ErrorHandlingEnricherProperties().setTimeoutMs(50L);

        ErrorEntryEnricher slow = (entry, ctx) -> {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            return entry.toBuilder().reason("should-not-be-applied").build();
        };

        AtomicReference<Class<?>> recordedTimeout = new AtomicReference<>();
        ExceptionMetricRecorder recorder = new ExceptionMetricRecorder() {
            @Override
            public void recordHandled(@NonNull ExceptionMetricSnapshot s) {
            }

            @Override
            public void recordUnhandled(@NonNull ExceptionMetricSnapshot s) {
            }

            @Override
            public void recordEnricherTimeout(@NonNull Class<?> enricherType) {
                recordedTimeout.set(enricherType);
            }
        };

        ReactiveGlobalExceptionHandler handler = createHandler(properties, enricherProps, List.of(slow), recorder);
        MockServerWebExchange ex = exchange();

        // when
        long startMs = System.currentTimeMillis();
        StepVerifier.create(handler.handle(ex, new RuntimeException("boom")))
                .verifyComplete();
        long elapsedMs = System.currentTimeMillis() - startMs;

        // then
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String body = ex.getResponse().getBodyAsString().block();
        assertThat(body).doesNotContain("should-not-be-applied");

        assertThat(recordedTimeout.get())
                .as("recordEnricherTimeout должен быть вызван с классом slow-enricher")
                .isNotNull()
                .isEqualTo(slow.getClass());

        assertThat(elapsedMs)
                .as("handle() не должен ждать полный sleep enricher (500ms) — должен оборваться timeout-ом")
                .isLessThan(450L);
    }

    @Test
    @DisplayName("Быстрый enricher применяется штатно (timeout не срабатывает)")
    void fastEnricher_isApplied() {
        // given
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);
        ErrorHandlingEnricherProperties enricherProps = new ErrorHandlingEnricherProperties().setTimeoutMs(500L);

        ErrorEntryEnricher fast = (entry, ctx) ->
                entry.toBuilder().reason("fast-enriched").build();

        ReactiveGlobalExceptionHandler handler = createHandler(properties, enricherProps, List.of(fast), new NoopExceptionMetricRecorder());
        MockServerWebExchange ex = exchange();

        // when
        StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

        // then
        String body = ex.getResponse().getBodyAsString().block();
        assertThat(body).contains("fast-enriched");
    }

    @Test
    @DisplayName("Enricher выполняется на boundedElastic (sync SPI фиксирован в handler)")
    void enricher_runsOnBoundedElastic_syncSpi() throws InterruptedException {
        // given
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);
        ErrorHandlingEnricherProperties enricherProps = new ErrorHandlingEnricherProperties().setTimeoutMs(1_000L);

        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch enricherLatch = new CountDownLatch(1);
        ErrorEntryEnricher capturing = (entry, ctx) -> {
            threadName.set(Thread.currentThread().getName());
            enricherLatch.countDown();
            return entry;
        };

        ReactiveGlobalExceptionHandler handler = createHandler(
                properties, enricherProps, List.of(capturing), new NoopExceptionMetricRecorder()
        );

        MockServerWebExchange ex = exchange();

        // when
        StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();
        assertThat(enricherLatch.await(2, TimeUnit.SECONDS))
                .as("Enricher должен быть выполнен в пределах 2 секунд")
                .isTrue();

        // then
        assertThat(threadName.get())
                .as("Синхронный enrich исполняется на boundedElastic-потоке")
                .isNotNull()
                .containsIgnoringCase("boundedElastic");
    }

    @Test
    @DisplayName("Enricher на boundedElastic видит MDC, помещённый handler'ом в Reactor Context (Hooks + ThreadLocalAccessor)")
    void enricher_seesMdc_onBoundedElasticThread() {
        // given
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);
        ErrorHandlingEnricherProperties enricherProps = new ErrorHandlingEnricherProperties().setTimeoutMs(1_000L);

        AtomicReference<String> seenCorrelationId = new AtomicReference<>();
        AtomicReference<String> seenExceptionType = new AtomicReference<>();
        AtomicReference<String> seenThread = new AtomicReference<>();
        ErrorEntryEnricher mdcCapturing = (entry, ctx) -> {
            seenCorrelationId.set(MDC.get(ErrorHandlingMdcKeys.CORRELATION_ID));
            seenExceptionType.set(MDC.get(ErrorHandlingMdcKeys.EXCEPTION_TYPE));
            seenThread.set(Thread.currentThread().getName());
            return entry;
        };

        ReactiveGlobalExceptionHandler handler = createHandler(
                properties, enricherProps, List.of(mdcCapturing), new NoopExceptionMetricRecorder());
        MockServerWebExchange ex = exchange();

        // when
        StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

        // then
        assertThat(seenThread.get())
                .as("Sanity-check: enricher действительно убегал на boundedElastic-пул")
                .isNotNull()
                .containsIgnoringCase("boundedElastic");
        assertThat(seenCorrelationId.get())
                .as("correlation_id (ErrorHandlingMdcKeys.CORRELATION_ID) должен быть пропагирован на boundedElastic через ThreadLocalAccessor")
                .isEqualTo("c2-corr-id");
        assertThat(seenExceptionType.get())
                .as("exceptionType (ErrorHandlingMdcKeys.EXCEPTION_TYPE) тоже должен присутствовать в MDC на новом потоке")
                .isEqualTo(RuntimeException.class.getName());
    }

    @Test
    @DisplayName("Цепочка enrichers выполняется последовательно: следующий видит результат предыдущего")
    void enricherChain_executesSequentially() {
        // given
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);
        ErrorHandlingEnricherProperties enricherProps = new ErrorHandlingEnricherProperties().setTimeoutMs(1_000L);

        AtomicReference<String> secondSawDomain = new AtomicReference<>();
        ErrorEntryEnricher first = (entry, ctx) -> entry.toBuilder().domain("from-first").build();
        ErrorEntryEnricher second = (entry, ctx) -> {
            secondSawDomain.set(entry.getDomain());
            return entry.toBuilder().reason("from-second").build();
        };

        ReactiveGlobalExceptionHandler handler = createHandler(
                properties, enricherProps, List.of(first, second), new NoopExceptionMetricRecorder());
        MockServerWebExchange ex = exchange();

        // when
        StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

        // then
        assertThat(secondSawDomain.get()).isEqualTo("from-first");
        String body = ex.getResponse().getBodyAsString().block();
        assertThat(body).contains("from-first");
        assertThat(body).contains("from-second");
    }

    @Test
    @DisplayName("Падение enricher с исключением — fail-open: оставшаяся цепочка отрабатывает на исходном entry")
    void enricherException_isolatedAsFailOpen() {
        // given
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);
        ErrorHandlingEnricherProperties enricherProps = new ErrorHandlingEnricherProperties().setTimeoutMs(1_000L);

        AtomicInteger thirdCallCount = new AtomicInteger(0);
        ErrorEntryEnricher first = (entry, ctx) -> entry.toBuilder().domain("d1").build();
        ErrorEntryEnricher middle = (entry, ctx) -> {
            throw new RuntimeException("middle blew up");
        };
        ErrorEntryEnricher third = (entry, ctx) -> {
            thirdCallCount.incrementAndGet();
            return entry.toBuilder().reason("r3").build();
        };

        ReactiveGlobalExceptionHandler handler = createHandler(
                properties, enricherProps, List.of(first, middle, third), new NoopExceptionMetricRecorder());
        MockServerWebExchange ex = exchange();

        // when
        StepVerifier.create(handler.handle(ex, new RuntimeException("x"))).verifyComplete();

        // then
        assertThat(thirdCallCount.get())
                .as("Третий enricher должен быть вызван даже если middle упал")
                .isEqualTo(1);
        String body = ex.getResponse().getBodyAsString().block();
        assertThat(body).contains("d1");
        assertThat(body).contains("r3");
    }

    private static TopExceptionHandlingStrategy noopBadRequestStrategy() {
        return new TopExceptionHandlingStrategy() {
            @Override
            public boolean supports(@NonNull Exception exception) {
                return true;
            }

            @Override
            @NonNull
            public ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext ctx) {
                return ErrorEntry.builder()
                        .httpStatus(HttpStatus.BAD_REQUEST)
                        .errorCode("C2_TEST")
                        .message("test")
                        .build();
            }

            @Override
            @NonNull
            public LogPolicy logPolicy(@NonNull Exception exception, @NonNull ErrorEntry e) {
                return LogPolicy.WARN;
            }

            @Override
            public int getOrder() {
                return 0;
            }
        };
    }

    private static UnknownExceptionHandler fixedUnknownHandler() {
        return new UnknownExceptionHandler() {
            @Override
            public @NotNull ErrorEntry handle(@NotNull Exception exception, @NotNull ErrorHandlingContext context) {
                return ErrorEntry.builder()
                        .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .errorCode("UNKNOWN")
                        .message("Unexpected")
                        .build();
            }

            @Override
            public @NotNull LogPolicy logPolicy(@NotNull Exception exception, @NotNull ErrorEntry errorEntry) {
                return LogPolicy.ERROR;
            }
        };
    }
}
