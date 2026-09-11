package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

class ControlledAgentRuntimeTest {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration KILL_TIMEOUT = Duration.ofSeconds(10);

    private static final String EXPECTED_PROBE_CAPABILITIES = "7";
    private static final String PROBE_OK_PREFIX = "CONTROLLED_AGENT_PROBE_OK";
    private static final String ANY_PTA_EVENT_CODE = "\"event_code\":\"PTA-";

    private static final String OTLP_PARTIAL_SUCCESS_PROBE_OK_PREFIX = "OTLP_PARTIAL_SUCCESS_PROBE_OK";
    private static final Pattern OTLP_PARTIAL_SUCCESS_LINE_PATTERN = Pattern.compile(
            OTLP_PARTIAL_SUCCESS_PROBE_OK_PREFIX + " partialSuccessBatches=(\\d+) rejectedSpans=(\\d+) exportedSpans=(\\d+)"
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void controlled_agent_starts_with_attested_identity_and_ready_extension() throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();

        // when
        ChildProcessResult result = runControlledAgentProbe(fixture);

        // then
        assertProcessCompletedSuccessfully(result);
        assertProbeReportedExpectedIdentity(result, fixture.distributionVersion());
        assertNoBootstrapAttestationLeakedToStdout(result);
        assertStderrIsFreeOfLoggingAndExtensionFailures(result);
    }

    @Test
    void controlled_agent_reports_otlp_partial_success_via_export_observation() throws Exception {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();

        try (OtlpPartialSuccessCollector collector = OtlpPartialSuccessCollector.start()) {

            // when
            ChildProcessResult result = runOtlpPartialSuccessProbe(fixture, collector.tracesEndpoint());

            // then
            assertProcessCompletedSuccessfully(result);
            assertOtlpPartialSuccessObserved(result);
            assertThat(collector.receivedBatches()).isGreaterThanOrEqualTo(1);
        }
    }

