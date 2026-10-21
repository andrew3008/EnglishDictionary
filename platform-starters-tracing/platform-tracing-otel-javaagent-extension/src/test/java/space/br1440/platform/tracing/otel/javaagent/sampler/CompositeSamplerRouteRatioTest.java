package space.br1440.platform.tracing.otel.javaagent.sampler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

class CompositeSamplerRouteRatioTest {

    private static final AttributeKey<String> REASON =
            AttributeKey.stringKey(PlatformAttributes.PLATFORM_SAMPLING_REASON);

    @Test
    void route_ratio_1_samples_when_global_ratio_0() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(),
                true, Map.of("/api/v1/critical", 1.0), 0.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        // when
        SamplingResult result = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("url.path", "/api/v1/critical/checkout")
                .sample();

        // then
        SamplerDecisionAssert.assertThat(result).isRecordAndSample();
        assertThat(result.getAttributes().get(REASON))
                .isEqualTo(PlatformSamplingReasons.ROUTE_RATIO);
    }

    @Test
    void route_ratio_0_drops_when_global_ratio_1() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(),
                true, Map.of("/api/v1/noisy", 0.0), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        // when
        SamplingResult result = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("url.path", "/api/v1/noisy/search")
                .sample();

        // then
        SamplerDecisionAssert.assertThat(result).isDrop();
        assertThat(result.getAttributes().get(REASON))
                .isEqualTo(PlatformSamplingReasons.ROUTE_RATIO_DROP);
    }

    @Test
    void path_outside_route_ratios_falls_back_to_global_ratio() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(),
                true, Map.of("/api/v1/noisy", 0.0), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        // when
        SamplingResult result = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("url.path", "/api/v1/orders")
                .sample();

        // then
        SamplerDecisionAssert.assertThat(result).isRecordAndSample();
        assertThat(result.getAttributes().get(REASON))
                .isEqualTo(PlatformSamplingReasons.GLOBAL_RATIO);
    }

    @Test
    void decision_is_deterministic_by_traceId_for_fractional_ratio() {
        // given
        TracingSettingsSource holderA = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of("/api", 0.5), 0.0);
        TracingSettingsSource holderB = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of("/api", 0.5), 0.0);
        CompositeSampler samplerA = new CompositeSampler(
                holderA, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));
        CompositeSampler samplerB = new CompositeSampler(
                holderB, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        String traceId = "0af7651916cd43dd8448eb211c80319c";
        SamplingDecision first = sampleDecision(samplerA, traceId);
        // when/then
        for (int i = 0; i < 100; i++) {
            assertThat(sampleDecision(samplerA, traceId))
                    .as("повторный вызов #%s с тем же traceId", i)
                    .isEqualTo(first);
        }
        assertThat(sampleDecision(samplerB, traceId))
                .as("независимый инстанс с той же конфигурацией")
                .isEqualTo(first);
    }

    @Test
    void overlapping_prefixes_most_specific_wins_deterministically() {
        // given
        java.util.LinkedHashMap<String, Double> ratios = new java.util.LinkedHashMap<>();
        ratios.put("/api", 0.0);
        ratios.put("/api/v2", 0.0);
        ratios.put("/api/v2/orders", 1.0);
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, ratios, 0.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on")));

        // when
        SamplingResult result = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("url.path", "/api/v2/orders/123")
                .sample();

        // then
        SamplerDecisionAssert.assertThat(result).isRecordAndSample();
        assertThat(result.getAttributes().get(REASON))
                .isEqualTo(PlatformSamplingReasons.ROUTE_RATIO);
    }

    @Test
    void hard_drop_beats_route_ratio_1() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(),
                true, Map.of("/api/v1/critical", 1.0), 1.0);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of("/api/v1/critical"), List.of("on")));

        // when
        SamplingResult result = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("url.path", "/api/v1/critical/checkout")
                .sample();

        // then
        SamplerDecisionAssert.assertThat(result).isDrop();
        assertThat(result.getAttributes().get(REASON))
                .isEqualTo(PlatformSamplingReasons.DROP_PATH);
    }

    private static SamplingDecision sampleDecision(CompositeSampler sampler, String traceId) {
        return SamplerHarness.of(sampler)
                .traceId(traceId)
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("url.path", "/api/v1/orders")
                .sample()
                .getDecision();
    }
}
