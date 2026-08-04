package space.br1440.platform.tracing.otel.javaagent.sampler;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyView;
import space.br1440.platform.tracing.otel.sampling.engine.SamplingPolicyEngine;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyRequest;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import lombok.NonNull;

public final class CompositeSampler implements Sampler, PlatformManagedSampler {

    private final AtomicReference<AcceptedPolicyAccess> acceptedPolicy;
    private final SamplingPolicyEngine policyEngine;

    private final ConcurrentHashMap<String, LongAdder> decisionCounters = new ConcurrentHashMap<>();

    public CompositeSampler(AcceptedPolicyAccess acceptedPolicy) {
        this.acceptedPolicy = new AtomicReference<>(Objects.requireNonNull(acceptedPolicy, "acceptedPolicy"));
        this.policyEngine = SamplingPolicyEngine.productionEngine();
    }

    @Override
    public SamplingResult shouldSample(@NonNull Context parentContext, @NonNull String traceId, @NonNull String name,
                                       @NonNull SpanKind spanKind, @NonNull Attributes attributes,
                                       @NonNull List<LinkData> parentLinks) {
        AcceptedPolicyAccess access = acceptedPolicy.get();
        AcceptedPolicyView policy = access.current();
        SamplingPolicyRequest request = SamplingPolicyOtelAdapter.toRequest(
                parentContext, traceId, name, spanKind, attributes, parentLinks
        );

        SamplingPolicyDecision decision = policyEngine.evaluate(
                request,
                policy.samplingEnabled(),
                policy.samplingRouteRatios(),
                policy.samplingRatio(),
                access.currentLegacySamplingRules());
        SamplingResult result = SamplingPolicyOtelAdapter.toSamplingResult(decision);
        recordDecision(result.getDecision(), SamplingPolicyOtelAdapter.metricReasonCode(decision));
        return result;
    }

    private void recordDecision(SamplingDecision decision, String reason) {
        String key = decision.name() + ":" + reason;
        decisionCounters.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    @Override
    public String getDescription() {
        return String.format("PlatformRuleBasedSampler{version=%d, defaultRatio=%.2f}",
                acceptedPolicy.get().current().acceptedSequence(),
                acceptedPolicy.get().current().samplingRatio());
    }

    @Override
    public String toString() {
        return getDescription();
    }

    @Override
    public CompositeSampler platformCompositeSampler() {
        return this;
    }

    public AcceptedPolicyAccess acceptedPolicy() {
        return acceptedPolicy.get();
    }

    public void bindAcceptedPolicy(AcceptedPolicyAccess policy) {
        acceptedPolicy.set(Objects.requireNonNull(policy, "policy"));
    }

    public SamplingPolicyEngine policyEngine() {
        return policyEngine;
    }

    public long getDecisionCount(String decision, String reason) {
        String key = decision + ":" + reason;
        LongAdder adder = decisionCounters.get(key);
        return adder != null ? adder.sum() : 0L;
    }

    public Map<String, Long> getDecisionCounts() {
        if (decisionCounters.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Long> snapshot = new HashMap<>(decisionCounters.size());
        for (Map.Entry<String, LongAdder> entry : decisionCounters.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().sum());
        }
        return snapshot;
    }

    public void resetCounters() {
        for (LongAdder adder : decisionCounters.values()) {
            adder.reset();
        }
    }
}
