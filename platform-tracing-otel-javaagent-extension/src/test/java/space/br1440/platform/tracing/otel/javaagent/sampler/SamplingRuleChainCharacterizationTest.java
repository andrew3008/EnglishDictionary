package space.br1440.platform.tracing.otel.javaagent.sampler;

import io.opentelemetry.api.trace.SpanKind;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;
import space.br1440.platform.tracing.test.assertions.SamplingAssertions;
import space.br1440.platform.tracing.test.harness.SamplerHarness;
import space.br1440.platform.tracing.test.harness.SamplingContextFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Приоритеты и взаимодействия правил rule chain — observed current behavior (PR-5A).
 */
class SamplingRuleChainCharacterizationTest {

    @Test
    void kill_switch_блокирует_force_qa_route_и_global_ratio() {
        SamplerStateHolder holder = new SamplerStateHolder(
                false,
                List.of(),
                List.of("on"),
                Map.of("/api", 1.0),
                1.0);
        CompositeSampler sampler = new CompositeSampler(holder);
        sampler.resetCounters();

        var result = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .parentContext(SamplingContextFactory.withForceAndQa("on"))
                .putStringAttribute("url.path", "/api/orders")
                .sample();

        SamplerDecisionAssert.assertThat(result).isDrop();
        SamplingAssertions.assertSamplingReason(result, null);
        assertThat(sampler.getDecisionCount("DROP", PlatformSamplingReasons.KILL_SWITCH)).isEqualTo(1L);
    }

    @Test
    void hard_drop_раньше_force_header_и_qa() {
        SamplerStateHolder holder = new SamplerStateHolder(
                true,
                List.of("/internal"),
                List.of("on"),
                Map.of("/internal", 1.0),
                1.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        var forceResult = SamplerHarness.of(sampler)
                .parentContext(SamplingContextFactory.withForceHeader("on"))
                .putStringAttribute("url.path", "/internal/metrics")
                .sample();
        SamplingAssertions.assertDecision(forceResult, io.opentelemetry.sdk.trace.samplers.SamplingDecision.DROP);
        SamplingAssertions.assertSamplingReason(forceResult, PlatformSamplingReasons.DROP_PATH);

        var qaResult = SamplerHarness.of(sampler)
                .parentContext(SamplingContextFactory.withQaTrace())
                .putStringAttribute("url.path", "/internal/metrics")
                .sample();
        SamplingAssertions.assertDecision(qaResult, io.opentelemetry.sdk.trace.samplers.SamplingDecision.DROP);
        SamplingAssertions.assertSamplingReason(qaResult, PlatformSamplingReasons.DROP_PATH);
    }

    @Test
    void force_header_раньше_parent_decision() {
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of("on"), Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        var result = SamplerHarness.of(sampler)
                .parentContext(SamplingContextFactory.withNotSampledParentAndForceHeader("on"))
                .putStringAttribute("url.path", "/api/orders")
                .sample();

        SamplerDecisionAssert.assertThat(result).isRecordAndSample();
        SamplingAssertions.assertSamplingReason(result, PlatformSamplingReasons.FORCE_HEADER);
    }

    @Test
    void qa_trace_раньше_parent_decision() {
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of("on"), Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        var result = SamplerHarness.of(sampler)
                .parentContext(SamplingContextFactory.withNotSampledParentAndQaTrace())
                .putStringAttribute("url.path", "/api/orders")
                .sample();

        SamplerDecisionAssert.assertThat(result).isRecordAndSample();
        SamplingAssertions.assertSamplingReason(result, PlatformSamplingReasons.QA_TRACE);
    }

    @Test
    void отсутствие_url_path_пропускает_hard_drop_и_route_ratio() {
        SamplerStateHolder holder = new SamplerStateHolder(
                true,
                List.of("/actuator"),
                List.of("on"),
                Map.of("/api", 0.0),
                1.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        var result = SamplerHarness.of(sampler).sample();

        SamplerDecisionAssert.assertThat(result).isRecordAndSample();
        SamplingAssertions.assertSamplingReason(result, PlatformSamplingReasons.GLOBAL_RATIO);
    }

    @Test
    void custom_force_value_срабатывает_при_совпадении_forceRecordValues() {
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of("custom-force"), Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        var result = SamplerHarness.of(sampler)
                .parentContext(SamplingContextFactory.withForceHeader("custom-force"))
                .sample();

        SamplerDecisionAssert.assertThat(result).isRecordAndSample();
        SamplingAssertions.assertSamplingReason(result, PlatformSamplingReasons.FORCE_HEADER);
    }
}
