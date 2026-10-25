package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures.boundDefaults;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyMutationAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.test.assertions.ValidationAssertions;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;

class ValidationRuntimeFoundationTest {

    @Test
    void processor_uses_updated_snapshot_without_reconstruct() {
        // given
        TracingSettingsSource access = boundDefaults(AgentInstanceId.random());
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(access);
        // when
        long initialSequence = processor.getPolicyVersion();
        // then
        assertThat(initialSequence).isZero();

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            tracer.spanBuilder("op").startSpan().end();
            ValidationAssertions.assertMissingRecorded(
                    h.exporter().getFinishedSpanItems().getFirst(),
                    PlatformAttributes.PLATFORM_TYPE,
                    PlatformAttributes.PLATFORM_RESULT);
        }

        assertThat(AcceptedPolicyMutationAccess.applyValidation(access, SpanAttributeValidationLevel.DISABLED))
                .isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(processor.getPolicyVersion()).isEqualTo(initialSequence + 1);

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            tracer.spanBuilder("op").startSpan().end();
            ValidationAssertions.assertMissingAbsent(h.exporter().getFinishedSpanItems().getFirst());
        }

        assertThat(AcceptedPolicyMutationAccess.applyValidation(access, SpanAttributeValidationLevel.ERROR))
                .isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(processor.getPolicyVersion()).isEqualTo(initialSequence + 2);

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            tracer.spanBuilder("op").startSpan().end();
            assertThat(h.exporter().getFinishedSpanItems()).hasSize(1);
            assertThat(processor.getValidationViolationsTotal()).isEqualTo(2L);
        }
    }

    @Test
    void warn_level_validation_failure_still_exports_span_not_dropped() {
        // given
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(
                TracingSettingsSourceTestFixtures.boundValidation(AgentInstanceId.random(), SpanAttributeValidationLevel.WARNING));

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            // when
            tracer.spanBuilder("op").startSpan().end();

            // then
            assertThat(h.exporter().getFinishedSpanItems()).hasSize(1);
            ValidationAssertions.assertMissingRecorded(
                    h.exporter().getFinishedSpanItems().getFirst(),
                    PlatformAttributes.PLATFORM_TYPE,
                    PlatformAttributes.PLATFORM_RESULT);
        }
    }

    @Test
    void concurrent_updateValidation_and_onEnding_never_throws() throws Exception {
        // given
        TracingSettingsSource access = boundDefaults(AgentInstanceId.random());
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(2);
        AtomicBoolean stopReaders = new AtomicBoolean(false);
        AtomicReference<String> violation = new AtomicReference<>();

        try (ValidatingSpanProcessor processor = new ValidatingSpanProcessor(access);
                ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            try {
                for (int w = 0; w < 2; w++) {
                    pool.submit(() -> {
                        await(start);
                        try {
                            for (int i = 0; i < 100; i++) {
                                AcceptedPolicyMutationAccess.applyValidation(
                                        access,
                                        SpanAttributeValidationLevel.values()[i % SpanAttributeValidationLevel.values().length]);
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
                                span.end();
                            }
                        } catch (Exception e) {
                            violation.compareAndSet(null, e.toString());
                        }
                    });
                }

                // when
                start.countDown();
                // then
                assertThat(writersDone.await(30, TimeUnit.SECONDS)).isTrue();
            } finally {
                stopReaders.set(true);
            }

            assertThat(processor.getPolicyVersion()).isPositive();
        }

        assertThat(violation.get()).isNull();
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
