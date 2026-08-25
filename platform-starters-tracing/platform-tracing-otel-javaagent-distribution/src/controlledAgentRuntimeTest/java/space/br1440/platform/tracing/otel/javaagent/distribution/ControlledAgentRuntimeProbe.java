package space.br1440.platform.tracing.otel.javaagent.distribution;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.management.MBeanServer;
import javax.management.ObjectName;

public final class ControlledAgentRuntimeProbe {

    private static final String READINESS_OBJECT_NAME = "space.br1440.platform.tracing:type=Readiness,name=PlatformExtension";
    private static final String IDENTITY_ENTRY = "META-INF/platform-tracing/javaagent-distribution.properties";

    private static final int EXPECTED_PROTOCOL_VERSION = 1;
    private static final String EXPECTED_PROFILE = "platform-agent-secure-v1";
    private static final String DEVELOPMENT_VERSION_MARKER = "development";

    private static final Set<String> EXPECTED_CAPABILITIES = Set.of(
            "CONFIGURATION_LOADED",
            "PLATFORM_SAMPLER_INSTALLED",
            "REQUIRED_SPAN_PROCESSORS_INSTALLED",
            "SANITIZER_INSTALLED",
            "PROPAGATION_HOOKS_INSTALLED",
            "SAFE_EXPORTER_INSTALLED",
            "EXPORT_PATH_PROTECTED"
    );

    private static final Set<String> CONTAMINATING_ENV_VARS = Set.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS");
    private static final String OTEL_ENV_PREFIX = "OTEL_";
    private static final String OTEL_EXTERNAL_EXTENSIONS_PROPERTY = "otel.javaagent.extensions";

    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration READINESS_POLL_INTERVAL = Duration.ofMillis(50);

    private static final String AGENT_OWNED_CLASS_MARKER = "io.opentelemetry.sdk.OpenTelemetrySdk";

    private ControlledAgentRuntimeProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        ProbeArguments probeArguments = ProbeArguments.parse(arguments);

        verifyHermeticLaunch(probeArguments.controlledAgent());
        verifyApplicationClassLoaderIsolation();
        verifyDistributionIdentity(probeArguments);
        verifyReadiness(probeArguments.version());

