package space.br1440.platform.tracing.autoconfigure.reactive.spike;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPIKE (CP-1 §21 R2-C3) — исполняемая проверка семантики поддерживаемого reactive correlation API.
 *
 * <p>Матрица: downstream visibility, concurrent subscriber isolation, retry/repeat, nested assignment,
 * publishOn/subscribeOn, complete/error/cancel, отсутствие переноса OTel Scope через async-границу,
 * MDC-проекция и birth-time projection в child-span (OTel bridge).</p>
 *
 * <p>Значение хранится как immutable {@code String} в Reactor Context; OTel {@code Scope} через
 * асинхронную границу не переносится.</p>
 */
class ReactiveCorrelationOperationsSpikeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ReactiveCorrelationOperations operations = ReactorCorrelationSupport.operations();

    private static Mono<String> readContextual() {
        return Mono.deferContextual(cv -> Mono.just(ReactorCorrelationSupport.read(cv).orElse("none")));
    }

    @Test
    void значение_видно_downstream_операторам() {
        String result = operations.withCorrelationId("biz-1", readContextual()).block(TIMEOUT);
        assertThat(result).isEqualTo("biz-1");
    }

    @Test
    void отсутствие_привязки_даёт_пустой_контекст_без_утечки() {
        String result = readContextual().block(TIMEOUT);
        assertThat(result).isEqualTo("none");
    }

    @Test
    void конкурентные_подписчики_изолированы() {
        Mono<String> a = operations.withCorrelationId("A", readContextual().subscribeOn(Schedulers.parallel()));
        Mono<String> b = operations.withCorrelationId("B", readContextual().subscribeOn(Schedulers.parallel()));

        Tuple2<String, String> result = Mono.zip(a, b).block(TIMEOUT);

        assertThat(result.getT1()).isEqualTo("A");
        assertThat(result.getT2()).isEqualTo("B");
    }

    @Test
    void retry_сохраняет_привязку_на_ресубскрипции() {
        AtomicInteger attempts = new AtomicInteger();
        Mono<String> execution = Mono.defer(() -> Mono.deferContextual(cv -> {
            String cid = ReactorCorrelationSupport.read(cv).orElse("none");
            if (attempts.getAndIncrement() == 0) {
                return Mono.error(new IllegalStateException("boom on first attempt: " + cid));
            }
            return Mono.just(cid);
        })).retry(1);

        String result = operations.withCorrelationId("R", execution).block(TIMEOUT);

        assertThat(result).isEqualTo("R");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void repeat_сохраняет_привязку() {
        List<String> results = operations.withCorrelationId("P", readContextual().repeat(2))
                .collectList().block(TIMEOUT);
        assertThat(results).containsExactly("P", "P", "P");
    }

    @Test
    void вложенная_привязка_LIFO_внутренняя_перекрывает_только_своё_поддерево() {
        Mono<String> inner = operations.withCorrelationId("INNER", readContextual());
        Mono<Tuple2<String, String>> outer =
                operations.withCorrelationId("OUTER", Mono.zip(readContextual(), inner));

        Tuple2<String, String> result = outer.block(TIMEOUT);

        assertThat(result.getT1()).isEqualTo("OUTER");
        assertThat(result.getT2()).isEqualTo("INNER");
    }

    @Test
    void publishOn_смена_scheduler_не_теряет_контекст() {
        String result = operations.withCorrelationId("PUB",
                Mono.just("x").publishOn(Schedulers.parallel()).then(readContextual())).block(TIMEOUT);
        assertThat(result).isEqualTo("PUB");
    }

    @Test
    void subscribeOn_не_теряет_контекст() {
        String result = operations.withCorrelationId("SUB",
                readContextual().subscribeOn(Schedulers.boundedElastic())).block(TIMEOUT);
        assertThat(result).isEqualTo("SUB");
    }

    @Test
    void значение_видно_на_error_пути() {
        String result = operations.withCorrelationId("ERR",
                Mono.<String>error(new IllegalStateException("x"))
                        .onErrorResume(e -> readContextual())).block(TIMEOUT);
        assertThat(result).isEqualTo("ERR");
    }

    @Test
    void flux_вариант_проецирует_на_все_элементы() {
        List<String> results = operations.withCorrelationId("F",
                Flux.range(1, 3).flatMap(i -> readContextual())).collectList().block(TIMEOUT);
        assertThat(results).containsExactly("F", "F", "F");
    }

    @Test
    void в_контексте_хранится_immutable_String_а_не_scope() {
        Object stored = operations.withCorrelationId("TYPE",
                Mono.deferContextual(cv -> Mono.just((Object) cv.get(ReactorCorrelationSupport.KEY))))
                .block(TIMEOUT);
        assertThat(stored).isInstanceOf(String.class).isEqualTo("TYPE");
    }

    @Test
    void cancel_не_оставляет_глобального_состояния() {
        // Подписка отменяется до завершения; последующая независимая подписка не видит значения.
        operations.withCorrelationId("CANCEL", Mono.never().then(readContextual()))
                .timeout(Duration.ofMillis(50))
                .onErrorResume(e -> Mono.just("cancelled"))
                .block(TIMEOUT);

        String after = readContextual().block(TIMEOUT);
        assertThat(after).isEqualTo("none");
    }

    /**
     * OTel bridge: automatic context propagation + Micrometer ThreadLocalAccessor.
     * Доказывает MDC-проекцию на worker-thread и birth-time projection в child-span,
     * созданный под поздней reactive-привязкой на другом потоке.
     */
    @Nested
    class OtelBridge {

        private InMemorySpanExporter exporter;
        private SdkTracerProvider tracerProvider;
        private OpenTelemetry openTelemetry;

        @BeforeAll
        static void enableBridge() {
            ReactorCorrelationSupport.registerAccessorIfAbsent();
            Hooks.enableAutomaticContextPropagation();
        }

        @AfterAll
        static void cleanupBridge() {
            MDC.clear();
        }

        @BeforeEach
        void setUp() {
            exporter = InMemorySpanExporter.create();
            tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build();
            openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        }

        @AfterEach
        void tearDown() {
            tracerProvider.shutdown();
            MDC.remove(ReactorCorrelationSupport.KEY);
        }

        @Test
        void mdc_и_threadLocal_восстанавливаются_на_worker_thread() {
            String onWorker = operations.withCorrelationId("MDC-1",
                    Mono.fromCallable(() ->
                                    ReactorCorrelationSupport.readThreadLocal().orElse("none")
                                            + "/" + MDC.get(ReactorCorrelationSupport.KEY))
                            .subscribeOn(Schedulers.parallel())).block(TIMEOUT);

            assertThat(onWorker).isEqualTo("MDC-1/MDC-1");
        }

        @Test
        void child_span_созданный_под_поздней_привязкой_получает_correlationId_birth_time() {
            Tracer tracer = openTelemetry.getTracer("spike");

            operations.withCorrelationId("biz-777",
                    Mono.fromCallable(() -> {
                        Span child = tracer.spanBuilder("child").startSpan();
                        try (Scope ignored = child.makeCurrent()) {
                            // Scope создаётся и закрывается синхронно в пределах callable —
                            // через async-границу Scope НЕ переносится.
                            ReactorCorrelationSupport.readThreadLocal()
                                    .ifPresent(cid -> child.setAttribute("platform.correlation_id", cid));
                        } finally {
                            child.end();
                        }
                        return "ok";
                    }).subscribeOn(Schedulers.parallel())).block(TIMEOUT);

            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).getName()).isEqualTo("child");
            assertThat(spans.get(0).getAttributes().get(AttributeKey.stringKey("platform.correlation_id")))
                    .isEqualTo("biz-777");
        }

        @Test
        void threadLocal_очищается_после_завершения_на_caller_thread() {
            operations.withCorrelationId("TMP",
                    Mono.fromCallable(() -> ReactorCorrelationSupport.readThreadLocal().orElse("none"))
                            .subscribeOn(Schedulers.parallel())).block(TIMEOUT);

            assertThat(ReactorCorrelationSupport.readThreadLocal()).isEmpty();
        }
    }
}
