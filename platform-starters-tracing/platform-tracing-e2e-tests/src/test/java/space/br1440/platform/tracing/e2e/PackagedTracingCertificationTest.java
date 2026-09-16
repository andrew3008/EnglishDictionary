package space.br1440.platform.tracing.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.e2e.artifact.ControlledAgentArtifactMutator;

class PackagedTracingCertificationTest {

    static final Pattern FAILURE_ENVELOPE = Pattern.compile(
            "Platform tracing startup rejected: "
                    + "runtimeState=([A-Z][A-Z0-9_]*), reason=([A-Z][A-Z0-9_]*)([^\\r\\n]*)"
    );
    private static final Pattern LIFECYCLE_FIELD = Pattern.compile("(?:^|, )lifecycle=([A-Z][A-Z0-9_]*)");
    private static final String STALE_EXTENSION_VERSION = "0.9.0-stale-fixture";
    private static final String CUSTOMIZER_PROVIDER_CLASS_ENTRY =
            "space/br1440/platform/tracing/otel/javaagent/bootstrap/BootstrapAutoConfigurationCustomizerProvider.class";
    private static final String CUSTOMIZER_PROVIDER_SERVICE_ENTRY =
            "META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider";

    private Path controlledAgent;
    private Path applicationBootJar;
    private Path dualSdkApplicationBootJar;
    private Path collisionAgent;
    private Path evidenceDirectory;
    private PackagedProcessLauncher launcher;

    @BeforeEach
    void prepare() throws IOException {
        controlledAgent = requiredFile("platformTracing.controlledAgentJar");
        applicationBootJar = requiredFile("platformTracing.applicationBootJar");
        dualSdkApplicationBootJar = requiredFile("platformTracing.dualSdkApplicationBootJar");
        collisionAgent = requiredFile("platformTracing.collisionAgentJar");
        evidenceDirectory = Path.of(requiredProperty("platformTracing.evidenceDirectory"))
                .toAbsolutePath()
                .normalize();

        Files.createDirectories(evidenceDirectory);
        launcher = new PackagedProcessLauncher(evidenceDirectory);
    }

    @Test
    void real_boot_jar_and_controlled_agent_start_and_exit_cleanly() throws Exception {
        // given
        verifyGeneratedContractParity();

        // when
        PackagedProcessLauncher.ProcessResult happy = launcher.launch(controlledRequest(
                "happy-path",
                applicationBootJar,
                controlledAgent,
                ControlledAgentArtifactMutator.sha256(controlledAgent),
                Map.of()
        ));

        // then
        assertSuccessful(happy, "happy-path");
        assertThat(happy.stdout()).contains(PackagedTracingCertificationApplication.PROCESS_OK_TOKEN);
        assertThat(happy.stderr()).doesNotContain("No SLF4J providers were found")
                .doesNotContain("Unable to load extension")
                .doesNotContain("Failed to load extension")
                .doesNotContain("FATAL");
    }

