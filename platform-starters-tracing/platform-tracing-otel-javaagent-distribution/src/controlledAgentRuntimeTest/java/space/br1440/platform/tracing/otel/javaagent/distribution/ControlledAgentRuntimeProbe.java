package space.br1440.platform.tracing.otel.javaagent.distribution;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeType;
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
    static final String EXPECTED_API_CLASSPATH_PROPERTY = "platform.tracing.controlled-agent.expected-api-classpath";

    private static final String DEVELOPMENT_VERSION_MARKER = "development";
    private static final String LIFECYCLE_READY = "READY";
    private static final String LIFECYCLE_FAILED = "FAILED";
    private static final String BOOTSTRAP_LOADER_LABEL = "bootstrap";
    private static final String VERIFY_BOOTSTRAP_POLICY_ARGUMENT = "verify-bootstrap-policy";
    private static final String SPRING_APPLICATION_RESOURCE = "org/springframework/boot/SpringApplication.class";
    private static final String BOOTSTRAP_POLICY_DROP_PATH = "/bootstrap-policy-probe";

    private static final Set<String> EXPECTED_CAPABILITIES = Set.of(
            "CONFIGURATION_LOADED",
            "PLATFORM_SAMPLER_INSTALLED",
            "REQUIRED_SPAN_PROCESSORS_INSTALLED",
            "SANITIZER_INSTALLED",
            "PROPAGATION_HOOKS_INSTALLED",
            "SAFE_EXPORTER_INSTALLED",
            "EXPORT_PATH_PROTECTED"
    );

    private static final List<String> CONTAMINATING_ENV_VARS = List.of(
            "JAVA_TOOL_OPTIONS",
            "_JAVA_OPTIONS",
            "JDK_JAVA_OPTIONS"
    );
    private static final String OTEL_ENV_PREFIX = "OTEL_";
    private static final String OTEL_EXTERNAL_EXTENSIONS_PROPERTY = "otel.javaagent.extensions";

    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration READINESS_POLL_INTERVAL = Duration.ofMillis(50);

    private static final String AGENT_OWNED_CLASS_MARKER = "io.opentelemetry.sdk.OpenTelemetrySdk";

    private ControlledAgentRuntimeProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        ProbeArguments probeArguments = ProbeArguments.parse(arguments);
        ProbeMode mode = probeArguments.mode();

        verifyHermeticLaunch(probeArguments.controlledAgent(), mode);
        verifyApplicationClassLoaderIsolation();
        if (mode.verifiesBootstrapPolicy()) {
            verifySpringUnavailable();
        }

        String upstreamVersion = verifyDistributionIdentity(probeArguments);
        Jmx jmx = Jmx.platform();
        String agentInstanceId = verifyReadiness(jmx, probeArguments.version(), upstreamVersion);
        verifyAcceptedSettingsIdentity(jmx, agentInstanceId, mode);
        if (mode.verifiesBootstrapPolicy()) {
            BootstrapDropPath.verify();
        }

        verifyProcessValidationState(jmx, agentInstanceId);

        System.out.printf("CONTROLLED_AGENT_PROBE_OK version=%s schema=%s capabilities=%d%n",
                probeArguments.version(), JmxWireContract.SCHEMA_ID, EXPECTED_CAPABILITIES.size());
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
            throw new IllegalStateException(
                    "Probe classpath differs from the expected isolated entries: " + actualEntries);
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

    private static String verifyReadiness(Jmx jmx,
                                          String expectedExtensionVersion,
                                          String expectedUpstreamVersion) throws Exception {
        long deadlineNanos = System.nanoTime() + READINESS_TIMEOUT.toNanos();
        String lastObservation = "endpoint not registered";

        while (System.nanoTime() < deadlineNanos) {
            if (!jmx.isRegistered(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME)) {
                lastObservation = "endpoint not registered";
                sleepPollInterval();
                continue;
            }

            CompositeData snapshot = jmx.read(
                    JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                    JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT,
                    JmxOpenTypes.READINESS_SNAPSHOT,
                    "ReadinessSnapshot"
            );
            String lifecycle = value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE, String.class);
            lastObservation = "lifecycle=%s, failureCode=%s".formatted(lifecycle, observedFailureCode(snapshot));
            if (LIFECYCLE_FAILED.equals(lifecycle)) {
                throw new IllegalStateException("Platform extension readiness failed: code=%s".formatted(
                        value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE, String.class)));
            }

            if (LIFECYCLE_READY.equals(lifecycle)) {
                verifyReadinessMetadata(jmx);
                verifyReadySnapshot(snapshot, expectedExtensionVersion, expectedUpstreamVersion);

                CompositeData repeated = jmx.read(
                        JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                        JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT,
                        JmxOpenTypes.READINESS_SNAPSHOT,
                        "ReadinessSnapshot"
                );
                require(snapshot.equals(repeated), "Readiness snapshot changed between consecutive reads");
                return value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_INSTANCE_ID, String.class);
            }

            sleepPollInterval();
        }

        throw new IllegalStateException(
                "Platform extension readiness did not become READY within %d seconds; lastObservation=%s"
                        .formatted(READINESS_TIMEOUT.toSeconds(), lastObservation)
        );
    }

    private static String observedFailureCode(CompositeData snapshot) {
        Object raw = snapshot.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE);
        return raw instanceof String code ? code : typeName(raw);
    }

    private static void verifyAcceptedSettingsIdentity(Jmx jmx,
                                                       String expectedAgentInstanceId,
                                                       ProbeMode mode) throws Exception {
        require(jmx.isRegistered(JmxWireContract.SETTINGS_STATUS_OBJECT_NAME),
                "Settings status MBean is not registered");

        CompositeData snapshot = jmx.read(
                JmxWireContract.SETTINGS_STATUS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT,
                JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT,
                "AcceptedPolicySnapshot"
        );

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

    private static void verifyProcessValidationState(Jmx jmx, String expectedAgentInstanceId) throws Exception {
        require(jmx.isRegistered(JmxWireContract.VALIDATION_OBSERVATION_OBJECT_NAME),
                "Validation observation MBean is not registered");

        CompositeData observation = jmx.read(
                JmxWireContract.VALIDATION_OBSERVATION_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_VALIDATION_OBSERVATION_VALIDATION_OBSERVATION,
                JmxOpenTypes.VALIDATION_OBSERVATION,
                "ValidationObservation"
        );
        requireEqual(
                "Validation observation agentInstanceId",
                expectedAgentInstanceId,
                value(observation, JmxWireContract.FIELD_VALIDATION_OBSERVATION_AGENT_INSTANCE_ID, String.class)
        );

        boolean ownerPresent = value(observation, JmxWireContract.FIELD_VALIDATION_OBSERVATION_OWNER_PRESENT, Boolean.class);
        String validationLevel = value(observation, JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_LEVEL, String.class);
        require(ownerPresent, "Validation owner is absent after agent readiness");

        System.out.printf("CONTROLLED_AGENT_VALIDATION level=%s%n", validationLevel);
    }

    private static void sleepPollInterval() throws InterruptedException {
        Thread.sleep(READINESS_POLL_INTERVAL.toMillis());
    }

    private static void verifyReadinessMetadata(Jmx jmx) throws Exception {
        MBeanInfo info = jmx.info(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);
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
                value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_CONTRACT_ID, String.class)
        );

        requireEqual(
                "Readiness schema digest",
                JmxWireContract.SCHEMA_DIGEST_SHA256,
                value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_SCHEMA_DIGEST, String.class)
        );
    }

    private static void verifyNoFailureData(CompositeData snapshot) {
        require(value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE, String.class).isEmpty(),
                "READY snapshot contains a failure code");

        require(value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_MISSING_REQUIRED, String[].class).length == 0,
                "READY snapshot reports missing required capabilities");
    }

    private static void verifyCapabilities(CompositeData snapshot) {
        String[] capabilities = value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_CAPABILITIES, String[].class);
        Set<String> actualCapabilities = new HashSet<>(Arrays.asList(capabilities));
        require(capabilities.length == EXPECTED_CAPABILITIES.size()
                        && actualCapabilities.equals(EXPECTED_CAPABILITIES),
                "Unexpected readiness capabilities: " + actualCapabilities);
    }

    private static void verifyVersions(CompositeData snapshot,
                                       String expectedExtensionVersion,
                                       String expectedUpstreamVersion) {
        String extensionVersion = value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_EXTENSION_VERSION, String.class);
        if (!expectedExtensionVersion.equals(extensionVersion)
                || DEVELOPMENT_VERSION_MARKER.equalsIgnoreCase(extensionVersion)) {
            throw new IllegalStateException(
                    "Unexpected extension version: expected=%s, actual=%s"
                            .formatted(expectedExtensionVersion, extensionVersion)
            );
        }

        requireEqual(
                "upstream agent version",
                expectedUpstreamVersion,
                value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_UPSTREAM_AGENT_VERSION, String.class)
        );

        requireEqual(
                "Readiness distribution SHA-256",
                requiredProperty(EXPECTED_AGENT_SHA256_PROPERTY),
                value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_DISTRIBUTION_SHA256, String.class)
        );
    }

    private static void verifyLoaderIsolation(CompositeData snapshot) {
        String applicationLoader = loaderDescription(ControlledAgentRuntimeProbe.class.getClassLoader());
        String extensionLoader = value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_EXTENSION_DEFINING_LOADER, String.class);
        String sdkLoader = value(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_SDK_DEFINING_LOADER, String.class);

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
        require(!value(snapshot, field, String.class).isBlank(), label + " is blank");
    }

    @SuppressWarnings("SameParameterValue")
    private static void requirePositive(CompositeData snapshot, String field, String label) {
        require(number(snapshot, field).longValue() > 0L, label + " must be positive");
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
                "JMX field has an unexpected type: field=%s, expected=%s, actual=%s"
                        .formatted(field, expectedType.getName(), typeName(raw))
        );
        return expectedType.cast(raw);
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getName();
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
                    value(snapshot, JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_ACCEPTED_SEQUENCE, Long.class),
                    value(snapshot, JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_AGENT_INSTANCE_ID, String.class),
                    value(snapshot, JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ENABLED, Boolean.class),
                    value(snapshot, JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_RATIO, Double.class),
                    samplingRouteRatios(snapshot),
                    value(snapshot, JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_VALIDATION_LEVEL, String.class),
                    value(snapshot, JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_EXPORTER_ENABLED, Boolean.class),
                    value(snapshot, JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_TRACE_CONTROL_HEADERS_ENABLED, Boolean.class),
                    value(snapshot, JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_DIAGNOSTICS_LOG_LEVEL, String.class),
                    value(snapshot, JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SOURCE_AUTHORITY, String.class)
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
            Object raw = snapshot.get(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ROUTE_RATIOS);
            require(raw instanceof TabularData,
                    "Accepted sampling.route-ratios must be TabularData, got " + typeName(raw));

            TabularData table = (TabularData) raw;
            Map<String, Double> routeRatios = new LinkedHashMap<>();
            for (Object rawRow : table.values()) {
                require(rawRow instanceof CompositeData,
                        "Accepted sampling.route-ratios row must be CompositeData, got " + typeName(rawRow));

                CompositeData row = (CompositeData) rawRow;
                String routePrefix = value(row, JmxWireContract.FIELD_ROUTE_RATIO_ROUTE_PREFIX, String.class);
                double ratio = value(row, JmxWireContract.FIELD_ROUTE_RATIO_RATIO, Double.class);
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

    private record Jmx(MBeanServer server) {

        static Jmx platform() {
            return new Jmx(ManagementFactory.getPlatformMBeanServer());
        }

        boolean isRegistered(ObjectName name) {
            return server.isRegistered(name);
        }

        @SuppressWarnings("SameParameterValue")
        MBeanInfo info(ObjectName name) throws Exception {
            return server.getMBeanInfo(name);
        }

        CompositeData read(ObjectName name,
                           String attribute,
                           CompositeType expectedType,
                           String label) throws Exception {
            Object raw = server.getAttribute(name, attribute);
            require(raw instanceof CompositeData, label + " must be CompositeData, got " + typeName(raw));

            CompositeData data = (CompositeData) raw;
            require(expectedType.equals(data.getCompositeType()),
                    label + " OpenType differs from the generated application contract");
            return data;
        }
    }
}
