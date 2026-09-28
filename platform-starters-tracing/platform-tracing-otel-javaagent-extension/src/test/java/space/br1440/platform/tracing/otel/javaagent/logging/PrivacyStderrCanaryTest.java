package space.br1440.platform.tracing.otel.javaagent.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResourcesPrivacyProbe;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.resource.HostNameResolverPrivacyProbe;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreakerPrivacyProbe;

class PrivacyStderrCanaryTest {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @MethodSource("privacyScenarios")
    void child_process_stderr_never_contains_the_secret(
            Class<?> probeClass,
            AgentEventCode expectedCode,
            int expectedRecordCount) throws Exception {
        // given
        String secret = "SECRET-PRIVACY-CANARY-6d514501";
        Path stdoutFile = temporaryDirectory.resolve(probeClass.getSimpleName() + ".stdout.log");
        Path stderrFile = temporaryDirectory.resolve(probeClass.getSimpleName() + ".stderr.log");
        ProcessBuilder processBuilder = new ProcessBuilder(command(probeClass, secret));
        removeContaminatingEnvironment(processBuilder.environment());
        processBuilder.redirectOutput(stdoutFile.toFile());
        processBuilder.redirectError(stderrFile.toFile());

        // when
        Process process = processBuilder.start();
        boolean finished = process.waitFor(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        // then
        String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
        String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
        assertThat(finished).as("probe stdout=%s stderr=%s", stdout, stderr).isTrue();
        assertThat(process.exitValue()).as("probe stdout=%s stderr=%s", stdout, stderr).isZero();
        assertThat(stdout).isEmpty();
        assertThat(stderr).doesNotContain(secret);

        List<JsonNode> records = new ArrayList<>();
        for (String line : stderr.lines().filter(value -> !value.isBlank()).toList()) {
            records.add(JSON.readTree(line));
        }
        assertThat(records).hasSize(expectedRecordCount);
        assertThat(records).extracting(record -> record.path("event_code").asText())
                .containsOnlyOnce(expectedCode.code());
    }

    static Stream<Arguments> privacyScenarios() {
        return Stream.of(
                Arguments.of(RuleCircuitBreakerPrivacyProbe.class, AgentEventCode.SCRUB_CIRCUIT_OPEN, 1),
                Arguments.of(HostNameResolverPrivacyProbe.class, AgentEventCode.BOOT_HOSTNAME_FAILED, 1),
                Arguments.of(BootstrapResourcesPrivacyProbe.class, AgentEventCode.BOOT_CLASSIFIED_FAILURE, 2)
        );
    }

    private static List<String> command(Class<?> probeClass, String secret) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(probeClass.getName());
        command.add(secret);
        return command;
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .startsWith("windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
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
}
