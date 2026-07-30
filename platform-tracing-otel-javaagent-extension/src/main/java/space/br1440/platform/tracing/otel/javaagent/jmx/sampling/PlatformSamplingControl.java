package space.br1440.platform.tracing.otel.javaagent.jmx.sampling;

import java.util.Collections;
import java.util.Map;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

public final class PlatformSamplingControl implements PlatformSamplingControlMBean {

    private final AcceptedPolicyAccess acceptedPolicy;
    private final CompositeSampler compositeSampler;

    public PlatformSamplingControl(
            AcceptedPolicyAccess acceptedPolicy,
            CompositeSampler compositeSampler) {
        this.acceptedPolicy = acceptedPolicy;
        this.compositeSampler = compositeSampler;
    }

    @Override
    public boolean isSamplerEnabled() {
        return acceptedPolicy != null && acceptedPolicy.current().samplingEnabled();
    }

    @Override
    public Map<String, Double> getRouteRatios() {
        return acceptedPolicy == null
                ? Collections.emptyMap()
                : acceptedPolicy.current().samplingRouteRatios();
    }

    @Override
    public double getSamplingRatio() {
        return acceptedPolicy == null ? -1.0d : acceptedPolicy.current().samplingRatio();
    }

    @Override
    public long getSamplingConfigVersion() {
        return acceptedPolicy == null ? -1L : acceptedPolicy.current().acceptedSequence();
    }

    @Override
    public String getSamplingConfigLastUpdatedSource() {
        return acceptedPolicy == null ? "unknown" : "accepted-state";
    }

    @Override
    public long getSamplerDecisionCount(String decision, String reason) {
        return compositeSampler == null ? 0L : compositeSampler.getDecisionCount(decision, reason);
    }

    @Override
    public Map<String, Long> getSamplerDecisionCounts() {
        return compositeSampler == null
                ? Collections.emptyMap()
                : compositeSampler.getDecisionCounts();
    }
}
