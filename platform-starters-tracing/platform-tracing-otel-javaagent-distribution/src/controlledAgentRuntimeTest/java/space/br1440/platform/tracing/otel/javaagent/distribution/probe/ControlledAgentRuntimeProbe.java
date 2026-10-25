package space.br1440.platform.tracing.otel.javaagent.distribution.probe;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.management.JMException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;

public final class ControlledAgentRuntimeProbe {

    private static final String IDENTITY_ENTRY = "META-INF/platform-tracing/javaagent-distribution.properties";
    private static final String UPSTREAM_COORDINATE_KEY = "upstream.coordinate";
    private static final String UPSTREAM_COORDINATE_PREFIX = "io.opentelemetry.javaagent:opentelemetry-javaagent:";
    private static final String EXPECTED_AGENT_SHA256_PROPERTY = "platform.tracing.controlled-agent.expected-sha256";
    public static final String EXPECTED_API_CLASSPATH_PROPERTY = "platform.tracing.controlled-agent.expected-api-classpath";

    private static final String LIFECYCLE_READY = "READY";
    private static final String LIFECYCLE_FAILED = "FAILED";
    private static final String BOOTSTRAP_LOADER_LABEL = "bootstrap";
    private static final String VERIFY_BOOTSTRAP_POLICY_ARGUMENT = "verify-bootstrap-policy";
    private static final String SPRING_APPLICATION_RESOURCE = "org/springframework/boot/SpringApplication.class";
    private static final String BOOTSTRAP_POLICY_DROP_PATH = "/bootstrap-policy-probe";

    private static final List<String> CONTAMINATING_ENV_VARS = List.of(
            "JAVA_TOOL_OPTIONS",
            "_JAVA_OPTIONS",
            "JDK_JAVA_OPTIONS"
    );
    private static final String OTEL_ENV_PREFIX = "OTEL_";
    private static final String OTEL_EXTERNAL_EXTENSIONS_PROPERTY = "otel.javaagent.extensions";

    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration READINESS_POLL_INTERVAL = Duration.ofMillis(50);
    private static final Duration SHUTDOWN_OBSERVER_TIMEOUT = Duration.ofSeconds(20);

    public static final String SHUTDOWN_OK_MARKER = "CONTROLLED_AGENT_SHUTDOWN_OK";

    private static final String AGENT_OWNED_CLASS_MARKER = "io.opentelemetry.sdk.OpenTelemetrySdk";

    private ControlledAgentRuntimeProbe() {
    }

    public static void main(String[] arguments) throws IOException, JMException, InterruptedException {
        ProbeArguments probeArguments = ProbeArguments.parse(arguments);
        ProbeMode mode = probeArguments.mode();

        verifyHermeticLaunch(probeArguments.controlledAgent(), mode);
        verifyApplicationClassLoaderIsolation();
        if (mode.verifiesBootstrapPolicy()) {
            verifySpringUnavailable();
        }

        String upstreamVersion = verifyDistributionIdentity(probeArguments);
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        String agentInstanceId = verifyReadiness(server, probeArguments.version(), upstreamVersion);
        verifyAcceptedSettingsIdentity(server, agentInstanceId, mode);
        if (mode.verifiesBootstrapPolicy()) {
            BootstrapDropPath.verify();
        }

        verifyProcessValidationState(server, agentInstanceId);
        installShutdownObserver(server);

        System.out.printf("CONTROLLED_AGENT_PROBE_OK version=%s schema=%s capabilities=%d%n",
                probeArguments.version(), JmxWireContract.SCHEMA_ID,
                JmxWireContract.REQUIRED_CAPABILITIES.size());
    }

    private static void installShutdownObserver(MBeanServer server) {
        Thread observer = new Thread(
                () -> awaitReadinessWithdrawal(server),
                "controlled-agent-readiness-withdrawal-observer"
        );
        Runtime.getRuntime().addShutdownHook(observer);
    }

    private static void awaitReadinessWithdrawal(MBeanServer server) {
        MonotonicDeadline deadline = MonotonicDeadline.after(SHUTDOWN_OBSERVER_TIMEOUT);
        while (deadline.hasRemaining()) {
            if (!server.isRegistered(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME)) {
                System.out.println(SHUTDOWN_OK_MARKER);
                return;
            }

            try {
                Thread.sleep(READINESS_POLL_INTERVAL);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Shutdown readiness observer was interrupted", interrupted);
            }
        }

        throw new IllegalStateException("Readiness MBean remained registered after shutdown");
    }

