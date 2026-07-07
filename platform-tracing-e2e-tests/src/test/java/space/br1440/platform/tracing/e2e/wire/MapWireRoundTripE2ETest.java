package space.br1440.platform.tracing.e2e.wire;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import space.br1440.platform.tracing.e2e.extension.jmx.wire.WireRoundTripTestMarkers;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent-runtime wire round-trip: a validated Map wire payload crosses
 * App CL -&gt; JMX -&gt; Agent ExtensionClassLoader.
 * <p>
 * The MBean is available only because the test-only {@code jmxWireExtension} JAR is loaded through
 * {@code -Dotel.javaagent.extensions}; there is no production spike package, no production gate
 * ({@code platform.tracing.spike.jmx.wire}), and no production {@code SPIKE_JMX_WIRE:} marker.
 */
@DisabledIfSystemProperty(named = "skipE2e", matches = "true")
class MapWireRoundTripE2ETest {

    private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(2);

    @Test
    void map_wire_payload_crosses_jmx_boundary_in_agent_runtime() throws Exception {
        String otelAgentJar = System.getProperty("otel.javaagent.jar");
        String extensionJar = System.getProperty("smoke.otel.extension.jar");
        String wireExtensionJar = System.getProperty("smoke.jmx.wire.extension.jar");
        String testRuntimeClasspath = System.getProperty("smoke.test.runtime.classpath");

        assertThat(otelAgentJar).isNotBlank();
        assertThat(extensionJar).isNotBlank();
        assertThat(wireExtensionJar).isNotBlank();
        assertThat(testRuntimeClasspath).isNotBlank();

        // OTel Agent создаёт отдельный ExtensionClassLoader на каждый JAR в каталоге extensions:
        // platform extension + test-only wire harness JAR (с embedded platform-tracing-api).
        Path extDir = Files.createTempDirectory("map-wire-ext");
        Files.copy(Path.of(extensionJar), extDir.resolve("extension.jar"));
        Files.copy(Path.of(wireExtensionJar), extDir.resolve("jmx-wire.jar"));
        String platformExtension = extDir.toAbsolutePath().toString();

        String output = runWireProcess(platformExtension, testRuntimeClasspath, otelAgentJar);
        System.out.println("=== Map Wire Round-Trip output ===");
        System.out.println(output);

        assertThat(output).contains("READY");
        assertThat(output).contains(WireRoundTripTestMarkers.LINE_PREFIX + "registered=true");

        Map<String, MapWireScenarioExpectation> expectations = Map.of(
                "validRoundTrip", MapWireScenarioExpectation.valid(),
                "invalidType", MapWireScenarioExpectation.invalid(),
                "unknownKey", MapWireScenarioExpectation.invalid(),
                "topologyField", MapWireScenarioExpectation.invalid(),
                "rawDto", MapWireScenarioExpectation.invalid(),
                "unsupportedContractVersion", MapWireScenarioExpectation.invalid());

        Map<String, ParsedScenario> scenarios = parseScenarios(output);
        for (Map.Entry<String, MapWireScenarioExpectation> entry : expectations.entrySet()) {
            ParsedScenario scenario = scenarios.get(entry.getKey());
            assertThat(scenario)
                    .as("scenario %s must be present in wire output", entry.getKey())
                    .isNotNull();
            if (entry.getValue().expectValid()) {
                assertThat(scenario.valid())
                        .as("scenario %s", entry.getKey())
                        .isTrue();
                assertThat(scenario.errorClass())
                        .as("scenario %s must not throw", entry.getKey())
                        .isBlank();
            } else {
                assertThat(scenario.valid())
                        .as("scenario %s", entry.getKey())
                        .isFalse();
                assertThat(scenario.errorClass())
                        .as("scenario %s must reject without throwable escape", entry.getKey())
                        .isBlank();
            }
        }
    }

    private static String runWireProcess(String extensionDir, String testRuntimeClasspath, String otelAgentJar)
            throws Exception {
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", "java");

        List<String> command = new ArrayList<>();
        command.add(javaBin.toString());
        command.add("-Dotel.javaagent.extensions=" + extensionDir.replace('\\', '/'));
        command.add("-Dotel.service.name=map-wire-round-trip");
        command.add("-Dotel.traces.exporter=none");
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
        command.add("-Dplatform.tracing.queue.overflow-policy=UPSTREAM");
        command.add("-Dotel.javaagent.logging=application");
        command.add("-javaagent:" + otelAgentJar);
        command.add("-cp");
        command.add(testRuntimeClasspath);
        command.add(MapWireRoundTripMain.class.getName());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            } catch (Exception ignored) {
            }
        }, "map-wire-output-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        reader.join(5_000L);

        assertThat(finished).as("Wire JVM output:\n%s", output).isTrue();
        assertThat(process.exitValue()).as("Wire JVM output:\n%s", output).isZero();
        assertThat(output.toString())
                .doesNotContain("OpenTelemetry Javaagent failed to start");
        assertThat(new File(otelAgentJar)).exists();

        return output.toString();
    }

    private static Map<String, ParsedScenario> parseScenarios(String output) {
        Map<String, ParsedScenario> scenarios = new LinkedHashMap<>();
        String currentScenario = null;
        boolean valid = false;
        String errorClass = "";

        for (String line : output.split("\\R")) {
            if (!line.startsWith(WireRoundTripTestMarkers.LINE_PREFIX)) {
                continue;
            }
            String payload = line.substring(WireRoundTripTestMarkers.LINE_PREFIX.length());
            if (payload.startsWith("scenario=") && !payload.startsWith("scenarioEnd=")) {
                currentScenario = payload.substring("scenario=".length());
                valid = false;
                errorClass = "";
            } else if (payload.startsWith("valid=") && currentScenario != null) {
                valid = "true".equals(payload.substring("valid=".length()));
            } else if (payload.startsWith("errorClass=") && currentScenario != null) {
                errorClass = payload.substring("errorClass=".length());
            } else if (payload.startsWith("scenarioEnd=") && currentScenario != null) {
                scenarios.put(currentScenario, new ParsedScenario(valid, errorClass));
                currentScenario = null;
            }
        }
        return scenarios;
    }

    private record MapWireScenarioExpectation(boolean expectValid) {
        static MapWireScenarioExpectation valid() {
            return new MapWireScenarioExpectation(true);
        }

        static MapWireScenarioExpectation invalid() {
            return new MapWireScenarioExpectation(false);
        }
    }

    private record ParsedScenario(boolean valid, String errorClass) {
    }
}
