package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanRegistrationState;

public record JmxMBeanRegistrationSnapshot(JmxMBeanRegistrationState sampling,
                                           JmxMBeanRegistrationState scrubbing,
                                           JmxMBeanRegistrationState validation,
                                           JmxMBeanRegistrationState export,
                                           JmxMBeanRegistrationState processorMetrics,
                                           JmxMBeanRegistrationState diagnostics) {

    public boolean allRegistered() {
        return sampling == JmxMBeanRegistrationState.REGISTERED
                && scrubbing == JmxMBeanRegistrationState.REGISTERED
                && validation == JmxMBeanRegistrationState.REGISTERED
                && export == JmxMBeanRegistrationState.REGISTERED
                && processorMetrics == JmxMBeanRegistrationState.REGISTERED
                && diagnostics == JmxMBeanRegistrationState.REGISTERED;
    }
}
