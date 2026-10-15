package space.br1440.platform.tracing.otel.javaagent.distribution;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import space.br1440.platform.tracing.otel.javaagent.distribution.probe.ControlledAgentRuntimeProbe;

final class ControlledAgentChild {

    static final Duration WAIT_TIMEOUT = Duration.ofSeconds(45);
    static final Duration KILL_TIMEOUT = Duration.ofSeconds(10);

    private static final String OTLP_PROBE_API_CLASSPATH_PROPERTY = "platformTracing.otlpProbeApiClasspath";

    private ControlledAgentChild() {
    }

    static ChildProcessResult run(Path directory, String label, List<String> command) throws IOException, InterruptedException {
        return run(directory, label, command, Map.of());
    }

    static ChildProcessResult run(Path directory,
                                  String label,
                                  List<String> command,
                                  Map<String, String> additionalEnvironment) throws IOException, InterruptedException {
        PreparedChild prepared = prepare(directory, label, command, additionalEnvironment);
        Process process = prepared.builder().start();
        return await(process, prepared.stdoutFile(), prepared.stderrFile(), WAIT_TIMEOUT);
    }

    @SuppressWarnings("SameParameterValue")
    static PreparedChild prepare(Path directory, String label, List<String> command) {
        return prepare(directory, label, command, Map.of());
    }

    static PreparedChild prepare(Path directory,
                                 String label,
                                 List<String> command,
                                 Map<String, String> additionalEnvironment) {
        Path stdoutFile = directory.resolve(label + ".stdout.log");
        Path stderrFile = directory.resolve(label + ".stderr.log");
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        removeContaminatingEnvironment(processBuilder.environment());
        processBuilder.environment().putAll(additionalEnvironment);
        processBuilder.redirectOutput(stdoutFile.toFile());
        processBuilder.redirectError(stderrFile.toFile());
        return new PreparedChild(processBuilder, stdoutFile, stderrFile);
    }

    static ChildProcessResult await(Process process,
                                    Path stdoutFile,
                                    Path stderrFile,
                                    Duration timeout) throws IOException, InterruptedException {
        boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor(KILL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
        String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
        Integer exitCode = finished ? process.exitValue() : null;
        return new ChildProcessResult(finished, exitCode, stdout, stderr);
    }

    static List<String> javaCommand(List<String> leadingOptions) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.addAll(leadingOptions);
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        return command;
    }

    static void appendLoggingExporter(List<String> command) {
        command.add("-Dotel.traces.exporter=logging");
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
    }

    static void appendOtlpExporter(List<String> command, String otlpEndpoint) {
        command.add("-Dotel.traces.exporter=otlp");
        command.add("-Dotel.exporter.otlp.traces.protocol=http/protobuf");
        command.add("-Dotel.exporter.otlp.endpoint=" + otlpEndpoint);
        command.add("-Dotel.exporter.otlp.traces.compression=none");
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
        command.add("-Dotel.bsp.schedule.delay=200");
    }

    static void appendSamplingAlwaysOn(List<String> command) {
        command.add("-Dplatform.tracing.sampling.enabled=true");
        command.add("-Dplatform.tracing.sampling.ratio=1.0");
    }

    static void appendErrorSpanValidation(List<String> command) {
        command.add("-Dplatform.tracing.javaagent.span-attributes.validation.level=ERROR");
    }

    static void appendServiceIdentity(List<String> command) {
        command.add("-Dplatform.tracing.service.name=controlled-agent-runtime-test");
        command.add("-Dplatform.tracing.service.version=1.0.0");
        command.add("-Dplatform.tracing.service.environment=test");
        command.add("-Dplatform.tracing.resource.validation-mode=ERROR");
    }

    static void appendInvalidScanInterval(List<String> command) {
        command.add("-Dplatform.tracing.span-timeout.scan-interval=0");
    }

    static void appendAgentLaunch(List<String> command,
                                  AgentTestFixture fixture,
                                  Class<?> probe,
                                  List<String> probeArguments) throws URISyntaxException {
        command.add("-Dplatform.tracing.controlled-agent.expected-sha256=" + fixture.expectedControlledAgentSha());
        command.add("-javaagent:" + fixture.controlledAgent());
        command.add("-cp");
        command.add(probeClassPath(probe).toString());
        command.add(probe.getName());
        command.addAll(probeArguments);
    }

    static void appendAgentLaunchWithApi(List<String> command,
                                         AgentTestFixture fixture,
                                         Class<?> probe,
                                         boolean publishExpectedApiClasspath,
                                         List<String> probeArguments) throws URISyntaxException {
        String apiClasspath = AgentTestFixture.requiredProperty(OTLP_PROBE_API_CLASSPATH_PROPERTY);
        command.add("-Dplatform.tracing.controlled-agent.expected-sha256=" + fixture.expectedControlledAgentSha());
        if (publishExpectedApiClasspath) {
            command.add("-D" + ControlledAgentRuntimeProbe.EXPECTED_API_CLASSPATH_PROPERTY + "=" + apiClasspath);
        }

        command.add("-javaagent:" + fixture.controlledAgent());
        command.add("-cp");
        command.add(probeClassPath(probe) + File.pathSeparator + apiClasspath);
        command.add(probe.getName());
        command.addAll(probeArguments);
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

    private static Path javaExecutable() {
        String javaCommand = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", javaCommand);
    }

    private static Path probeClassPath(Class<?> probe) throws URISyntaxException {
        return Path.of(probe.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    record PreparedChild(ProcessBuilder builder, Path stdoutFile, Path stderrFile) {
    }
}
