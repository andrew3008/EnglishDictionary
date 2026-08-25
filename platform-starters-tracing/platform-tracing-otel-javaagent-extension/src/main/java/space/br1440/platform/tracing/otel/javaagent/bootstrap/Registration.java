package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

interface Registration {

    void close();

    static Registration adapt(AutoCloseable closeable, String label) {
        Objects.requireNonNull(closeable, "closeable");
        Objects.requireNonNull(label, "label");

        return () -> {
            try {
                closeable.close();
            } catch (Exception failure) {
                throw new IllegalStateException("Bootstrap cleanup failed for '" + label + "'", failure);
            }
        };
    }
}
