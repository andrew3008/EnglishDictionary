package space.br1440.platform.tracing.otel.javaagent.exporter.response;

import java.util.Objects;
import jakarta.annotation.Nullable;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class ExportResponseScope {

    private static final ThreadLocal<ExportResponseObservation> CURRENT = new ThreadLocal<>();

    public static Handle open(ExportResponseObservation observation) {
        Objects.requireNonNull(observation, "observation");

        ExportResponseObservation previous = CURRENT.get();
        CURRENT.set(observation);

        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    @Nullable
    public static ExportResponseObservation current() {
        return CURRENT.get();
    }

    @FunctionalInterface
    public interface Handle extends AutoCloseable {

        @Override
        void close();
    }
}
