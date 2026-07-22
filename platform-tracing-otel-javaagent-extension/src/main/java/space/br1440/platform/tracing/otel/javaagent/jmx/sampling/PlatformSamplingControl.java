package space.br1440.platform.tracing.otel.javaagent.jmx.sampling;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.otel.javaagent.jmx.support.JmxConfigReloadRecorder;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.javaagent.sampler.SamplerState;
import space.br1440.platform.tracing.otel.javaagent.sampler.SamplerStateHolder;
import space.br1440.platform.tracing.otel.javaagent.utils.Strings;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
@RequiredArgsConstructor
public final class PlatformSamplingControl implements PlatformSamplingControlMBean {

    private final SamplerStateHolder configHolder;
    private final CompositeSampler compositeSampler;
    private final LongAdder invalidConfigCounter;

    @Override
    public boolean isSamplerEnabled() {
        return (configHolder != null) && configHolder.current().enabled();
    }

    @Override
    public void setSamplerEnabled(boolean enabled) {
        if (configHolder == null) {
            throw new IllegalStateException("SamplerStateHolder is not registered");
        }

        boolean applied = configHolder.tryUpdate(prev -> new SamplerState(
                enabled,
                prev.droppedRoutes(),
                prev.forceRecordValues(),
                prev.routeRatios(),
                prev.defaultRatio(),
                prev.version() + 1,
                Instant.now(),
                "JMX"
        ));

        if (!applied) {
            invalidConfigCounter.increment();
        }
    }

    @Override
    public Map<String, Double> getRouteRatios() {
        if (configHolder == null) {
            return Collections.emptyMap();
        }

        return configHolder.current().routeRatios();
    }

    @Override
    public void setRouteRatios(Map<String, Double> ratios) {
        if (configHolder == null) {
            throw new IllegalStateException("SamplerStateHolder is not registered");
        }

        if (ratios == null) {
            invalidConfigCounter.increment();
            throw new IllegalArgumentException("Ratios map cannot be null");
        }

        for (Double val : ratios.values()) {
            if (val == null || val < 0.0 || val > 1.0) {
                invalidConfigCounter.increment();
                throw new IllegalArgumentException("Ratio must be in [0.0, 1.0]");
            }
        }

        boolean applied = configHolder.tryUpdate(prev -> new SamplerState(
                prev.enabled(),
                prev.droppedRoutes(),
                prev.forceRecordValues(),
                ratios,
                prev.defaultRatio(),
                prev.version() + 1,
                Instant.now(),
                "JMX"
        ));

        if (!applied) {
            invalidConfigCounter.increment();
        }
    }

    @Override
    public double getSamplingRatio() {
        return (configHolder != null) ? configHolder.current().defaultRatio() : -1.0;
    }

    @Override
    public void setSamplingRatio(double newRatio) {
        if (configHolder == null) {
            throw new IllegalStateException("SamplerStateHolder is not registered");
        }

        if (newRatio < 0.0 || newRatio > 1.0) {
            invalidConfigCounter.increment();
            throw new IllegalArgumentException("Ratio must be in [0.0, 1.0]");
        }

        boolean applied = configHolder.tryUpdate(prev -> new SamplerState(
                prev.enabled(),
                prev.droppedRoutes(),
                prev.forceRecordValues(),
                prev.routeRatios(),
                newRatio,
                prev.version() + 1,
                Instant.now(),
                "JMX"
        ));

        if (!applied) {
            invalidConfigCounter.increment();
        }
    }

    @Override
    public String[] getDropPathPrefixes() {
        if (configHolder == null) {
            return new String[0];
        }

        List<String> dropPaths = configHolder.current().droppedRoutes();
        return dropPaths.toArray(new String[0]);
    }

    @Override
    public void setDropPathPrefixes(String[] prefixes) {
        if (configHolder == null) {
            throw new IllegalStateException("SamplerStateHolder is not registered");
        }

        if (prefixes == null) {
            invalidConfigCounter.increment();
            throw new IllegalArgumentException("Prefixes array cannot be null");
        }

        if (prefixes.length > 100) {
            invalidConfigCounter.increment();
            throw new IllegalArgumentException("Too many drop paths configured");
        }

        for (String p : prefixes) {
            if (p != null && (!p.startsWith("/") && !p.isEmpty())) {
                invalidConfigCounter.increment();
                throw new IllegalArgumentException("Drop path must start with / or be empty");
            }
        }

        boolean applied = configHolder.tryUpdate(prev -> new SamplerState(
                prev.enabled(),
                Arrays.asList(prefixes),
                prev.forceRecordValues(),
                prev.routeRatios(),
                prev.defaultRatio(),
                prev.version() + 1,
                Instant.now(),
                "JMX"
        ));

        if (!applied) {
            invalidConfigCounter.increment();
        }
    }

