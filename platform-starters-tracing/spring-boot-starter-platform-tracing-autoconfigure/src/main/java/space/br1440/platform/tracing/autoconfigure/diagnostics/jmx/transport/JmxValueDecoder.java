package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

@FunctionalInterface
public interface JmxValueDecoder<T> {

    T decode(Object raw);

}
