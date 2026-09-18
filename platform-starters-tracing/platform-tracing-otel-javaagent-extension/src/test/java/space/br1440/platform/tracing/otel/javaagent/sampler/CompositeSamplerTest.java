package space.br1440.platform.tracing.otel.javaagent.sampler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.runtime.propagation.control.PlatformTraceContextKeys;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;
import space.br1440.platform.tracing.test.harness.InboundTraceControls;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

class CompositeSamplerTest {

    private static Context withControl(boolean force, boolean qa) {
        String reason = force ? PlatformSamplingReasons.FORCE_HEADER : (qa ? PlatformSamplingReasons.QA_TRACE : null);
        return Context.root().with(
                PlatformTraceContextKeys.TRACE_CONTROL,
                InboundTraceControls.of(force, qa, null, reason, force ? "on" : null));
    }

    private static Context withControlRaw(String raw) {
        boolean force = "on".equalsIgnoreCase(raw);
        return Context.root().with(
                PlatformTraceContextKeys.TRACE_CONTROL,
                InboundTraceControls.of(force, false, null, force ? PlatformSamplingReasons.FORCE_HEADER : null, raw));
    }

    @Test
    void forced_recording_returns_record_and_sample() {
        // given
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        // when/then
        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .parentContext(withControl(true, false))
                        .sample())
                .isRecordAndSample();
    }

    @Test
    void absent_signal_delegates_decision_to_external_sampler() {
        // given
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        // when/then
        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .sample())
                .isRecordAndSample();
    }

    @Test
    void QA_signal_presence_enables_recording() {
        // given
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        // when/then
        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .parentContext(withControl(false, true))
                        .sample())
                .isRecordAndSample();
    }

    @Test
    void custom_value_comparison_depends_on_forceRecordValues() {
        // given
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("custom-force")));

        // when/then
        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .parentContext(withControlRaw("custom-force"))
                        .sample())
                .isRecordAndSample();
    }

    @Test
    void drop_paths_drop_span_when_no_force_signal() {
        // given
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of("/actuator/health"), List.of("on")));

        // when/then
        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .putStringAttribute("url.path", "/actuator/health")
                        .sample())
                .isDrop();
    }

    @Test
    void force_signal_does_not_override_hard_drop() {
        // given
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of("/actuator/health"), List.of("on")));

        // when/then
        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .parentContext(withControl(true, false))
                        .putStringAttribute("url.path", "/actuator/health")
                        .sample())
                .isDrop();
    }

    @Test
    void qa_signal_does_not_override_hard_drop() {
        // given
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of("/actuator/health"), List.of("on")));

        // when/then
        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .parentContext(withControl(false, true))
                        .putStringAttribute("url.path", "/actuator/health")
                        .sample())
                .isDrop();
    }

    @Test
    void sampling_reason_from_force_header() {
        // given
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        // when
        SamplingResult result = SamplerHarness.of(sampler)
                .parentContext(withControl(true, false))
                .sample();

        // then
        assertThat(result.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_SAMPLING_REASON)))
                .isEqualTo("force_header");
    }

    @Test
    void sampling_reason_from_parent() {
        // given
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        SpanContext parent = SpanContext.create("00000000000000000000000000000001", "0000000000000002",
                TraceFlags.getSampled(), TraceState.getDefault());

        // when
        SamplingResult result = SamplerHarness.of(sampler)
                .parentContext(Context.root().with(Span.wrap(parent)))
                .sample();

        // then
        assertThat(result.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_SAMPLING_REASON)))
                .isEqualTo("parent_sampled");
    }

    @Test
    void sampling_reason_from_ratio() {
        // given
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        // when
        SamplingResult result = SamplerHarness.of(sampler).sample();

        // then
        assertThat(result.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_SAMPLING_REASON)))
                .isEqualTo("global_ratio");
    }

    @Test
    void sampling_reason_on_drop_paths() {
        // given
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of("/actuator/health"), List.of("on")));

        // when
        SamplingResult result = SamplerHarness.of(sampler)
                .putStringAttribute("url.path", "/actuator/health")
                .sample();

        // then
        assertThat(result.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_SAMPLING_REASON)))
                .isEqualTo("drop_path");
    }
}
