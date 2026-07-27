package space.br1440.platform.tracing.e2e.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.cloud.context.refresh.ContextRefresher;

import space.br1440.platform.tracing.e2e.configserver.support.ConfigClientHarness;
import space.br1440.platform.tracing.e2e.configserver.support.EvidenceRecorder;
import space.br1440.platform.tracing.e2e.configserver.support.ProvenanceInspector;
import space.br1440.platform.tracing.e2e.configserver.support.ProvenanceInspector.KeyEvidence;
import space.br1440.platform.tracing.e2e.configserver.support.ProvenanceInspector.MarkerEvidence;
import space.br1440.platform.tracing.e2e.configserver.support.ProvenanceInspector.ProvenanceCategory;

class ConfigServerGitProcessCharacterizationTest {

    private static final String POLICY_RATIO = "platform.tracing.policy.sampling.ratio";
    private static final Duration SERVER_START_TIMEOUT = Duration.ofSeconds(45);

    @Test
    void realConfigServerProcessReadsGitRevisionAndRefreshesAfterCommit(@TempDir Path directory)
            throws Exception {
        Path repository = directory.resolve("config-repository");
        Files.createDirectories(repository);
        initializeGit(repository);
        writeConfiguration(repository, "0.31");
        String firstCommit = commit(repository, "initial config");

        int port = randomPort();
        Path serverLog = directory.resolve("config-server.log");
        Process server = startServer(repository, port, serverLog);
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/provenance-client/default/main");
            long readinessStartNanos = System.nanoTime();
            awaitServer(endpoint, server, serverLog);
            long readinessMillis = Duration.ofNanos(System.nanoTime() - readinessStartNanos).toMillis();

            try (ConfigClientHarness client = ConfigClientHarness.start("http://127.0.0.1:" + port)) {
                KeyEvidence initial = ProvenanceInspector.inspect(client.environment(), POLICY_RATIO);
                MarkerEvidence initialMarker = ProvenanceInspector.marker(client.environment());

                assertThat(initialMarker.present()).isTrue();
                assertThat(String.valueOf(initialMarker.version())).startsWith(firstCommit);
                assertThat(initial.effectiveCategory()).isEqualTo(ProvenanceCategory.CONFIG_SERVER_REMOTE);
                assertThat(initial.effectiveValue()).isEqualTo("0.31");
                assertThat(initial.remoteSource().origin()).isNotBlank();

                writeConfiguration(repository, "0.62");
                String secondCommit = commit(repository, "updated config");
                Set<String> changedKeys = client.context().getBean(ContextRefresher.class).refresh();
                KeyEvidence refreshed = ProvenanceInspector.inspect(client.environment(), POLICY_RATIO);
                MarkerEvidence refreshedMarker = ProvenanceInspector.marker(client.environment());

                assertThat(changedKeys).contains(POLICY_RATIO);
                assertThat(refreshed.effectiveValue()).isEqualTo("0.62");
                assertThat(refreshed.remoteDesiredValue()).isEqualTo("0.62");
                assertThat(String.valueOf(refreshedMarker.version())).startsWith(secondCommit);
                assertThat(refreshedMarker.version()).isNotEqualTo(initialMarker.version());

                EvidenceRecorder.record(
                        "Layer-B-real-config-server-git",
                        refreshed,
                        refreshedMarker,
                        ProvenanceInspector.sanitizedTree(client.environment(), POLICY_RATIO),
                        "initialCommit=" + firstCommit + "; refreshedCommit=" + secondCommit
                                + "; changedKeys=" + changedKeys + "; childServerProcess=true"
                                + "; childServerReadinessMillis=" + readinessMillis
                                + "; childServerReadinessBudgetMillis=" + SERVER_START_TIMEOUT.toMillis()
                );
            }
        } finally {
            boolean gracefulStop = stop(server);
            EvidenceRecorder.record(
                    "Layer-B-cleanup",
                    "gracefulStop=" + gracefulStop + "; childServerProcessAlive=" + server.isAlive()
            );
        }
    }

    private static Process startServer(Path repository, int port, Path log) throws IOException {
        String javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        ).toString();
        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ConfigServerProcessApplication.class.getName());
        command.add("--server.address=127.0.0.1");
        command.add("--server.port=" + port);
        command.add("--spring.application.name=config-server-process-fixture");
        command.add("--spring.cloud.config.server.git.uri=" + repository.toUri());
        command.add("--spring.cloud.config.server.git.default-label=main");
        command.add("--spring.cloud.config.server.git.clone-on-start=true");
        command.add("--spring.cloud.config.server.git.force-pull=true");
        command.add("--spring.jmx.enabled=false");
        command.add("--management.endpoints.enabled-by-default=false");
        command.add("--logging.level.root=INFO");
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
    }

    private static void awaitServer(URI endpoint, Process process, Path log) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        long deadline = System.nanoTime() + SERVER_START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new AssertionError("Config Server завершился до readiness:\n" + readLog(log));
            }
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                // Порт ещё не принимает соединения; продолжаем bounded polling.
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        throw new AssertionError("Config Server не стал готов за " + SERVER_START_TIMEOUT + ":\n" + readLog(log));
    }

    private static void initializeGit(Path repository) throws Exception {
        runGit(repository, "init", "-b", "main");
        runGit(repository, "config", "user.email", "config-provenance@example.invalid");
        runGit(repository, "config", "user.name", "Config Provenance Test");
    }

    private static void writeConfiguration(Path repository, String ratio) throws IOException {
        Files.writeString(
                repository.resolve("provenance-client.yml"),
                "platform:\n  tracing:\n    policy:\n      sampling:\n        ratio: \"" + ratio + "\"\n",
                StandardCharsets.UTF_8
        );
    }

    private static String commit(Path repository, String message) throws Exception {
        runGit(repository, "add", "provenance-client.yml");
        runGit(repository, "commit", "-m", message);
        return runGit(repository, "rev-parse", "HEAD").trim();
    }

    private static String runGit(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(20, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new AssertionError("Истёк timeout команды Git: " + command);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue())
                .as("Git command %s output:%n%s", command, output)
                .isZero();
        return output;
    }

    private static int randomPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean stop(Process process) throws InterruptedException {
        if (process == null || !process.isAlive()) {
            return true;
        }
        process.destroy();
        if (process.waitFor(10, TimeUnit.SECONDS)) {
            return true;
        }
        process.destroyForcibly();
        return process.waitFor(10, TimeUnit.SECONDS);
    }

    private static String readLog(Path log) {
        try {
            return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "<log missing>";
        } catch (IOException exception) {
            return "<log unreadable: " + exception.getMessage() + ">";
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }
}
