package space.br1440.platform.tracing.otel.javaagent.bootstrap.resources;

import java.util.Objects;

public interface Registration extends AutoCloseable {

    @Override
    void close();

    static Registration adapt(AutoCloseable closeable, String label) {
        Objects.requireNonNull(closeable, "closeable");
        Objects.requireNonNull(label, "label");

        return () -> {
            try {
                closeable.close();
            } catch (Exception failure) {
                throw new BootstrapResourceCleanupException(label, failure);
            }
        };
    }
}
