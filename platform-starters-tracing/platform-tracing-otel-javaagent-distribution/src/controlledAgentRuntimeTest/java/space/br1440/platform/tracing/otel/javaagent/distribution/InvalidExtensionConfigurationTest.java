package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.br1440.platform.tracing.otel.javaagent.distribution.probe.InvalidExtensionConfigurationProbe;

class InvalidExtensionConfigurationTest {

    private static final String EXPECTED_AGENT_SHA256_OPTION_PREFIX = "-Dplatform.tracing.controlled-agent.expected-sha256=";
    private static final String SAMPLER_ARGUMENT_CANARY = "sampler-arg-must-not-appear-7f31";

    @TempDir
    Path temporaryDirectory;

    @Test
    void invalid_extension_configuration_fails_bootstrap_without_platform_export()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();
        String controlSpanName = "extension-configuration-control-" + UUID.randomUUID();
        String invalidSpanName = "extension-configuration-invalid-" + UUID.randomUUID();
        ChildProcessResult control;
        ChildProcessResult invalid;
        OtlpTraceCollector controlCollector;
        OtlpTraceCollector invalidCollector;

        try (OtlpTraceCollector startedControlCollector = OtlpTraceCollector.startAcceptingAllSpans();
             OtlpTraceCollector startedInvalidCollector = OtlpTraceCollector.startAcceptingAllSpans()) {
            controlCollector = startedControlCollector;
            invalidCollector = startedInvalidCollector;

            // when
            control = run(
                    fixture, "extension-configuration-control", controlCollector.otlpEndpoint(), controlSpanName, false, Map.of()
            );
            invalid = run(
                    fixture, "extension-configuration-invalid", invalidCollector.otlpEndpoint(), invalidSpanName, true, Map.of()
            );
        }

        // then
        control.assertCompletedSuccessfully();
        assertThat(control.stdout()).contains(InvalidExtensionConfigurationProbe.MAIN_REACHED_MARKER);
        assertReadiness(control, "READY", "", controlSpanName);
        assertSamplerCapability(control, true);
        assertThat(controlCollector.receivedSpans())
                .extracting(OtlpTraceCollector.ReceivedSpan::name)
                .contains(controlSpanName);

