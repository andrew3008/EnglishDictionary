package space.br1440.platform.tracing.otel.javaagent.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;

class PlatformStderrSinkFd2Test {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void production_sink_writes_to_os_fd2_after_system_err_is_replaced() throws Exception {
        // given
        Path stdoutFile = temporaryDirectory.resolve("fd2-probe.stdout.log");
        Path stderrFile = temporaryDirectory.resolve("fd2-probe.stderr.log");
        ProcessBuilder processBuilder = new ProcessBuilder(command());
        removeContaminatingEnvironment(processBuilder);
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

        List<String> records = stderr.lines().filter(line -> !line.isBlank()).toList();
        assertThat(records).hasSize(1);
        JsonNode record = JSON.readTree(records.getFirst());
        assertThat(record.path("event_code").asText()).isEqualTo(AgentEventCode.BOOT_INSTALLED.code());
        assertThat(record.path("message").asText()).isEqualTo("Platform tracing extension installed");
    }

    private static List<String> command() {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(PlatformStderrSinkFd2Probe.class.getName());
        return command;
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .startsWith("windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private static void removeContaminatingEnvironment(ProcessBuilder processBuilder) {
        processBuilder.environment().keySet().removeIf(name -> {
            String normalized = name.toUpperCase(Locale.ROOT);
            return normalized.equals("JAVA_TOOL_OPTIONS")
                    || normalized.equals("_JAVA_OPTIONS")
                    || normalized.equals("JDK_JAVA_OPTIONS")
                    || normalized.equals("CLASSPATH");
        });
    }
}
