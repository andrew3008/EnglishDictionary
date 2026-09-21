package space.br1440.platform.tracing.e2e;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

final class PackagedProcessLauncher {

    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration FORCED_TERMINATION_TIMEOUT = Duration.ofSeconds(10);
    private static final int EVIDENCE_TAIL_CHARS = 4_000;

    private static final Pattern SCENARIO_FILE_NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private static final List<String> FORKED_JVM_ENCODING = List.of(
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8"
    );

    private static final Set<String> INHERITED_JVM_OPTION_VARIABLES = Set.of(
            "JAVA_TOOL_OPTIONS",
            "_JAVA_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "CLASSPATH"
    );

    private static final List<String> CONTAMINATING_VARIABLE_PREFIXES = List.of(
            "OTEL_",
            "SPRING_",
            "MANAGEMENT_",
            "PLATFORM_"
    );

    private final Path javaExecutable;
    private final Path evidenceDirectory;

    PackagedProcessLauncher(Path evidenceDirectory) {
        this.evidenceDirectory = Objects.requireNonNull(evidenceDirectory, "evidenceDirectory");
        this.javaExecutable = currentJavaExecutable();
    }

    ProcessResult launch(LaunchRequest request) throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(request, "request");

        Files.createDirectories(evidenceDirectory);
        Path stdout = evidenceDirectory.resolve(request.scenarioFileName() + ".stdout.log");
        Path stderr = evidenceDirectory.resolve(request.scenarioFileName() + ".stderr.log");

        ProcessBuilder processBuilder = new ProcessBuilder(command(request))
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        removeContaminatingEnvironment(processBuilder.environment());

        int exitCode = awaitExit(processBuilder.start(), request.scenarioFileName(), stdout, stderr);
        return new ProcessResult(
                exitCode,
                Files.readString(stdout, StandardCharsets.UTF_8),
                Files.readString(stderr, StandardCharsets.UTF_8)
        );
    }

    private List<String> command(LaunchRequest request) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.addAll(FORKED_JVM_ENCODING);
        request.systemProperties().forEach((name, value) -> command.add("-D" + name + "=" + value));
        request.javaAgents().forEach(agent -> command.add("-javaagent:" + agent));
        command.add("-jar");
        command.add(request.applicationJar().toString());
        return List.copyOf(command);
    }

    private static int awaitExit(Process process, String scenario, Path stdout, Path stderr)
            throws InterruptedException, TimeoutException {
        boolean exited;
        try {
            exited = process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            throw interrupted;
        }
        if (exited) {
            return process.exitValue();
        }

        boolean terminated = process.destroyForcibly()
                .waitFor(FORCED_TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        throw new TimeoutException(
                "Forked certification JVM [%s] did not exit within %ds%s; stdout=%s, stderr=%s, stderr tail:%n%s"
                        .formatted(
                                scenario,
                                PROCESS_TIMEOUT.toSeconds(),
                                terminated ? "" : " and survived forced destruction",
                                stdout,
                                stderr,
                                tail(stderr)
                        )
        );
    }

    private static String tail(Path evidence) {
        try {
            String content = Files.readString(evidence, StandardCharsets.UTF_8);
            return content.length() <= EVIDENCE_TAIL_CHARS
                    ? content
                    : content.substring(content.length() - EVIDENCE_TAIL_CHARS);
        } catch (IOException unreadable) {
            return "<unreadable: " + unreadable + ">";
        }
    }

    private static void removeContaminatingEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(name -> {
            String normalized = name.toUpperCase(Locale.ROOT);
            return INHERITED_JVM_OPTION_VARIABLES.contains(normalized)
                    || CONTAMINATING_VARIABLE_PREFIXES.stream().anyMatch(normalized::startsWith);
        });
    }

    private static Path currentJavaExecutable() {
        Path executable = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        if (!Files.isExecutable(executable)) {
            throw new IllegalStateException("Java executable of the current JVM is not available: " + executable);
        }

        return executable;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    record LaunchRequest(String scenarioFileName,
                         Path applicationJar,
                         List<Path> javaAgents,
                         Map<String, String> systemProperties) {

        LaunchRequest {
            Objects.requireNonNull(scenarioFileName, "scenarioFileName");
            if (!SCENARIO_FILE_NAME.matcher(scenarioFileName).matches()) {
                throw new IllegalArgumentException(
                        "Scenario file name must match " + SCENARIO_FILE_NAME.pattern() + ": " + scenarioFileName
                );
            }
            Objects.requireNonNull(applicationJar, "applicationJar");
            javaAgents = List.copyOf(javaAgents);
            systemProperties.forEach((name, value) -> {
                Objects.requireNonNull(name, "system property name");
                Objects.requireNonNull(value, () -> "system property value for " + name);
            });
            systemProperties = Collections.unmodifiableMap(new LinkedHashMap<>(systemProperties));
        }
    }

    record ProcessResult(int exitCode, String stdout, String stderr) {

        String combinedOutput() {
            return stdout + System.lineSeparator() + stderr;
        }
    }
}
