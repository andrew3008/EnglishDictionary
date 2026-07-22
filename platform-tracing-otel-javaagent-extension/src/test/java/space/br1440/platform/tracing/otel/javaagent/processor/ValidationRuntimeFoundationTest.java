package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.exception.TracingValidationException;
import space.br1440.platform.tracing.test.assertions.ValidationAssertions;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-8A: ValidatingSpanProcessor reads holder snapshot without reconstruct; concurrent updates safe.
 */
class ValidationRuntimeFoundationTest {

    @Test
    void processor_uses_updated_snapshot_without_reconstruct() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false, true);
        assertThat(processor.getPolicyVersion()).isEqualTo(1);
        assertThat(processor.getPolicySource()).isEqualTo("startup");

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            tracer.spanBuilder("op").startSpan().end();
            ValidationAssertions.assertMissingRecorded(
                    h.exporter().getFinishedSpanItems().get(0),
                    PlatformAttributes.PLATFORM_TYPE,
                    PlatformAttributes.PLATFORM_RESULT);
        }

        assertThat(processor.tryApplyPolicyUpdate(false, false, "test-disable")).isTrue();
        assertThat(processor.getPolicyVersion()).isEqualTo(2);
        assertThat(processor.getPolicySource()).isEqualTo("test-disable");

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            tracer.spanBuilder("op").startSpan().end();
            ValidationAssertions.assertMissingAbsent(h.exporter().getFinishedSpanItems().get(0));
        }

        assertThat(processor.tryApplyPolicyUpdate(true, true, "test-strict")).isTrue();
        assertThat(processor.getPolicyVersion()).isEqualTo(3);

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            assertThatThrownBy(() -> tracer.spanBuilder("op").startSpan().end())
                    .isInstanceOf(TracingValidationException.class);
        }
    }

    @Test
    void lenient_validation_failure_still_exports_span_not_dropped() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false);

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            tracer.spanBuilder("op").startSpan().end();

            assertThat(h.exporter().getFinishedSpanItems()).hasSize(1);
            ValidationAssertions.assertMissingRecorded(
                    h.exporter().getFinishedSpanItems().get(0),
                    PlatformAttributes.PLATFORM_TYPE,
                    PlatformAttributes.PLATFORM_RESULT);
        }
    }

    @Test
    void concurrent_updateValidationPolicy_and_onEnding_never_throws() throws Exception {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false);

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
                            processor.tryApplyPolicyUpdate(i % 2 == 0, i % 3 == 0, "concurrent");
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
                    try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
                        Tracer tracer = h.tracer("t");
                        while (!stopReaders.get()) {
                            Span span = tracer.spanBuilder("op").startSpan();
                            span.setAttribute(PlatformAttributes.PLATFORM_TYPE, "HTTP");
                            span.setAttribute(PlatformAttributes.PLATFORM_RESULT, "success");
                            try {
                                span.end();
                            } catch (TracingValidationException e) {
                                // strict mode may throw; still not a dropped-span taxonomy case
                            }
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
