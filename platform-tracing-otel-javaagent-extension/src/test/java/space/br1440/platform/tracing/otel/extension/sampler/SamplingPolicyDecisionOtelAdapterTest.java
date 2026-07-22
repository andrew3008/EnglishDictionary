package space.br1440.platform.tracing.otel.extension.sampler;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyDecisionType;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.test.harness.SamplingContextFactory;
import space.br1440.platform.tracing.test.harness.SamplingDecisionCase;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SamplingPolicyDecisionOtelAdapterTest {

    private static final AttributeKey<String> REASON =
            AttributeKey.stringKey(PlatformAttributes.PLATFORM_SAMPLING_REASON);

    @Test
    void killSwitchDrop_hasNoSpanReasonAttribute() {
        SamplingPolicyDecision decision = SamplingPolicyDecision.drop(
                SamplingPolicyReason.KILL_SWITCH, PlatformSamplingReasons.KILL_SWITCH);

        SamplingResult result = SamplingPolicyOtelAdapter.toSamplingResult(decision);

        assertThat(result.getDecision()).isEqualTo(SamplingDecision.DROP);
        assertThat(result.getAttributes().get(REASON)).isNull();
    }

    @Test
    void hardDrop_mapsReasonAttribute() {
        SamplingPolicyDecision decision = SamplingPolicyDecision.drop(
                SamplingPolicyReason.HARD_DROP, "hard_drop");

        SamplingResult result = SamplingPolicyOtelAdapter.toSamplingResult(decision);

        assertThat(result.getDecision()).isEqualTo(SamplingDecision.DROP);
        assertThat(result.getAttributes().get(REASON)).isEqualTo(PlatformSamplingReasons.DROP_PATH);
    }

    @Test
    void routeRatioSample_usesForwardingResult() {
        SamplingPolicyDecision decision = SamplingPolicyDecision.recordAndSample(
                SamplingPolicyReason.ROUTE_RATIO, PlatformSamplingReasons.ROUTE_RATIO);

        SamplingResult result = SamplingPolicyOtelAdapter.toSamplingResult(decision);

        assertThat(result).isInstanceOf(ForwardingSamplingResult.class);
        assertThat(result.getDecision()).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        assertThat(result.getAttributes().get(REASON)).isEqualTo(PlatformSamplingReasons.ROUTE_RATIO);
    }

    @Test
    void abstain_mapsToFallbackDrop() {
        SamplingResult result = SamplingPolicyOtelAdapter.toSamplingResult(SamplingPolicyDecision.abstain());

        assertThat(result.getDecision()).isEqualTo(SamplingDecision.DROP);
        assertThat(SamplingPolicyOtelAdapter.metricRuleName(SamplingPolicyDecision.abstain()))
                .isEqualTo(PlatformSamplingReasons.FALLBACK_DROP);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("characterizedCases")
    void adapterRequest_producesSameDecisionAsComposite(SamplingDecisionCase c) {
        SamplerStateHolder holder = new SamplerStateHolder(
                c.enabled(),
                c.droppedRoutes(),
                c.forceRecordValues(),
                c.routeRatios(),
                c.defaultRatio());
        CompositeSampler sampler = new CompositeSampler(holder);

        SamplingResult compositeResult = sampleViaComposite(sampler, c);
        SamplingResult adapterResult = SamplingPolicyOtelAdapter.toSamplingResult(
                sampler.policyEngine().evaluate(
                        SamplingPolicyOtelAdapter.toRequest(
                                c.parentContext(),
                                c.traceId(),
                                "test-span",
                                SpanKind.SERVER,
                                c.urlPath() == null
                                        ? io.opentelemetry.api.common.Attributes.empty()
                                        : io.opentelemetry.api.common.Attributes.builder()
                                                .put("url.path", c.urlPath())
                                                .build(),
                                java.util.List.of()),
                        holder.current().policySnapshot()));

        assertThat(adapterResult.getDecision()).isEqualTo(compositeResult.getDecision());
        assertThat(adapterResult.getAttributes().get(REASON))
                .isEqualTo(compositeResult.getAttributes().get(REASON));
    }

    private static SamplingResult sampleViaComposite(CompositeSampler sampler, SamplingDecisionCase c) {
        var harness = space.br1440.platform.tracing.test.harness.SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .traceId(c.traceId())
                .parentContext(c.parentContext());
        if (c.urlPath() != null) {
            harness.putStringAttribute("url.path", c.urlPath());
        }
        return harness.sample();
    }

    private static Stream<SamplingDecisionCase> characterizedCases() {
        return SamplingCharacterizationSupport.decisionMatrix();
    }
}
