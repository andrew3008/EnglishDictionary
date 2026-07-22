package space.br1440.platform.tracing.otel.javaagent.sampler;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.otel.propagation.control.PlatformTraceContextKeys;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.otel.sampling.model.ParentContextState;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecisionType;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyRequest;

import java.util.List;

final class SamplingPolicyOtelAdapter {

    private static final AttributeKey<String> SAMPLING_REASON_KEY =
            AttributeKey.stringKey(PlatformAttributes.PLATFORM_SAMPLING_REASON);
    private static final AttributeKey<String> URL_PATH_KEY = AttributeKey.stringKey("url.path");

    private static final SamplingResult FALLBACK_DROP = SamplingResult.create(SamplingDecision.DROP);
    private static final SamplingResult KILL_SWITCH_DROP = SamplingResult.create(SamplingDecision.DROP);
    private static final SamplingResult DROP_HARD = SamplingResult.create(
            SamplingDecision.DROP,
            Attributes.of(SAMPLING_REASON_KEY, PlatformSamplingReasons.DROP_PATH));
    private static final SamplingResult DROP_PARENT = SamplingResult.create(
            SamplingDecision.DROP,
            Attributes.of(SAMPLING_REASON_KEY, PlatformSamplingReasons.PARENT_DROP));
    private static final SamplingResult DROP_ROUTE_RATIO = SamplingResult.create(
            SamplingDecision.DROP,
            Attributes.of(SAMPLING_REASON_KEY, PlatformSamplingReasons.ROUTE_RATIO_DROP));
    private static final SamplingResult DROP_DEFAULT_RATIO = SamplingResult.create(
            SamplingDecision.DROP,
            Attributes.of(SAMPLING_REASON_KEY, PlatformSamplingReasons.GLOBAL_RATIO_DROP));
    private static final SamplingResult SAMPLE_FORCE_HEADER = SamplingResult.create(
            SamplingDecision.RECORD_AND_SAMPLE,
            Attributes.of(SAMPLING_REASON_KEY, PlatformSamplingReasons.FORCE_HEADER));
    private static final SamplingResult SAMPLE_QA_TRACE = SamplingResult.create(
            SamplingDecision.RECORD_AND_SAMPLE,
            Attributes.of(SAMPLING_REASON_KEY, PlatformSamplingReasons.QA_TRACE));
    private static final SamplingResult SAMPLE_PARENT = SamplingResult.create(
            SamplingDecision.RECORD_AND_SAMPLE,
            Attributes.of(SAMPLING_REASON_KEY, PlatformSamplingReasons.PARENT_SAMPLED));
    private static final SamplingResult ROUTE_RATIO_SAMPLE = new ForwardingSamplingResult(
            SamplingResult.create(SamplingDecision.RECORD_AND_SAMPLE),
            Attributes.of(SAMPLING_REASON_KEY, PlatformSamplingReasons.ROUTE_RATIO));
    private static final SamplingResult DEFAULT_RATIO_SAMPLE = new ForwardingSamplingResult(
            SamplingResult.create(SamplingDecision.RECORD_AND_SAMPLE),
            Attributes.of(SAMPLING_REASON_KEY, PlatformSamplingReasons.GLOBAL_RATIO));

    private SamplingPolicyOtelAdapter() {
    }

    static SamplingPolicyRequest toRequest(
            Context parentContext,
            String traceId,
            String name,
            SpanKind spanKind,
            io.opentelemetry.api.common.Attributes attributes,
            List<LinkData> parentLinks) {
        String urlPath = attributes.get(URL_PATH_KEY);
        InboundTraceControl control = parentContext.get(PlatformTraceContextKeys.TRACE_CONTROL);
        String forceValue = control == null ? null : control.rawForceTraceValue();
        boolean qaTrace = control != null && control.qaTrace();
        ParentContextState parentState = resolveParentState(parentContext);
        return new SamplingPolicyRequest(urlPath, traceId, forceValue, qaTrace, parentState);
    }

    static SamplingResult toSamplingResult(SamplingPolicyDecision decision) {
        if (decision.decisionType() == SamplingPolicyDecisionType.ABSTAIN) {
            return FALLBACK_DROP;
        }
        if (decision.decisionType() == SamplingPolicyDecisionType.RECORD_ONLY) {
            return SamplingResult.create(
                    SamplingDecision.RECORD_ONLY,
                    Attributes.of(SAMPLING_REASON_KEY, decision.reason().reasonCode()));
        }
        return switch (decision.reason()) {
            case KILL_SWITCH -> KILL_SWITCH_DROP;
            case HARD_DROP -> DROP_HARD;
            case FORCE_HEADER -> SAMPLE_FORCE_HEADER;
            case QA_TRACE -> SAMPLE_QA_TRACE;
            case PARENT_DECISION -> SAMPLE_PARENT;
            case PARENT_DROP -> DROP_PARENT;
            case ROUTE_RATIO -> ROUTE_RATIO_SAMPLE;
            case ROUTE_RATIO_DROP -> DROP_ROUTE_RATIO;
            case DEFAULT_RATIO -> DEFAULT_RATIO_SAMPLE;
            case DEFAULT_RATIO_DROP -> DROP_DEFAULT_RATIO;
            case NO_MATCH -> FALLBACK_DROP;
        };
    }

    static String metricRuleName(SamplingPolicyDecision decision) {
        if (decision.decisionType() == SamplingPolicyDecisionType.ABSTAIN) {
            return PlatformSamplingReasons.FALLBACK_DROP;
        }
        return decision.winningRule();
    }

    private static ParentContextState resolveParentState(Context parentContext) {
        SpanContext parent = Span.fromContext(parentContext).getSpanContext();
        if (!parent.isValid()) {
            return ParentContextState.ABSENT;
        }
        return parent.isSampled() ? ParentContextState.SAMPLED : ParentContextState.NOT_SAMPLED;
    }
}
