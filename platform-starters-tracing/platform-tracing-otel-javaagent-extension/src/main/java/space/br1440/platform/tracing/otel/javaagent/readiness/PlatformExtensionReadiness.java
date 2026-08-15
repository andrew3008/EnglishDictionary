package space.br1440.platform.tracing.otel.javaagent.readiness;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Set;
import java.util.jar.Manifest;

import space.br1440.platform.common.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class PlatformExtensionReadiness {

    public static final int PROTOCOL_VERSION = 1;
    public static final String PROFILE = "platform-agent-secure-v1";

    private static final String IMPLEMENTATION_TITLE = "platform-tracing-otel-javaagent-extension";
    private static final int FAILURE_MESSAGE_MAX_LENGTH = 512;
    private static final String DEFAULT_FAILURE_CODE = "EXTENSION_INITIALIZATION_FAILED";
    private static final String DEFAULT_FAILURE_MESSAGE = "Extension initialization failed without a diagnostic cause";

    private static final Set<PlatformExtensionCapability> REQUIRED_CAPABILITIES =  EnumSet.allOf(PlatformExtensionCapability.class);

    private final String extensionVersion;

    private final Set<PlatformExtensionCapability> installedCapabilities = EnumSet.noneOf(PlatformExtensionCapability.class);

    private PlatformExtensionLifecycle lifecycle = PlatformExtensionLifecycle.INITIALIZING;
    private String failureCode = "";
    private String failureMessage = "";

    public PlatformExtensionReadiness() {
        this.extensionVersion = resolveExtensionVersion();
    }

    public synchronized void markInstalled(PlatformExtensionCapability capability) {
        Objects.requireNonNull(capability, "capability");

        if (lifecycle == PlatformExtensionLifecycle.FAILED) {
            return;
        }

        installedCapabilities.add(capability);
        if (installedCapabilities.containsAll(REQUIRED_CAPABILITIES)) {
            lifecycle = PlatformExtensionLifecycle.READY;
        }
    }

    public synchronized void fail(String code, Throwable failure) {
        if (lifecycle == PlatformExtensionLifecycle.FAILED) {
            return;
        }

        lifecycle = PlatformExtensionLifecycle.FAILED;
        failureCode = orDefault(code, DEFAULT_FAILURE_CODE);
        failureMessage = boundedMessage(failure);
    }

    public synchronized PlatformExtensionLifecycle lifecycle() {
        return lifecycle;
    }

    public synchronized void requireReady() {
        if (lifecycle != PlatformExtensionLifecycle.READY) {
            Set<PlatformExtensionCapability> missing = EnumSet.copyOf(REQUIRED_CAPABILITIES);
            missing.removeAll(installedCapabilities);
            throw new IllegalStateException(
                    "Platform extension readiness is not READY: lifecycle=" + lifecycle
                            + ", missingCapabilities=" + missing);
        }
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
        return installedCapabilities.stream().map(Enum::name).sorted().toArray(String[]::new);
    }

    public synchronized boolean has(PlatformExtensionCapability capability) {
        Objects.requireNonNull(capability, "capability");
        return installedCapabilities.contains(capability);
    }

    @Override
    public synchronized String toString() {
        return "PlatformExtensionReadiness{"
                + "profile=" + PROFILE
                + ", extensionVersion=" + extensionVersion
                + ", lifecycle=" + lifecycle
                + ", capabilities=" + installedCapabilities.size() + "/" + REQUIRED_CAPABILITIES.size()
                + (lifecycle == PlatformExtensionLifecycle.FAILED ? ", failureCode=" + failureCode : "")
                + '}';
    }

    private static String resolveExtensionVersion() {
        String implementationVersion = readImplementationVersionFromManifest();
        return StringUtils.isNullOrBlank(implementationVersion)
                ? "development"
                : implementationVersion;
    }

    private static String readImplementationVersionFromManifest() {
        ClassLoader loader = PlatformExtensionReadiness.class.getClassLoader();
        if (loader == null) {
            return null;
        }

        try {
            Enumeration<URL> manifestUrls = loader.getResources("META-INF/MANIFEST.MF");
            for (URL manifestUrl : Collections.list(manifestUrls)) {
                try (InputStream manifestStream = manifestUrl.openStream()) {
                    Manifest manifest = new Manifest(manifestStream);
                    String title = manifest.getMainAttributes().getValue("Implementation-Title");
                    if (IMPLEMENTATION_TITLE.equals(title)) {
                        return manifest.getMainAttributes().getValue("Implementation-Version");
                    }
                }
            }
        } catch (IOException failure) {
            log.debug("Unable to resolve {} Implementation-Version from MANIFEST.MF, falling back to 'development'",
                    IMPLEMENTATION_TITLE, failure);
        }

        return null;
    }

    private static String boundedMessage(Throwable failure) {
        if (failure == null) {
            return DEFAULT_FAILURE_MESSAGE;
        }

        String message = failure.getMessage();
        String value = failure.getClass().getSimpleName() + (StringUtils.isNullOrBlank(message) ? "" : ": " + message);
        return (value.length() <= FAILURE_MESSAGE_MAX_LENGTH) ? value : value.substring(0, FAILURE_MESSAGE_MAX_LENGTH);
    }

    @SuppressWarnings("SameParameterValue")
    private static String orDefault(String value, String fallback) {
        return StringUtils.isNullOrBlank(value) ? fallback : value;
    }
}
