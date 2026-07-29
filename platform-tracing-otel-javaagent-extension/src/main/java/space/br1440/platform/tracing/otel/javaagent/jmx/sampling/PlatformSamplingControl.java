package space.br1440.platform.tracing.otel.javaagent.jmx.sampling;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

import lombok.extern.slf4j.Slf4j;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyUpdateOutcome;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyView;
import space.br1440.platform.tracing.otel.javaagent.jmx.support.JmxConfigReloadRecorder;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.javaagent.utils.Strings;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;

@Slf4j
public final class PlatformSamplingControl implements PlatformSamplingControlMBean {

    private final AcceptedPolicyAccess acceptedPolicy;
    private final CompositeSampler compositeSampler;
    private final LongAdder invalidConfigCounter;

    public PlatformSamplingControl(
            AcceptedPolicyAccess acceptedPolicy,
            CompositeSampler compositeSampler,
            LongAdder invalidConfigCounter) {
        this.acceptedPolicy = acceptedPolicy;
        this.compositeSampler = compositeSampler;
        this.invalidConfigCounter = invalidConfigCounter;
    }

    @Override
    public boolean isSamplerEnabled() {
        return acceptedPolicy != null && acceptedPolicy.current().samplingEnabled();
    }

    @Override
    public void setSamplerEnabled(boolean enabled) {
        AcceptedPolicyView current = requirePolicy().current();
        recordOutcome(requirePolicy().updateSampling(
                enabled,
                current.samplingRatio(),
                current.samplingRouteRatios()));
    }

    @Override
    public Map<String, Double> getRouteRatios() {
        return acceptedPolicy == null
                ? Collections.emptyMap()
                : acceptedPolicy.current().samplingRouteRatios();
    }

    @Override
    public void setRouteRatios(Map<String, Double> ratios) {
        validateRouteRatios(ratios);
        AcceptedPolicyView current = requirePolicy().current();
        recordOutcome(requirePolicy().updateSampling(
                current.samplingEnabled(),
                current.samplingRatio(),
                ratios));
    }

    @Override
    public double getSamplingRatio() {
        return acceptedPolicy == null ? -1.0d : acceptedPolicy.current().samplingRatio();
    }

    @Override
    public void setSamplingRatio(double newRatio) {
        validateRatio(newRatio);
        AcceptedPolicyView current = requirePolicy().current();
        recordOutcome(requirePolicy().updateSampling(
                current.samplingEnabled(),
                newRatio,
                current.samplingRouteRatios()));
    }

    @Override
    public String[] getDropPathPrefixes() {
        if (acceptedPolicy == null) {
            return new String[0];
        }
        List<String> values = acceptedPolicy.currentLegacySamplingRules().getDroppedRoutes();
        return values.toArray(new String[0]);
    }

    @Override
    public void setDropPathPrefixes(String[] prefixes) {
        validateDropPaths(prefixes);
        recordOutcome(requirePolicy().updateDropPaths(Arrays.asList(prefixes)));
    }

    @Override
    public String[] getForceRecordValues() {
        if (acceptedPolicy == null) {
            return new String[0];
        }
        return acceptedPolicy.currentLegacySamplingRules()
                .getForceRecordValues()
                .toArray(new String[0]);
    }

    @Override
    public void setForceRecordValues(String[] values) {
        validateForceValues(values);
        recordOutcome(requirePolicy().updateForceRecordValues(Set.copyOf(Arrays.asList(values))));
    }

    @Override
    public void updateSamplingPolicy(
            boolean enabled,
            double ratio,
            Map<String, Double> routeRatios,
            String[] dropPaths,
            String[] forceValues) {
        validateRouteRatios(routeRatios);
        updateSamplingPolicy(
                enabled,
                ratio,
                dropPaths,
                forceValues,
                routeRatios == null ? new String[0] : routeRatios.keySet().toArray(new String[0]),
                routeRatioValues(routeRatios),
                "JMX");
    }

