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

import space.br1440.platform.tracing.autoconfigure.support.AgentRuntimeState;
import space.br1440.platform.tracing.e2e.consumer.MinimalStarterAttestationProbeMain;

@DisabledIfSystemProperty(named = "skipE2e", matches = "true")
class AgentExtensionAttestationE2ETest {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    private static final String PREFIX = "E1_ATTESTATION:";

    @Test
    void noAgentIsDistinguishedFromMissingExtension() throws Exception {
        String output = run(false, null, AgentRuntimeState.AGENT_MISSING, "AUTO", false, List.of());
        assertCompleted(output, AgentRuntimeState.AGENT_MISSING)
                .contains(PREFIX + "facadeNoop=true")
                .contains(PREFIX + "endpointPresent=false");
    }

    @Test
    void stockAgentWithoutPlatformExtensionIsNotReady() throws Exception {
        String output = run(true, null, AgentRuntimeState.EXTENSION_MISSING, "AUTO", false, List.of());
        assertCompleted(output, AgentRuntimeState.EXTENSION_MISSING)
                .contains(PREFIX + "facadeNoop=true")
                .contains(PREFIX + "endpointPresent=false");
    }

    @Test
    void endpointWithInitializingLifecycleIsNotReady() throws Exception {
        assertFixtureState(AgentRuntimeState.EXTENSION_INITIALIZING,
                "-De1.fixture.lifecycle=INITIALIZING");
    }

    @Test
    void failedSanitizerInitializationIsNotReady() throws Exception {
        String output = runWithFixture(
                AgentRuntimeState.EXTENSION_FAILED,
                "AUTO",
                false,
                List.of(
                        "-De1.fixture.lifecycle=FAILED",
                        "-De1.fixture.failure.code=SANITIZER_INITIALIZATION_FAILED",
                        "-De1.fixture.failure.message=safe-fixture-message"));
        assertCompleted(output, AgentRuntimeState.EXTENSION_FAILED)
                .contains(PREFIX + "failureCode=SANITIZER_INITIALIZATION_FAILED")
                .contains(PREFIX + "facadeNoop=true");
    }

    @Test
    void incompatibleProtocolIsRejected() throws Exception {
        String output = runWithFixture(
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "AUTO",
                false,
                List.of(
                        "-De1.fixture.lifecycle=READY",
                        "-De1.fixture.complete=true",
                        "-De1.fixture.protocol.version=99"));
        assertCompleted(output, AgentRuntimeState.EXTENSION_INCOMPATIBLE)
                .contains(PREFIX + "facadeNoop=true");
    }

    @Test
    void managementEndpointWithoutRequiredProcessorIsRejected() throws Exception {
        String output = runWithFixture(
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "AUTO",
                false,
                List.of("-De1.fixture.lifecycle=READY"));
        assertCompleted(output, AgentRuntimeState.EXTENSION_INCOMPATIBLE)
                .contains(PREFIX + "facadeNoop=true");
    }

    @Test
    void explicitAgentWithoutCompatibleExtensionFailsClearly() throws Exception {
        String output = run(true, null, AgentRuntimeState.EXTENSION_MISSING, "AGENT", false, List.of());
        assertCompleted(output, AgentRuntimeState.EXTENSION_MISSING)
                .contains("requires a READY compatible platform Java Agent extension");
    }

    @Test
    void agentAndApplicationSdkAreRejectedEvenWhenFacadeIsDisabled() throws Exception {
        String output = run(true, null, AgentRuntimeState.DUAL_SDK_DETECTED, "DISABLED", true, List.of());
        assertCompleted(output, AgentRuntimeState.DUAL_SDK_DETECTED)
                .contains("OpenTelemetry bean and active Java Agent detected simultaneously");
    }

    private static void assertFixtureState(AgentRuntimeState state, String property) throws Exception {
        String output = runWithFixture(state, "AUTO", false, List.of(property));
        assertCompleted(output, state).contains(PREFIX + "facadeNoop=true");
    }

    private static String runWithFixture(
            AgentRuntimeState expected,
            String mode,
            boolean dual,
            List<String> properties) throws Exception {
        String fixture = requiredProperty("smoke.readiness.fixture.extension.jar");
        return run(true, fixture, expected, mode, dual, properties);
    }

    private static String run(
            boolean withAgent,
            String extension,
            AgentRuntimeState expected,
            String mode,
            boolean dual,
            List<String> properties) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        if (extension != null) {
            command.add("-Dotel.javaagent.extensions=" + extension.replace('\\', '/'));
        }
        command.add("-Dotel.traces.exporter=none");
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
        command.add("-Dotel.javaagent.logging=application");
        command.addAll(properties);
        if (withAgent) {
            command.add("-javaagent:" + requiredProperty("otel.javaagent.jar"));
        }
        command.add("-cp");
        command.add(requiredProperty(dual
                ? "smoke.test.runtime.classpath"
                : "smoke.e1.consumer.runtime.classpath"));
        command.add(dual
                ? AgentExtensionAttestationProbeMain.class.getName()
                : MinimalStarterAttestationProbeMain.class.getName());
        command.add(expected.name());
        command.add(mode);
        command.add(Boolean.toString(dual));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> read(process, output), "e1-attestation-output-reader");
        reader.setDaemon(true);
        reader.start();
        boolean finished = process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        reader.join(5_000L);

        assertThat(finished).as("Child JVM timeout. Output:%n%s", output).isTrue();
        assertThat(process.exitValue()).as("Child JVM failure. Output:%n%s", output).isZero();
        return output.toString();
    }

    private static org.assertj.core.api.AbstractStringAssert<?> assertCompleted(
            String output,
            AgentRuntimeState expected) {
        return assertThat(output)
                .contains(PREFIX + "runtimeState=" + expected)
                .contains(PREFIX + "COMPLETE")
                .doesNotContain("OpenTelemetry Javaagent failed to start");
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        assertThat(value).as(name).isNotBlank();
        return value;
    }

    private static void read(Process process, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        } catch (Exception ignored) {
            // Дочерний процесс уже завершён, поток закрыт.
        }
    }
}
