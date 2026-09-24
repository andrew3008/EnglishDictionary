package space.br1440.platform.tracing.otel.javaagent.readiness;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Потоко-безопасная state machine состояния запуска (startup) для javaagent-расширения платформы.
 * <p>
 * Для готовности (readiness) обязателен каждый объявленный {@link PlatformExtensionCapability}.
 * Мутации сериализованы, так как происходят только во время bootstrap и shutdown. Чтение возвращает
 * единый неизменяемый снимок (snapshot), публикуемый через volatile, поэтому вызывающая сторона не
 * может наблюдать поля из разных переходов состояния.
 */
public final class PlatformExtensionReadiness {

    private static final String DEFAULT_FAILURE_CODE = "EXTENSION_INITIALIZATION_FAILED";
    private static final Pattern FAILURE_CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");
    private static final Set<PlatformExtensionCapability> REQUIRED_CAPABILITIES =
            Collections.unmodifiableSet(EnumSet.allOf(PlatformExtensionCapability.class));

    private final Object monitor = new Object();
    private final String extensionVersion;

    private volatile PlatformExtensionReadinessSnapshot current;

    public PlatformExtensionReadiness() {
        this(ExtensionVersionResolver.resolve(PlatformExtensionReadiness.class));
    }

    PlatformExtensionReadiness(String extensionVersion) {
        this.extensionVersion = Objects.requireNonNull(extensionVersion, "extensionVersion");
        this.current = snapshotOf(
                PlatformExtensionLifecycle.INITIALIZING,
                Set.of(),
                ""
        );
    }

    public PlatformExtensionReadinessSnapshot markInstalled(PlatformExtensionCapability capability) {
        Objects.requireNonNull(capability, "capability");

        synchronized (monitor) {
            PlatformExtensionReadinessSnapshot observed = current;
            if (observed.lifecycle() == PlatformExtensionLifecycle.FAILED || observed.has(capability)) {
                return observed;
            }

            EnumSet<PlatformExtensionCapability> installed = copyOf(observed.installedCapabilities());
            installed.add(capability);
            current = snapshotOf(PlatformExtensionLifecycle.INITIALIZING, installed, "");
            return current;
        }
    }

    public PlatformExtensionReadinessSnapshot fail(String failureCode) {
        String normalizedCode = normalizeFailureCode(failureCode);
        synchronized (monitor) {
            if (current.lifecycle() != PlatformExtensionLifecycle.FAILED) {
                current = snapshotOf(
                        PlatformExtensionLifecycle.FAILED,
                        current.installedCapabilities(),
                        normalizedCode
                );
            }

            return current;
        }
    }

    public PlatformExtensionReadinessSnapshot recordTerminalCleanupFailure(String failureCode) {
        String normalizedCode = normalizeFailureCode(failureCode);
        synchronized (monitor) {
            current = snapshotOf(
                    PlatformExtensionLifecycle.FAILED,
                    current.installedCapabilities(),
                    normalizedCode
            );

            return current;
        }
    }

    public PlatformExtensionReadinessSnapshot snapshot() {
        return current;
    }

    public PlatformExtensionReadinessSnapshot completeInitialization() {
        synchronized (monitor) {
            PlatformExtensionReadinessSnapshot observed = current;
            if (observed.lifecycle() == PlatformExtensionLifecycle.READY) {
                return observed;
            }

            if (observed.lifecycle() == PlatformExtensionLifecycle.FAILED
                    || !observed.missingRequiredCapabilities().isEmpty()) {
                throw notReady(observed);
            }

            current = snapshotOf(
                    PlatformExtensionLifecycle.READY,
                    observed.installedCapabilities(),
                    ""
            );

            return current;
        }
    }

    @Override
    public String toString() {
        return current.toString();
    }

    private PlatformExtensionReadinessSnapshot snapshotOf(PlatformExtensionLifecycle lifecycle,
                                                          Set<PlatformExtensionCapability> installedCapabilities,
                                                          String failureCode) {
        EnumSet<PlatformExtensionCapability> installed = copyOf(installedCapabilities);
        EnumSet<PlatformExtensionCapability> missing = EnumSet.copyOf(REQUIRED_CAPABILITIES);
        missing.removeAll(installed);

        return new PlatformExtensionReadinessSnapshot(
                extensionVersion,
                lifecycle,
                installed,
                missing,
                failureCode
        );
    }

    private static EnumSet<PlatformExtensionCapability> copyOf(Set<PlatformExtensionCapability> capabilities) {
        return capabilities.isEmpty()
                ? EnumSet.noneOf(PlatformExtensionCapability.class)
                : EnumSet.copyOf(capabilities);
    }

    private static String normalizeFailureCode(String failureCode) {
        return failureCode != null && FAILURE_CODE_PATTERN.matcher(failureCode).matches()
                ? failureCode
                : DEFAULT_FAILURE_CODE;
    }

    private static IllegalStateException notReady(PlatformExtensionReadinessSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        String message = """
            Platform extension readiness is not READY: lifecycle=%s, missingCapabilities=%s\
            """.formatted(snapshot.lifecycle(), snapshot.missingRequiredCapabilities());
        return new IllegalStateException(message);
    }
}
