package space.br1440.platform.tracing.verification;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.management.MBeanServer;
import javax.management.ObjectName;

public final class PublishedAgentReadinessProbe {

    private static final String READINESS_OBJECT_NAME =
            "space.br1440.platform.tracing:type=Readiness,name=PlatformExtension";
    private static final String IDENTITY_ENTRY =
            "META-INF/platform-tracing/javaagent-distribution.properties";
    private static final String EXTENSION_ENTRY =
            "extensions/platform-tracing-otel-javaagent-extension.jar";
    private static final String UPSTREAM_COORDINATE =
            "io.opentelemetry.javaagent:opentelemetry-javaagent:2.30.0";
    private static final Set<String> EXPECTED_CAPABILITIES = Set.of(
            "CONFIGURATION_LOADED",
            "PLATFORM_SAMPLER_INSTALLED",
            "REQUIRED_SPAN_PROCESSORS_INSTALLED",
            "SANITIZER_INSTALLED",
            "PROPAGATION_HOOKS_INSTALLED",
            "SAFE_EXPORTER_INSTALLED",
            "EXPORT_PATH_PROTECTED");

    private PublishedAgentReadinessProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException(
                    "Expected distribution version, controlled agent path and upstream SHA-256");
        }
        if (Runtime.version().feature() != 21) {
            throw new IllegalStateException("Published agent probe must run on Java 21");
        }

        String version = arguments[0];
        Path controlledAgent = Path.of(arguments[1]);
        String upstreamSha256 = requireSha256("upstream SHA-256", arguments[2]);

        verifyHermeticLaunch(controlledAgent);
        verifyApplicationClassLoaderIsolation();
        verifyDistributionIdentity(controlledAgent, version, upstreamSha256);
        verifyReadiness(version);

        System.out.println("PUBLISHED_AGENT_PROBE_OK version=" + version
                + " profile=platform-agent-secure-v1 capabilities=7");
    }

    private static void verifyHermeticLaunch(Path controlledAgent) throws Exception {
        String classPath = System.getProperty("java.class.path");
        if (classPath == null || classPath.isBlank()
                || classPath.indexOf(File.pathSeparatorChar) >= 0) {
            throw new IllegalStateException("Probe classpath must contain exactly one entry: " + classPath);
        }
        Path actualClassPath = Path.of(classPath).toRealPath();
        Path probeLocation = Path.of(PublishedAgentReadinessProbe.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()).toRealPath();
        if (!actualClassPath.equals(probeLocation) || actualClassPath.equals(controlledAgent.toRealPath())) {
            throw new IllegalStateException("Probe classpath is not isolated: " + actualClassPath);
        }
        if (System.getProperty("otel.javaagent.extensions") != null) {
            throw new IllegalStateException("External Java agent extensions must not be configured");
        }
        for (String name : Set.of(
                "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH")) {
            if (System.getenv(name) != null) {
                throw new IllegalStateException("Contaminating JVM environment variable is present: " + name);
            }
        }
        for (String name : System.getenv().keySet()) {
            String normalized = name.toUpperCase(Locale.ROOT);
            if (normalized.startsWith("OTEL_") || normalized.startsWith("PLATFORM_TRACING_")) {
                throw new IllegalStateException("Contaminating tracing environment variable is present: " + name);
            }
        }
    }

    private static void verifyApplicationClassLoaderIsolation() {
        assertClassUnavailable(
                "io.opentelemetry.sdk.OpenTelemetrySdk", ClassLoader.getSystemClassLoader());
        assertClassUnavailable(
                "io.opentelemetry.sdk.OpenTelemetrySdk",
                PublishedAgentReadinessProbe.class.getClassLoader());
    }

    private static void assertClassUnavailable(String name, ClassLoader loader) {
        try {
            Class.forName(name, false, loader);
            throw new IllegalStateException("Application class loader can load agent-owned class: " + name);
        } catch (ClassNotFoundException expected) {
            return;
        }
    }

    private static void verifyDistributionIdentity(
            Path controlledAgent,
            String version,
            String upstreamSha256
    ) throws IOException {
        Properties identity = new Properties();
        String actualExtensionSha256;
        try (ZipFile zip = new ZipFile(controlledAgent.toFile())) {
            ZipEntry identityEntry = zip.getEntry(IDENTITY_ENTRY);
            if (identityEntry == null) {
                throw new IllegalStateException("Controlled agent identity entry is missing");
            }
            try (InputStream input = zip.getInputStream(identityEntry)) {
                identity.load(input);
            }

            ZipEntry extensionEntry = zip.getEntry(EXTENSION_ENTRY);
            if (extensionEntry == null || extensionEntry.isDirectory()) {
                throw new IllegalStateException("Controlled agent extension entry is missing");
            }
            try (InputStream input = zip.getInputStream(extensionEntry)) {
                actualExtensionSha256 = sha256Hex(input);
            }
        }

        requireIdentity(identity, "format.version", "1");
        requireIdentity(identity, "distribution.group", "space.br1440.platform.starters.tracing");
        requireIdentity(identity, "distribution.artifact", "platform-tracing-javaagent");
        requireIdentity(identity, "distribution.version", version);
        requireIdentity(identity, "upstream.coordinate", UPSTREAM_COORDINATE);
        requireIdentity(identity, "upstream.sha256", upstreamSha256);
        requireIdentity(identity, "extension.path", EXTENSION_ENTRY);
        requireIdentity(identity, "extension.sha256", actualExtensionSha256);
        requireIdentity(identity, "minimum.java", "21");
        if (identity.size() != 9) {
            throw new IllegalStateException(
                    "Controlled agent identity contains unexpected keys: " + identity.keySet());
        }
    }

    private static void requireIdentity(Properties identity, String name, String expected) {
        String actual = identity.getProperty(name);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Controlled agent identity mismatch for " + name
                    + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static String requireSha256(String name, String value) {
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must contain exactly 64 lowercase hexadecimal characters");
        }
        return value;
    }

    private static String sha256Hex(InputStream input) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void verifyReadiness(String expectedVersion) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName objectName = new ObjectName(READINESS_OBJECT_NAME);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (!server.isRegistered(objectName)) {
                Thread.sleep(50);
                continue;
            }
            String lifecycle = (String) server.getAttribute(objectName, "LifecycleState");
            if ("FAILED".equals(lifecycle)) {
                throw new IllegalStateException("Platform extension readiness failed: code="
                        + server.getAttribute(objectName, "FailureCode")
                        + ", message=" + server.getAttribute(objectName, "FailureMessage"));
            }
            if ("READY".equals(lifecycle)) {
                verifyReadySnapshot(server, objectName, expectedVersion);
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Platform extension readiness did not become READY within 20 seconds");
    }

    private static void verifyReadySnapshot(
            MBeanServer server,
            ObjectName objectName,
            String expectedVersion
    ) throws Exception {
        Object protocolVersion = server.getAttribute(objectName, "ProtocolVersion");
        String profile = (String) server.getAttribute(objectName, "Profile");
        String extensionVersion = (String) server.getAttribute(objectName, "ExtensionVersion");
        String failureCode = (String) server.getAttribute(objectName, "FailureCode");
        String failureMessage = (String) server.getAttribute(objectName, "FailureMessage");
        String[] capabilities = (String[]) server.getAttribute(objectName, "Capabilities");

        if (!(protocolVersion instanceof Number number) || number.intValue() != 1) {
            throw new IllegalStateException("Unexpected readiness protocol version: " + protocolVersion);
        }
        if (!"platform-agent-secure-v1".equals(profile)) {
            throw new IllegalStateException("Unexpected readiness profile: " + profile);
        }
        if (!expectedVersion.equals(extensionVersion) || "development".equalsIgnoreCase(extensionVersion)) {
            throw new IllegalStateException("Unexpected extension version: " + extensionVersion);
        }
        if (!"".equals(failureCode) || !"".equals(failureMessage)) {
            throw new IllegalStateException("READY snapshot contains failure data: code="
                    + failureCode + ", message=" + failureMessage);
        }
        Set<String> actualCapabilities = new HashSet<>(Arrays.asList(capabilities));
        if (capabilities.length != EXPECTED_CAPABILITIES.size()
                || !actualCapabilities.equals(EXPECTED_CAPABILITIES)) {
            throw new IllegalStateException("Unexpected readiness capabilities: " + actualCapabilities);
        }
    }
}