    private static void verifyHermeticLaunch(Path controlledAgent, ProbeMode mode) throws IOException {
        verifyClasspathIsolatedFromAgent(controlledAgent, mode.expectsApiClasspath());
        verifyNoExternalExtensionsConfigured();
        verifyNoContaminatingEnvironment();
    }

    private static void verifyClasspathIsolatedFromAgent(Path controlledAgent,
                                                         boolean expectApiClasspath) throws IOException {
        String classPath = System.getProperty("java.class.path");
        List<Path> actualEntries = resolveClasspath(classPath);

        List<Path> expectedEntries = new ArrayList<>();
        expectedEntries.add(probeLocation());
        if (expectApiClasspath) {
            expectedEntries.addAll(resolveClasspath(requiredProperty(EXPECTED_API_CLASSPATH_PROPERTY)));
        }

        if (actualEntries.contains(controlledAgent.toRealPath())) {
            throw new IllegalStateException("Probe classpath contains the controlled agent");
        }

        if (!actualEntries.equals(expectedEntries)) {
            throw new IllegalStateException("Probe classpath differs from the expected isolated entries: " + actualEntries);
        }
    }

    private static Path probeLocation() {
        try {
            return Path.of(ControlledAgentRuntimeProbe.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()).toRealPath();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to resolve probe class location", exception);
        }
    }

    private static List<Path> resolveClasspath(String classPath) throws IOException {
        if (classPath == null || classPath.isBlank()) {
            throw new IllegalStateException("Probe classpath must not be blank");
        }

        List<Path> entries = new ArrayList<>();
        for (String entry : classPath.split(Pattern.quote(File.pathSeparator), -1)) {
            if (entry.isBlank()) {
                throw new IllegalStateException("Probe classpath contains a blank entry: " + classPath);
            }

            entries.add(Path.of(entry).toRealPath());
        }

        return List.copyOf(entries);
    }

    private static void verifyNoExternalExtensionsConfigured() {
        if (System.getProperty(OTEL_EXTERNAL_EXTENSIONS_PROPERTY) != null) {
            throw new IllegalStateException("External Java agent extensions must not be configured");
        }
    }

    private static void verifyNoContaminatingEnvironment() {
        List<String> contaminated = new ArrayList<>();
        for (String name : CONTAMINATING_ENV_VARS) {
            if (System.getenv(name) != null) {
                contaminated.add(name);
            }
        }

        System.getenv().keySet().stream()
                .filter(name -> name.toUpperCase(Locale.ROOT).startsWith(OTEL_ENV_PREFIX))
                .sorted()
                .forEach(contaminated::add);
        if (!contaminated.isEmpty()) {
            throw new IllegalStateException("Contaminating environment variables are present: " + contaminated);
        }
    }

    private static void verifyApplicationClassLoaderIsolation() {
        assertClassUnavailable(AGENT_OWNED_CLASS_MARKER, ClassLoader.getSystemClassLoader());
        assertClassUnavailable(AGENT_OWNED_CLASS_MARKER, ControlledAgentRuntimeProbe.class.getClassLoader());
    }

    private static void verifySpringUnavailable() {
        require(
                ClassLoader.getSystemResource(SPRING_APPLICATION_RESOURCE) == null,
                "Spring must not be available to the bootstrap policy probe"
        );
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

    private static String verifyDistributionIdentity(ProbeArguments arguments) throws IOException {
        Properties identity = readIdentityProperties(arguments.controlledAgent());
        Map<String, String> expected = expectedIdentity(arguments);
        Set<String> expectedNames = new LinkedHashSet<>(expected.keySet());
        expectedNames.add(UPSTREAM_COORDINATE_KEY);
        Set<String> actualNames = identity.stringPropertyNames();
        if (!actualNames.equals(expectedNames)) {
            throw new IllegalStateException("Controlled agent identity contains unexpected keys: " + actualNames);
        }

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            requireEqual(
                    "Controlled agent identity " + entry.getKey(),
                    entry.getValue(),
                    identity.getProperty(entry.getKey())
            );
        }
        return upstreamVersion(identity.getProperty(UPSTREAM_COORDINATE_KEY));
    }

