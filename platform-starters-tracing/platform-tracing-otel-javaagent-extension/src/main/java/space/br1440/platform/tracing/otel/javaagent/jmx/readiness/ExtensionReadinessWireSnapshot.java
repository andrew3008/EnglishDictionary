package space.br1440.platform.tracing.otel.javaagent.jmx.readiness;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

public record ExtensionReadinessWireSnapshot(String extensionVersion,
                                             int protocolVersion,
                                             String profile,
                                             String lifecycle,
                                             String failureCode,
                                             String failureMessage,
                                             String[] capabilities) {

    public ExtensionReadinessWireSnapshot {
        Objects.requireNonNull(extensionVersion, "extensionVersion");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(failureCode, "failureCode");
        Objects.requireNonNull(failureMessage, "failureMessage");
        Objects.requireNonNull(capabilities, "capabilities");
        capabilities = capabilities.clone();
    }

    @Override
    public String[] capabilities() {
        return capabilities.clone();
    }

    public static ExtensionReadinessWireSnapshot initializing() {
        return new ExtensionReadinessWireSnapshot(
                "development",
                PlatformExtensionReadiness.PROTOCOL_VERSION,
                PlatformExtensionReadiness.PROFILE,
                "INITIALIZING",
                "",
                "",
                new String[0]
        );
    }

    public static ExtensionReadinessWireSnapshot initializing(PlatformExtensionReadiness readiness) {
        Objects.requireNonNull(readiness, "readiness");
        return new ExtensionReadinessWireSnapshot(
                readiness.extensionVersion(),
                PlatformExtensionReadiness.PROTOCOL_VERSION,
                PlatformExtensionReadiness.PROFILE,
                "INITIALIZING",
                "",
                "",
                readiness.capabilities()
        );
    }

    public static ExtensionReadinessWireSnapshot ready(PlatformExtensionReadiness readiness) {
        Objects.requireNonNull(readiness, "readiness");

        if (readiness.lifecycle() != PlatformExtensionLifecycle.READY) {
            throw new IllegalStateException("Cannot construct a READY wire snapshot: underlying lifecycle=" + readiness.lifecycle());
        }

        return new ExtensionReadinessWireSnapshot(
                readiness.extensionVersion(),
                PlatformExtensionReadiness.PROTOCOL_VERSION,
                PlatformExtensionReadiness.PROFILE,
                "READY",
                "",
                "",
                readiness.capabilities());
    }

    public static ExtensionReadinessWireSnapshot failed(PlatformExtensionReadiness readiness,
                                                        String failureCode,
                                                        String failureMessage) {
        Objects.requireNonNull(readiness, "readiness");
        return new ExtensionReadinessWireSnapshot(
                readiness.extensionVersion(),
                PlatformExtensionReadiness.PROTOCOL_VERSION,
                PlatformExtensionReadiness.PROFILE,
                "FAILED",
                failureCode == null ? "" : failureCode,
                failureMessage == null ? "" : failureMessage,
                readiness.capabilities()
        );
    }
}
