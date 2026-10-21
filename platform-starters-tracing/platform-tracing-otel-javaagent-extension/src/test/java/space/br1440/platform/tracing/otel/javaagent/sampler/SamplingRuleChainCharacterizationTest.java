package space.br1440.platform.tracing.otel.javaagent.sampler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;
import space.br1440.platform.tracing.test.assertions.SamplingAssertions;
import space.br1440.platform.tracing.test.harness.SamplerHarness;
import space.br1440.platform.tracing.test.harness.SamplingContextFactory;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

class SamplingRuleChainCharacterizationTest {

    @Test
    void kill_switch_blocks_force_qa_route_and_global_ratio() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), false, Map.of("/api", 1.0), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));
        sampler.resetCounters();

        // when
        SamplingResult result = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .parentContext(SamplingContextFactory.withForceAndQa("on"))
                .putStringAttribute("url.path", "/api/orders")
                .sample();

        // then
        SamplerDecisionAssert.assertThat(result).isDrop();
        SamplingAssertions.assertSamplingReason(result, null);
        assertThat(sampler.getDecisionCount("DROP", PlatformSamplingReasons.KILL_SWITCH)).isEqualTo(1L);
    }

    @Test
    void hard_drop_before_force_header_and_qa() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of("/internal", 1.0), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of("/internal"), List.of("on")));

        // when
        SamplingResult forceResult = SamplerHarness.of(sampler)
                .parentContext(SamplingContextFactory.withForceHeader("on"))
                .putStringAttribute("url.path", "/internal/metrics")
                .sample();
        // then
        SamplingAssertions.assertDecision(forceResult, io.opentelemetry.sdk.trace.samplers.SamplingDecision.DROP);
        SamplingAssertions.assertSamplingReason(forceResult, PlatformSamplingReasons.DROP_PATH);

        // when
        SamplingResult qaResult = SamplerHarness.of(sampler)
                .parentContext(SamplingContextFactory.withQaTrace())
                .putStringAttribute("url.path", "/internal/metrics")
                .sample();
        // then
        SamplingAssertions.assertDecision(qaResult, io.opentelemetry.sdk.trace.samplers.SamplingDecision.DROP);
        SamplingAssertions.assertSamplingReason(qaResult, PlatformSamplingReasons.DROP_PATH);
    }

    @Test
    void force_header_before_parent_decision() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        // when
        SamplingResult result = SamplerHarness.of(sampler)
                .parentContext(SamplingContextFactory.withNotSampledParentAndForceHeader("on"))
                .putStringAttribute("url.path", "/api/orders")
                .sample();

        // then
        SamplerDecisionAssert.assertThat(result).isRecordAndSample();
        SamplingAssertions.assertSamplingReason(result, PlatformSamplingReasons.FORCE_HEADER);
    }

    @Test
    void qa_trace_before_parent_decision() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        // when
        SamplingResult result = SamplerHarness.of(sampler)
                .parentContext(SamplingContextFactory.withNotSampledParentAndQaTrace())
                .putStringAttribute("url.path", "/api/orders")
                .sample();

        // then
        SamplerDecisionAssert.assertThat(result).isRecordAndSample();
        SamplingAssertions.assertSamplingReason(result, PlatformSamplingReasons.QA_TRACE);
    }

    @Test
    void absent_url_path_skips_hard_drop_and_route_ratio() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of("/api", 0.0), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of("/actuator"), List.of("on")));

        // when
        SamplingResult result = SamplerHarness.of(sampler).sample();

        // then
        SamplerDecisionAssert.assertThat(result).isRecordAndSample();
        SamplingAssertions.assertSamplingReason(result, PlatformSamplingReasons.GLOBAL_RATIO);
    }

    @Test
    void custom_force_value_fires_when_forceRecordValues_match() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("custom-force")));

        // when
        SamplingResult result = SamplerHarness.of(sampler)
                .parentContext(SamplingContextFactory.withForceHeader("custom-force"))
                .sample();

        // then
        SamplerDecisionAssert.assertThat(result).isRecordAndSample();
        SamplingAssertions.assertSamplingReason(result, PlatformSamplingReasons.FORCE_HEADER);
    }
}
