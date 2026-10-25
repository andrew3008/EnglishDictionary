package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import java.util.Objects;
import java.util.function.Function;
import javax.management.openmbean.OpenType;

public record JmxAttribute<T>(String name,
                              JmxContractPoint point,
                              OpenType<?> openType,
                              JmxValueDecoder<T> decoder,
                              Function<T, String> agentInstanceIdExtractor) {

    JmxAttribute(String name,
                 JmxContractPoint point,
                 OpenType<?> openType,
                 JmxValueDecoder<T> decoder) {
        this(name, point, openType, decoder, ignored -> null);
    }

    public JmxAttribute {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(openType, "openType");
        Objects.requireNonNull(decoder, "decoder");
        Objects.requireNonNull(agentInstanceIdExtractor, "agentInstanceIdExtractor");
    }
}
