package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

public record OwnerCapture<T>(OwnerRegistrationState<T> state, long offsetNanos) {

    public static <T> OwnerCapture<T> of(OwnerRegistrationSlot<T> slot) {
        long baseNanos = System.nanoTime();
        OwnerRegistrationState<T> state = slot.capture();
        long offsetNanos = (System.nanoTime() - baseNanos);
        return new OwnerCapture<>(state, offsetNanos);
    }
}
