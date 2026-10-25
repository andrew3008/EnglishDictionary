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
    private static final String RESOURCE_IDENTITY_MESSAGE = "Resource identity resolved";
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
        assertThat(happy.stdout()).containsSubsequence(
                PackagedTracingCertificationApplication.LIFECYCLE_SENTINEL_STARTED_TOKEN,
                PackagedTracingCertificationApplication.APPLICATION_READY_TOKEN,
                PackagedTracingCertificationApplication.PROCESS_OK_TOKEN
        );
        assertThat(happy.stderr()).doesNotContain("No SLF4J providers were found")
                .doesNotContain("Unable to load extension")
                .doesNotContain("Failed to load extension")
                .doesNotContain("FATAL");
    }

    @Test
    void config_server_refresh_flow_is_certified_in_packaged_child_jvm() throws Exception {
        // given
        PackagedProcessLauncher.LaunchRequest request = controlledRequest(
                "config-server-refresh",
                applicationBootJar,
                controlledAgent,
                ControlledAgentArtifactMutator.sha256(controlledAgent),
                Map.of(
                        "logging.level.space.br1440.platform.tracing.autoconfigure.control.configserver", "INFO",
                        "platform.tracing.control.channel", "CONFIG_SERVER",
                        "platformTracing.packagedScenario", "CONFIG_SERVER"
                )
        );

        // when
        PackagedProcessLauncher.ProcessResult result = launcher.launch(request);

        // then
        assertSuccessful(result, "config-server-refresh");
        assertThat(result.stdout()).containsSubsequence(
                PackagedTracingCertificationApplication.APPLICATION_READY_TOKEN,
                PackagedConfigServerScenario.INITIAL_APPLIED_TOKEN,
                PackagedConfigServerScenario.REFRESH_APPLIED_TOKEN,
                PackagedConfigServerScenario.REPEAT_UNCHANGED_TOKEN,
                PackagedConfigServerScenario.INVALID_REJECTED_TOKEN,
                PackagedTracingCertificationApplication.PROCESS_OK_TOKEN
        );
        assertThat(result.combinedOutput())
                .contains("Config Server settings delivery: phase=INITIAL")
                .contains("Config Server settings delivery: phase=REFRESH")
                .contains("outcome=APPLIED")
                .contains("outcome=UNCHANGED")
                .contains("Config Server manifest rejected locally; LKG retained");
    }

    @Test
    void invalid_scan_interval_fails_before_lifecycle_start() throws Exception {
        // given
        String controlledSha = ControlledAgentArtifactMutator.sha256(controlledAgent);
        PackagedProcessLauncher.LaunchRequest request = controlledRequest(
                "invalid-scan-interval",
                applicationBootJar,
                controlledAgent,
                controlledSha,
                Map.of("platform.tracing.span-timeout.scan-interval", "0")
        );

        // when
        PackagedProcessLauncher.ProcessResult result = launcher.launch(request);

        // then
        assertExtensionConfigurationFailure(result, "invalid-scan-interval");
    }

    @Test
    void invalid_scan_interval_fails_before_lifecycle_start_with_lazy_initialization() throws Exception {
        // given
        String controlledSha = ControlledAgentArtifactMutator.sha256(controlledAgent);
        PackagedProcessLauncher.LaunchRequest request = controlledRequest(
                "invalid-scan-interval-lazy",
                applicationBootJar,
                controlledAgent,
                controlledSha,
                Map.of(
                        "platform.tracing.span-timeout.scan-interval", "0",
                        "spring.main.lazy-initialization", "true"
                )
        );

        // when
        PackagedProcessLauncher.ProcessResult result = launcher.launch(request);

        // then
        assertExtensionConfigurationFailure(result, "invalid-scan-interval-lazy");
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
        Path buildMetadataRemovedAgent = ControlledAgentArtifactMutator.withoutExtensionEntry(
                controlledAgent,
                evidenceDirectory.resolve("fixture-agent-build-metadata-removed.jar"),
                ControlledAgentArtifactMutator.EXTENSION_BUILD_METADATA_ENTRY
        );
        Path mismatchedBuildVersionAgent =
                ControlledAgentArtifactMutator.withMismatchedExtensionBuildVersion(
                        controlledAgent,
                        evidenceDirectory.resolve("fixture-agent-build-version-mismatch.jar"),
                        STALE_EXTENSION_VERSION
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
        PackagedProcessLauncher.ProcessResult buildMetadataRemoved = launcher.launch(controlledRequest(
                "build-metadata-removed",
                applicationBootJar,
                buildMetadataRemovedAgent,
                ControlledAgentArtifactMutator.sha256(buildMetadataRemovedAgent),
                Map.of()
        ));
        PackagedProcessLauncher.ProcessResult mismatchedBuildVersion = launcher.launch(controlledRequest(
                "build-version-mismatch",
                applicationBootJar,
                mismatchedBuildVersionAgent,
                ControlledAgentArtifactMutator.sha256(mismatchedBuildVersionAgent),
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
                "EXTENSION_FAILED", "DISTRIBUTION_MISMATCH", "FAILED");

        assertFailure("accidental-remote-jmx", remoteConfigured,
                "EXTENSION_FAILED",
                "REMOTE_JMX_MANAGEMENT_AGENT_CONFIGURED",
                "FAILED");

        assertFailure("extension-missing", extensionMissing,
                "EXTENSION_MISSING", "PLATFORM_EXTENSION_MISSING", null);

        assertFailure("extension-provider-class-removed", providerClassRemoved,
                "EXTENSION_MISSING", "PLATFORM_EXTENSION_MISSING", null);

        assertFailure("spi-metadata-removed", spiMetadataRemoved,
                "EXTENSION_FAILED", "PLATFORM_ACP_NOT_EXECUTED", "FAILED");

        assertFailure("build-metadata-removed", buildMetadataRemoved,
                "EXTENSION_FAILED", "DISTRIBUTION_EVIDENCE_MISSING", "FAILED");

        assertFailure("build-version-mismatch", mismatchedBuildVersion,
                "EXTENSION_FAILED", "DISTRIBUTION_MISMATCH", "FAILED");

        assertFailure("stale-topology-api", staleTopologyApi,
                "EXTENSION_FAILED", "EXTENSION_LINKAGE_ERROR", "FAILED");

        assertThat(staleTopologyApi.combinedOutput())
                .contains("extensionVersion=" + STALE_EXTENSION_VERSION);

        assertThat(ControlledAgentArtifactMutator.identity(staleTopologyAgent))
                .containsEntry("distribution.version", STALE_EXTENSION_VERSION);

        assertFailure("dual-sdk", dualSdk,
                "DUAL_SDK_DETECTED", "DUAL_SDK_COLLISION", null);
    }

    @Test
    void build_info_supplies_service_version_in_boot_jar_layout() throws Exception {
        // given
        String fixtureVersion = requiredProperty("platformTracing.buildInfoFixtureVersion");
        Map<String, String> properties = controlledProperties(
                ControlledAgentArtifactMutator.sha256(controlledAgent)
        );
        properties.remove("platform.tracing.service.version");

        // when
        PackagedProcessLauncher.ProcessResult result = launcher.launch(
                new PackagedProcessLauncher.LaunchRequest(
                        "build-info-service-version",
                        applicationBootJar,
                        List.of(controlledAgent),
                        properties
                ));

        // then
        assertSuccessful(result, "build-info-service-version");
        assertThat(resourceIdentityLine(result.combinedOutput()))
                .as("platform customizer supplies service.version from BOOT-INF build-info; "
                        + "a missing version field means the build-info detector produced no value")
                .contains("\"version\":\"" + fixtureVersion + "\"");
    }

    @Test
    void foreign_mbean_collision_fails_closed() throws Exception {
        // given
        PackagedProcessLauncher.LaunchRequest request = new PackagedProcessLauncher.LaunchRequest(
                "mbean-collision",
                applicationBootJar,
                List.of(collisionAgent, controlledAgent),
                controlledProperties(ControlledAgentArtifactMutator.sha256(controlledAgent))
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
        properties.put("platform.tracing.control.channel", "ACTUATOR");
        return new PackagedProcessLauncher.LaunchRequest(
                scenario,
                applicationBootJar,
                List.of(),
                properties
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
                properties
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
        properties.put("platform.tracing.resource.validation-mode", "ERROR");
        properties.put("platform.tracing.controlled-agent.expected-sha256", expectedAgentSha);
        properties.put("platform.tracing.enabled", "true");
        properties.put("platform.tracing.control.channel", "ACTUATOR");
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

    private static void assertExtensionConfigurationFailure(PackagedProcessLauncher.ProcessResult result,
                                                            String scenario) {
        assertFailure(
                scenario,
                result,
                "EXTENSION_FAILED",
                "CONFIGURATION_INITIALIZATION_FAILED",
                "FAILED"
        );
        assertThat(result.combinedOutput())
                .doesNotContain(PackagedTracingCertificationApplication.LIFECYCLE_SENTINEL_STARTED_TOKEN)
                .doesNotContain(PackagedTracingCertificationApplication.APPLICATION_READY_TOKEN);
    }

    private static String resourceIdentityLine(String output) {
        return output.lines()
                .filter(line -> line.contains(RESOURCE_IDENTITY_MESSAGE))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Agent did not emit '" + RESOURCE_IDENTITY_MESSAGE + "'; output=" + output));
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
        assertThat(result.exitCode())
                .as("%s process unexpectedly succeeded; stdout=%s, stderr=%s",
                        scenario, result.stdout(), result.stderr())
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
