package space.br1440.platform.tracing.e2e.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

@DisabledIfSystemProperty(named = "skipE2e", matches = "true")
class SpringAgentCompositionE2ETest {

    private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(2);
    private static final String PREFIX = "SPRING_AGENT_COMPOSITION:";

    @Test
    void springAndDisabledFacadesRespectAgentOwnership() throws Exception {
        String otelAgentJar = System.getProperty("otel.javaagent.jar");
        String extensionJar = System.getProperty("smoke.otel.extension.jar");
        String testRuntimeClasspath = System.getProperty("smoke.test.runtime.classpath");

        assertThat(otelAgentJar).as("otel.javaagent.jar").isNotBlank();
        assertThat(extensionJar).as("smoke.otel.extension.jar").isNotBlank();
        assertThat(testRuntimeClasspath).as("smoke.test.runtime.classpath").isNotBlank();

        String output = runProbe(otelAgentJar, extensionJar, testRuntimeClasspath);

        assertThat(output)
                .contains(PREFIX + "auto.mode=AGENT")
                .contains(PREFIX + "auto.agentDetected=true")
                .contains(PREFIX + "auto.facadeNoop=false")
                .contains(PREFIX + "auto.openTelemetryBeans=0")
                .contains(PREFIX + "auto.currentContextVisible=true")
                .contains(PREFIX + "disabled.mode=DISABLED")
                .contains(PREFIX + "disabled.agentDetected=true")
                .contains(PREFIX + "disabled.facadeNoop=true")
                .contains(PREFIX + "disabled.agentSpanValid=true")
                .contains(PREFIX + "disabled.openTelemetryBeans=0")
                .contains(PREFIX + "COMPLETE");
    }

    private static String runProbe(
            String otelAgentJar,
            String extensionJar,
            String testRuntimeClasspath) throws Exception {
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>();
        command.add(javaBin.toString());
        command.add("-Dotel.javaagent.extensions=" + extensionJar.replace('\\', '/'));
        command.add("-Dotel.service.name=spring-agent-composition-e2e");
        // Logging exporter активирует реальный export callback без внешней инфраструктуры.
        command.add("-Dotel.traces.exporter=logging");
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
        command.add("-Dplatform.tracing.queue.overflow-policy=UPSTREAM");
        command.add("-Dotel.javaagent.logging=application");
        command.add("-javaagent:" + otelAgentJar);
        command.add("-cp");
        command.add(testRuntimeClasspath);
        command.add(SpringAgentCompositionProbeMain.class.getName());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> readOutput(process, output), "spring-agent-composition-output-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        reader.join(5_000L);

        assertThat(finished)
                .as("Probe JVM должна завершиться за %s. Output:%n%s", PROCESS_TIMEOUT, output)
                .isTrue();
        assertThat(process.exitValue())
                .as("Probe JVM exit code. Output:%n%s", output)
                .isZero();
        assertThat(output.toString()).doesNotContain("OpenTelemetry Javaagent failed to start");
        return output.toString();
    }

    private static void readOutput(Process process, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        } catch (Exception ignored) {
            // Процесс завершён, поток вывода закрыт.
        }
    }
}