        invalid.assertCompletedSuccessfully();
        assertThat(invalid.stdout()).contains(InvalidExtensionConfigurationProbe.MAIN_REACHED_MARKER);
        assertReadiness(invalid, "FAILED", "CONFIGURATION_INITIALIZATION_FAILED", invalidSpanName);
        assertThat(invalidCollector.receivedSpans()).isEmpty();
    }

    @Test
    void invalid_max_span_age_environment_fails_bootstrap_without_platform_export()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();
        String spanName = "extension-configuration-max-span-age-" + UUID.randomUUID();

        try (OtlpTraceCollector collector = OtlpTraceCollector.startAcceptingAllSpans()) {
            // when
            ChildProcessResult result = run(
                    fixture,
                    "extension-configuration-max-span-age",
                    collector.otlpEndpoint(),
                    spanName,
                    false,
                    Map.of("PLATFORM_TRACING_SPAN_TIMEOUT_MAX_SPAN_AGE", "0")
            );

            // then
            result.assertCompletedSuccessfully();
            assertThat(result.stdout()).contains(InvalidExtensionConfigurationProbe.MAIN_REACHED_MARKER);
            assertReadiness(result, "FAILED", "CONFIGURATION_INITIALIZATION_FAILED", spanName);
            assertThat(collector.receivedSpans()).isEmpty();
        }
    }

    @Test
    void sampler_argument_environment_fails_bootstrap_without_platform_export_or_value_disclosure()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();
        String spanName = "extension-configuration-sampler-argument-" + UUID.randomUUID();

        try (OtlpTraceCollector collector = OtlpTraceCollector.startAcceptingAllSpans()) {
            // when
            ChildProcessResult result = run(
                    fixture,
                    "extension-configuration-sampler-argument",
                    collector.otlpEndpoint(),
                    spanName,
                    false,
                    Map.of("OTEL_TRACES_SAMPLER_ARG", SAMPLER_ARGUMENT_CANARY)
            );

            // then
            result.assertCompletedSuccessfully();
            assertThat(result.stdout()).contains(InvalidExtensionConfigurationProbe.MAIN_REACHED_MARKER);
            assertReadiness(result, "FAILED", "CONFIGURATION_INITIALIZATION_FAILED", spanName);
            assertThat(result.stdout()).doesNotContain(SAMPLER_ARGUMENT_CANARY);
            assertThat(result.stderr()).doesNotContain(SAMPLER_ARGUMENT_CANARY);
            assertThat(collector.receivedSpans()).isEmpty();
        }
    }

    @Test
    void sdk_disabled_environment_fails_bootstrap_without_platform_export()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();
        String spanName = "extension-configuration-sdk-disabled-" + UUID.randomUUID();

        try (OtlpTraceCollector collector = OtlpTraceCollector.startAcceptingAllSpans()) {
            // when
            ChildProcessResult result = run(
                    fixture,
                    "extension-configuration-sdk-disabled",
                    collector.otlpEndpoint(),
                    spanName,
                    false,
                    Map.of("OTEL_SDK_DISABLED", "true")
            );

            // then
            result.assertCompletedSuccessfully();
            assertThat(result.stdout()).contains(InvalidExtensionConfigurationProbe.MAIN_REACHED_MARKER);
            assertReadiness(result, "FAILED", "CONFIGURATION_INITIALIZATION_FAILED", spanName);
            assertThat(collector.receivedSpans()).isEmpty();
        }
    }

    @Test
    void external_javaagent_extension_environment_fails_bootstrap_without_platform_export()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();
        String spanName = "extension-configuration-external-extension-" + UUID.randomUUID();
        Path emptyExtension = temporaryDirectory.resolve("empty-extension.jar");
        writeEmptyJar(emptyExtension);

        try (OtlpTraceCollector collector = OtlpTraceCollector.startAcceptingAllSpans()) {
            // when
            ChildProcessResult result = run(
                    fixture,
                    "extension-configuration-external-extension",
                    collector.otlpEndpoint(),
                    spanName,
                    false,
                    Map.of("OTEL_JAVAAGENT_EXTENSIONS", emptyExtension.toAbsolutePath().toString())
            );

            // then
            result.assertCompletedSuccessfully();
            assertThat(result.stdout()).contains(InvalidExtensionConfigurationProbe.MAIN_REACHED_MARKER);
            assertReadiness(result, "FAILED", "CONFIGURATION_INITIALIZATION_FAILED", spanName);
            assertThat(collector.receivedSpans()).isEmpty();
        }
    }

    @Test
    void mismatched_controlled_agent_digest_fails_before_sdk_build_without_platform_export()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();
        String spanName = "extension-configuration-agent-digest-" + UUID.randomUUID();
        String expectedSha256 = fixture.expectedControlledAgentSha();

        try (OtlpTraceCollector collector = OtlpTraceCollector.startAcceptingAllSpans()) {
            List<String> command = command(fixture, collector.otlpEndpoint(), spanName, false, List.of());
            replaceExpectedAgentSha(command, expectedSha256, mismatchedSha(expectedSha256));

            // when
            ChildProcessResult result = ControlledAgentChild.run(
                    temporaryDirectory,
                    "extension-configuration-agent-digest",
                    command,
                    Map.of()
            );

            // then
            result.assertCompletedSuccessfully();
            assertThat(result.stdout()).contains(InvalidExtensionConfigurationProbe.MAIN_REACHED_MARKER);
            assertReadiness(result, "FAILED", "DISTRIBUTION_MISMATCH", spanName);
            assertThat(collector.receivedSpans()).isEmpty();
        }
    }

    @Test
    void foreign_sampler_system_property_fails_bootstrap_without_platform_export()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();
        String spanName = "extension-configuration-foreign-sampler-" + UUID.randomUUID();

        try (OtlpTraceCollector collector = OtlpTraceCollector.startAcceptingAllSpans()) {
            // when
            ChildProcessResult result = run(
                    fixture,
                    "extension-configuration-foreign-sampler",
                    collector.otlpEndpoint(),
                    spanName,
                    false,
                    List.of("-Dotel.traces.sampler=always_on"),
                    Map.of()
            );

            // then
            result.assertCompletedSuccessfully();
            assertThat(result.stdout()).contains(InvalidExtensionConfigurationProbe.MAIN_REACHED_MARKER);
            assertReadiness(result, "FAILED", "CONFIGURATION_INITIALIZATION_FAILED", spanName);
            assertThat(collector.receivedSpans()).isEmpty();
        }
    }

    @Test
    void foreign_sampler_environment_fails_bootstrap_without_platform_export()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();
        String spanName = "extension-configuration-foreign-sampler-env-" + UUID.randomUUID();

        try (OtlpTraceCollector collector = OtlpTraceCollector.startAcceptingAllSpans()) {
            // when
            ChildProcessResult result = run(
                    fixture,
                    "extension-configuration-foreign-sampler-env",
                    collector.otlpEndpoint(),
                    spanName,
                    false,
                    Map.of("OTEL_TRACES_SAMPLER", "always_on")
            );

            // then
            result.assertCompletedSuccessfully();
            assertThat(result.stdout()).contains(InvalidExtensionConfigurationProbe.MAIN_REACHED_MARKER);
            assertReadiness(result, "FAILED", "CONFIGURATION_INITIALIZATION_FAILED", spanName);
            assertThat(collector.receivedSpans()).isEmpty();
        }
    }

    @Test
    void declarative_configuration_file_fails_bootstrap_without_platform_export()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();
        String spanName = "extension-configuration-declarative-" + UUID.randomUUID();
        Path configurationFile = temporaryDirectory.resolve("otel-declarative-config.yaml");
        Files.writeString(configurationFile, """
                file_format: "1.1"
                tracer_provider:
                  processors:
                    - simple:
                        exporter:
                          console: {}
                """, StandardCharsets.UTF_8);

        try (OtlpTraceCollector collector = OtlpTraceCollector.startAcceptingAllSpans()) {
            // when
            ChildProcessResult result = run(
                    fixture,
                    "extension-configuration-declarative",
                    collector.otlpEndpoint(),
                    spanName,
                    false,
                    List.of("-Dotel.config.file=" + configurationFile.toAbsolutePath()),
                    Map.of()
            );

            // then
            result.assertCompletedSuccessfully();
            assertThat(result.stdout()).contains(InvalidExtensionConfigurationProbe.MAIN_REACHED_MARKER);
            assertReadiness(result, "FAILED", "PLATFORM_ACP_NOT_EXECUTED", spanName);
            assertThat(collector.receivedSpans()).isEmpty();
        }
    }

    private ChildProcessResult run(AgentTestFixture fixture,
                                   String label,
                                   String otlpEndpoint,
                                   String spanName,
                                   boolean invalidScanInterval,
                                   Map<String, String> additionalEnvironment)
            throws IOException, InterruptedException, URISyntaxException {
        return run(fixture, label, otlpEndpoint, spanName, invalidScanInterval, List.of(), additionalEnvironment);
    }

    private ChildProcessResult run(AgentTestFixture fixture,
                                   String label,
                                   String otlpEndpoint,
                                   String spanName,
                                   boolean invalidScanInterval,
                                   List<String> extraJvmOptions,
                                   Map<String, String> additionalEnvironment)
            throws IOException, InterruptedException, URISyntaxException {
        return ControlledAgentChild.run(
                temporaryDirectory,
                label,
                command(fixture, otlpEndpoint, spanName, invalidScanInterval, extraJvmOptions),
                additionalEnvironment
        );
    }

    private static List<String> command(AgentTestFixture fixture,
                                        String otlpEndpoint,
                                        String spanName,
                                        boolean invalidScanInterval,
                                        List<String> extraJvmOptions) throws URISyntaxException {
        List<String> command = ControlledAgentChild.javaCommand(List.of());
        ControlledAgentChild.appendOtlpExporter(command, otlpEndpoint);
        ControlledAgentChild.appendSamplingAlwaysOn(command);

        command.addAll(extraJvmOptions);
        ControlledAgentChild.appendServiceIdentity(command);
        if (invalidScanInterval) {
            ControlledAgentChild.appendInvalidScanInterval(command);
        }
        ControlledAgentChild.appendAgentLaunchWithApi(
                command,
                fixture,
                InvalidExtensionConfigurationProbe.class,
                false,
                List.of(spanName)
        );
        return command;
    }

    private static void writeEmptyJar(Path target) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

        // JAR содержит только манифест: записей классов и SPI-дескрипторов расширения нет
        //noinspection EmptyTryBlock
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(target), manifest)) {
        }
    }

    private static String mismatchedSha(String expectedSha256) {
        if (expectedSha256.length() != 64) {
            throw new IllegalArgumentException("Expected a 64-character SHA-256 value");
        }
        char replacement = expectedSha256.charAt(0) == '0' ? '1' : '0';
        return replacement + expectedSha256.substring(1);
    }

    private static void replaceExpectedAgentSha(List<String> command,
                                                String expectedSha256,
                                                String replacementSha256) {
        String expectedOption = EXPECTED_AGENT_SHA256_OPTION_PREFIX + expectedSha256;
        int optionIndex = command.indexOf(expectedOption);
        if (optionIndex < 0) {
            throw new IllegalStateException("Controlled-agent SHA-256 option is missing from the child command");
        }

        if (command.lastIndexOf(expectedOption) != optionIndex) {
            throw new IllegalStateException("Controlled-agent SHA-256 option is duplicated in the child command");
        }

        command.set(optionIndex, EXPECTED_AGENT_SHA256_OPTION_PREFIX + replacementSha256);
    }

    @SuppressWarnings("SameParameterValue")
    private static void assertSamplerCapability(ChildProcessResult result, boolean installed) {
        String expectedLine = InvalidExtensionConfigurationProbe.SAMPLER_CAPABILITY_PREFIX
                + " capability=PLATFORM_SAMPLER_INSTALLED"
                + " installed=" + installed;

        assertThat(result.stdout()
                .lines()
                .filter(line -> line.startsWith(InvalidExtensionConfigurationProbe.SAMPLER_CAPABILITY_PREFIX)))
                .as("Child stdout must contain exactly one sampler capability probe line: %s", result.stdout())
                .containsExactly(expectedLine);
    }

    private static void assertReadiness(ChildProcessResult result,
                                        String lifecycle,
                                        String failureCode,
                                        String spanName) {
        String expectedLine = InvalidExtensionConfigurationProbe.RESULT_PREFIX
                + " lifecycle=" + lifecycle
                + " failureCode=" + failureCode
                + " spanName=" + spanName;

        assertThat(result.stdout()
                .lines()
                .filter(line -> line.startsWith(InvalidExtensionConfigurationProbe.RESULT_PREFIX)))
                .as("Child stdout must contain exactly one extension configuration probe line: %s", result.stdout())
                .containsExactly(expectedLine);
    }
}
