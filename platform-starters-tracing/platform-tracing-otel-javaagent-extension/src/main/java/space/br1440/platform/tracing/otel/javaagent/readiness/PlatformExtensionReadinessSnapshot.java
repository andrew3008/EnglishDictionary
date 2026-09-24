package space.br1440.platform.tracing.otel.javaagent.readiness;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Неизменяемое представление состояния готовности расширения на определённый момент времени (point-in-time).
 * <p>
 * Наборы установленных и недостающих capabilities относятся к одному и тому же переходу состояния.
 * При передаче между подсистемами вызывающая сторона должна передавать этот объект целиком, а не
 * читать несколько полей readiness по отдельности.
 */
public final class PlatformExtensionReadinessSnapshot {

    private static final int CAPABILITY_COUNT = PlatformExtensionCapability.values().length;

    private final String extensionVersion;
    private final PlatformExtensionLifecycle lifecycle;
    private final Set<PlatformExtensionCapability> installedCapabilities;
    private final Set<PlatformExtensionCapability> missingRequiredCapabilities;
    private final String failureCode;

    PlatformExtensionReadinessSnapshot(String extensionVersion,
                                       PlatformExtensionLifecycle lifecycle,
                                       Set<PlatformExtensionCapability> installedCapabilities,
                                       Set<PlatformExtensionCapability> missingRequiredCapabilities,
                                       String failureCode) {
        this.extensionVersion = requireText(extensionVersion, "extensionVersion");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.installedCapabilities = immutableCapabilities(installedCapabilities);
        this.missingRequiredCapabilities = immutableCapabilities(missingRequiredCapabilities);
        this.failureCode = Objects.requireNonNull(failureCode, "failureCode");
        validateInvariant();
    }

    public String extensionVersion() {
        return extensionVersion;
    }

    public PlatformExtensionLifecycle lifecycle() {
        return lifecycle;
    }

    public Set<PlatformExtensionCapability> installedCapabilities() {
        return installedCapabilities;
    }

    public Set<PlatformExtensionCapability> missingRequiredCapabilities() {
        return missingRequiredCapabilities;
    }

    public String failureCode() {
        return failureCode;
    }

    public boolean has(PlatformExtensionCapability capability) {
        Objects.requireNonNull(capability, "capability");
        return installedCapabilities.contains(capability);
    }

    @Override
    public String toString() {
        return """
            PlatformExtensionReadinessSnapshot{\
            extensionVersion=%s, \
            lifecycle=%s, \
            capabilities=%d/%d\
            %s}""".formatted(
                extensionVersion,
                lifecycle,
                installedCapabilities.size(),
                CAPABILITY_COUNT,
                failureSuffix()
        );
    }

    private String failureSuffix() {
        if (lifecycle == PlatformExtensionLifecycle.FAILED) {
            return ", failureCode=" + failureCode;
        }

        return "";
    }

    private void validateInvariant() {
        EnumSet<PlatformExtensionCapability> installed = copyOf(installedCapabilities);
        EnumSet<PlatformExtensionCapability> missing = copyOf(missingRequiredCapabilities);
        if (!Collections.disjoint(installed, missing)) {
            throw new IllegalArgumentException("Installed and missing capability sets overlap");
        }

        installed.addAll(missing);
        if (!installed.equals(EnumSet.allOf(PlatformExtensionCapability.class))) {
            throw new IllegalArgumentException("Readiness snapshot must account for every capability");
        }

        switch (lifecycle) {
            case INITIALIZING -> {
                if (!failureCode.isEmpty()) {
                    throw new IllegalArgumentException("INITIALIZING readiness snapshot is inconsistent");
                }
            }

            case READY -> {
                if (!missingRequiredCapabilities.isEmpty() || !failureCode.isEmpty()) {
                    throw new IllegalArgumentException("READY readiness snapshot is inconsistent");
                }
            }

            case FAILED -> {
                if (failureCode.isEmpty()) {
                    throw new IllegalArgumentException("FAILED readiness snapshot requires a failure code");
                }
            }
        }
    }

    private static Set<PlatformExtensionCapability> immutableCapabilities(Set<PlatformExtensionCapability> capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");

        if (capabilities.isEmpty()) {
            return Set.of();
        }

        return Set.copyOf(EnumSet.copyOf(capabilities));
    }

    private static EnumSet<PlatformExtensionCapability> copyOf(Set<PlatformExtensionCapability> capabilities) {
        return capabilities.isEmpty()
                ? EnumSet.noneOf(PlatformExtensionCapability.class)
                : EnumSet.copyOf(capabilities);
    }

    @SuppressWarnings("SameParameterValue")
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }

        return value;
    }
}