    @Test
    void packaged_failure_and_security_matrix_fails_closed() throws Exception {
        // given
        String controlledSha = ControlledAgentArtifactMutator.sha256(controlledAgent);
        Path noExtensionAgent = ControlledAgentArtifactMutator.withoutPackagedExtension(
                controlledAgent,
                evidenceDirectory.resolve("fixture-agent-extension-missing.jar")
        );
        Path providerClassRemovedAgent = ControlledAgentArtifactMutator.withoutExtensionEntry(
                controlledAgent,
                evidenceDirectory.resolve("fixture-agent-provider-class-removed.jar"),
                CUSTOMIZER_PROVIDER_CLASS_ENTRY
        );
        Path spiMetadataRemovedAgent = ControlledAgentArtifactMutator.withoutExtensionEntry(
                controlledAgent,
                evidenceDirectory.resolve("fixture-agent-spi-metadata-removed.jar"),
                CUSTOMIZER_PROVIDER_SERVICE_ENTRY
        );
        Path staleTopologyAgent =
                ControlledAgentArtifactMutator.withSeparatelyVersionedStaleTopologyApi(
                        controlledAgent,
                        evidenceDirectory.resolve("fixture-agent-stale-topology-api.jar"),
                        STALE_EXTENSION_VERSION
                );

        // when
        PackagedProcessLauncher.ProcessResult missingAgent = launcher.launch(
                tracingEnabledWithoutAgentRequest("agent-missing"));
        PackagedProcessLauncher.ProcessResult digestMismatch = launcher.launch(controlledRequest(
                "distribution-mismatch",
                applicationBootJar,
                controlledAgent,
                "0".repeat(64),
                Map.of()
        ));
        PackagedProcessLauncher.ProcessResult remoteConfigured = launcher.launch(controlledRequest(
                "accidental-remote-jmx",
                applicationBootJar,
                controlledAgent,
                controlledSha,
                Map.of("com.sun.management.jmxremote.authenticate", "true")
        ));
        PackagedProcessLauncher.ProcessResult extensionMissing = launcher.launch(controlledRequest(
                "extension-missing",
                applicationBootJar,
                noExtensionAgent,
                ControlledAgentArtifactMutator.sha256(noExtensionAgent),
                Map.of()
        ));
        PackagedProcessLauncher.ProcessResult providerClassRemoved = launcher.launch(controlledRequest(
                "extension-provider-class-removed",
                applicationBootJar,
                providerClassRemovedAgent,
                ControlledAgentArtifactMutator.sha256(providerClassRemovedAgent),
                Map.of()
        ));
        PackagedProcessLauncher.ProcessResult spiMetadataRemoved = launcher.launch(controlledRequest(
                "spi-metadata-removed",
                applicationBootJar,
                spiMetadataRemovedAgent,
                ControlledAgentArtifactMutator.sha256(spiMetadataRemovedAgent),
                Map.of()
        ));
        PackagedProcessLauncher.ProcessResult staleTopologyApi = launcher.launch(controlledRequest(
                "stale-topology-api",
                applicationBootJar,
                staleTopologyAgent,
                ControlledAgentArtifactMutator.sha256(staleTopologyAgent),
                Map.of()
        ));
        PackagedProcessLauncher.ProcessResult dualSdk = launcher.launch(controlledRequest(
                "dual-sdk",
                dualSdkApplicationBootJar,
                controlledAgent,
                controlledSha,
                Map.of()
        ));

        // then
        assertFailure("agent-missing", missingAgent,
                "AGENT_MISSING", "CONTROLLED_AGENT_MISSING", null);

        assertFailure("distribution-mismatch", digestMismatch,
                "EXTENSION_INCOMPATIBLE", "DISTRIBUTION_MISMATCH", "FAILED");

        assertFailure("accidental-remote-jmx", remoteConfigured,
                "EXTENSION_INCOMPATIBLE",
                "REMOTE_JMX_UNCERTIFIED_STARTUP_CONFIGURATION",
                "FAILED");

        assertFailure("extension-missing", extensionMissing,
                "EXTENSION_MISSING", "PLATFORM_EXTENSION_MISSING", null);

        assertFailure("extension-provider-class-removed", providerClassRemoved,
                "EXTENSION_MISSING", "PLATFORM_EXTENSION_MISSING", null);

        assertFailure("spi-metadata-removed", spiMetadataRemoved,
                "EXTENSION_MISSING", "PLATFORM_EXTENSION_MISSING", null);

        assertFailure("stale-topology-api", staleTopologyApi,
                "EXTENSION_INCOMPATIBLE", "EXTENSION_LINKAGE_ERROR", "FAILED");

        assertThat(staleTopologyApi.combinedOutput())
                .contains("extensionVersion=" + STALE_EXTENSION_VERSION);

        assertThat(ControlledAgentArtifactMutator.identity(staleTopologyAgent))
                .containsEntry("distribution.version", STALE_EXTENSION_VERSION);

        assertFailure("dual-sdk", dualSdk,
                "DUAL_SDK_DETECTED", "DUAL_SDK_COLLISION", null);
    }

    @Test
    void foreign_mbean_collision_fails_closed() throws Exception {
        // given
        PackagedProcessLauncher.LaunchRequest request = new PackagedProcessLauncher.LaunchRequest(
                "mbean-collision",
                applicationBootJar,
                List.of(collisionAgent, controlledAgent),
                controlledProperties(ControlledAgentArtifactMutator.sha256(controlledAgent)),
                List.of()
        );

        // when
        PackagedProcessLauncher.ProcessResult result = launcher.launch(request);

        // then
        assertFailedProcess(result, "mbean-collision");
        assertThat(result.combinedOutput()).contains("JMX_BATCH_REGISTRATION_FAILED");
    }

