package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import java.util.Objects;

public record JmxAttribute<T>(String name, JmxContractPoint point, JmxValueDecoder<T> decoder) {

    public JmxAttribute {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(decoder, "decoder");
    }
}
