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
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

public final class ControlledAgentRuntimeProbe {

    private static final String IDENTITY_ENTRY = "META-INF/platform-tracing/javaagent-distribution.properties";
    private static final String UPSTREAM_COORDINATE_PREFIX = "io.opentelemetry.javaagent:opentelemetry-javaagent:";
    private static final String EXPECTED_AGENT_SHA256_PROPERTY = "platform.tracing.controlled-agent.expected-sha256";
    private static final int EXPECTED_IDENTITY_KEY_COUNT = 9;

    private static final String DEVELOPMENT_VERSION_MARKER = "development";
    private static final String LIFECYCLE_READY = "READY";
    private static final String LIFECYCLE_FAILED = "FAILED";
    private static final String BOOTSTRAP_LOADER_LABEL = "bootstrap";

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
        String upstreamVersion = verifyDistributionIdentity(probeArguments);
        verifyReadiness(probeArguments.version(), upstreamVersion);
        verifyProcessValidationState();

        System.out.printf("CONTROLLED_AGENT_PROBE_OK version=%s schema=%s capabilities=%d%n",
                probeArguments.version(), JmxWireContract.SCHEMA_ID, EXPECTED_CAPABILITIES.size());
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

    /** Проверяет identity-ресурс дистрибутива и возвращает версию upstream-агента. */
    private static String verifyDistributionIdentity(ProbeArguments arguments) throws IOException {
        Properties identity = readIdentityProperties(arguments.controlledAgent());
        if (identity.stringPropertyNames().size() != EXPECTED_IDENTITY_KEY_COUNT) {
            throw new IllegalStateException(
                    "Controlled agent identity contains unexpected keys: " + identity.stringPropertyNames());
        }

        requireIdentity(identity, "format.version", "1");
        requireIdentity(identity, "distribution.group", "space.br1440.platform.starters.tracing");
        requireIdentity(identity, "distribution.artifact", "platform-tracing-javaagent");
        requireIdentity(identity, "distribution.version", arguments.version());
        requireIdentity(identity, "extension.path", "extensions/platform-tracing-otel-javaagent-extension.jar");
        requireIdentity(identity, "extension.sha256", arguments.extensionSha256());
        requireIdentity(identity, "upstream.sha256", arguments.upstreamSha256());
        requireIdentity(identity, "minimum.java", "21");

        return upstreamVersion(identity.getProperty("upstream.coordinate"));
    }

    private static String upstreamVersion(String coordinate) {
        if (coordinate == null
                || !coordinate.startsWith(UPSTREAM_COORDINATE_PREFIX)
                || coordinate.length() == UPSTREAM_COORDINATE_PREFIX.length()) {
            throw new IllegalStateException("Controlled agent upstream coordinate is invalid: " + coordinate);
        }

        return coordinate.substring(UPSTREAM_COORDINATE_PREFIX.length());
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

    private static void verifyReadiness(String expectedExtensionVersion, String expectedUpstreamVersion)
            throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        long deadlineNanos = System.nanoTime() + READINESS_TIMEOUT.toNanos();

        while (System.nanoTime() < deadlineNanos) {
            if (!server.isRegistered(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME)) {
                sleepPollInterval();
                continue;
            }

            CompositeData snapshot = readinessSnapshot(server);
            String lifecycle = value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE,
                    String.class);
            if (LIFECYCLE_FAILED.equals(lifecycle)) {
                throw new IllegalStateException("Platform extension readiness failed: code=%s".formatted(
                        value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE,
                                String.class)));
            }

            if (LIFECYCLE_READY.equals(lifecycle)) {
                verifyReadinessMetadata(server);
                verifyReadySnapshot(snapshot, expectedExtensionVersion, expectedUpstreamVersion);

                // Повторное чтение доказывает, что снимок опубликован, а не собирается на каждый вызов.
                CompositeData repeated = readinessSnapshot(server);
                verifyReadySnapshot(repeated, expectedExtensionVersion, expectedUpstreamVersion);
                require(snapshot.equals(repeated), "Readiness snapshot changed between consecutive reads");
                return;
            }

