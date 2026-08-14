package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOrigin;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuntimeRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5: HMAC, circuit breakers и счётчики принадлежат сессии. Проверяется, что параллельное
 * использование одного runtime детерминировано, а два runtime полностью изолированы.
 */
class ScrubbingRuntimeConcurrencyTest {

    private static final int THREADS = 8;
    private static final int ITERATIONS = 500;

    @Test
    @DisplayName("HMAC is stable across threads: the thread-local Mac never leaks state")
    void hmacIsDeterministicUnderConcurrency() throws Exception {
        ScrubbingRuntime runtime = runtimeOf("shared-hmac-key");
        String expected = runtime.hmacHex("ivan@example.com");

        Set<String> observed = ConcurrentHashMap.newKeySet();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runConcurrently(() -> {
            try {
                observed.add(runtime.hmacHex("ivan@example.com"));
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        assertThat(failure.get()).isNull();
        assertThat(observed).containsExactly(expected);
    }

    @Test
    @DisplayName("Two sessions are isolated: different HMAC keys, independent counters and state flags")
    void sessionsDoNotShareHmacCountersOrState() {
        ScrubbingRuntime first = runtimeOf("key-one");
        ScrubbingRuntime second = runtimeOf("key-two");

        assertThat(first.hmacHex("value")).isNotEqualTo(second.hmacHex("value"));

        first.boundaryInputSpansAdder().increment();
        first.recordCollapsedSpan();
        first.markAttributesUnverified();

        assertThat(second.boundaryInputSpansAdder().sum()).isZero();
        assertThat(second.collapsedSpans()).isZero();
        assertThat(second.attributesVerified()).isTrue();
        assertThat(first.attributesVerified()).isFalse();
        assertThat(first.installation().wrappedExporterCount()).isZero();
    }

    @Test
    @DisplayName("Concurrent span scrubbing counts every action exactly once")
    void concurrentScrubbingProducesExactCounters() throws Exception {
        ScrubbingRuntime runtime = runtimeOf(null);
        ScrubbingSpanProcessor processor = new ScrubbingSpanProcessor(runtime);

        try (SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(processor).build()) {
            Tracer tracer = provider.get("concurrency-test");
            runConcurrently(() -> tracer.spanBuilder("op")
                    .setAttribute(AttributeKey.stringKey("db.password"), "supersecret")
                    .startSpan()
                    .end());
        }

        assertThat(runtime.droppedActions()).isEqualTo((long) THREADS * ITERATIONS);
        assertThat(runtime.failures()).isZero();
        assertThat(runtime.openBreakerCount()).isZero();
    }

    @Test
    @DisplayName("Обе плоскости на одном runtime: processor и exporter-boundary считают точно и не мешают друг другу")
    void processorAndBoundaryPlanesShareOneRuntimeWithoutInterference() throws Exception {
        ScrubbingRuntime runtime = runtimeOf("shared-hmac-key");
        ScrubbingSpanProcessor processor = new ScrubbingSpanProcessor(runtime);
        InMemorySpanExporter delegate = InMemorySpanExporter.create();
        SpanExporter boundaryExporter = new SafeSpanExporter(
                delegate, TracingSettingsSource.isolatedDefaults(), runtime);

        SpanData eventCarryingSpan = TestSpanData.builder()
                .setName("op")
                .setKind(SpanKind.INTERNAL)
                .setStatus(StatusData.unset())
                .setStartEpochNanos(0L)
                .setEndEpochNanos(1L)
                .setHasEnded(true)
                .setEvents(List.of(EventData.create(
                        1L, "login", Attributes.of(AttributeKey.stringKey("db.password"), "supersecret"))))
                .build();

        try (SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(processor).build()) {
            Tracer tracer = provider.get("concurrency-test");
            runConcurrently(
                    () -> tracer.spanBuilder("op")
                            .setAttribute(AttributeKey.stringKey("db.password"), "supersecret")
                            .startSpan()
                            .end(),
                    () -> boundaryExporter.export(List.of(eventCarryingSpan)));
        }

        long perPlane = (long) (THREADS / 2) * ITERATIONS;
        assertThat(runtime.droppedActions())
                .as("processor-плоскость: одно DROP на span")
                .isEqualTo(perPlane);
        assertThat(runtime.boundaryInputSpansAdder().sum()).isEqualTo(perPlane);
        assertThat(runtime.boundaryEventsScrubbedAdder().sum()).isEqualTo(perPlane);
        assertThat(runtime.failures()).isZero();
        assertThat(runtime.boundaryCriticalFailuresAdder().sum()).isZero();
        assertThat(runtime.boundaryNonCriticalFailuresAdder().sum()).isZero();
        assertThat(runtime.openBreakerCount()).isZero();
        assertThat(runtime.attributesVerified())
                .as("исправная конкурентная работа не должна деградировать сессию")
                .isTrue();
    }

    private static void runConcurrently(Runnable action) throws Exception {
        runConcurrently(action, action);
    }

    private static void runConcurrently(Runnable evenThreadAction, Runnable oddThreadAction) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Runnable> workers = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            Runnable action = (i % 2 == 0) ? evenThreadAction : oddThreadAction;
            workers.add(() -> {
                awaitQuietly(start);
                for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                    action.run();
                }
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            workers.forEach(worker -> futures.add(pool.submit(worker)));
            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ScrubbingRuntime runtimeOf(String hmacKey) {
        SpanAttributeScrubbingRule password = BuiltInSpanAttributeScrubbingRules.resolve("password");
        RuntimeRule rule = new RuntimeRule(
                new ExecutableRule(password, new RuleCircuitBreaker(password.name())), RuleOrigin.BUILT_IN);
        return ScrubbingRuntime.create(List.of(rule), 1L, "test", MaskedSecret.of(hmacKey), false);
    }
}
