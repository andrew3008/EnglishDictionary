package space.br1440.platform.tracing.otel.javaagent.sampler;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;

import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecisionType;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.test.assertions.SamplingAssertions;
import space.br1440.platform.tracing.test.harness.SamplerHarness;
import space.br1440.platform.tracing.test.harness.SamplingContextFactory;
import space.br1440.platform.tracing.test.harness.SamplingDecisionCase;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.experimental.UtilityClass;

@UtilityClass
final class SamplingCharacterizationSupport {

    static void assertCharacterizedCase(SamplingDecisionCase c) {
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(),
                c.enabled(),
                c.routeRatios(),
                c.defaultRatio());
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(c.droppedRoutes(), c.forceRecordValues()));
        sampler.resetCounters();

        SamplerHarness harness = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .traceId(c.traceId())
                .parentContext(c.parentContext());
        if (c.urlPath() != null) {
            harness.putStringAttribute("url.path", c.urlPath());
        }

        SamplingResult result = harness.sample();

        SamplingAssertions.assertDecision(result, c.expectedDecision());
        SamplingAssertions.assertSamplingReason(result, c.expectedReason());

        assertThat(sampler.getDecisionCount(result.getDecision().name(), c.expectedMetricReasonCode()))
                .as("JMX decision:reasonCode counter for case %s", c.caseId())
                .isEqualTo(1L);

        assertRuleOwnership(sampler, c);
    }

    private static void assertRuleOwnership(CompositeSampler sampler, SamplingDecisionCase c) {
        SamplingPolicyRequest request = SamplingPolicyOtelAdapter.toRequest(
                c.parentContext(),
                c.traceId(),
                c.urlPath() == null
                        ? Attributes.empty()
                        : Attributes.builder().put("url.path", c.urlPath()).build());
        SamplingPolicyDecision decision = sampler.policyEngine().evaluate(
                request,
                sampler.acceptedPolicy().current().samplingEnabled(),
                sampler.acceptedPolicy().current().samplingRouteRatios(),
                sampler.acceptedPolicy().current().samplingRatio(),
                sampler.categoryBOverrides());

        String actualWinningRule = decision.decisionType() == SamplingPolicyDecisionType.ABSTAIN
                ? null
                : decision.winningRule();
        assertThat(actualWinningRule)
                .as("winning rule for case %s", c.caseId())
                .isEqualTo(c.expectedWinningRule());
    }

    static Stream<SamplingDecisionCase> decisionMatrix() {
        return Stream.of(
                new SamplingDecisionCase(
                        "KS-01",
                        false, List.of(), List.of("on"), Map.of(), 1.0,
                        SamplingContextFactory.withForceAndQa("on"),
                        "/api/orders", null,
                        SamplingDecision.DROP, null, PlatformSamplingReasons.KILL_SWITCH,
                        PlatformSamplingReasons.KILL_SWITCH),

                new SamplingDecisionCase(
                        "HD-01",
                        true, List.of("/actuator"), List.of("on"), Map.of(), 1.0,
                        SamplingContextFactory.withForceHeader("on"),
                        "/actuator/health", null,
                        SamplingDecision.DROP, PlatformSamplingReasons.DROP_PATH, "hard_drop",
                        PlatformSamplingReasons.DROP_PATH),

                new SamplingDecisionCase(
                        "FH-01",
                        true, List.of(), List.of("on"), Map.of(), 0.0,
                        SamplingContextFactory.withForceHeader("on"),
                        null, null,
                        SamplingDecision.RECORD_AND_SAMPLE,
                        PlatformSamplingReasons.FORCE_HEADER,
                        PlatformSamplingReasons.FORCE_HEADER,
                        PlatformSamplingReasons.FORCE_HEADER),

                new SamplingDecisionCase(
                        "QA-01",
                        true, List.of(), List.of("on"), Map.of(), 0.0,
                        SamplingContextFactory.withQaTrace(),
                        null, null,
                        SamplingDecision.RECORD_AND_SAMPLE,
                        PlatformSamplingReasons.QA_TRACE,
                        PlatformSamplingReasons.QA_TRACE,
                        PlatformSamplingReasons.QA_TRACE),

                new SamplingDecisionCase(
                        "FH-QA",
                        true, List.of(), List.of("on"), Map.of(), 0.0,
                        SamplingContextFactory.withForceAndQa("on"),
                        null, null,
                        SamplingDecision.RECORD_AND_SAMPLE,
                        PlatformSamplingReasons.FORCE_HEADER,
                        PlatformSamplingReasons.FORCE_HEADER,
                        PlatformSamplingReasons.FORCE_HEADER),

                new SamplingDecisionCase(
                        "PD-S",
                        true, List.of(), List.of("on"), Map.of(), 0.0,
                        SamplingContextFactory.withSampledParent(),
                        null, null,
                        SamplingDecision.RECORD_AND_SAMPLE,
                        PlatformSamplingReasons.PARENT_SAMPLED,
                        "parent_decision",
                        PlatformSamplingReasons.PARENT_SAMPLED),

                new SamplingDecisionCase(
                        "PD-D",
                        true, List.of(), List.of("on"), Map.of(), 1.0,
                        SamplingContextFactory.withNotSampledParent(),
                        null, null,
                        SamplingDecision.DROP,
                        PlatformSamplingReasons.PARENT_DROP,
                        "parent_decision",
                        PlatformSamplingReasons.PARENT_DROP),

                new SamplingDecisionCase(
                        "PD-RR",
                        true, List.of(), List.of("on"), Map.of("/api/v1/critical", 1.0), 1.0,
                        SamplingContextFactory.withNotSampledParent(),
                        "/api/v1/critical/checkout", null,
                        SamplingDecision.DROP,
                        PlatformSamplingReasons.PARENT_DROP,
                        "parent_decision",
                        PlatformSamplingReasons.PARENT_DROP),

                new SamplingDecisionCase(
                        "RR-01",
                        true, List.of(), List.of("on"), Map.of("/api/v1/critical", 1.0), 0.0,
                        SamplingContextFactory.root(),
                        "/api/v1/critical/checkout", null,
                        SamplingDecision.RECORD_AND_SAMPLE,
                        PlatformSamplingReasons.ROUTE_RATIO,
                        PlatformSamplingReasons.ROUTE_RATIO,
                        PlatformSamplingReasons.ROUTE_RATIO),

                new SamplingDecisionCase(
                        "RR-D",
                        true, List.of(), List.of("on"), Map.of("/api/v1/noisy", 0.0), 1.0,
                        SamplingContextFactory.root(),
                        "/api/v1/noisy/search", null,
                        SamplingDecision.DROP,
                        PlatformSamplingReasons.ROUTE_RATIO_DROP,
                        PlatformSamplingReasons.ROUTE_RATIO,
                        PlatformSamplingReasons.ROUTE_RATIO_DROP),

                new SamplingDecisionCase(
                        "GR-01",
                        true, List.of(), List.of("on"), Map.of("/api/v1/noisy", 0.0), 1.0,
                        SamplingContextFactory.root(),
                        "/api/v1/orders", null,
                        SamplingDecision.RECORD_AND_SAMPLE,
                        PlatformSamplingReasons.GLOBAL_RATIO,
                        "default_ratio",
                        PlatformSamplingReasons.GLOBAL_RATIO),

                new SamplingDecisionCase(
                        "GR-D",
                        true, List.of(), List.of("on"), Map.of(), 0.0,
                        SamplingContextFactory.root(),
                        null, null,
                        SamplingDecision.DROP,
                        PlatformSamplingReasons.GLOBAL_RATIO_DROP,
                        "default_ratio",
                        PlatformSamplingReasons.GLOBAL_RATIO_DROP)
        );
    }
}