    @SuppressWarnings("SameParameterValue")
    private PackagedProcessLauncher.LaunchRequest tracingEnabledWithoutAgentRequest(String scenario) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("spring.main.banner-mode", "off");
        properties.put("logging.level.root", "ERROR");
        properties.put("platform.tracing.enabled", "true");
        properties.put("platform.tracing.configuration.authority", "ACTUATOR");
        properties.put("platform.environment", "LOCAL");
        return new PackagedProcessLauncher.LaunchRequest(
                scenario,
                applicationBootJar,
                List.of(),
                properties,
                List.of()
        );
    }

    private PackagedProcessLauncher.LaunchRequest controlledRequest(String scenarioFileName,
                                                                    Path bootJar,
                                                                    Path agent,
                                                                    String expectedAgentSha,
                                                                    Map<String, String> overrides) {
        Map<String, String> properties = controlledProperties(expectedAgentSha);
        properties.putAll(overrides);
        return new PackagedProcessLauncher.LaunchRequest(
                scenarioFileName,
                bootJar,
                List.of(agent),
                properties,
                List.of()
        );
    }

    private Map<String, String> controlledProperties(String expectedAgentSha) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("spring.main.banner-mode", "off");
        properties.put("logging.level.root", "ERROR");
        properties.put("otel.traces.exporter", "logging");
        properties.put("otel.metrics.exporter", "none");
        properties.put("otel.logs.exporter", "none");
        properties.put("platform.tracing.service.name", "packaged-tracing-certification");
        properties.put("platform.tracing.service.version", "1.0.0");
        properties.put("platform.tracing.service.environment", "test");
        properties.put("platform.tracing.resource.validation-mode", "STRICT");
        properties.put("platform.tracing.jmx.remote.mode", "REMOTE_PROHIBITED");
        properties.put("platform.tracing.jmx.remote.listener-confirmed", "false");
        properties.put("platform.tracing.jmx.remote.authorization-forwarder-installed", "false");
        properties.put("platform.tracing.controlled-agent.expected-sha256", expectedAgentSha);
        properties.put("platform.tracing.enabled", "true");
        properties.put("platform.tracing.configuration.authority", "ACTUATOR");
        properties.put("platform.environment", "LOCAL");
        return properties;
    }

    private void verifyGeneratedContractParity() throws Exception {
        Path extension = evidenceDirectory.resolve("isolated-extension-contract.jar");
        Files.write(extension, ControlledAgentArtifactMutator.packagedExtension(controlledAgent));
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{extension.toUri().toURL()},
                ClassLoader.getPlatformClassLoader()
        )) {
            Class<?> extensionContract = Class.forName(
                    "space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated."
                            + "JmxWireContract",
                    true,
                    loader
            );

            assertThat(extensionContract).isNotSameAs(JmxWireContract.class);

            assertThat(stringField(extensionContract, "SCHEMA_ID"))
                    .isEqualTo(JmxWireContract.SCHEMA_ID);

            assertThat(stringField(extensionContract, "SCHEMA_DIGEST_SHA256"))
                    .isEqualTo(JmxWireContract.SCHEMA_DIGEST_SHA256);

            assertThat(stringField(extensionContract, "DESCRIPTOR_DIGEST_SHA256"))
                    .isEqualTo(JmxWireContract.DESCRIPTOR_DIGEST_SHA256);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static void assertSuccessful(PackagedProcessLauncher.ProcessResult result,
                                         String scenario) {
        assertThat(result.finished()).as(scenario + " process must terminate").isTrue();
        assertThat(result.exitCode())
                .as("%s process failed; stdout=%s, stderr=%s",
                        scenario, result.stdout(), result.stderr())
                .isZero();
    }

    private static void assertFailure(String scenario,
                                      PackagedProcessLauncher.ProcessResult result,
                                      String expectedRuntimeState,
                                      String expectedReason,
                                      String expectedLifecycle) {
        assertFailedProcess(result, scenario);
        String output = result.combinedOutput();
        Set<FailureEnvelope> observed = failureEnvelopes(output);
        FailureEnvelope expected = new FailureEnvelope(
                expectedRuntimeState,
                expectedReason,
                expectedLifecycle
        );

        assertThat(observed)
                .as("%s output must contain only the exact failure envelope; output=%s",
                        scenario, output)
                .containsExactly(expected);

        assertThat(output).doesNotContain(PackagedTracingCertificationApplication.PROCESS_OK_TOKEN);
    }

    private static Set<FailureEnvelope> failureEnvelopes(String output) {
        Matcher matcher = FAILURE_ENVELOPE.matcher(output);

        Set<FailureEnvelope> envelopes = new LinkedHashSet<>();
        while (matcher.find()) {
            Matcher lifecycle = LIFECYCLE_FIELD.matcher(matcher.group(3));
            envelopes.add(new FailureEnvelope(
                    matcher.group(1),
                    matcher.group(2),
                    lifecycle.find() ? lifecycle.group(1) : null
            ));
        }

        return envelopes;
    }

    private static void assertFailedProcess(PackagedProcessLauncher.ProcessResult result,
                                            String scenario) {
        assertThat(result.finished()).as(scenario + " process must terminate").isTrue();
        assertThat(result.exitCode())
                .as("%s process unexpectedly succeeded; stdout=%s, stderr=%s",
                        scenario, result.stdout(), result.stderr())
                .isNotNull()
                .isNotZero();
    }

    private static String stringField(Class<?> type, String name) throws ReflectiveOperationException {
        return (String) type.getField(name).get(null);
    }

    private record FailureEnvelope(String runtimeState, String reason, String lifecycle) {
    }

    private static Path requiredFile(String property) {
        Path path = Path.of(requiredProperty(property)).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Required certification artifact is missing: " + path);
        }

        return path;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required test system property is missing: " + name);
        }

        return value;
    }
}