    private static Map<String, String> expectedIdentity(ProbeArguments arguments) {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("format.version", "1");
        expected.put("distribution.group", "space.br1440.platform.starters.tracing");
        expected.put("distribution.artifact", "platform-tracing-javaagent");
        expected.put("distribution.version", arguments.version());
        expected.put("extension.path", "extensions/platform-tracing-otel-javaagent-extension.jar");
        expected.put("extension.sha256", arguments.extensionSha256());
        expected.put("upstream.sha256", arguments.upstreamSha256());
        expected.put("minimum.java", "21");
        return expected;
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
        try (ZipFile zip = new ZipFile(controlledAgent.toFile())) {
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

    private static String verifyReadiness(MBeanServer server,
                                          String expectedExtensionVersion,
                                          String expectedUpstreamVersion) throws JMException, InterruptedException {
        MonotonicDeadline deadline = MonotonicDeadline.after(READINESS_TIMEOUT);
        String lastObservation = "endpoint not registered";

        while (deadline.hasRemaining()) {
            if (server.isRegistered(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME)) {
                CompositeData snapshot = readReadinessSnapshot(server);
                String lifecycle = JmxValues.token(
                        snapshot,
                        JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE,
                        JmxWireContract.VALID_READINESS_LIFECYCLES
                );
                String failureCode = JmxValues.string(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE);
                lastObservation = "lifecycle=%s, failureCode=%s".formatted(lifecycle, failureCode);

                if (LIFECYCLE_FAILED.equals(lifecycle)) {
                    throw new IllegalStateException("Platform extension readiness failed: code=" + failureCode);
                }

                if (LIFECYCLE_READY.equals(lifecycle)) {
                    verifyReadinessMetadata(server);
                    verifyReadySnapshot(snapshot, expectedExtensionVersion, expectedUpstreamVersion);
                    require(snapshot.equals(readReadinessSnapshot(server)),
                            "Readiness snapshot changed between consecutive reads");
                    return JmxValues.string(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_INSTANCE_ID);
                }
            }
            Thread.sleep(READINESS_POLL_INTERVAL);
        }

        throw new IllegalStateException(
                "Platform extension readiness did not become READY within %d seconds; lastObservation=%s"
                        .formatted(READINESS_TIMEOUT.toSeconds(), lastObservation)
        );
    }

    private static CompositeData readReadinessSnapshot(MBeanServer server) {
        return JmxValues.attribute(
                server,
                JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT,
                JmxOpenTypes.READINESS_SNAPSHOT
        );
    }

    private static void verifyAcceptedSettingsIdentity(MBeanServer server,
                                                       String expectedAgentInstanceId,
                                                       ProbeMode mode) {
        require(server.isRegistered(JmxWireContract.SETTINGS_STATUS_OBJECT_NAME),
                "Settings status MBean is not registered");

        CompositeData snapshot = JmxValues.attribute(
                server,
                JmxWireContract.SETTINGS_STATUS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT,
                JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT);

        AcceptedPolicy policy = AcceptedPolicy.decode(snapshot);
        requireEqual(
                "Accepted settings agentInstanceId",
                expectedAgentInstanceId,
                policy.agentInstanceId()
        );

        if (mode.verifiesBootstrapPolicy()) {
            policy.verifyBootstrapDefaults();
        }
    }

    private static void verifyProcessValidationState(MBeanServer server, String expectedAgentInstanceId) {
        require(server.isRegistered(JmxWireContract.VALIDATION_OBSERVATION_OBJECT_NAME),
                "Validation observation MBean is not registered");

        CompositeData observation = JmxValues.attribute(
                server,
                JmxWireContract.VALIDATION_OBSERVATION_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_VALIDATION_OBSERVATION_VALIDATION_OBSERVATION,
                JmxOpenTypes.VALIDATION_OBSERVATION);
        requireEqual(
                "Validation observation agentInstanceId",
                expectedAgentInstanceId,
                JmxValues.string(observation, JmxWireContract.FIELD_VALIDATION_OBSERVATION_AGENT_INSTANCE_ID)
        );

        boolean ownerPresent = JmxValues.bool(observation, JmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_PRESENT);
        String validationLevel = JmxValues.string(observation, JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_LEVEL);
        require(ownerPresent, "Validation owner is absent after agent readiness");

        System.out.printf("CONTROLLED_AGENT_VALIDATION level=%s%n", validationLevel);
    }

    private static void verifyReadinessMetadata(MBeanServer server) throws JMException {
        MBeanInfo info = server.getMBeanInfo(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);
        MBeanAttributeInfo[] attributes = info.getAttributes();
        require(attributes.length == 1, "Readiness MBean must expose exactly one attribute");

        MBeanAttributeInfo attribute = attributes[0];
        require(JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT.equals(attribute.getName()),
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
        requireEqual(
                "Readiness contractId",
                JmxWireContract.SCHEMA_ID,
                JmxValues.string(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_CONTRACT_ID)
        );

        requireEqual(
                "Readiness schema digest",
                JmxWireContract.SCHEMA_DIGEST_SHA256,
                JmxValues.string(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_SCHEMA_DIGEST)
        );
    }

    private static void verifyNoFailureData(CompositeData snapshot) {
        require(JmxValues.string(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE).isEmpty(),
                "READY snapshot contains a failure code");

        require(JmxValues.strings(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_MISSING_REQUIRED).length == 0,
                "READY snapshot reports missing required capabilities");
    }

    private static void verifyCapabilities(CompositeData snapshot) {
        String[] capabilities = JmxValues.strings(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_CAPABILITIES);
        String[] requiredCapabilities = JmxWireContract.REQUIRED_CAPABILITIES.stream()
                .sorted()
                .toArray(String[]::new);
        require(Arrays.equals(capabilities, requiredCapabilities),
                "Unexpected readiness capabilities: " + Arrays.toString(capabilities));
    }

    private static void verifyVersions(CompositeData snapshot,
                                       String expectedExtensionVersion,
                                       String expectedUpstreamVersion) {
        String extensionVersion = JmxValues.string(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_EXTENSION_VERSION);
        if (!expectedExtensionVersion.equals(extensionVersion)) {
            throw new IllegalStateException(
                    "Unexpected extension version: expected=%s, actual=%s"
                            .formatted(expectedExtensionVersion, extensionVersion)
            );
        }

        requireEqual(
                "upstream agent version",
                expectedUpstreamVersion,
                JmxValues.string(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_UPSTREAM_AGENT_VERSION)
        );

        requireEqual(
                "Readiness distribution SHA-256",
                requiredProperty(EXPECTED_AGENT_SHA256_PROPERTY),
                JmxValues.string(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_DISTRIBUTION_SHA256)
        );
    }

    private static void verifyLoaderIsolation(CompositeData snapshot) {
        String applicationLoader = loaderDescription(ControlledAgentRuntimeProbe.class.getClassLoader());
        String extensionLoader = JmxValues.string(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_EXTENSION_DEFINING_LOADER);
        String sdkLoader = JmxValues.string(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_SDK_DEFINING_LOADER);

        require(!extensionLoader.isBlank() && !extensionLoader.equals(applicationLoader),
                "Extension defining loader is not isolated from the application loader");
        require(!sdkLoader.isBlank() && !sdkLoader.equals(applicationLoader),
                "OpenTelemetry SDK defining loader is not isolated from the application loader");
    }

    private static void verifyEvidenceIdentity(CompositeData snapshot) {
        requireNotBlank(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_INSTANCE_ID, "Readiness agentInstanceId");
        requireNotBlank(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_BATCH_ID, "Readiness batchId");
        requirePositive(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_EVIDENCE_GENERATION, "Readiness evidenceGeneration");
    }

    private static void requireNotBlank(CompositeData snapshot, String field, String label) {
        require(!JmxValues.string(snapshot, field).isBlank(), label + " is blank");
    }

    @SuppressWarnings("SameParameterValue")
    private static void requirePositive(CompositeData snapshot, String field, String label) {
        require(JmxValues.longValue(snapshot, field) > 0L, label + " must be positive");
    }

    private static String loaderDescription(ClassLoader loader) {
        if (loader == null) {
            return BOOTSTRAP_LOADER_LABEL;
        }

        String name = loader.getName();
        String stableName = (name == null || name.isBlank()) ? loader.getClass().getName() : name;
        return stableName + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

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

    private static void requireEqual(String label, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException("%s mismatch: expected=%s, actual=%s".formatted(label, expected, actual));
        }
    }

    private enum ProbeMode {
        STANDARD,
        BOOTSTRAP_POLICY;

        boolean verifiesBootstrapPolicy() {
            return this == BOOTSTRAP_POLICY;
        }

        boolean expectsApiClasspath() {
            return verifiesBootstrapPolicy();
        }
    }

    private record ProbeArguments(String version,
                                  Path controlledAgent,
                                  String upstreamSha256,
                                  String extensionSha256,
                                  ProbeMode mode) {

        static ProbeArguments parse(String[] arguments) {
            if (arguments.length != 4 && arguments.length != 5) {
                throw new IllegalArgumentException(
                        "Expected version, agent path, upstream SHA-256, extension SHA-256 and optional probe mode");
            }

            if (arguments.length == 5 && !VERIFY_BOOTSTRAP_POLICY_ARGUMENT.equals(arguments[4])) {
                throw new IllegalArgumentException("Unknown probe mode: " + arguments[4]);
            }

            return new ProbeArguments(
                    arguments[0],
                    Path.of(arguments[1]),
                    arguments[2],
                    arguments[3],
                    arguments.length == 5 ? ProbeMode.BOOTSTRAP_POLICY : ProbeMode.STANDARD
            );
        }
    }

    private record AcceptedPolicy(long acceptedSequence,
                                  String agentInstanceId,
                                  boolean samplingEnabled,
                                  double samplingRatio,
                                  Map<String, Double> samplingRouteRatios,
                                  String validationLevel,
                                  boolean exporterEnabled,
                                  boolean traceControlHeadersEnabled,
                                  String diagnosticsLogLevel,
                                  String sourceAuthority) {

        static AcceptedPolicy decode(CompositeData snapshot) {
            return new AcceptedPolicy(
                    JmxValues.longValue(snapshot, JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_ACCEPTED_SEQUENCE),
                    JmxValues.string(snapshot, JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_AGENT_INSTANCE_ID),
                    JmxValues.bool(snapshot, JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ENABLED),
                    JmxValues.doubleValue(snapshot, JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_RATIO),
                    samplingRouteRatios(snapshot),
                    JmxValues.string(snapshot, JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_VALIDATION_LEVEL),
                    JmxValues.bool(snapshot, JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_EXPORTER_ENABLED),
                    JmxValues.bool(snapshot, JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_TRACE_CONTROL_HEADERS_ENABLED),
                    JmxValues.string(snapshot, JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_DIAGNOSTICS_LOG_LEVEL),
                    JmxValues.string(snapshot, JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SOURCE_AUTHORITY)
            );
        }

        void verifyBootstrapDefaults() {
            require(acceptedSequence == 0L, "Bootstrap accepted sequence must be zero, got " + acceptedSequence);

            require(samplingEnabled, "Bootstrap sampling.enabled must be true");

            require(Double.compare(samplingRatio, 1.0d) == 0,
                    "Bootstrap sampling.ratio must be 1.0, got " + samplingRatio);

            require(Map.of("/health", 0.0d, "/orders", 0.25d).equals(samplingRouteRatios),
                    "Bootstrap sampling.route-ratios differ: " + samplingRouteRatios);

            require("ERROR".equals(validationLevel),
                    "Bootstrap validation level must be ERROR, got " + validationLevel);

            require(!exporterEnabled, "Bootstrap exporter.enabled must be false");

            require(!traceControlHeadersEnabled,
                    "Bootstrap propagation.trace-control-headers.enabled must be false");

            require("DEBUG".equals(diagnosticsLogLevel),
                    "Bootstrap diagnostics.log-level must be DEBUG, got " + diagnosticsLogLevel);

            require("BOOTSTRAP".equals(sourceAuthority),
                    "Bootstrap source authority must be BOOTSTRAP, got " + sourceAuthority);

            System.out.printf("CONTROLLED_AGENT_BOOTSTRAP_POLICY_OK sequence=%d%n", acceptedSequence);
        }

        private static Map<String, Double> samplingRouteRatios(CompositeData snapshot) {
            TabularData table = JmxValues.tabular(snapshot, JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ROUTE_RATIOS);
            Map<String, Double> routeRatios = new LinkedHashMap<>();
            for (Object rawRow : table.values()) {
                CompositeData row = JmxValues.asComposite(rawRow, "Accepted sampling.route-ratios row");
                String routePrefix = JmxValues.string(row, JmxWireContract.FIELD_ROUTE_RATIO_ROUTE_PREFIX);
                double ratio = JmxValues.doubleValue(row, JmxWireContract.FIELD_ROUTE_RATIO_RATIO);
                Double previous = routeRatios.put(routePrefix, ratio);
                require(previous == null, "Accepted sampling.route-ratios contains duplicate route " + routePrefix);
            }
            return Map.copyOf(routeRatios);
        }
    }

    private static final class BootstrapDropPath {

        private BootstrapDropPath() {
        }

        static void verify() {
            Span span = GlobalOpenTelemetry.getTracer("bootstrap-policy-probe")
                    .spanBuilder("bootstrap-policy-drop-path")
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute("url.path", BOOTSTRAP_POLICY_DROP_PATH)
                    .startSpan();
            try {
                require(!span.isRecording(), "Bootstrap sampling drop path must create a non-recording span");

                require(!span.getSpanContext().isSampled(), "Bootstrap sampling drop path must create an unsampled span");
            } finally {
                span.end();
            }
        }
    }
}
