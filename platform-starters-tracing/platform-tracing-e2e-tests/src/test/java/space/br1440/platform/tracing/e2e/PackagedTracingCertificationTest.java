package space.br1440.platform.tracing.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.e2e.artifact.ControlledAgentArtifactMutator;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.Span;

class PackagedTracingCertificationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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
    private Path postSdkFailureListener;
    private Path evidenceDirectory;
    private PackagedProcessLauncher launcher;

    @BeforeEach
    void prepare() throws IOException {
        controlledAgent = requiredFile("platformTracing.controlledAgentJar");
        applicationBootJar = requiredFile("platformTracing.applicationBootJar");
        dualSdkApplicationBootJar = requiredFile("platformTracing.dualSdkApplicationBootJar");
        collisionAgent = requiredFile("platformTracing.collisionAgentJar");
        postSdkFailureListener = requiredFile("platformTracing.postSdkFailureListenerJar");
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
    void packaged_agent_preserves_sampling_order_reasons_and_runtime_updates() throws Exception {
        // given
        List<byte[]> payloads = new CopyOnWriteArrayList<>();
        HttpServer collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        collector.createContext("/v1/traces", exchange -> {
            try (InputStream input = exchange.getRequestBody()) {
                payloads.add(input.readAllBytes());
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        collector.start();

        PackagedProcessLauncher.ProcessResult result;
        try {
            Map<String, String> properties = new LinkedHashMap<>();
            properties.put("platformTracing.packagedScenario", "SAMPLING");
            properties.put("platform.environment", "LOCAL");
            properties.put("platform.tracing.exporter.enabled", "true");
            properties.put("platform.tracing.sampling.ratio", "0");
            properties.put("platform.tracing.sampling.route-ratios", "/api=0,/api/keep=1");
            properties.put("platform.tracing.javaagent.sampling.drop-paths", "/drop");
            properties.put("platform.tracing.javaagent.sampling.force-record-values", "on");
            properties.put("otel.exporter.otlp.traces.protocol", "http/protobuf");
            properties.put("otel.exporter.otlp.traces.compression", "none");
            properties.put("otel.exporter.otlp.traces.endpoint",
                    "http://127.0.0.1:" + collector.getAddress().getPort() + "/v1/traces");

            // when
            result = launcher.launch(controlledRequest("sampling-core", applicationBootJar, controlledAgent,
                    ControlledAgentArtifactMutator.sha256(controlledAgent), properties));
        } finally {
            collector.stop(0);
        }

        // then
        assertSuccessful(result, "sampling-core");
        assertThat(result.stdout()).contains(PackagedSamplingScenario.SUCCESS_TOKEN);
        Map<String, String> reasons = new LinkedHashMap<>();
        for (byte[] payload : payloads) {
            for (var resource : ExportTraceServiceRequest.parseFrom(payload).getResourceSpansList()) {
                for (var scope : resource.getScopeSpansList()) {
                    for (Span span : scope.getSpansList()) {
                        String caseId = samplingAttribute(span, "sampling.case");
                        if (caseId != null) {
                            assertThat(reasons.put(caseId, samplingAttribute(span, "platform.sampling.reason"))).isNull();
                        }
                    }
                }
            }
        }

        assertThat(reasons).containsExactlyInAnyOrderEntriesOf(Map.of(
                "sampling-force", "force_header",
                "sampling-qa", "qa_trace",
                "sampling-route-keep", "route_ratio",
                "sampling-parent-force", "force_header",
                "sampling-updated-default", "global_ratio"
        ));
    }

    private static String samplingAttribute(Span span, String key) {
        return span.getAttributesList().stream().filter(attribute -> attribute.getKey().equals(key))
                .map(attribute -> attribute.getValue().getStringValue()).findFirst().orElse(null);
    }

    @ParameterizedTest
    @CsvSource({"default,1000", "password,1000", "password,64"})
    void packaged_agent_masks_diagnostic_text_in_the_otlp_payload(String builtInRules, int attributeLimit) throws Exception {
        // given
        String scenario = "diagnostic-masking-" + builtInRules + "-" + attributeLimit;
        List<String> payloads = new CopyOnWriteArrayList<>();
        HttpServer collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        collector.createContext("/v1/traces", exchange -> {
            try (InputStream input = exchange.getRequestBody()) {
                payloads.add(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        collector.start();
        PackagedProcessLauncher.ProcessResult result;
        try {
            Map<String, String> properties = new LinkedHashMap<>(Map.of(
                    "platformTracing.packagedScenario", "URL_MASKING",
                    "platform.tracing.exporter.enabled", "true",
                    "platform.tracing.sampling.ratio", "1.0",
                    "otel.exporter.otlp.traces.protocol", "http/protobuf",
                    "otel.exporter.otlp.traces.compression", "none",
                    "otel.exporter.otlp.traces.endpoint",
                    "http://127.0.0.1:" + collector.getAddress().getPort() + "/v1/traces"
            ));

            properties.put("otel.span.attribute.value.length.limit", Integer.toString(attributeLimit));
            if (!"default".equals(builtInRules)) {
                properties.put("platform.tracing.javaagent.scrubbing.built-in-rules", builtInRules);
            }

            PackagedProcessLauncher.LaunchRequest request = controlledRequest(
                    scenario, applicationBootJar, controlledAgent,
                    ControlledAgentArtifactMutator.sha256(controlledAgent),
                    properties
            );

            // when
            result = launcher.launch(request);
        } finally {
            collector.stop(0);
        }

        // then
        assertSuccessful(result, scenario);
        assertThat(payloads).isNotEmpty();
        String maskedMessage = "Request failed: https://***@service.example/path?token=***&mode=***#***";
        assertThat(String.join("\n", payloads))
                .contains("packaged-url-failure", "packaged-native-failure", "packaged-facade-failure",
                        "packaged-sensitive-facade-failure", "exception.message", "exception.type",
                        "java.lang.IllegalStateException", "Connection refused", "<redacted>", maskedMessage)
                .doesNotContain("packaged-user", "packaged-password-canary", "packaged-query-canary",
                        "packaged-fragment-canary", "debug", "packaged-cause-canary", "packaged-root-canary",
                        "packaged-suppressed-canary", "packaged-facade-canary", "packaged-cut-canary", "x".repeat(63));
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

    @ParameterizedTest
    @ValueSource(strings = {"otel.config.file", "otel.javaagent.configuration-file"})
    void unsupported_configuration_channel_is_rejected_by_spring_without_readiness_endpoint(String property) throws Exception {
        // given
        String scenario = property.equals("otel.config.file") ? "declarative-config" : "agent-config-file";
        Path configuration = evidenceDirectory.resolve(scenario + ".config");
        Files.writeString(configuration, "");
        PackagedProcessLauncher.LaunchRequest request = controlledRequest(
                scenario, applicationBootJar, controlledAgent,
                ControlledAgentArtifactMutator.sha256(controlledAgent),
                Map.of(property, configuration.toAbsolutePath().toString()));

        // when
        PackagedProcessLauncher.ProcessResult result = launcher.launch(request);

        // then
        assertFailure(scenario, result, "EXTENSION_MISSING", "PLATFORM_EXTENSION_MISSING", null);
        assertStartupFailureSummary(result);
        assertThat(result.stderr()).doesNotContain("OpenTelemetry Javaagent failed to start");
    }

    @Test
    @SuppressWarnings("HttpUrlsUsage")
    void hostless_otlp_endpoint_is_rejected_by_spring_without_readiness_timeout() throws Exception {
        // given
        PackagedProcessLauncher.LaunchRequest request = controlledRequest(
                "hostless-otlp-endpoint", applicationBootJar, controlledAgent,
                ControlledAgentArtifactMutator.sha256(controlledAgent),
                Map.of("otel.exporter.otlp.endpoint", "http:///v1/traces")
        );

        // when
        PackagedProcessLauncher.ProcessResult result = launcher.launch(request);

        // then
        assertFailure("hostless-otlp-endpoint", result, "EXTENSION_FAILED", "AGENT_STARTUP_FAILED", "FAILED");
        assertStartupFailureSummary(result);
        assertThat(result.combinedOutput())
                .doesNotContain("EXTENSION_READINESS_TIMEOUT")
                .doesNotContain(PackagedTracingCertificationApplication.LIFECYCLE_SENTINEL_STARTED_TOKEN)
                .doesNotContain(PackagedTracingCertificationApplication.APPLICATION_READY_TOKEN);
        assertThat(result.stderr()).doesNotContain("OpenTelemetry Javaagent failed to start");
    }

    @Test
    void listener_failure_after_sdk_creation_is_rejected_before_platform_activation() throws Exception {
        // given
        String sourceAgentSha = ControlledAgentArtifactMutator.sha256(controlledAgent);
        Path fixtureAgent = ControlledAgentArtifactMutator.withFailingBeforeActivationListener(
                controlledAgent,
                evidenceDirectory.resolve("fixture-agent-post-sdk-listener-failure.jar"),
                postSdkFailureListener
        );
        String fixtureAgentSha = ControlledAgentArtifactMutator.sha256(fixtureAgent);
        String fixtureExtensionSha = ControlledAgentArtifactMutator.sha256(
                ControlledAgentArtifactMutator.packagedExtension(fixtureAgent));
        Files.writeString(evidenceDirectory.resolve("post-sdk-listener-failure.digests.properties"),
                "source.agent.sha256=" + sourceAgentSha + "\n"
                        + "fixture.agent.sha256=" + fixtureAgentSha + "\n"
                        + "fixture.extension.sha256=" + fixtureExtensionSha + "\n"
                        + "listener.fixture.sha256=" + ControlledAgentArtifactMutator.sha256(postSdkFailureListener) + "\n",
                StandardCharsets.UTF_8);
        PackagedProcessLauncher.LaunchRequest request = controlledRequest(
                "post-sdk-listener-failure", applicationBootJar, fixtureAgent, fixtureAgentSha, Map.of());

        // when
        PackagedProcessLauncher.ProcessResult result = launcher.launch(request);

        // then
        assertThat(result.stdout().lines()
                .filter(line -> line.equals("SDK_CREATED_BEFORE_PLATFORM_ACTIVATION")))
                .containsExactly("SDK_CREATED_BEFORE_PLATFORM_ACTIVATION");
        assertFailure("post-sdk-listener-failure", result, "EXTENSION_FAILED", "AGENT_STARTUP_FAILED", "FAILED");
        JsonNode summary = assertStartupFailureSummary(result);
        assertThat(summary.path("fields").path("lifecycle").asText()).isEqualTo("FAILED");
        assertThat(summary.path("fields").path("failureCode").asText()).isEqualTo("AGENT_STARTUP_FAILED");
        assertThat(summary.path("fields").path("primaryFailureCode").asText()).isEqualTo("AGENT_STARTUP_FAILED");
        assertThat(result.combinedOutput())
                .doesNotContain("EXTENSION_READINESS_TIMEOUT")
                .doesNotContain("\"event_name\":\"EXTENSION_INSTALLED\"")
                .doesNotContain("lifecycle=READY")
                .doesNotContain("\"lifecycle\":\"READY\"")
                .doesNotContain(PackagedTracingCertificationApplication.LIFECYCLE_SENTINEL_STARTED_TOKEN)
                .doesNotContain(PackagedTracingCertificationApplication.APPLICATION_READY_TOKEN);
        assertThat(result.stderr()).doesNotContain("OpenTelemetry Javaagent failed to start");
        assertThat(ControlledAgentArtifactMutator.sha256(controlledAgent)).isEqualTo(sourceAgentSha);
        assertThat(fixtureAgentSha).isNotEqualTo(sourceAgentSha);
        assertThat(ControlledAgentArtifactMutator.identity(fixtureAgent).getProperty("extension.sha256"))
                .isEqualTo(fixtureExtensionSha);
    }

    @Test
    void disabled_sdk_is_rejected_by_spring_as_failed_extension() throws Exception {
        // given
        PackagedProcessLauncher.LaunchRequest request = controlledRequest(
                "sdk-disabled", applicationBootJar, controlledAgent,
                ControlledAgentArtifactMutator.sha256(controlledAgent),
                Map.of("otel.sdk.disabled", "true"));

        // when
        PackagedProcessLauncher.ProcessResult result = launcher.launch(request);

        // then
        assertExtensionConfigurationFailure(result, "sdk-disabled");
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

    private static JsonNode assertStartupFailureSummary(PackagedProcessLauncher.ProcessResult result) throws IOException {
        List<JsonNode> summaries = new ArrayList<>();
        for (String line : result.stderr().lines().filter(value -> value.startsWith("{")).toList()) {
            JsonNode event = JSON.readTree(line);
            if ("STARTUP_FAILED".equals(event.path("event_name").asText())) {
                summaries.add(event);
            }
        }

        assertThat(summaries)
                .as("Packaged stderr must contain exactly one startup failure summary: %s", result.stderr())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.path("level").asText()).isEqualTo("ERROR");
                    assertThat(event.path("event_code").asText()).isEqualTo("PTA-BOOT-012");
                });
        return summaries.getFirst();
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
        properties.put("otel.traces.exporter", "otlp");
        properties.put("platform.tracing.exporter.enabled", "false");
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
        if (StringUtils.isNullOrBlank(value)) {
            throw new IllegalStateException("Required test system property is missing: " + name);
        }

        return value;
    }
}
