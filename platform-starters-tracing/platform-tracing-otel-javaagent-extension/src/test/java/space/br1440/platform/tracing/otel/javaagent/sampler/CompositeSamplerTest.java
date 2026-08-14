package space.br1440.platform.tracing.otel.javaagent.sampler;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;

import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.otel.propagation.control.PlatformTraceContextKeys;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;
import space.br1440.platform.tracing.test.harness.InboundTraceControls;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
    void форсированная_запись_возвращает_record_and_sample() {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .parentContext(withControl(true, false))
                        .sample())
                .isRecordAndSample();
    }

    @Test
    void отсутствие_сигнала_делегирует_решение_внешнему_семплеру() {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .sample())
                .isRecordAndSample();
    }

    @Test
    void наличие_QA_сигнала_включает_запись() {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .parentContext(withControl(false, true))
                        .sample())
                .isRecordAndSample();
    }

    @Test
    void сравнение_кастомных_значений_зависит_от_forceRecordValues() {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("custom-force")));

        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .parentContext(withControlRaw("custom-force"))
                        .sample())
                .isRecordAndSample();
    }
    
    @Test
    void drop_paths_отбрасывают_спан_если_нет_force_сигнала() {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of("/actuator/health"), List.of("on")));

        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .putStringAttribute("url.path", "/actuator/health")
                        .sample())
                .isDrop();
    }

    @Test
    void force_сигнал_не_переопределяет_hard_drop() {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of("/actuator/health"), List.of("on")));

        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .parentContext(withControl(true, false))
                        .putStringAttribute("url.path", "/actuator/health")
                        .sample())
                .isDrop();
    }

    @Test
    void qa_сигнал_не_переопределяет_hard_drop() {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of("/actuator/health"), List.of("on")));

        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .parentContext(withControl(false, true))
                        .putStringAttribute("url.path", "/actuator/health")
                        .sample())
                .isDrop();
    }

    @Test
    void причина_семплирования_от_force_header() {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        SamplingResult result = SamplerHarness.of(sampler)
                .parentContext(withControl(true, false))
                .sample();

        assertThat(result.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_SAMPLING_REASON)))
                .isEqualTo("force_header");
    }

    @Test
    void причина_семплирования_от_parent() {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        SpanContext parent = SpanContext.create("00000000000000000000000000000001", "0000000000000002",
                TraceFlags.getSampled(), TraceState.getDefault());

        SamplingResult result = SamplerHarness.of(sampler)
                .parentContext(Context.root().with(Span.wrap(parent)))
                .sample();

        assertThat(result.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_SAMPLING_REASON)))
                .isEqualTo("parent_sampled");
    }

    @Test
    void причина_семплирования_от_ratio() {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        SamplingResult result = SamplerHarness.of(sampler).sample();

        assertThat(result.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_SAMPLING_REASON)))
                .isEqualTo("global_ratio");
    }

    @Test
    void причина_семплирования_при_drop_paths() {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of("/actuator/health"), List.of("on")));

        SamplingResult result = SamplerHarness.of(sampler)
                .putStringAttribute("url.path", "/actuator/health")
                .sample();

        assertThat(result.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_SAMPLING_REASON)))
                .isEqualTo("drop_path");
    }
}
