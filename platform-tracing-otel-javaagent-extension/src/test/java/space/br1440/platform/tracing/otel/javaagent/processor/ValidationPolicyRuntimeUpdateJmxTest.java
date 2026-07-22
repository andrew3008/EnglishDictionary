package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.validation.ValidationSnapshot;
import space.br1440.platform.tracing.otel.javaagent.exception.TracingValidationException;
import space.br1440.platform.tracing.otel.javaagent.jmx.validation.PlatformValidationControl;
import space.br1440.platform.tracing.test.assertions.ValidationAssertions;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-8B: atomic runtime validation policy update via JMX bridge → {@link ValidationPolicyHolder}.
 */
class ValidationPolicyRuntimeUpdateJmxTest {

    @Test
    void updateValidationPolicy_publishes_enabled_strict_source_and_version_when_guard_allows() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false, true);
        PlatformValidationControl control = controlWith(processor);
        long v0 = processor.getPolicyVersion();

        control.updateValidationPolicy(true, true, "pr-8b-test");

        assertThat(processor.getPolicyVersion()).isEqualTo(v0 + 1);
        assertThat(processor.getPolicySource()).isEqualTo("pr-8b-test");
        assertThat(processor.isEnabled()).isTrue();
        assertThat(processor.isStrict()).isTrue();
        assertThat(control.getValidationConfigVersion()).isEqualTo(v0 + 1);
        assertThat(control.getValidationConfigLastUpdatedSource()).isEqualTo("pr-8b-test");
    }

    @Test
    void twoArgUpdate_with_strict_rejected_by_default_guard() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false);
        LongAdder counter = new LongAdder();
        PlatformValidationControl control = new PlatformValidationControl(processor, counter);
        long v0 = processor.getPolicyVersion();

        control.updateValidationPolicy(false, true);

        assertThat(processor.getPolicyVersion()).isEqualTo(v0);
        assertThat(processor.getPolicySource()).isEqualTo("startup");
        assertThat(processor.isEnabled()).isTrue();
        assertThat(processor.isStrict()).isFalse();
        assertThat(counter.sum()).isEqualTo(1);
    }

    @Test
    void blankSource_normalizedToJmx() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false);
        PlatformValidationControl control = controlWith(processor);

        control.updateValidationPolicy(true, false, "  ");

        assertThat(processor.getPolicySource()).isEqualTo("JMX");
        assertThat(control.getValidationConfigLastUpdatedSource()).isEqualTo("JMX");
    }

    @Test
    void nullSource_normalizedToJmx() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false);
        PlatformValidationControl control = controlWith(processor);

        control.updateValidationPolicy(true, false, null);

        assertThat(processor.getPolicySource()).isEqualTo("JMX");
    }

    @Test
    void disabled_update_causes_bypass_without_reconstruct() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false);
        PlatformValidationControl control = controlWith(processor);

        control.updateValidationPolicy(false, false, "pr-8b-test");
        assertThat(processor.isEnabled()).isFalse();

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            tracer.spanBuilder("op").startSpan().end();
            ValidationAssertions.assertMissingAbsent(h.exporter().getFinishedSpanItems().get(0));
        }
    }

    @Test
    void lenient_update_annotates_without_throwing() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(true);
        PlatformValidationControl control = controlWith(processor);

        control.updateValidationPolicy(true, false, "lenient");

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            tracer.spanBuilder("op").startSpan().end();
            ValidationAssertions.assertMissingRecorded(
                    h.exporter().getFinishedSpanItems().get(0),
                    PlatformAttributes.PLATFORM_TYPE,
                    PlatformAttributes.PLATFORM_RESULT);
        }
    }

    @Test
    void strict_update_throws_on_missing_attrs_when_guard_allows() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false, true);
        PlatformValidationControl control = controlWith(processor);

        control.updateValidationPolicy(true, true, "strict");

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            assertThatThrownBy(() -> tracer.spanBuilder("op").startSpan().end())
                    .isInstanceOf(TracingValidationException.class);
        }
    }

    @Test
    void toggling_strict_at_runtime_changes_behavior_when_guard_allows() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false, true);
        PlatformValidationControl control = controlWith(processor);

        control.updateValidationPolicy(true, false, "lenient");
        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            tracer.spanBuilder("op").startSpan().end();
            assertThat(h.exporter().getFinishedSpanItems()).hasSize(1);
        }

        control.updateValidationPolicy(true, true, "strict");
        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            assertThatThrownBy(() -> tracer.spanBuilder("op").startSpan().end())
                    .isInstanceOf(TracingValidationException.class);
        }
    }

    @Test
    void holder_rejection_keepsLkg() {
        ValidationPolicyHolder holder = new ValidationPolicyHolder(
                ValidationSnapshot.fromPolicy(true, false, 3, java.time.Instant.now(), "startup"));
        ValidationSnapshot before = holder.current();

        boolean applied = holder.tryUpdate(prev -> {
            throw new IllegalStateException("simulated invalid update");
        });

        assertThat(applied).isFalse();
        assertThat(holder.current()).isSameAs(before);
        assertThat(holder.version()).isEqualTo(3);
    }

    @Test
    void concurrentUpdateValidationPolicy_monotonicVersion_noPartialState() throws Exception {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false, true);
        PlatformValidationControl control = controlWith(processor);
        long v0 = processor.getPolicyVersion();
        int threads = 4;
        int updatesPerThread = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<String> violation = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            boolean enabled = t % 2 == 0;
            boolean strict = t % 3 == 0;
            pool.submit(() -> {
                await(start);
                try {
                    for (int i = 0; i < updatesPerThread; i++) {
                        control.updateValidationPolicy(enabled, strict, "concurrency");
                    }
                } catch (RuntimeException e) {
                    violation.compareAndSet(null, e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        pool.submit(() -> {
            await(start);
            try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
                Tracer tracer = h.tracer("t");
                while (done.getCount() > 0) {
                    try {
                        tracer.spanBuilder("op")
                                .setAttribute(PlatformAttributes.PLATFORM_TYPE, "HTTP")
                                .setAttribute(PlatformAttributes.PLATFORM_RESULT, "success")
                                .startSpan()
                                .end();
                    } catch (TracingValidationException e) {
                        // strict mode may throw; not dropped-span taxonomy
                    }
                }
            } catch (Exception e) {
                violation.compareAndSet(null, e.toString());
            }
        });

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(violation.get()).isNull();
        assertThat(processor.getPolicyVersion()).isEqualTo(v0 + (long) threads * updatesPerThread);
        assertThat(control.getValidationConfigVersion()).isEqualTo(processor.getPolicyVersion());
    }

    private static PlatformValidationControl controlWith(ValidatingSpanProcessor processor) {
        return new PlatformValidationControl(processor, new LongAdder());
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
