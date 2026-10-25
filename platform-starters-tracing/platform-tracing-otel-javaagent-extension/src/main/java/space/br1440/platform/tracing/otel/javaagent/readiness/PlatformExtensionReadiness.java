package space.br1440.platform.tracing.otel.javaagent.readiness;

import java.util.Objects;
import java.util.Set;

public final class PlatformExtensionReadiness {

    private final Object monitor = new Object();

    private volatile PlatformExtensionReadinessSnapshot current;

    PlatformExtensionReadiness(String extensionVersion) {
        this.current = new PlatformExtensionReadinessSnapshot.Initializing(
                Objects.requireNonNull(extensionVersion, "extensionVersion"),
                Set.of()
        );
    }

    public static PlatformExtensionReadiness forCurrentExtension() {
        return new PlatformExtensionReadiness(
                ExtensionVersionResolver.resolve(PlatformExtensionReadiness.class).orElse("")
        );
    }

    public PlatformExtensionReadinessSnapshot markInstalled(PlatformExtensionCapability capability) {
        Objects.requireNonNull(capability, "capability");

        synchronized (monitor) {
            PlatformExtensionReadinessSnapshot observed = current;
            if (!(observed instanceof PlatformExtensionReadinessSnapshot.Initializing initializing)
                    || observed.has(capability)) {
                return observed;
            }

            return publish(initializing.withInstalled(capability));
        }
    }

    public PlatformExtensionReadinessSnapshot.Failed fail(String primaryCode, String effectiveCode) {
        ReadinessFailure failure = ReadinessFailure.normalized(primaryCode, effectiveCode);
        synchronized (monitor) {
            PlatformExtensionReadinessSnapshot observed = current;
            if (observed instanceof PlatformExtensionReadinessSnapshot.Failed failed
                    && failed.failure().equals(failure)) {
                return failed;
            }

            return publish(observed.failed(failure));
        }
    }

    public PlatformExtensionReadinessSnapshot snapshot() {
        return current;
    }

    public PlatformExtensionReadinessSnapshot.Ready completeInitialization() {
        synchronized (monitor) {
            return switch (current) {
                case PlatformExtensionReadinessSnapshot.Ready ready -> ready;
                case PlatformExtensionReadinessSnapshot.Initializing initializing -> publish(initializing.ready());
                case PlatformExtensionReadinessSnapshot.Failed failed -> {
                    String message = "Platform extension readiness is not READY: " + failed;
                    throw new IllegalStateException(message);
                }
            };
        }
    }

    private <S extends PlatformExtensionReadinessSnapshot> S publish(S next) {
        current = next;
        return next;
    }
}