        System.out.printf("CONTROLLED_AGENT_PROBE_OK version=%s profile=%s capabilities=%d%n",
                probeArguments.version(), EXPECTED_PROFILE, EXPECTED_CAPABILITIES.size());
    }

    private static void verifyHermeticLaunch(Path controlledAgent) throws IOException {
        Path actualClassPath = verifySingleClasspathEntry();
        verifyClasspathIsolatedFromAgent(actualClassPath, controlledAgent);
        verifyNoExternalExtensionsConfigured();
        verifyNoContaminatingEnvironment();
    }

    private static Path verifySingleClasspathEntry() throws IOException {
        String classPath = System.getProperty("java.class.path");
        if (classPath == null || classPath.isBlank() || classPath.indexOf(File.pathSeparatorChar) >= 0) {
            throw new IllegalStateException("Probe classpath must contain exactly one entry: " + classPath);
        }

        return Path.of(classPath).toRealPath();
    }

    private static void verifyClasspathIsolatedFromAgent(Path actualClassPath, Path controlledAgent) throws IOException {
        Path probeLocation;
        try {
            probeLocation = Path.of(ControlledAgentRuntimeProbe.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()).toRealPath();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to resolve probe class location", exception);
        }

        if (!actualClassPath.equals(probeLocation) || actualClassPath.equals(controlledAgent.toRealPath())) {
            throw new IllegalStateException("Probe classpath is not isolated: " + actualClassPath);
        }
    }

    private static void verifyNoExternalExtensionsConfigured() {
        if (System.getProperty(OTEL_EXTERNAL_EXTENSIONS_PROPERTY) != null) {
            throw new IllegalStateException("External Java agent extensions must not be configured");
        }
    }

    private static void verifyNoContaminatingEnvironment() {
        for (String name : CONTAMINATING_ENV_VARS) {
            if (System.getenv(name) != null) {
                throw new IllegalStateException("Contaminating JVM environment variable is present: " + name);
            }
        }

        for (String name : System.getenv().keySet()) {
            if (name.toUpperCase(Locale.ROOT).startsWith(OTEL_ENV_PREFIX)) {
                throw new IllegalStateException("Contaminating OTel environment variable is present: " + name);
            }
        }
    }

    private static void verifyApplicationClassLoaderIsolation() {
        assertClassUnavailable(AGENT_OWNED_CLASS_MARKER, ClassLoader.getSystemClassLoader());
        assertClassUnavailable(AGENT_OWNED_CLASS_MARKER, ControlledAgentRuntimeProbe.class.getClassLoader());
    }

    @SuppressWarnings("SameParameterValue")
    private static void assertClassUnavailable(String name, ClassLoader loader) {
        try {
            Class.forName(name, false, loader);
            throw new IllegalStateException("Application class loader can load agent-owned class: " + name);
        } catch (ClassNotFoundException expected) {
            // Ожидаемое поведение: класс, принадлежащий агенту, не должен быть виден этому ClassLoader
        }
    }

    private static void verifyDistributionIdentity(ProbeArguments arguments) throws IOException {
        Properties identity = readIdentityProperties(arguments.controlledAgent());
        requireIdentity(identity, "format.version", "1");
        requireIdentity(identity, "distribution.version", arguments.version());
        requireIdentity(identity, "otel.javaagent.sha256", arguments.otelJavaAgentSha256());
        requireIdentity(identity, "extension.sha256", arguments.extensionSha256());
    }

    private static Properties readIdentityProperties(Path controlledAgent) throws IOException {
        Properties identity = new Properties();
        try (ZipFile zip = new java.util.zip.ZipFile(controlledAgent.toFile())) {
            ZipEntry entry = zip.getEntry(IDENTITY_ENTRY);
            if (entry == null) {
                throw new IllegalStateException("Controlled agent identity entry is missing");
            }

            try (InputStream input = zip.getInputStream(entry)) {
                identity.load(input);
            }
        }

        return identity;
    }

    private static void requireIdentity(Properties identity, String name, String expected) {
        String actual = identity.getProperty(name);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Controlled agent identity mismatch for %s: expected=%s, actual=%s"
                            .formatted(name, expected, actual)
            );
        }
    }

    private static void verifyReadiness(String expectedVersion) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName objectName = new ObjectName(READINESS_OBJECT_NAME);
        long deadlineNanos = System.nanoTime() + READINESS_TIMEOUT.toNanos();

        while (System.nanoTime() < deadlineNanos) {
            if (!server.isRegistered(objectName)) {
                sleepPollInterval();
                continue;
            }

            String lifecycle = (String) server.getAttribute(objectName, "LifecycleState");
            if ("FAILED".equals(lifecycle)) {
                throw new IllegalStateException(
                        "Platform extension readiness failed: code=%s, message=%s".formatted(
                                server.getAttribute(objectName, "FailureCode"),
                                server.getAttribute(objectName, "FailureMessage")
                        )
                );
            }

            if ("READY".equals(lifecycle)) {
                verifyReadySnapshot(ReadinessSnapshot.capture(server, objectName), expectedVersion);
                return;
            }

            sleepPollInterval();
        }

        throw new IllegalStateException(
                "Platform extension readiness did not become READY within %d seconds"
                        .formatted(READINESS_TIMEOUT.toSeconds())
        );
    }

    private static void sleepPollInterval() throws InterruptedException {
        Thread.sleep(READINESS_POLL_INTERVAL.toMillis());
    }

    private static void verifyReadySnapshot(ReadinessSnapshot snapshot, String expectedVersion) {
        verifyProtocolVersion(snapshot.protocolVersion());
        verifyProfile(snapshot.profile());
        verifyExtensionVersion(snapshot.extensionVersion(), expectedVersion);
        verifyNoFailureData(snapshot.failureCode(), snapshot.failureMessage());
        verifyCapabilities(snapshot.capabilities());
    }

    private static void verifyProtocolVersion(Object protocolVersion) {
        if (!(protocolVersion instanceof Number number) || number.intValue() != EXPECTED_PROTOCOL_VERSION) {
            throw new IllegalStateException("Unexpected readiness protocol version: " + protocolVersion);
        }
    }

    private static void verifyProfile(String profile) {
        if (!EXPECTED_PROFILE.equals(profile)) {
            throw new IllegalStateException("Unexpected readiness profile: " + profile);
        }
    }

    private static void verifyExtensionVersion(String extensionVersion, String expectedVersion) {
        if (!expectedVersion.equals(extensionVersion)
                || DEVELOPMENT_VERSION_MARKER.equalsIgnoreCase(extensionVersion)) {
            throw new IllegalStateException("Unexpected extension version: " + extensionVersion);
        }
    }

    private static void verifyNoFailureData(String failureCode, String failureMessage) {
        if (!failureCode.isEmpty() || !failureMessage.isEmpty()) {
            throw new IllegalStateException(
                    "READY snapshot contains failure data: code=%s, message=%s"
                            .formatted(failureCode, failureMessage)
            );
        }
    }

    private static void verifyCapabilities(String[] capabilities) {
        Set<String> actualCapabilities = new HashSet<>(Arrays.asList(capabilities));
        if (capabilities.length != EXPECTED_CAPABILITIES.size()
                || !actualCapabilities.equals(EXPECTED_CAPABILITIES)) {
            throw new IllegalStateException("Unexpected readiness capabilities: " + actualCapabilities);
        }
    }

    private record ProbeArguments(String version,
                                  Path controlledAgent,
                                  String otelJavaAgentSha256,
                                  String extensionSha256) {
        static ProbeArguments parse(String[] arguments) {
            if (arguments.length != 4) {
                throw new IllegalArgumentException(
                        "Expected version, agent path, OTel Java agent SHA-256 and extension SHA-256");
            }

            return new ProbeArguments(
                    arguments[0],
                    Path.of(arguments[1]),
                    arguments[2],
                    arguments[3]
            );
        }
    }

    private record ReadinessSnapshot(Object protocolVersion,
                                     String profile,
                                     String extensionVersion,
                                     String failureCode,
                                     String failureMessage,
                                     String[] capabilities) {

        static ReadinessSnapshot capture(MBeanServer server, ObjectName objectName) throws Exception {
            return new ReadinessSnapshot(
                    server.getAttribute(objectName, "ProtocolVersion"),
                    (String) server.getAttribute(objectName, "Profile"),
                    (String) server.getAttribute(objectName, "ExtensionVersion"),
                    (String) server.getAttribute(objectName, "FailureCode"),
                    (String) server.getAttribute(objectName, "FailureMessage"),
                    (String[]) server.getAttribute(objectName, "Capabilities")
            );
        }
    }
}
