package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlledAgentRuntimeTest {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration KILL_TIMEOUT = Duration.ofSeconds(10);

    private static final String EXPECTED_PROBE_PROFILE = "platform-agent-secure-v1";
    private static final String EXPECTED_PROBE_CAPABILITIES = "7";
    private static final String PROBE_OK_PREFIX = "CONTROLLED_AGENT_PROBE_OK";
    private static final String BOOT_EVIDENCE_EVENT_CODE = "\"event_code\":\"PTA-BOOT-010\"";
    private static final String ANY_PTA_EVENT_CODE = "\"event_code\":\"PTA-";
    private static final String PIN_FORMAT_VERSION_ONE = "\"pin_format_version\":\"1\"";

    @TempDir
    Path temporaryDirectory;

    @Test
    void controlled_agent_starts_with_exact_attestation_and_ready_extension() throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();

        // when
        ChildProcessResult result = runControlledAgentProbe(fixture);

        // then
        assertProcessCompletedSuccessfully(result);
        assertProbeReportedExpectedIdentity(result, fixture.distributionVersion());
        assertNoBootstrapAttestationLeakedToStdout(result);
        assertExactlyOneBootAttestationEvidenceEmitted(result, fixture.expectedOtelJavaAgentSha());
        assertStderrIsFreeOfLoggingAndExtensionFailures(result);
    }

    private ChildProcessResult runControlledAgentProbe(AgentTestFixture fixture) throws IOException, InterruptedException, URISyntaxException {
        Path javaExecutable = javaExecutable();
        Path probeClassPath = probeClassPath();
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
                + " profile=" + EXPECTED_PROBE_PROFILE
                + " capabilities=" + EXPECTED_PROBE_CAPABILITIES;

        assertThat(result.stdout()
                .lines()
                .filter(line -> line.startsWith(PROBE_OK_PREFIX)))
                .containsExactly(expectedLine);
    }

    private void assertNoBootstrapAttestationLeakedToStdout(ChildProcessResult result) {
        assertThat(result.stdout()).doesNotContain(ANY_PTA_EVENT_CODE);
    }

    private void assertExactlyOneBootAttestationEvidenceEmitted(ChildProcessResult result,
                                                                String expectedOtelJavaAgentSha) {
        List<String> evidenceMarkers = result.stderr()
                .lines()
                .filter(line -> line.contains(BOOT_EVIDENCE_EVENT_CODE))
                .toList();

        assertThat(evidenceMarkers).hasSize(1);
        assertThat(evidenceMarkers.getFirst())
                .contains("\"otel_javaagent_sha256\":\"" + expectedOtelJavaAgentSha + "\"")
                .contains(PIN_FORMAT_VERSION_ONE);
    }

    private void assertStderrIsFreeOfLoggingAndExtensionFailures(ChildProcessResult result) {
        assertThat(result.stderr())
                .doesNotContain("No SLF4J providers were found")
                .doesNotContain("Defaulting to no-operation (NOP) logger implementation")
                .doesNotContain("Failed to load extension")
                .doesNotContain("Unable to load extension")
                .doesNotContain("FATAL");
    }

    private static Path javaExecutable() {
        String javaCommand = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", javaCommand);
    }

    private static Path probeClassPath() throws URISyntaxException {
        return Path.of(ControlledAgentRuntimeProbe.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    }

    private static List<String> childCommand(Path javaExecutable,
                                             Path probeClassPath,
                                             AgentTestFixture fixture) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        command.add("-Dotel.traces.exporter=logging");
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
        command.add("-Dplatform.environment=dev");
        command.add("-Dplatform.tracing.spi-attestation.mode=EXACT_EVIDENCE");
        command.add("-Dplatform.tracing.service.name=controlled-agent-runtime-test");
        command.add("-Dplatform.tracing.service.version=1.0.0");
        command.add("-Dplatform.tracing.service.environment=test");
        command.add("-Dplatform.tracing.service.c-group=controlled-agent-tests");
        command.add("-Dplatform.tracing.resource.validation-mode=STRICT");
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
                                    String expectedExtensionSha) {

        static AgentTestFixture load() throws IOException {
            Path controlledAgent = requiredPath("platformTracing.controlledAgentJar");
            Path extension = requiredPath("platformTracing.extensionJar");
            String distributionVersion = requiredProperty("platformTracing.distributionVersion");
            String expectedOtelJavaAgentSha = sha256Hex(Files.readAllBytes(requiredPath("platformTracing.otelJavaAgentJar")));
            String expectedExtensionSha = sha256Hex(Files.readAllBytes(extension));
            return new AgentTestFixture(
                    controlledAgent, extension, distributionVersion,
                    expectedOtelJavaAgentSha, expectedExtensionSha
            );
        }
    }

    private record ChildProcessResult(boolean finished, Integer exitCode, String stdout, String stderr) {
    }
}
