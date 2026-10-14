package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireGraphMeter;

public final class JmxWireGraphValidator {

    public void validate(Object value) {
        try {
            JmxWireGraphMeter.measure(value);
        } catch (IllegalArgumentException violation) {
            throw new JmxWireTypeViolationException(violation.getMessage(), violation);
        }
    }
}
