package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

public enum JmxContractPoint {

    SAMPLING_OBSERVATION(JmxDomain.SAMPLING),
    SAMPLING_REGISTRATION(JmxDomain.SAMPLING),

    SCRUBBING_OBSERVATION(JmxDomain.SCRUBBING),
    SCRUBBING_REGISTRATION(JmxDomain.SCRUBBING),

    VALIDATION_OBSERVATION(JmxDomain.VALIDATION),
    VALIDATION_REGISTRATION(JmxDomain.VALIDATION),

    EXPORT_OBSERVATION(JmxDomain.EXPORT),
    EXPORT_REGISTRATION(JmxDomain.EXPORT),

    PROCESSOR_METRICS_OBSERVATION(JmxDomain.PROCESSOR_METRICS),
    PROCESSOR_METRICS_REGISTRATION(JmxDomain.PROCESSOR_METRICS),

    DIAGNOSTICS_OBSERVATION(JmxDomain.DIAGNOSTICS),
    DIAGNOSTICS_REGISTRATION(JmxDomain.DIAGNOSTICS);

    private final JmxDomain domain;

    JmxContractPoint(JmxDomain domain) {
        this.domain = domain;
    }

    public JmxDomain domain() {
        return domain;
    }
}
