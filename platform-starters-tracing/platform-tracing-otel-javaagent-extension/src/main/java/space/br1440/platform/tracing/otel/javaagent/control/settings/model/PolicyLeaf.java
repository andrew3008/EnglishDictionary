package space.br1440.platform.tracing.otel.javaagent.control.settings.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public enum PolicyLeaf {

    SAMPLING_ENABLED,
    SAMPLING_RATIO,
    SAMPLING_ROUTE_RATIOS,
    VALIDATION_LEVEL,
    EXPORTER_ENABLED,
    TRACE_CONTROL_HEADERS_ENABLED,
    DIAGNOSTICS_LOG_LEVEL;

    public static Set<PolicyLeaf> diff(TracingSettings current, TracingSettings candidate) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(candidate, "candidate");

        EnumSet<PolicyLeaf> changed = EnumSet.noneOf(PolicyLeaf.class);

        if (current.samplingEnabled() != candidate.samplingEnabled()) {
            changed.add(SAMPLING_ENABLED);
        }

        if (Double.compare(current.samplingRatio(), candidate.samplingRatio()) != 0) {
            changed.add(SAMPLING_RATIO);
        }

        if (!current.samplingRouteRatios().equals(candidate.samplingRouteRatios())) {
            changed.add(SAMPLING_ROUTE_RATIOS);
        }

        if (current.validationLevel() != candidate.validationLevel()) {
            changed.add(VALIDATION_LEVEL);
        }

        if (current.exporterEnabled() != candidate.exporterEnabled()) {
            changed.add(EXPORTER_ENABLED);
        }

        if (current.traceControlHeadersEnabled() != candidate.traceControlHeadersEnabled()) {
            changed.add(TRACE_CONTROL_HEADERS_ENABLED);
        }

        if (current.diagnosticsLogLevel() != candidate.diagnosticsLogLevel()) {
            changed.add(DIAGNOSTICS_LOG_LEVEL);
        }

        return Collections.unmodifiableSet(changed);
    }
}
