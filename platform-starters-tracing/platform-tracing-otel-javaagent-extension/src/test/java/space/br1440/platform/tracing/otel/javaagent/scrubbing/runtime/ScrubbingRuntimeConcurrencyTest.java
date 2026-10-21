package space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
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

class ScrubbingRuntimeConcurrencyTest {

    private static final int THREADS = 8;
    private static final int ITERATIONS = 500;

    @Test
    @DisplayName("HMAC is stable across threads: the thread-local Mac never leaks state")
    void hmacIsDeterministicUnderConcurrency() throws Exception {
        // given
        ScrubbingRuntime runtime = runtimeOf("shared-hmac-key");
        String expected = runtime.hmacHex("ivan@example.com");

        Set<String> observed = ConcurrentHashMap.newKeySet();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // when
        runConcurrently(() -> {
            try {
                observed.add(runtime.hmacHex("ivan@example.com"));
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        // then
        assertThat(failure.get()).isNull();
        assertThat(observed).containsExactly(expected);
    }

    @Test
    @DisplayName("Two sessions are isolated: different HMAC keys, independent counters and state flags")
    void sessionsDoNotShareHmacCountersOrState() {
        // given
        ScrubbingRuntime first = runtimeOf("key-one");
        ScrubbingRuntime second = runtimeOf("key-two");

        // when
        assertThat(first.hmacHex("value")).isNotEqualTo(second.hmacHex("value"));

        first.recordBoundaryInputSpan();
        first.recordCollapsedSpan();
        first.markAttributesUnverified();

        // then
        assertThat(second.boundaryMetrics().inputSpans()).isZero();
        assertThat(second.collapsedSpans()).isZero();
        assertThat(second.attributesVerified()).isTrue();
        assertThat(first.attributesVerified()).isFalse();
    }

    @Test
    @DisplayName("Concurrent span scrubbing counts every action exactly once")
    void concurrentScrubbingProducesExactCounters() throws Exception {
        // given
        ScrubbingRuntime runtime = runtimeOf(null);
        ScrubbingSpanProcessor processor = new ScrubbingSpanProcessor(runtime);

        // when
        try (SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(processor).build()) {
            Tracer tracer = provider.get("concurrency-test");
            runConcurrently(() -> tracer.spanBuilder("op")
                    .setAttribute(AttributeKey.stringKey("db.password"), "supersecret")
                    .startSpan()
                    .end());
        }

        // then
        assertThat(runtime.droppedActions()).isEqualTo((long) THREADS * ITERATIONS);
        assertThat(runtime.failures()).isZero();
        assertThat(runtime.openBreakerCount()).isZero();
    }

    @Test
    @DisplayName("Обе плоскости на одном runtime: processor и exporter-boundary считают точно и не мешают друг другу")
    void processorAndBoundaryPlanesShareOneRuntimeWithoutInterference() throws Exception {
        // given
        ScrubbingRuntime runtime = runtimeOf("shared-hmac-key");
        ScrubbingSpanProcessor processor = new ScrubbingSpanProcessor(runtime);
        InMemorySpanExporter delegate = InMemorySpanExporter.create();
        SpanExporter boundaryExporter = new SafeSpanExporter(
                delegate, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), runtime);

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

        // when
        try (SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(processor).build()) {
            Tracer tracer = provider.get("concurrency-test");
            runConcurrently(
                    () -> tracer.spanBuilder("op")
                            .setAttribute(AttributeKey.stringKey("db.password"), "supersecret")
                            .startSpan()
                            .end(),
                    () -> boundaryExporter.export(List.of(eventCarryingSpan)));
        }

        // then
        long perPlane = (long) (THREADS / 2) * ITERATIONS;
        assertThat(runtime.droppedActions())
                .as("processor-плоскость: одно DROP на span")
                .isEqualTo(perPlane);
        assertThat(runtime.boundaryMetrics().inputSpans()).isEqualTo(perPlane);
        assertThat(runtime.boundaryMetrics().eventsScrubbed()).isEqualTo(perPlane);
        assertThat(runtime.failures()).isZero();
        assertThat(runtime.boundaryMetrics().criticalFailures()).isZero();
        assertThat(runtime.boundaryMetrics().nonCriticalFailures()).isZero();
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
                awaitStart(start);
                for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                    action.run();
                }
            });
        }

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            workers.forEach(worker -> futures.add(pool.submit(worker)));
            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }
    }

    private static void awaitStart(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new AssertionError("start signal was not released in time");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for start signal", interrupted);
        }
    }

    private static ScrubbingRuntime runtimeOf(String hmacKey) {
        SpanAttributeScrubbingRule password = BuiltInSpanAttributeScrubbingRules.PASSWORD_KEY.create();
        RuntimeRule rule = new RuntimeRule(
                new ExecutableRule(password, new RuleCircuitBreaker(password.name())),
                RuleOrigin.BUILT_IN
        );

        return ScrubbingRuntime.create(List.of(rule), 1L, MaskedSecret.of(hmacKey), false);
    }
}
