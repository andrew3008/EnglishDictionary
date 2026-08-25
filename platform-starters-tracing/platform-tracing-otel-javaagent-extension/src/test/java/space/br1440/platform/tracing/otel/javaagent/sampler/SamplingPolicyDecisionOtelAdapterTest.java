package space.br1440.platform.tracing.otel.javaagent.sampler;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.test.harness.SamplingDecisionCase;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

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
        assertThat(SamplingPolicyOtelAdapter.metricReasonCode(SamplingPolicyDecision.abstain()))
                .isEqualTo(PlatformSamplingReasons.FALLBACK_DROP);
    }

    /**
     * Полный список 11 wire-ключей {@code decision:reasonCode}, включая синтетический
     * {@code ABSTAIN → fallback_drop}, который недостижим через production-цепочку правил
     * ({@link space.br1440.platform.tracing.runtime.sampling.policy.ProductionSamplingPolicyChain#production()}
     * всегда завершается {@code DefaultRatioPolicyRule}, возвращающим только {@code SAMPLE}/
     * {@code DROP}) — поэтому проверяется здесь изолированно от {@link CompositeSampler},
     * а не через реальный прогон движка (см. интеграционный тест на 10 достижимых веток).
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allReasonCodeMappings")
    void metricKeyCoversAllElevenWireKeys(SamplingPolicyDecision decision, String expectedWireKey) {
        SamplingResult result = SamplingPolicyOtelAdapter.toSamplingResult(decision);
        String actualWireKey = result.getDecision().name()
                + ":"
                + SamplingPolicyOtelAdapter.metricReasonCode(decision);

        assertThat(actualWireKey).isEqualTo(expectedWireKey);
    }

    private static Stream<Arguments> allReasonCodeMappings() {
        return Stream.of(
                Arguments.of(
                        SamplingPolicyDecision.recordAndSample(SamplingPolicyReason.FORCE_HEADER, "force_header_rule"),
                        "RECORD_AND_SAMPLE:" + PlatformSamplingReasons.FORCE_HEADER),
                Arguments.of(
                        SamplingPolicyDecision.recordAndSample(SamplingPolicyReason.QA_TRACE, "qa_trace_rule"),
                        "RECORD_AND_SAMPLE:" + PlatformSamplingReasons.QA_TRACE),
                Arguments.of(
                        SamplingPolicyDecision.recordAndSample(SamplingPolicyReason.PARENT_DECISION, "parent_decision"),
                        "RECORD_AND_SAMPLE:" + PlatformSamplingReasons.PARENT_SAMPLED),
                Arguments.of(
                        SamplingPolicyDecision.recordAndSample(SamplingPolicyReason.ROUTE_RATIO, "route_ratio"),
                        "RECORD_AND_SAMPLE:" + PlatformSamplingReasons.ROUTE_RATIO),
                Arguments.of(
                        SamplingPolicyDecision.recordAndSample(SamplingPolicyReason.DEFAULT_RATIO, "default_ratio"),
                        "RECORD_AND_SAMPLE:" + PlatformSamplingReasons.GLOBAL_RATIO),
                Arguments.of(
                        SamplingPolicyDecision.drop(SamplingPolicyReason.KILL_SWITCH, "kill_switch"),
                        "DROP:" + PlatformSamplingReasons.KILL_SWITCH),
                Arguments.of(
                        SamplingPolicyDecision.drop(SamplingPolicyReason.HARD_DROP, "hard_drop"),
                        "DROP:" + PlatformSamplingReasons.DROP_PATH),
                Arguments.of(
                        SamplingPolicyDecision.drop(SamplingPolicyReason.PARENT_DROP, "parent_decision"),
                        "DROP:" + PlatformSamplingReasons.PARENT_DROP),
                Arguments.of(
                        SamplingPolicyDecision.drop(SamplingPolicyReason.ROUTE_RATIO_DROP, "route_ratio"),
                        "DROP:" + PlatformSamplingReasons.ROUTE_RATIO_DROP),
                Arguments.of(
                        SamplingPolicyDecision.drop(SamplingPolicyReason.DEFAULT_RATIO_DROP, "default_ratio"),
                        "DROP:" + PlatformSamplingReasons.GLOBAL_RATIO_DROP),
                Arguments.of(
                        SamplingPolicyDecision.abstain(),
                        "DROP:" + PlatformSamplingReasons.FALLBACK_DROP));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("characterizedCases")
    void adapterRequest_producesSameDecisionAsComposite(SamplingDecisionCase c) {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(
                c.enabled(),
                c.routeRatios(),
                c.defaultRatio());
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(c.droppedRoutes(), c.forceRecordValues()));

        SamplingResult compositeResult = sampleViaComposite(sampler, c);
        SamplingResult adapterResult = SamplingPolicyOtelAdapter.toSamplingResult(
                sampler.policyEngine().evaluate(
                        SamplingPolicyOtelAdapter.toRequest(
                                c.parentContext(),
                                c.traceId(),
                                c.urlPath() == null
                                        ? io.opentelemetry.api.common.Attributes.empty()
                                        : io.opentelemetry.api.common.Attributes.builder()
                                                .put("url.path", c.urlPath())
                                                .build()),
                        holder.current().samplingEnabled(),
                        holder.current().samplingRouteRatios(),
                        holder.current().samplingRatio(),
                        sampler.categoryBOverrides()));

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
