package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RemoteJmxAssessorCurrentProcessTest {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(60);
    private static final String JMXREMOTE_AUTHENTICATE = "-Dcom.sun.management.jmxremote.authenticate";

    @TempDir
    Path temporaryDirectory;

    @Test
    void child_process_without_flags_is_authorized() throws Exception {
        // given
        // when
        ProbeVerdict verdict = runProbe(List.of(), Map.of());

        // then
        assertThat(verdict.allows()).isTrue();
        assertThat(verdict.failureCode()).isEqualTo(RemoteJmxDenialReason.NONE.name());
        assertThat(verdict.offendingArguments()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"})
    void management_arguments_injected_through_the_environment_are_detected(String variable) throws Exception {
        // given
        // when
        ProbeVerdict verdict = runProbe(
                List.of(),
                Map.of(variable, JMXREMOTE_AUTHENTICATE + "=false"));

        // then
        assertThat(verdict.allows()).isFalse();
        assertThat(verdict.failureCode()).isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_MANAGEMENT_AGENT_CONFIGURED.name());
        assertThat(verdict.offendingArguments()).contains(JMXREMOTE_AUTHENTICATE);
    }

    private ProbeVerdict runProbe(List<String> jvmArguments, Map<String, String> environment) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.addAll(jvmArguments);
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(RemoteJmxAssessorCurrentProcessProbe.class.getName());

        Path stdoutFile = temporaryDirectory.resolve("probe.stdout.log");
        Path stderrFile = temporaryDirectory.resolve("probe.stderr.log");

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        removeContaminatingEnvironment(processBuilder.environment());
        processBuilder.environment().putAll(environment);
        processBuilder.redirectOutput(stdoutFile.toFile());
        processBuilder.redirectError(stderrFile.toFile());

        Process process = processBuilder.start();
        boolean finished = process.waitFor(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
        String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
        assertThat(finished).as("probe stdout=%s stderr=%s", stdout, stderr).isTrue();
        assertThat(process.exitValue()).as("probe stdout=%s stderr=%s", stdout, stderr).isZero();

        return new ProbeVerdict(
                Boolean.parseBoolean(field(stdout, RemoteJmxAssessorCurrentProcessProbe.ALLOWS_PREFIX)),
                field(stdout, RemoteJmxAssessorCurrentProcessProbe.FAILURE_CODE_PREFIX),
                offendingArguments(field(stdout, RemoteJmxAssessorCurrentProcessProbe.OFFENDING_PREFIX)),
                field(stdout, RemoteJmxAssessorCurrentProcessProbe.REMEDIATION_PREFIX)
        );
    }

    private static List<String> offendingArguments(String joined) {
        return joined.isEmpty() ? List.of() : List.of(joined.split(","));
    }

    private static String field(String stdout, String prefix) {
        return stdout.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()).trim())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Probe did not report '" + prefix + "'. Output: " + stdout));
    }

    private static Path javaExecutable() {
        String javaCommand = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .startsWith("windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", javaCommand);
    }

    private static void removeContaminatingEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(name -> {
            String normalized = name.toUpperCase(Locale.ROOT);
            return normalized.equals("JAVA_TOOL_OPTIONS")
                    || normalized.equals("_JAVA_OPTIONS")
                    || normalized.equals("JDK_JAVA_OPTIONS")
                    || normalized.equals("CLASSPATH");
        });
    }

    private record ProbeVerdict(boolean allows,
                                String failureCode,
                                List<String> offendingArguments,
                                String remediation) {
    }
}
