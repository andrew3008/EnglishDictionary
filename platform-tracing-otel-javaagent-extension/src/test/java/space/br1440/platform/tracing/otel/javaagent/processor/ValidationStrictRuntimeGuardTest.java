package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.validation.ValidationSnapshot;
import space.br1440.platform.tracing.otel.javaagent.exception.TracingValidationException;
import space.br1440.platform.tracing.otel.javaagent.jmx.validation.PlatformValidationControl;

import java.util.concurrent.atomic.LongAdder;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-9F: agent-side runtime strict-mode safety guard (W-001).
 */
class ValidationStrictRuntimeGuardTest {

    @Test
    void defaultGuard_rejects_runtime_strict_true_when_enabled() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false);
        LongAdder counter = new LongAdder();
        PlatformValidationControl control = new PlatformValidationControl(processor, counter);
        long v0 = processor.getPolicyVersion();
        String source0 = processor.getPolicySource();

        control.updateValidationPolicy(true, true, "JMX");

        assertThat(processor.getPolicyVersion()).isEqualTo(v0);
        assertThat(processor.getPolicySource()).isEqualTo(source0);
        assertThat(processor.isStrict()).isFalse();
        assertThat(counter.sum()).isEqualTo(1);
    }

    @Test
    void defaultGuard_rejects_runtime_strict_true_when_disabled() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false);
        LongAdder counter = new LongAdder();
        PlatformValidationControl control = new PlatformValidationControl(processor, counter);
        long v0 = processor.getPolicyVersion();

        control.updateValidationPolicy(false, true, "JMX");

        assertThat(processor.getPolicyVersion()).isEqualTo(v0);
        assertThat(processor.isStrict()).isFalse();
        assertThat(counter.sum()).isEqualTo(1);
    }

    @Test
    void defaultGuard_allows_lenient_runtime_updates() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false);
        LongAdder counter = new LongAdder();
        PlatformValidationControl control = new PlatformValidationControl(processor, counter);
        long v0 = processor.getPolicyVersion();

        control.updateValidationPolicy(true, false, "JMX");
        assertThat(processor.getPolicyVersion()).isEqualTo(v0 + 1);
        assertThat(processor.isStrict()).isFalse();

        control.updateValidationPolicy(false, false, "JMX");
        assertThat(processor.getPolicyVersion()).isEqualTo(v0 + 2);
        assertThat(processor.isEnabled()).isFalse();
        assertThat(counter.sum()).isZero();
    }

    @Test
    void twoArgUpdate_rejects_strict_by_default() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false);
        LongAdder counter = new LongAdder();
        PlatformValidationControl control = new PlatformValidationControl(processor, counter);
        long v0 = processor.getPolicyVersion();

        control.updateValidationPolicy(true, true);

        assertThat(processor.getPolicyVersion()).isEqualTo(v0);
        assertThat(processor.isStrict()).isFalse();
        assertThat(counter.sum()).isEqualTo(1);
    }

    @Test
    void strictRuntimeAllowed_allows_runtime_strict_and_preserves_throw_behavior() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false, true);
        PlatformValidationControl control = controlWith(processor);
        long v0 = processor.getPolicyVersion();

        control.updateValidationPolicy(true, true, "test-preprod");

        assertThat(processor.getPolicyVersion()).isEqualTo(v0 + 1);
        assertThat(processor.isStrict()).isTrue();
        assertThat(processor.getPolicySource()).isEqualTo("test-preprod");

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            assertThatThrownBy(() -> tracer.spanBuilder("op").startSpan().end())
                    .isInstanceOf(TracingValidationException.class);
        }
    }

    @Test
    void holder_rejects_strict_without_changing_lkg() {
        ValidationPolicyHolder holder = new ValidationPolicyHolder(
                ValidationSnapshot.fromPolicy(true, false, 5, Instant.now(), "startup"),
                false);
        ValidationSnapshot before = holder.current();

        boolean applied = holder.tryApplyPolicyUpdate(true, true, "JMX");

        assertThat(applied).isFalse();
        assertThat(holder.current()).isSameAs(before);
        assertThat(holder.version()).isEqualTo(5);
        assertThat(holder.current().strict()).isFalse();
        assertThat(holder.current().source()).isEqualTo("startup");
    }

    @Test
    void strictRuntimeAllowed_default_false() {
        assertThat(new ValidatingSpanProcessor(false).isStrictRuntimeAllowed()).isFalse();
    }

    @Test
    void startup_strict_true_allowed_without_runtime_guard_flag() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(true, false);
        assertThat(processor.isStrict()).isTrue();
        assertThat(processor.isStrictRuntimeAllowed()).isFalse();

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            assertThatThrownBy(() -> tracer.spanBuilder("op").startSpan().end())
                    .isInstanceOf(TracingValidationException.class);
        }
    }

    private static PlatformValidationControl controlWith(ValidatingSpanProcessor processor) {
        return new PlatformValidationControl(processor, new LongAdder());
    }
}
