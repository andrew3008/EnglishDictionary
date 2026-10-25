package space.br1440.platform.tracing.otel.javaagent.readiness;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public abstract sealed class PlatformExtensionReadinessSnapshot
        permits PlatformExtensionReadinessSnapshot.Initializing,
                PlatformExtensionReadinessSnapshot.Ready,
                PlatformExtensionReadinessSnapshot.Failed {

    private static final int CAPABILITY_COUNT = PlatformExtensionCapability.values().length;

    private final String extensionVersion;
    private final Set<PlatformExtensionCapability> installedCapabilities;

    private PlatformExtensionReadinessSnapshot(String extensionVersion,
                                               Set<PlatformExtensionCapability> installedCapabilities) {
        this.extensionVersion = Objects.requireNonNull(extensionVersion, "extensionVersion");
        this.installedCapabilities = Collections.unmodifiableSet(copyOf(installedCapabilities));
    }

    public abstract PlatformExtensionLifecycle lifecycle();

    public String extensionVersion() {
        return extensionVersion;
    }

    public Set<PlatformExtensionCapability> installedCapabilities() {
        return installedCapabilities;
    }

    public Set<PlatformExtensionCapability> missingRequiredCapabilities() {
        return Collections.unmodifiableSet(EnumSet.complementOf(copyOf(installedCapabilities)));
    }

    public boolean has(PlatformExtensionCapability capability) {
        Objects.requireNonNull(capability, "capability");
        return installedCapabilities.contains(capability);
    }

    Failed failed(ReadinessFailure failure) {
        return new Failed(extensionVersion, installedCapabilities, failure);
    }

    @Override
    public String toString() {
        return "PlatformExtensionReadinessSnapshot{"
                + "extensionVersion=" + extensionVersion
                + ", lifecycle=" + lifecycle()
                + ", capabilities=" + installedCapabilities.size() + "/" + CAPABILITY_COUNT
                + failureDetails()
                + '}';
    }

    private String failureDetails() {
        if (this instanceof Failed failed) {
            return ", primaryFailureCode=" + failed.failure().primaryCode()
                    + ", failureCode=" + failed.failure().effectiveCode();
        }

        return "";
    }

    private static EnumSet<PlatformExtensionCapability> copyOf(Set<PlatformExtensionCapability> capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        return capabilities.isEmpty()
                ? EnumSet.noneOf(PlatformExtensionCapability.class)
                : EnumSet.copyOf(capabilities);
    }

    private static String requireResolvedVersion(String extensionVersion) {
        if (extensionVersion == null || extensionVersion.isBlank()) {
            throw new IllegalStateException(
                    "Platform extension readiness is not READY: extensionVersion is missing"
            );
        }

        return extensionVersion;
    }

    public static final class Initializing extends PlatformExtensionReadinessSnapshot {

        Initializing(String extensionVersion, Set<PlatformExtensionCapability> installedCapabilities) {
            super(extensionVersion, installedCapabilities);
        }

        @Override
        public PlatformExtensionLifecycle lifecycle() {
            return PlatformExtensionLifecycle.INITIALIZING;
        }

        Initializing withInstalled(PlatformExtensionCapability capability) {
            EnumSet<PlatformExtensionCapability> installed = copyOf(installedCapabilities());
            installed.add(capability);
            return new Initializing(extensionVersion(), installed);
        }

        Ready ready() {
            Set<PlatformExtensionCapability> missing = missingRequiredCapabilities();
            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                        "Platform extension readiness is not READY: lifecycle=%s, missingCapabilities=%s"
                                .formatted(lifecycle(), missing)
                );
            }

            return new Ready(extensionVersion());
        }
    }

    public static final class Ready extends PlatformExtensionReadinessSnapshot {

        private Ready(String extensionVersion) {
            super(requireResolvedVersion(extensionVersion), EnumSet.allOf(PlatformExtensionCapability.class));
        }

        @Override
        public PlatformExtensionLifecycle lifecycle() {
            return PlatformExtensionLifecycle.READY;
        }
    }

    public static final class Failed extends PlatformExtensionReadinessSnapshot {

        private final ReadinessFailure failure;

        private Failed(String extensionVersion,
                       Set<PlatformExtensionCapability> installedCapabilities,
                       ReadinessFailure failure) {
            super(extensionVersion, installedCapabilities);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        @Override
        public PlatformExtensionLifecycle lifecycle() {
            return PlatformExtensionLifecycle.FAILED;
        }

        public ReadinessFailure failure() {
            return failure;
        }
    }
}
