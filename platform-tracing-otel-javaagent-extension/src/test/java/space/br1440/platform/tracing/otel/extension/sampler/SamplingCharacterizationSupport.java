package space.br1440.platform.tracing.otel.extension.sampler;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.test.assertions.SamplingAssertions;
import space.br1440.platform.tracing.test.harness.SamplerHarness;
import space.br1440.platform.tracing.test.harness.SamplingContextFactory;
import space.br1440.platform.tracing.test.harness.SamplingDecisionCase;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Выполнение {@link SamplingDecisionCase} против текущего {@link CompositeSampler}.
 */
final class SamplingCharacterizationSupport {

    private SamplingCharacterizationSupport() {
    }

    static void assertCharacterizedCase(SamplingDecisionCase c) {
        SamplerStateHolder holder = new SamplerStateHolder(
                c.enabled(),
                c.droppedRoutes(),
                c.forceRecordValues(),
                c.routeRatios(),
                c.defaultRatio());
        CompositeSampler sampler = new CompositeSampler(holder);
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
        assertThat(sampler.getDecisionCount(result.getDecision().name(), c.expectedWinningRule()))
                .as("winning rule counter for case %s", c.caseId())
                .isEqualTo(1L);
    }

    static Stream<SamplingDecisionCase> decisionMatrix() {
        return Stream.of(
                new SamplingDecisionCase(
                        "KS-01",
                        false, List.of(), List.of("on"), Map.of(), 1.0,
                        SamplingContextFactory.withForceAndQa("on"),
                        "/api/orders", null,
                        SamplingDecision.DROP, null, PlatformSamplingReasons.KILL_SWITCH),

                new SamplingDecisionCase(
                        "HD-01",
                        true, List.of("/actuator"), List.of("on"), Map.of(), 1.0,
                        SamplingContextFactory.withForceHeader("on"),
                        "/actuator/health", null,
                        SamplingDecision.DROP, PlatformSamplingReasons.DROP_PATH, "hard_drop"),

                new SamplingDecisionCase(
                        "FH-01",
                        true, List.of(), List.of("on"), Map.of(), 0.0,
                        SamplingContextFactory.withForceHeader("on"),
                        null, null,
                        SamplingDecision.RECORD_AND_SAMPLE,
                        PlatformSamplingReasons.FORCE_HEADER,
                        PlatformSamplingReasons.FORCE_HEADER),

                new SamplingDecisionCase(
                        "QA-01",
                        true, List.of(), List.of("on"), Map.of(), 0.0,
                        SamplingContextFactory.withQaTrace(),
                        null, null,
                        SamplingDecision.RECORD_AND_SAMPLE,
                        PlatformSamplingReasons.QA_TRACE,
                        PlatformSamplingReasons.QA_TRACE),

                new SamplingDecisionCase(
                        "FH-QA",
                        true, List.of(), List.of("on"), Map.of(), 0.0,
                        SamplingContextFactory.withForceAndQa("on"),
                        null, null,
                        SamplingDecision.RECORD_AND_SAMPLE,
                        PlatformSamplingReasons.FORCE_HEADER,
                        PlatformSamplingReasons.FORCE_HEADER),

                new SamplingDecisionCase(
                        "PD-S",
                        true, List.of(), List.of("on"), Map.of(), 0.0,
                        SamplingContextFactory.withSampledParent(),
                        null, null,
                        SamplingDecision.RECORD_AND_SAMPLE,
                        PlatformSamplingReasons.PARENT_SAMPLED,
                        "parent_decision"),

                new SamplingDecisionCase(
                        "PD-D",
                        true, List.of(), List.of("on"), Map.of(), 1.0,
                        SamplingContextFactory.withNotSampledParent(),
                        null, null,
                        SamplingDecision.DROP,
                        PlatformSamplingReasons.PARENT_DROP,
                        "parent_decision"),

                new SamplingDecisionCase(
                        "PD-RR",
                        true, List.of(), List.of("on"), Map.of("/api/v1/critical", 1.0), 1.0,
                        SamplingContextFactory.withNotSampledParent(),
                        "/api/v1/critical/checkout", null,
                        SamplingDecision.DROP,
                        PlatformSamplingReasons.PARENT_DROP,
                        "parent_decision"),

                new SamplingDecisionCase(
                        "RR-01",
                        true, List.of(), List.of("on"), Map.of("/api/v1/critical", 1.0), 0.0,
                        SamplingContextFactory.root(),
                        "/api/v1/critical/checkout", null,
                        SamplingDecision.RECORD_AND_SAMPLE,
                        PlatformSamplingReasons.ROUTE_RATIO,
                        PlatformSamplingReasons.ROUTE_RATIO),

                new SamplingDecisionCase(
                        "RR-D",
                        true, List.of(), List.of("on"), Map.of("/api/v1/noisy", 0.0), 1.0,
                        SamplingContextFactory.root(),
                        "/api/v1/noisy/search", null,
                        SamplingDecision.DROP,
                        PlatformSamplingReasons.ROUTE_RATIO_DROP,
                        PlatformSamplingReasons.ROUTE_RATIO),

                new SamplingDecisionCase(
                        "GR-01",
                        true, List.of(), List.of("on"), Map.of("/api/v1/noisy", 0.0), 1.0,
                        SamplingContextFactory.root(),
                        "/api/v1/orders", null,
                        SamplingDecision.RECORD_AND_SAMPLE,
                        PlatformSamplingReasons.GLOBAL_RATIO,
                        "default_ratio"),

                new SamplingDecisionCase(
                        "GR-D",
                        true, List.of(), List.of("on"), Map.of(), 0.0,
                        SamplingContextFactory.root(),
                        null, null,
                        SamplingDecision.DROP,
                        PlatformSamplingReasons.GLOBAL_RATIO_DROP,
                        "default_ratio")
        );
    }
}