    @Override
    public synchronized void updateSamplingPolicy(
            boolean enabled,
            double defaultRatio,
            String[] droppedRoutes,
            String[] forceRecordValues,
            String[] routeRatioPrefixes,
            double[] routeRatioValues,
            String source) {
        validateRatio(defaultRatio);
        // null drop/force => preserve Agent Category B startup-only state (P6.1 spring reconcile).
        if (droppedRoutes != null) {
            validateDropPaths(droppedRoutes);
        }
        if (forceRecordValues != null) {
            validateForceValues(forceRecordValues);
        }
        Map<String, Double> routeRatios =
                toRouteRatios(routeRatioPrefixes, routeRatioValues);

        AcceptedPolicyUpdateOutcome categoryA = requirePolicy().updateSampling(
                enabled,
                defaultRatio,
                routeRatios);
        if (!isAccepted(categoryA)) {
            recordOutcome(categoryA);
            JmxConfigReloadRecorder.record(
                    "sampling",
                    false,
                    requirePolicy().current().acceptedSequence());
            return;
        }

        boolean applied = true;
        if (droppedRoutes != null) {
            AcceptedPolicyUpdateOutcome dropOutcome =
                    requirePolicy().updateDropPaths(Arrays.asList(droppedRoutes));
            applied = isAccepted(dropOutcome);
        }
        if (forceRecordValues != null) {
            AcceptedPolicyUpdateOutcome forceOutcome =
                    requirePolicy().updateForceRecordValues(
                            Set.copyOf(Arrays.asList(forceRecordValues)));
            applied = applied && isAccepted(forceOutcome);
        }
        if (!applied) {
            invalidConfigCounter.increment();
        }

        long sequence = requirePolicy().current().acceptedSequence();
        JmxConfigReloadRecorder.record("sampling", applied, sequence);
        if (applied) {
            log.info(
                    "Sampling policy updated via accepted state (source={}). Sequence: {}",
                    source,
                    sequence);
        }
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

    @Override
    public void resetSamplerCounters() {
        if (compositeSampler != null) {
            compositeSampler.resetCounters();
        }
    }

    private AcceptedPolicyAccess requirePolicy() {
        if (acceptedPolicy == null) {
            throw new IllegalStateException("AcceptedPolicyAccess is not registered");
        }
        return acceptedPolicy;
    }

    private void recordOutcome(AcceptedPolicyUpdateOutcome outcome) {
        if (!isAccepted(outcome)) {
            invalidConfigCounter.increment();
        }
    }

    private static boolean isAccepted(AcceptedPolicyUpdateOutcome outcome) {
        return outcome == AcceptedPolicyUpdateOutcome.APPLIED
                || outcome == AcceptedPolicyUpdateOutcome.UNCHANGED;
    }

    private void validateRatio(double ratio) {
        if (ratio < 0.0d || ratio > 1.0d || !Double.isFinite(ratio)) {
            invalidConfigCounter.increment();
            throw new IllegalArgumentException("Ratio must be in [0.0, 1.0]");
        }
    }

    private void validateRouteRatios(Map<String, Double> ratios) {
        if (ratios == null) {
            invalidConfigCounter.increment();
            throw new IllegalArgumentException("Ratios map cannot be null");
        }
        for (Map.Entry<String, Double> entry : ratios.entrySet()) {
            if (Strings.isBlank(entry.getKey())) {
                invalidConfigCounter.increment();
                throw new IllegalArgumentException("Route ratio prefix must not be null or blank");
            }
            Double value = entry.getValue();
            if (value == null || !Double.isFinite(value) || value < 0.0d || value > 1.0d) {
                invalidConfigCounter.increment();
                throw new IllegalArgumentException("Route ratio must be in [0.0, 1.0]");
            }
        }
    }

    private void validateDropPaths(String[] prefixes) {
        if (prefixes == null) {
            invalidConfigCounter.increment();
            throw new IllegalArgumentException("Prefixes array cannot be null");
        }
        if (prefixes.length > 100) {
            invalidConfigCounter.increment();
            throw new IllegalArgumentException("Too many drop paths configured");
        }
        for (String prefix : prefixes) {
            if (prefix != null && !prefix.isEmpty() && !prefix.startsWith("/")) {
                invalidConfigCounter.increment();
                throw new IllegalArgumentException("Drop path must start with / or be empty");
            }
        }
    }

    private void validateForceValues(String[] values) {
        if (values == null) {
            invalidConfigCounter.increment();
            throw new IllegalArgumentException("Values array cannot be null");
        }
        if (values.length > 50) {
            invalidConfigCounter.increment();
            throw new IllegalArgumentException("Too many force record values");
        }
        for (String value : values) {
            if (value == null) {
                invalidConfigCounter.increment();
                throw new IllegalArgumentException("Force record value cannot be null");
            }
            if (value.length() > 255) {
                invalidConfigCounter.increment();
                throw new IllegalArgumentException("Force record value too long");
            }
        }
    }

    private void validateRouteRatioArrays(String[] prefixes, double[] values) {
        if (prefixes == null || values == null || prefixes.length != values.length) {
            invalidConfigCounter.increment();
            throw new IllegalArgumentException("Route ratio prefixes and values must have equal length");
        }
    }

    private Map<String, Double> toRouteRatios(String[] prefixes, double[] values) {
        validateRouteRatioArrays(prefixes, values);
        Map<String, Double> result = new LinkedHashMap<>(prefixes.length);
        for (int index = 0; index < prefixes.length; index++) {
            result.put(prefixes[index], values[index]);
        }
        validateRouteRatios(result);
        return result;
    }

    private static double[] routeRatioValues(Map<String, Double> routeRatios) {
        if (routeRatios == null || routeRatios.isEmpty()) {
            return new double[0];
        }
        double[] values = new double[routeRatios.size()];
        int index = 0;
        for (Double value : routeRatios.values()) {
            values[index++] = value;
        }
        return values;
    }
}
