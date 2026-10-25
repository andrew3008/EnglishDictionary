package space.br1440.platform.tracing.otel.javaagent.sampler;

import java.util.List;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.runtime.propagation.control.PlatformTraceContextKeys;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;
import space.br1440.platform.tracing.test.harness.InboundTraceControls;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;

class CompositeSamplerEdgeCasesTest {

    private static Context withControl(boolean force, boolean qa) {
        String reason = force ? PlatformSamplingReasons.FORCE_HEADER : (qa ? PlatformSamplingReasons.QA_TRACE : null);
        return Context.root().with(
                PlatformTraceContextKeys.TRACE_CONTROL,
                InboundTraceControls.of(force, qa, null, reason, force ? "on" : null));
    }

    @SuppressWarnings("SameParameterValue")
    private static Context withControlRaw(String raw) {
        boolean force = "on".equalsIgnoreCase(raw);
        return Context.root().with(
                PlatformTraceContextKeys.TRACE_CONTROL,
                InboundTraceControls.of(force, false, null, force ? PlatformSamplingReasons.FORCE_HEADER : null, raw));
    }

    @Test
    void does_not_fail_on_empty_value_list() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, java.util.Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of()));

        // when/then
        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .parentContext(withControlRaw("on"))
                        .sample())
                .isDrop();
    }

    @Test
    void null_url_path_does_not_cause_npe() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, java.util.Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of("/actuator"), List.of("on")));

        // when/then
        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .sample())
                .isRecordAndSample();
    }

    @Test
    void kill_switch_overrides_even_force_and_qa() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), false, java.util.Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        // when/then
        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .parentContext(withControl(true, true))
                        .sample())
                .isDrop();
    }

    @Test
    void forcing_does_not_override_drop_paths() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, java.util.Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of("/actuator"), List.of("on")));

        // when/then
        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .parentContext(withControl(true, false))
                        .putStringAttribute("url.path", "/actuator/health")
                        .sample())
                .isDrop();
    }

    @Test
    void qa_signal_does_not_override_drop_paths() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, java.util.Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of("/actuator"), List.of("on")));

        // when/then
        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .parentContext(withControl(false, true))
                        .putStringAttribute("url.path", "/actuator/health")
                        .sample())
                .isDrop();
    }
}