            sleepPollInterval();
        }

        throw new IllegalStateException(
                "Platform extension readiness did not become READY within %d seconds"
                        .formatted(READINESS_TIMEOUT.toSeconds())
        );
    }

    private static void verifyProcessValidationState() throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        require(server.isRegistered(JmxWireContract.VALIDATION_OBSERVATION_OBJECT_NAME),
                "Validation observation MBean is not registered");

        Object raw = server.getAttribute(
                JmxWireContract.VALIDATION_OBSERVATION_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_VALIDATION_OBSERVATION_VALIDATION_OBSERVATION);
        require(raw instanceof CompositeData,
                "ValidationObservation must be CompositeData, got " + typeName(raw));

        CompositeData observation = (CompositeData) raw;
        require(JmxOpenTypes.VALIDATION_OBSERVATION.equals(observation.getCompositeType()),
                "ValidationObservation OpenType differs from the generated application contract");

        boolean ownerPresent = value(observation,
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_PRESENT, Boolean.class);
        boolean enabled = value(observation,
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_ENABLED, Boolean.class);
        boolean strict = value(observation,
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_STRICT, Boolean.class);
        require(ownerPresent, "Validation owner is absent after agent readiness");

        String requestedMode = System.getProperty("platform.tracing.validation.mode", "");
        if ("STRICT".equalsIgnoreCase(requestedMode)) {
            require(enabled && strict,
                    "Process STRICT did not become the accepted validation state: enabled="
                            + enabled + " strict=" + strict);
        }

        System.out.printf("CONTROLLED_AGENT_VALIDATION enabled=%s strict=%s%n", enabled, strict);
    }

    private static void sleepPollInterval() throws InterruptedException {
        Thread.sleep(READINESS_POLL_INTERVAL.toMillis());
    }

    private static CompositeData readinessSnapshot(MBeanServer server) throws Exception {
        Object raw = server.getAttribute(
                JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT);
        require(raw instanceof CompositeData,
                "ReadinessSnapshot must be CompositeData, got " + typeName(raw));

        CompositeData snapshot = (CompositeData) raw;
        require(JmxOpenTypes.READINESS_SNAPSHOT.equals(snapshot.getCompositeType()),
                "ReadinessSnapshot OpenType differs from the generated application contract");
        return snapshot;
    }

    /** Форма метаданных — часть контракта: consumer отвергает readiness с иной формой. */
    private static void verifyReadinessMetadata(MBeanServer server) throws Exception {
        MBeanInfo info = server.getMBeanInfo(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);
        MBeanAttributeInfo[] attributes = info.getAttributes();
        require(attributes.length == 1, "Readiness MBean must expose exactly one attribute");

        MBeanAttributeInfo attribute = attributes[0];
        require(JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT
                        .equals(attribute.getName()),
                "Readiness MBean exposes an unexpected attribute: " + attribute.getName());
        require(CompositeData.class.getName().equals(attribute.getType())
                        && attribute.isReadable()
                        && !attribute.isWritable(),
                "ReadinessSnapshot metadata is incompatible");
        require(info.getOperations().length == 0, "Readiness MBean must not expose operations");
    }

    private static void verifyReadySnapshot(CompositeData snapshot,
                                            String expectedExtensionVersion,
                                            String expectedUpstreamVersion) {
        verifySchemaIdentity(snapshot);
        verifyNoFailureData(snapshot);
        verifyCapabilities(snapshot);
        verifyVersions(snapshot, expectedExtensionVersion, expectedUpstreamVersion);
        verifyLoaderIsolation(snapshot);
        verifyEvidenceIdentity(snapshot);
    }

    private static void verifySchemaIdentity(CompositeData snapshot) {
        require(JmxWireContract.SCHEMA_ID.equals(
                        value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_CONTRACT_ID,
                                String.class)),
                "Readiness contractId mismatch");
        require(JmxWireContract.SCHEMA_DIGEST_SHA256.equals(
                        value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_SCHEMA_DIGEST,
                                String.class)),
                "Readiness schema digest mismatch");
    }

    private static void verifyNoFailureData(CompositeData snapshot) {
        require(value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE, String.class)
                        .isEmpty(),
                "READY snapshot contains a failure code");
        require(value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_MISSING_REQUIRED,
                        String[].class).length == 0,
                "READY snapshot reports missing required capabilities");
    }

    private static void verifyCapabilities(CompositeData snapshot) {
        String[] capabilities = value(snapshot,
                JmxWireContract.FIELD_READINESS_SNAPSHOT_CAPABILITIES, String[].class);
        Set<String> actualCapabilities = new HashSet<>(Arrays.asList(capabilities));
        require(capabilities.length == EXPECTED_CAPABILITIES.size()
                        && actualCapabilities.equals(EXPECTED_CAPABILITIES),
                "Unexpected readiness capabilities: " + actualCapabilities);
    }

    private static void verifyVersions(CompositeData snapshot,
                                       String expectedExtensionVersion,
                                       String expectedUpstreamVersion) {
        String extensionVersion = value(snapshot,
                JmxWireContract.FIELD_READINESS_SNAPSHOT_EXTENSION_VERSION, String.class);
        require(expectedExtensionVersion.equals(extensionVersion)
                        && !DEVELOPMENT_VERSION_MARKER.equalsIgnoreCase(extensionVersion),
                "Unexpected extension version: " + extensionVersion);
        require(expectedUpstreamVersion.equals(value(snapshot,
                        JmxWireContract.FIELD_READINESS_SNAPSHOT_UPSTREAM_AGENT_VERSION,
                        String.class)),
                "Unexpected upstream agent version");
        require(requiredProperty(EXPECTED_AGENT_SHA256_PROPERTY).equals(value(snapshot,
                        JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_DISTRIBUTION_SHA256,
                        String.class)),
                "Readiness distribution SHA-256 mismatch");
    }

    /**
     * Плоскости изолированы: расширение и SDK определяются загрузчиками агента, поэтому их
     * описания не могут совпасть с загрузчиком приложения.
     */
    private static void verifyLoaderIsolation(CompositeData snapshot) {
        String applicationLoader = loaderDescription(ControlledAgentRuntimeProbe.class.getClassLoader());
        String extensionLoader = value(snapshot,
                JmxWireContract.FIELD_READINESS_SNAPSHOT_EXTENSION_DEFINING_LOADER, String.class);
        String sdkLoader = value(snapshot,
                JmxWireContract.FIELD_READINESS_SNAPSHOT_SDK_DEFINING_LOADER, String.class);

        require(!extensionLoader.isBlank() && !extensionLoader.equals(applicationLoader),
                "Extension defining loader is not isolated from the application loader");
        require(!sdkLoader.isBlank() && !sdkLoader.equals(applicationLoader),
                "OpenTelemetry SDK defining loader is not isolated from the application loader");
    }

    private static void verifyEvidenceIdentity(CompositeData snapshot) {
        require(!value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_RUNTIME_EPOCH,
                        String.class).isBlank(),
                "Readiness runtimeEpoch is blank");
        require(!value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_BATCH_ID,
                        String.class).isBlank(),
                "Readiness batchId is blank");
        require(number(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_EVIDENCE_GENERATION)
                        .longValue() > 0L,
                "Readiness evidenceGeneration must be positive");
    }

    private static String loaderDescription(ClassLoader loader) {
        if (loader == null) {
            return BOOTSTRAP_LOADER_LABEL;
        }

        String name = loader.getName();
        String stableName = (name == null || name.isBlank()) ? loader.getClass().getName() : name;
        return stableName + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

    @SuppressWarnings("SameParameterValue")
    private static Number number(CompositeData snapshot, String field) {
        return value(snapshot, field, Number.class);
    }

    private static <T> T value(CompositeData snapshot, String field, Class<T> expectedType) {
        Object raw = snapshot.get(field);
        require(expectedType.isInstance(raw),
                "Readiness field has an unexpected type: field=%s, expected=%s, actual=%s"
                        .formatted(field, expectedType.getName(), typeName(raw)));
        return expectedType.cast(raw);
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    @SuppressWarnings("SameParameterValue")
    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required system property is missing: " + name);
        }

        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record ProbeArguments(String version,
                                  Path controlledAgent,
                                  String upstreamSha256,
                                  String extensionSha256) {
        static ProbeArguments parse(String[] arguments) {
            if (arguments.length != 4) {
                throw new IllegalArgumentException(
                        "Expected version, agent path, upstream SHA-256 and extension SHA-256");
            }

            return new ProbeArguments(
                    arguments[0],
                    Path.of(arguments[1]),
                    arguments[2],
                    arguments[3]
            );
        }
    }
}