    private ChildProcessResult runControlledAgentProbe(AgentTestFixture fixture) throws IOException, InterruptedException, URISyntaxException {
        Path javaExecutable = javaExecutable();
        Path probeClassPath = probeClassPath(ControlledAgentRuntimeProbe.class);
        Path stdoutFile = temporaryDirectory.resolve("child.stdout.log");
        Path stderrFile = temporaryDirectory.resolve("child.stderr.log");

        ProcessBuilder processBuilder = new ProcessBuilder(
                childCommand(javaExecutable, probeClassPath, fixture));
        removeContaminatingEnvironment(processBuilder.environment());
        processBuilder.redirectOutput(stdoutFile.toFile());
        processBuilder.redirectError(stderrFile.toFile());

        Process process = processBuilder.start();
        boolean finished = process.waitFor(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor(KILL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
        String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
        Integer exitCode = finished ? process.exitValue() : null;

        return new ChildProcessResult(finished, exitCode, stdout, stderr);
    }

    private ChildProcessResult runOtlpPartialSuccessProbe(AgentTestFixture fixture, String tracesEndpoint)
            throws IOException, InterruptedException, URISyntaxException {
        Path javaExecutable = javaExecutable();
        Path probeClassPath = probeClassPath(OtlpPartialSuccessProbe.class);
        Path stdoutFile = temporaryDirectory.resolve("otlp-child.stdout.log");
        Path stderrFile = temporaryDirectory.resolve("otlp-child.stderr.log");

        ProcessBuilder processBuilder = new ProcessBuilder(
                otlpPartialSuccessChildCommand(javaExecutable, probeClassPath, fixture, tracesEndpoint));
        removeContaminatingEnvironment(processBuilder.environment());
        processBuilder.redirectOutput(stdoutFile.toFile());
        processBuilder.redirectError(stderrFile.toFile());

        Process process = processBuilder.start();
        boolean finished = process.waitFor(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor(KILL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
        String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
        Integer exitCode = finished ? process.exitValue() : null;

        return new ChildProcessResult(finished, exitCode, stdout, stderr);
    }

    private void assertProcessCompletedSuccessfully(ChildProcessResult result) {
        assertThat(result.finished()).as("Controlled agent child JVM must terminate").isTrue();
        assertThat(result.exitCode())
                .as("Controlled agent child JVM failed; stdout=%s, stderr=%s",
                        result.stdout(), result.stderr())
                .isZero();
    }

    private void assertProbeReportedExpectedIdentity(ChildProcessResult result, String distributionVersion) {
        String expectedLine = PROBE_OK_PREFIX
                + " version=" + distributionVersion
                + " schema=" + JmxWireContract.SCHEMA_ID
                + " capabilities=" + EXPECTED_PROBE_CAPABILITIES;

        assertThat(result.stdout()
                .lines()
                .filter(line -> line.startsWith(PROBE_OK_PREFIX)))
                .containsExactly(expectedLine);
    }

    private void assertNoBootstrapAttestationLeakedToStdout(ChildProcessResult result) {
        assertThat(result.stdout()).doesNotContain(ANY_PTA_EVENT_CODE);
    }

    private void assertStderrIsFreeOfLoggingAndExtensionFailures(ChildProcessResult result) {
        assertThat(result.stderr())
                .doesNotContain("No SLF4J providers were found")
                .doesNotContain("Defaulting to no-operation (NOP) logger implementation")
                .doesNotContain("Failed to load extension")
                .doesNotContain("Unable to load extension")
                .doesNotContain("FATAL");
    }

    private void assertOtlpPartialSuccessObserved(ChildProcessResult result) {
        List<String> matchingLines = result.stdout()
                .lines()
                .filter(line -> line.startsWith(OTLP_PARTIAL_SUCCESS_PROBE_OK_PREFIX))
                .toList();
        assertThat(matchingLines)
                .as("Child stdout must contain exactly one OTLP partial-success probe line: %s", result.stdout())
                .hasSize(1);

        Matcher matcher = OTLP_PARTIAL_SUCCESS_LINE_PATTERN.matcher(matchingLines.get(0));
        assertThat(matcher.matches())
                .as("Probe line does not match the expected shape: %s", matchingLines.get(0))
                .isTrue();

        long partialSuccessBatches = Long.parseLong(matcher.group(1));
        long rejectedSpans = Long.parseLong(matcher.group(2));
        long exportedSpans = Long.parseLong(matcher.group(3));

        assertThat(partialSuccessBatches)
                .as("partial_success_batches must be observed at least once")
                .isGreaterThanOrEqualTo(1L);
        assertThat(rejectedSpans)
                .as("rejected_spans must reflect the stub collector's rejection")
                .isGreaterThanOrEqualTo(1L);
        assertThat(exportedSpans)
                .as("exported_spans must count the span accepted from the partial batch")
                .isGreaterThanOrEqualTo(1L);
    }

    private static Path javaExecutable() {
        String javaCommand = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", javaCommand);
    }

    private static Path probeClassPath(Class<?> probeClass) throws URISyntaxException {
        return Path.of(probeClass
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    }

    private static String openTelemetryApiClasspathEntries() {
        return requiredProperty("platformTracing.otlpProbeApiClasspath");
    }

    private static List<String> childCommand(Path javaExecutable,
                                             Path probeClassPath,
                                             AgentTestFixture fixture) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-XX:+DisableAttachMechanism");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        command.add("-Dotel.traces.exporter=logging");
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
        command.add("-Dplatform.environment=dev");
        command.add("-Dplatform.tracing.service.name=controlled-agent-runtime-test");
        command.add("-Dplatform.tracing.service.version=1.0.0");
        command.add("-Dplatform.tracing.service.environment=test");
        command.add("-Dplatform.tracing.resource.validation-mode=STRICT");
        command.add("-Dplatform.tracing.jmx.remote.mode=REMOTE_PROHIBITED");
        command.add("-Dplatform.tracing.jmx.remote.listener-confirmed=false");
        command.add("-Dplatform.tracing.jmx.remote.authorization-forwarder-installed=false");
        command.add("-Dplatform.tracing.controlled-agent.expected-sha256=" + fixture.expectedControlledAgentSha());
        command.add("-javaagent:" + fixture.controlledAgent());
        command.add("-cp");
        command.add(probeClassPath.toString());
        command.add(ControlledAgentRuntimeProbe.class.getName());
        command.add(fixture.distributionVersion());
        command.add(fixture.controlledAgent().toString());
        command.add(fixture.expectedOtelJavaAgentSha());
        command.add(fixture.expectedExtensionSha());
        return command;
    }

    private static List<String> otlpPartialSuccessChildCommand(Path javaExecutable,
                                                                Path probeClassPath,
                                                                AgentTestFixture fixture,
                                                                String tracesEndpoint) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-XX:+DisableAttachMechanism");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        command.add("-Dotel.traces.exporter=otlp");
        command.add("-Dotel.exporter.otlp.traces.protocol=http/protobuf");
        command.add("-Dotel.exporter.otlp.traces.endpoint=" + tracesEndpoint);
        command.add("-Dotel.exporter.otlp.traces.compression=none");
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
        command.add("-Dotel.bsp.schedule.delay=200");
        command.add("-Dplatform.environment=dev");
        command.add("-Dplatform.tracing.service.name=controlled-agent-runtime-test");
        command.add("-Dplatform.tracing.service.version=1.0.0");
        command.add("-Dplatform.tracing.service.environment=test");
        command.add("-Dplatform.tracing.resource.validation-mode=STRICT");
        command.add("-Dplatform.tracing.jmx.remote.mode=REMOTE_PROHIBITED");
        command.add("-Dplatform.tracing.jmx.remote.listener-confirmed=false");
        command.add("-Dplatform.tracing.jmx.remote.authorization-forwarder-installed=false");
        command.add("-Dplatform.tracing.controlled-agent.expected-sha256=" + fixture.expectedControlledAgentSha());
        command.add("-javaagent:" + fixture.controlledAgent());
        command.add("-cp");
        command.add(probeClassPath + File.pathSeparator + openTelemetryApiClasspathEntries());
        command.add(OtlpPartialSuccessProbe.class.getName());
        return command;
    }

    private static void removeContaminatingEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(name -> {
            String normalized = name.toUpperCase(Locale.ROOT);
            return normalized.equals("JAVA_TOOL_OPTIONS")
                    || normalized.equals("_JAVA_OPTIONS")
                    || normalized.equals("JDK_JAVA_OPTIONS")
                    || normalized.equals("CLASSPATH")
                    || normalized.startsWith("OTEL_")
                    || normalized.startsWith("PLATFORM_TRACING_");
        });
    }

    private static Path requiredPath(String name) {
        Path path = Path.of(requiredProperty(name));
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Required test file is missing: " + path);
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

    private static boolean isWindows() {
        return System.getProperty("os.name")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
            }

            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private record AgentTestFixture(Path controlledAgent,
                                    Path extension,
                                    String distributionVersion,
                                    String expectedOtelJavaAgentSha,
                                    String expectedExtensionSha,
                                    String expectedControlledAgentSha) {

        static AgentTestFixture load() throws IOException {
            Path controlledAgent = requiredPath("platformTracing.controlledAgentJar");
            Path extension = requiredPath("platformTracing.extensionJar");
            String distributionVersion = requiredProperty("platformTracing.distributionVersion");
            String expectedOtelJavaAgentSha = sha256Hex(Files.readAllBytes(requiredPath("platformTracing.otelJavaAgentJar")));
            String expectedExtensionSha = sha256Hex(Files.readAllBytes(extension));
            String expectedControlledAgentSha = sha256Hex(Files.readAllBytes(controlledAgent));
            return new AgentTestFixture(
                    controlledAgent, extension, distributionVersion,
                    expectedOtelJavaAgentSha, expectedExtensionSha, expectedControlledAgentSha
            );
        }
    }

    private record ChildProcessResult(boolean finished, Integer exitCode, String stdout, String stderr) {
    }
}
