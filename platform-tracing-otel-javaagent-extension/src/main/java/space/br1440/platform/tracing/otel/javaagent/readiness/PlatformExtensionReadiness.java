package space.br1440.platform.tracing.otel.javaagent.readiness;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Set;
import java.util.jar.Manifest;

/**
 * Extension-local автомат состояния готовности secure agent profile.
 *
 * <p>Экземпляр принадлежит одному bootstrap-проходу extension и никогда не передаётся в
 * application classloader. Через JMX публикуются только простые значения и массивы строк.
 */
public final class PlatformExtensionReadiness {

    public static final int PROTOCOL_VERSION = 1;
    public static final String PROFILE = "platform-agent-secure-v1";

    /**
     * Must equal the {@code Implementation-Title} manifest attribute this module's {@code jar} and
     * {@code agentExtensionJar} tasks stamp (see {@code build.gradle}) — used to pick this
     * extension's own manifest out of every {@code META-INF/MANIFEST.MF} visible through this class
     * loader (see {@link #resolveImplementationVersion()}).
     */
    private static final String IMPLEMENTATION_TITLE = "platform-tracing-otel-javaagent-extension";

    private static final Set<PlatformExtensionCapability> REQUIRED_CAPABILITIES = EnumSet.allOf(
            PlatformExtensionCapability.class);

    private final String extensionVersion;
    private final EnumSet<PlatformExtensionCapability> capabilities = EnumSet.noneOf(
            PlatformExtensionCapability.class);

    private PlatformExtensionLifecycle lifecycle = PlatformExtensionLifecycle.INITIALIZING;
    private String failureCode = "";
    private String failureMessage = "";

    public PlatformExtensionReadiness() {
        String implementationVersion = resolveImplementationVersion();
        this.extensionVersion = implementationVersion == null || implementationVersion.isBlank()
                ? "development"
                : implementationVersion;
    }

    /**
     * {@link Package#getImplementationVersion()} is unreliable here, and so is a single
     * {@code getResourceAsStream("META-INF/MANIFEST.MF")}: the OpenTelemetry Java Agent loads each
     * {@code extensions/} JAR through its own {@code ExtensionClassLoader} (see
     * javaagent-structure.md), and standard parent-first resource delegation means the first
     * {@code META-INF/MANIFEST.MF} found on that lookup can belong to a parent (agent) class loader
     * instead of this extension's own JAR. This walks every manifest visible through this class's
     * loader and picks the one this module's own build stamps with {@link #IMPLEMENTATION_TITLE}.
     */
    private static String resolveImplementationVersion() {
        ClassLoader loader = PlatformExtensionReadiness.class.getClassLoader();
        if (loader == null) {
            return null;
        }
        try {
            Enumeration<URL> manifestUrls = loader.getResources("META-INF/MANIFEST.MF");
            for (URL manifestUrl : Collections.list(manifestUrls)) {
                try (InputStream manifestStream = manifestUrl.openStream()) {
                    Manifest manifest = new Manifest(manifestStream);
                    if (IMPLEMENTATION_TITLE.equals(
                            manifest.getMainAttributes().getValue("Implementation-Title"))) {
                        return manifest.getMainAttributes().getValue("Implementation-Version");
                    }
                }
            }
        } catch (IOException failure) {
            return null;
        }
        return null;
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
