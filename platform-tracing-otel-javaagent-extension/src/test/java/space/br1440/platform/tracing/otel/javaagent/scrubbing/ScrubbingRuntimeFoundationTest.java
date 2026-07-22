package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.test.assertions.ScrubbingAssertions;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-7A: ScrubbingSpanProcessor reads holder snapshot without reconstruct; concurrent updates safe.
 */
class ScrubbingRuntimeFoundationTest {

    @Test
    void processor_uses_updated_snapshot_without_reconstruct() {
        ScrubbingSpanProcessor processor = new ScrubbingSpanProcessor(
                List.of(BuiltInSpanAttributeScrubbingRules.resolve("password")));
        assertThat(processor.getPolicyVersion()).isEqualTo(1);

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor)) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("db.password", "secret");
            span.end();
            assertThat(h.exporter().getFinishedSpanItems().get(0).getAttributes()
                    .get(AttributeKey.stringKey("db.password"))).isEmpty();
        }

        assertThat(processor.updateScrubbingPolicy(false, null)).isTrue();
        assertThat(processor.getPolicyVersion()).isEqualTo(2);

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor)) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("db.password", "secret");
            span.end();
            ScrubbingAssertions.assertStringAttributePreserved(
                    h.exporter().getFinishedSpanItems().get(0), "db.password", "secret");
        }

        assertThat(processor.updateScrubbingPolicy(true, List.of("jwt"))).isTrue();
        assertThat(processor.getPolicyVersion()).isEqualTo(3);
        assertThat(processor.getRuleCount()).isEqualTo(1);

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor)) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("token.value", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.sig");
            span.end();
            assertThat(h.exporter().getFinishedSpanItems().get(0).getAttributes()
                    .get(AttributeKey.stringKey("token.value"))).isEmpty();
        }
    }

    @Test
    void concurrent_updateScrubbingPolicy_and_onEnding_never_throws() throws Exception {
        ScrubbingSpanProcessor processor = new ScrubbingSpanProcessor(
                List.of(BuiltInSpanAttributeScrubbingRules.resolve("password")));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(2);
        AtomicBoolean stopReaders = new AtomicBoolean(false);
        AtomicReference<String> violation = new AtomicReference<>();

        try {
            for (int w = 0; w < 2; w++) {
                pool.submit(() -> {
                    await(start);
                    try {
                        for (int i = 0; i < 100; i++) {
                            processor.updateScrubbingPolicy(i % 2 == 0, List.of("password", "jwt"));
                        }
                    } finally {
                        writersDone.countDown();
                    }
                });
            }

            pool.submit(() -> {
                await(start);
                long lastVersion = 0;
                while (!stopReaders.get()) {
                    long version = processor.getPolicyVersion();
                    if (version < lastVersion) {
                        violation.compareAndSet(null, "version decreased: " + lastVersion + " -> " + version);
                    }
                    lastVersion = version;
                }
            });

            for (int r = 0; r < 3; r++) {
                pool.submit(() -> {
                    await(start);
                    try (SpanProcessorHarness h = SpanProcessorHarness.of(processor)) {
                        Tracer tracer = h.tracer("t");
                        while (!stopReaders.get()) {
                            Span span = tracer.spanBuilder("op").startSpan();
                            span.setAttribute("db.password", "secret");
                            span.setAttribute("just.text", "ok");
                            span.end();
                        }
                    } catch (Exception e) {
                        violation.compareAndSet(null, e.toString());
                    }
                });
            }

            start.countDown();
            assertThat(writersDone.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            stopReaders.set(true);
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(violation.get()).isNull();
        assertThat(processor.getPolicyVersion()).isGreaterThan(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
