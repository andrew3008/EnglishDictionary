package space.br1440.platform.tracing.e2e;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class PackagedProcessLauncher {

    private static final long PROCESS_TIMEOUT_SECONDS = 60L;

    private final Path javaExecutable;
    private final Path evidenceDirectory;

    PackagedProcessLauncher(Path evidenceDirectory) {
        this.javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        );
        this.evidenceDirectory = evidenceDirectory;
    }

    ProcessResult launch(LaunchRequest request) throws IOException, InterruptedException {
        Files.createDirectories(evidenceDirectory);
        Path stdout = evidenceDirectory.resolve(request.scenarioFileName() + ".stdout.log");
        Path stderr = evidenceDirectory.resolve(request.scenarioFileName() + ".stderr.log");

        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-XX:+DisableAttachMechanism");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        request.systemProperties().forEach((name, value) -> command.add("-D" + name + "=" + value));
        for (Path javaAgent : request.javaAgents()) {
            command.add("-javaagent:" + javaAgent);
        }
        command.add("-jar");
        command.add(request.applicationJar().toString());
        command.addAll(request.applicationArguments());

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        removeContaminatingEnvironment(processBuilder.environment());
        processBuilder.redirectOutput(stdout.toFile());
        processBuilder.redirectError(stderr.toFile());

        long started = System.nanoTime();
        Process process = processBuilder.start();
        boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            boolean terminated = process.destroyForcibly().waitFor(10L, TimeUnit.SECONDS);
            if (!terminated) {
                throw new IOException("Forked certification JVM did not terminate after forced destruction");
            }
        }

        long durationNanos = System.nanoTime() - started;
        return new ProcessResult(
                finished,
                finished ? process.exitValue() : null,
                durationNanos,
                stdout,
                stderr,
                Files.readString(stdout, StandardCharsets.UTF_8),
                Files.readString(stderr, StandardCharsets.UTF_8)
        );
    }

    private static void removeContaminatingEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(name -> {
            String normalized = name.toUpperCase(Locale.ROOT);
            return normalized.equals("JAVA_TOOL_OPTIONS")
                    || normalized.equals("_JAVA_OPTIONS")
                    || normalized.equals("JDK_JAVA_OPTIONS")
                    || normalized.equals("CLASSPATH")
                    || normalized.startsWith("OTEL_")
                    || normalized.startsWith("SPRING_")
                    || normalized.startsWith("MANAGEMENT_")
                    || normalized.startsWith("PLATFORM_");
        });
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    record LaunchRequest(String scenarioFileName, Path applicationJar, List<Path> javaAgents,
                         Map<String, String> systemProperties, List<String> applicationArguments) {

        LaunchRequest {
            javaAgents = List.copyOf(javaAgents);
            systemProperties = Map.copyOf(new LinkedHashMap<>(systemProperties));
            applicationArguments = List.copyOf(applicationArguments);
        }
    }

    record ProcessResult(boolean finished, Integer exitCode, long durationNanos,
                         Path stdoutPath, Path stderrPath, String stdout, String stderr) {

        String combinedOutput() {
            return stdout + System.lineSeparator() + stderr;
        }
    }
}
