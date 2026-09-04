package space.br1440.platform.tracing.otel.javaagent.sampler;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.runtime.sampling.engine.SamplingPolicyEngine;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicySnapshot;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import lombok.NonNull;

public final class CompositeSampler implements Sampler, PlatformManagedSampler {

    private final AtomicReference<TracingSettingsSource> acceptedPolicy;
    private final SamplingPolicyEngine policyEngine;
    private final SamplingPolicySnapshot categoryBOverrides;

    private final ConcurrentHashMap<String, LongAdder> decisionCounters = new ConcurrentHashMap<>();

    public CompositeSampler(TracingSettingsSource acceptedPolicy, SamplingPolicySnapshot categoryBOverrides) {
        this.acceptedPolicy = new AtomicReference<>(Objects.requireNonNull(acceptedPolicy, "acceptedPolicy"));
        this.categoryBOverrides = Objects.requireNonNull(categoryBOverrides, "categoryBOverrides");
        this.policyEngine = SamplingPolicyEngine.productionEngine();
    }

    @Override
    public SamplingResult shouldSample(@NonNull Context parentContext, @NonNull String traceId, @NonNull String name,
                                       @NonNull SpanKind spanKind, @NonNull Attributes attributes,
                                       @NonNull List<LinkData> parentLinks) {
        TracingSettingsSource access = acceptedPolicy.get();
        TracingSettingsRevision policy = access.current();
        SamplingPolicyRequest request = SamplingPolicyOtelAdapter.toRequest(
                parentContext, traceId, attributes
        );

        SamplingPolicyDecision decision = policyEngine.evaluate(
                request,
                policy.samplingEnabled(),
                policy.samplingRouteRatios(),
                policy.samplingRatio(),
                categoryBOverrides
        );
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
        return String.format(Locale.ROOT, "PlatformRuleBasedSampler{version=%d, defaultRatio=%.2f}",
                acceptedPolicy.get().current().acceptedSequence(),
                acceptedPolicy.get().current().samplingRatio()
        );
    }

    @Override
    public String toString() {
        return getDescription();
    }

    @Override
    public CompositeSampler platformCompositeSampler() {
        return this;
    }

    public TracingSettingsSource acceptedPolicy() {
        return acceptedPolicy.get();
    }

    public SamplingPolicySnapshot categoryBOverrides() {
        return categoryBOverrides;
    }

    public void bindAcceptedPolicy(TracingSettingsSource policy) {
        acceptedPolicy.set(Objects.requireNonNull(policy, "policy"));
    }

    public SamplingPolicyEngine policyEngine() {
        return policyEngine;
    }

    public long getDecisionCount(String decision, String reason) {
        String key = decision + ":" + reason;
        LongAdder adder = decisionCounters.get(key);
        return (adder != null) ? adder.sum() : 0L;
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