    @Override
    public String[] getForceRecordValues() {
        if (configHolder == null) {
            return new String[0];
        }

        return configHolder.current().forceRecordValues().toArray(new String[0]);
    }

    @Override
    public void setForceRecordValues(String[] values) {
        if (configHolder == null) {
            throw new IllegalStateException("SamplerStateHolder is not registered");
        }

        if (values == null) {
            invalidConfigCounter.increment();
            throw new IllegalArgumentException("Values array cannot be null");
        }

        if (values.length > 50) {
            invalidConfigCounter.increment();
            throw new IllegalArgumentException("Too many force record values");
        }

        for (String v : values) {
            if (v != null && v.length() > 255) {
                invalidConfigCounter.increment();
                throw new IllegalArgumentException("Force record value too long");
            }
        }

        boolean applied = configHolder.tryUpdate(prev -> new SamplerState(
                prev.enabled(),
                prev.droppedRoutes(),
                new java.util.HashSet<>(Arrays.asList(values)),
                prev.routeRatios(),
                prev.defaultRatio(),
                prev.version() + 1,
                Instant.now(),
                "JMX"
        ));

        if (!applied) {
            invalidConfigCounter.increment();
        }
    }

    @Override
    public void updateSamplingPolicy(boolean enabled,
                                     double ratio,
                                     Map<String, Double> routeRatios,
                                     String[] dropPaths,
                                     String[] forceValues) {
        if (routeRatios != null) {
            for (Map.Entry<String, Double> entry : routeRatios.entrySet()) {
                if (Strings.isBlank(entry.getKey())) {
                    invalidConfigCounter.increment();
                    throw new IllegalArgumentException("Route ratio prefix must not be null or blank");
                }

                Double val = entry.getValue();
                if (val == null || val < 0.0 || val > 1.0) {
                    invalidConfigCounter.increment();
                    throw new IllegalArgumentException("Route ratio must be in [0.0, 1.0]");
                }
            }
        }

        String[] prefixes;
        double[] values;
        if (routeRatios == null || routeRatios.isEmpty()) {
            prefixes = new String[0];
            values = new double[0];
        } else {
            prefixes = routeRatios.keySet().toArray(new String[0]);
            values = new double[prefixes.length];
            for (int i = 0; i < prefixes.length; i++) {
                values[i] = routeRatios.get(prefixes[i]);
            }
        }

        updateSamplingPolicy(enabled, ratio, dropPaths, forceValues, prefixes, values, "JMX");
    }

    @Override
    public void updateSamplingPolicy(boolean enabled,
                                     double defaultRatio,
                                     String[] droppedRoutes,
                                     String[] forceRecordValues,
                                     String[] routeRatioPrefixes,
                                     double[] routeRatioValues,
                                     String source) {
        if (configHolder == null) {
            throw new IllegalStateException("SamplerStateHolder is not registered");
        }

        try {
            configHolder.validatePolicyUpdateDomain(defaultRatio, droppedRoutes, forceRecordValues, routeRatioPrefixes, routeRatioValues);
        } catch (IllegalArgumentException e) {
            invalidConfigCounter.increment();
            throw e;
        }

        boolean applied = configHolder.tryApplyPolicyUpdate(
                enabled,
                defaultRatio,
                droppedRoutes,
                forceRecordValues,
                routeRatioPrefixes,
                routeRatioValues,
                source);

        if (!applied) {
            invalidConfigCounter.increment();
            JmxConfigReloadRecorder.record("sampling", false, configHolder.current().version());
            return;
        }

        JmxConfigReloadRecorder.record("sampling", true, configHolder.current().version());
        log.info("Sampling policy updated atomically via JMX (source={}). Version: {}",
                configHolder.current().source(), configHolder.current().version());
    }

    @Override
    public long getSamplingConfigVersion() {
        return (configHolder != null) ? configHolder.current().version() : -1;
    }

    @Override
    public String getSamplingConfigLastUpdatedSource() {
        return (configHolder != null) ? configHolder.current().source() : "unknown";
    }

    @Override
    public long getSamplerDecisionCount(String decision, String reason) {
        return (compositeSampler != null) ? compositeSampler.getDecisionCount(decision, reason) : 0L;
    }

    @Override
    public Map<String, Long> getSamplerDecisionCounts() {
        return (compositeSampler != null) ? compositeSampler.getDecisionCounts() : Collections.emptyMap();
    }

    @Override
    public void resetSamplerCounters() {
        if (compositeSampler != null) {
            compositeSampler.resetCounters();
        }
    }
}
