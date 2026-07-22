package space.br1440.platform.tracing.otel.javaagent.readiness;

import java.util.EnumSet;
import java.util.Set;

/**
 * Extension-local автомат состояния готовности secure agent profile.
 *
 * <p>Экземпляр принадлежит одному bootstrap-проходу extension и никогда не передаётся в
 * application classloader. Через JMX публикуются только простые значения и массивы строк.
 */
public final class PlatformExtensionReadiness {

    public static final int PROTOCOL_VERSION = 1;
    public static final String PROFILE = "platform-agent-secure-v1";

    private static final Set<PlatformExtensionCapability> REQUIRED_CAPABILITIES = EnumSet.allOf(
            PlatformExtensionCapability.class);

    private final String extensionVersion;
    private final EnumSet<PlatformExtensionCapability> capabilities = EnumSet.noneOf(
            PlatformExtensionCapability.class);

    private PlatformExtensionLifecycle lifecycle = PlatformExtensionLifecycle.INITIALIZING;
    private String failureCode = "";
    private String failureMessage = "";

    public PlatformExtensionReadiness() {
        Package extensionPackage = PlatformExtensionReadiness.class.getPackage();
        String implementationVersion = extensionPackage == null
                ? null
                : extensionPackage.getImplementationVersion();
        this.extensionVersion = implementationVersion == null || implementationVersion.isBlank()
                ? "development"
                : implementationVersion;
    }

    public synchronized void markInstalled(PlatformExtensionCapability capability) {
        if (lifecycle == PlatformExtensionLifecycle.FAILED) {
            return;
        }
        capabilities.add(capability);
        if (capabilities.containsAll(REQUIRED_CAPABILITIES)) {
            lifecycle = PlatformExtensionLifecycle.READY;
        }
    }

    public synchronized void fail(String code, Throwable failure) {
        lifecycle = PlatformExtensionLifecycle.FAILED;
        failureCode = safe(code, "EXTENSION_INITIALIZATION_FAILED");
        failureMessage = safeMessage(failure);
    }

    public synchronized PlatformExtensionLifecycle lifecycle() {
        return lifecycle;
    }

    public String extensionVersion() {
        return extensionVersion;
    }

    public synchronized String failureCode() {
        return failureCode;
    }

    public synchronized String failureMessage() {
        return failureMessage;
    }

    public synchronized String[] capabilities() {
        return capabilities.stream().map(Enum::name).sorted().toArray(String[]::new);
    }

    public String[] requiredCapabilities() {
        return REQUIRED_CAPABILITIES.stream().map(Enum::name).sorted().toArray(String[]::new);
    }

    public synchronized boolean has(PlatformExtensionCapability capability) {
        return capabilities.contains(capability);
    }

    private static String safeMessage(Throwable failure) {
        if (failure == null) {
            return "Extension initialization failed without a diagnostic cause";
        }
        String message = failure.getMessage();
        String value = failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
