package space.br1440.platform.tracing.otel.javaagent.distribution;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.management.MBeanServer;
import javax.management.ObjectName;

public final class ControlledAgentRuntimeProbe {

    private static final String READINESS_OBJECT_NAME =
            "space.br1440.platform.tracing:type=Readiness,name=PlatformExtension";
    private static final String IDENTITY_ENTRY =
            "META-INF/platform-tracing/javaagent-distribution.properties";
    private static final Set<String> EXPECTED_CAPABILITIES = Set.of(
            "CONFIGURATION_LOADED",
            "PLATFORM_SAMPLER_INSTALLED",
            "REQUIRED_SPAN_PROCESSORS_INSTALLED",
            "SANITIZER_INSTALLED",
            "PROPAGATION_HOOKS_INSTALLED",
            "SAFE_EXPORTER_INSTALLED",
            "EXPORT_PATH_PROTECTED");

    private ControlledAgentRuntimeProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 4) {
            throw new IllegalArgumentException("Expected version, agent path, upstream SHA-256 and extension SHA-256");
        }

        String version = arguments[0];
        Path controlledAgent = Path.of(arguments[1]);
        String upstreamSha256 = arguments[2];
        String extensionSha256 = arguments[3];

        verifyHermeticLaunch(controlledAgent);
        verifyApplicationClassLoaderIsolation();
        verifyDistributionIdentity(controlledAgent, version, upstreamSha256, extensionSha256);
        verifyReadiness(version);

        System.out.println("CONTROLLED_AGENT_PROBE_OK version=" + version
                + " profile=platform-agent-secure-v1 capabilities=7");
    }

    private static void verifyHermeticLaunch(Path controlledAgent) throws Exception {
        String classPath = System.getProperty("java.class.path");
        if (classPath == null || classPath.isBlank()
                || classPath.indexOf(File.pathSeparatorChar) >= 0) {
            throw new IllegalStateException("Probe classpath must contain exactly one entry: " + classPath);
        }
        Path actualClassPath = Path.of(classPath).toRealPath();
        Path probeLocation = Path.of(ControlledAgentRuntimeProbe.class
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
        for (String name : Set.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
            if (System.getenv(name) != null) {
                throw new IllegalStateException("Contaminating JVM environment variable is present: " + name);
            }
        }
        for (String name : System.getenv().keySet()) {
            if (name.toUpperCase(Locale.ROOT).startsWith("OTEL_")) {
                throw new IllegalStateException("Contaminating OTel environment variable is present: " + name);
            }
        }
    }

    private static void verifyApplicationClassLoaderIsolation() {
        assertClassUnavailable(
                "io.opentelemetry.sdk.OpenTelemetrySdk", ClassLoader.getSystemClassLoader());
        assertClassUnavailable(
                "io.opentelemetry.sdk.OpenTelemetrySdk",
                ControlledAgentRuntimeProbe.class.getClassLoader());
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
            String upstreamSha256,
            String extensionSha256
    ) throws IOException {
        Properties identity = new Properties();
        try (ZipFile zip = new ZipFile(controlledAgent.toFile())) {
            ZipEntry entry = zip.getEntry(IDENTITY_ENTRY);
            if (entry == null) {
                throw new IllegalStateException("Controlled agent identity entry is missing");
            }
            try (InputStream input = zip.getInputStream(entry)) {
                identity.load(input);
            }
        }
        requireIdentity(identity, "format.version", "1");
        requireIdentity(identity, "distribution.group", "space.br1440.platform.starters.tracing");
        requireIdentity(identity, "distribution.artifact", "platform-tracing-javaagent");
        requireIdentity(identity, "distribution.version", version);
        requireIdentity(
                identity,
                "upstream.coordinate",
                "io.opentelemetry.javaagent:opentelemetry-javaagent:2.30.0");
        requireIdentity(identity, "upstream.sha256", upstreamSha256);
        requireIdentity(
                identity,
                "extension.path",
                "extensions/platform-tracing-otel-javaagent-extension.jar");
        requireIdentity(identity, "extension.sha256", extensionSha256);
        requireIdentity(identity, "minimum.java", "21");
        if (identity.size() != 9) {
            throw new IllegalStateException("Controlled agent identity contains unexpected keys: "
                    + identity.keySet());
        }
    }

    private static void requireIdentity(Properties identity, String name, String expected) {
        String actual = identity.getProperty(name);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Controlled agent identity mismatch for " + name
                    + ": expected=" + expected + ", actual=" + actual);
        }
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
        if (!failureCode.isEmpty() || !failureMessage.isEmpty()) {
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
