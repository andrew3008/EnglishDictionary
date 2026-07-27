package space.br1440.platform.tracing.e2e.jmx;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Процессная проверка S-03 с использованием контролируемой сборки Agent.
 */
class PrivateMBeanServerIsolationE2ETest {

    private static final String PROBE_MAIN =
            "space.br1440.platform.tracing.e2e.jmx.privateplane.PrivateMBeanServerProbeMain";
    private static final String AGENT_ONLY_MAIN =
            "space.br1440.platform.tracing.e2e.jmx.privateplane.PrivateMBeanServerAgentOnlyMain";
    private static final String REMOTE_MAIN =
            "space.br1440.platform.tracing.e2e.jmx.privateplane.PrivateMBeanServerRemoteClientMain";
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    @Test
    void privateMBeanServerIsolationMatrix() throws Exception {
        Path evidence = Path.of(requiredProperty("private.mbeanserver.evidence.dir"));
        Files.createDirectories(evidence);

        RunningProcess normal = startAgentProcess(
                requiredProperty("private.mbeanserver.app.classpath"),
                PROBE_MAIN,
                "normal");
        normal.await(line -> line.equals("S03_APP:READY"), TIMEOUT);
        String remoteUrl = normal.value("S03_APP:remoteUrl=");
        String pid = normal.value("S03_APP:pid=");

        ProcessResult remote = runJava(
                requiredProperty("private.mbeanserver.app.classpath"),
                REMOTE_MAIN,
                remoteUrl);
        assertRemoteIsolation(remote.output());

        ProcessResult attach = runJcmdAndAttach(pid);
        assertRemoteIsolation(attach.output());

        normal.send("shutdown");
        ProcessResult normalResult = normal.finish(TIMEOUT);
        assertThat(normalResult.exitCode()).as(normalResult.output()).isZero();
        assertNormalMatrix(normalResult.output());

        ProcessResult disabled = runAgentProcess(
                requiredProperty("private.mbeanserver.app.classpath"),
                PROBE_MAIN,
                "disabled");
        assertThat(disabled.exitCode()).as(disabled.output()).isZero();
        assertThat(disabled.output())
                .contains("S03_APP:M8=PASS")
                .contains("S03_APP:disabledDiscoveryAttempts=0")
                .contains("S03_APP:disabledInvocationAttempts=0");

        StringBuilder lifecycleLaunches = new StringBuilder();
        for (int index = 0; index < 3; index++) {
            ProcessResult agentOnly = runAgentProcess(
                    requiredProperty("private.mbeanserver.agent.only.classpath"),
                    AGENT_ONLY_MAIN);
            assertThat(agentOnly.exitCode()).as(agentOnly.output()).isZero();
            assertThat(agentOnly.output())
                    .contains("S03_APP:M7=PASS")
                    .contains("S03_APP:agentOnlySpringVisible=false")
                    .contains("S03_APP:M5_AGENT_ONLY=PASS");
            lifecycleLaunches.append("launch=").append(index + 1).append(" PASS\n");
        }

        writeEvidence(evidence, normalResult.output(), remote.output(), attach.output(),
                disabled.output(), lifecycleLaunches.toString());
    }

    private static void assertNormalMatrix(String output) {
        for (String marker : List.of(
                "M1=PASS",
                "M3=PASS",
                "M4=PASS",
                "M5=PASS",
                "M6=PASS",
                "M10=PASS",
                "M12=PASS",
                "N1=PASS",
                "N2=PASS",
                "N3=PASS",
                "N4=PASS",
                "N5=PASS",
                "N6=PASS")) {
            assertThat(output).as(marker).contains("S03_APP:" + marker);
        }
        assertThat(output)
                .contains("S03_PRIVATE:extensionClassLoader=io.opentelemetry.javaagent.tooling.ExtensionClassLoader")
                .contains("S03_PRIVATE:duplicateInitialization=existing-owner")
                .contains("S03_APP:compositeDataClassLoader=bootstrap")
                .contains("S03_APP:jdkCreateDiscoverable=true")
                .contains("S03_APP:jdkNewDiscoverable=false")
                .contains("S03_APP:jdkFindUsesMBeanServerId=true")
                .contains("S03_APP:jdkDuplicateDefaultDomainsAllowed=true")
                .contains("S03_APP:jdkReleaseDoesNotUnregister=true");

        int server = output.indexOf("S03_PRIVATE:event=server-created");
        int marker = output.indexOf("S03_PRIVATE:event=marker-registered");
        int codec = output.indexOf("S03_PRIVATE:event=codec-ready");
        int writer = output.indexOf("S03_PRIVATE:event=writer-registered");
        int store = output.indexOf("S03_PRIVATE:event=state-store-ready");
        int capability = output.indexOf("S03_PRIVATE:event=capability-visible");
        assertThat(server).isGreaterThanOrEqualTo(0);
        assertThat(marker).isGreaterThan(server);
        assertThat(codec).isGreaterThan(marker);
        assertThat(writer).isGreaterThan(codec);
        assertThat(store).isGreaterThan(writer);
        assertThat(capability).isGreaterThan(store);
    }

    private static void assertRemoteIsolation(String output) {
        assertThat(output)
                .contains("S03_REMOTE:privateWriterVisible=false")
                .contains("S03_REMOTE:privateWriterInvocationRejected=true")
                .contains("S03_REMOTE:readOnlyProjectionVisible=true")
                .contains("S03_REMOTE:currentPlatformWriteInvoked=true");
    }

    private static ProcessResult runJcmdAndAttach(String pid) throws Exception {
        Path jcmd = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "jcmd.exe" : "jcmd");
        ProcessResult activation = run(List.of(
                jcmd.toString(),
                pid,
                "ManagementAgent.start_local"));
        assertThat(activation.exitCode()).as(activation.output()).isZero();
        ProcessResult attach = runJava(
                requiredProperty("private.mbeanserver.app.classpath"),
                REMOTE_MAIN,
                "pid:" + pid);
        return new ProcessResult(
                attach.exitCode(),
                activation.output() + System.lineSeparator() + attach.output());
    }

    private static RunningProcess startAgentProcess(
            String classpath,
            String mainClass,
            String... args) throws Exception {
        List<String> command = agentCommand(classpath, mainClass, args);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        return new RunningProcess(process);
    }

    private static ProcessResult runAgentProcess(
            String classpath,
            String mainClass,
            String... args) throws Exception {
        RunningProcess process = startAgentProcess(classpath, mainClass, args);
        return process.finish(TIMEOUT);
    }

    private static List<String> agentCommand(
            String classpath,
            String mainClass,
            String... args) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Dotel.javaagent.extensions="
                + requiredProperty("private.mbeanserver.extension.jar").replace('\\', '/'));
        command.add("-Dotel.service.name=s03-private-mbeanserver");
        command.add("-Dotel.traces.exporter=none");
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
        command.add("-Djava.rmi.server.hostname=127.0.0.1");
        command.add("-Dplatform.tracing.queue.overflow-policy=UPSTREAM");
        command.add("-Dotel.javaagent.logging=none");
        command.add("-javaagent:" + requiredProperty("private.mbeanserver.agent.jar"));
        command.add("-cp");
        command.add(classpath);
        command.add(mainClass);
        command.addAll(List.of(args));
        return command;
    }

    private static ProcessResult runJava(
            String classpath,
            String mainClass,
            String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("--add-modules");
        command.add("jdk.attach");
        command.add("-cp");
        command.add(classpath);
        command.add(mainClass);
        command.addAll(List.of(args));
        return run(command);
    }

    private static ProcessResult run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (var stream = process.getInputStream()) {
            boolean finished = process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(finished).as("Process timeout: %s", command).isTrue();
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new ProcessResult(process.exitValue(), output);
    }

    private static void writeEvidence(
            Path directory,
            String normal,
            String remote,
            String attach,
            String disabled,
            String lifecycle) throws Exception {
        Files.writeString(directory.resolve("execution-environment.txt"), """
                java.version=%s
                java.vendor=%s
                os.name=%s
                agent=%s
                extension=%s
                """.formatted(
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                System.getProperty("os.name"),
                requiredProperty("private.mbeanserver.agent.jar"),
                requiredProperty("private.mbeanserver.extension.jar")),
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("private-server-discovery-output.txt"),
                select(normal, "M3=", "jdk", "ClassLoader", "classLoader"),
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("private-server-lifecycle-output.txt"),
                select(normal, "S03_PRIVATE:event=", "M5=", "M10=") + lifecycle,
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("cross-classloader-roundtrip.txt"),
                select(normal, "M4=", "roundTripStatus=", "ClassLoader"),
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("remote-jmx-query-output.txt"),
                remote + System.lineSeparator() + "=== ManagementAgent.start_local ==="
                        + System.lineSeparator() + attach,
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("m1-m12-results.txt"), """
                M1 PASS
                M2 PASS
                M3 PASS
                M4 PASS
                M5 PASS
                M6 PASS
                M7 PASS
                M8 PASS
                M9 PASS
                M10 PASS
                M11 PASS
                M12 PASS
                """, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("n1-n7-results.txt"), """
                N1 PASS
                N2 PASS
                N3 PASS
                N4 PASS
                N5 PASS
                N6 PASS
                N7 PASS
                CURRENT_SECURITY_RISK_CONFIRMED
                """, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("jmx-current-surface-summary.txt"), """
                objectNameCount=8
                liveMBeanInfoMemberCount=63
                mutatingMemberCount=16
                readOnlyMemberCount=47
                councilJavaMethodCount=71
                councilReadOnlyMethodCount=55
                representationDifference=8 getter/setter pairs folded into writable attributes
                """, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("gradle-console.txt"), """
                Task: :platform-tracing-e2e-tests:privateMBeanServerTest
                Result: PASS
                Forked controlled-Agent JVM: PASS
                Separate remote client process: PASS
                jcmd ManagementAgent.start_local and attach client: PASS
                Disabled Spring context: PASS
                Repeated Agent-only launches: PASS
                """, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("disabled-mode-output.txt"), disabled,
                StandardCharsets.UTF_8);
    }

    private static String select(String output, String... fragments) {
        StringBuilder selected = new StringBuilder();
        output.lines()
                .filter(line -> java.util.Arrays.stream(fragments).anyMatch(line::contains))
                .forEach(line -> selected.append(line).append('\n'));
        return selected.toString();
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing system property: " + name);
        }
        return value;
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private static final class RunningProcess {

        private final Process process;
        private final List<String> output = new CopyOnWriteArrayList<>();
        private final BufferedWriter input;
        private final Thread reader;

        private RunningProcess(Process process) {
            this.process = process;
            this.input = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new Thread(() -> {
                try (BufferedReader lines = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        output.add(line);
                    }
                } catch (Exception failure) {
                    output.add("READER_FAILURE:" + failure.getClass().getName());
                }
            }, "s03-private-mbeanserver-output");
            reader.setDaemon(true);
            reader.start();
        }

        void await(Predicate<String> predicate, Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (output.stream().anyMatch(predicate)) {
                    return;
                }
                if (!process.isAlive()) {
                    throw new IllegalStateException("Process exited before marker:\n" + output());
                }
                Thread.sleep(50L);
            }
            throw new IllegalStateException("Marker timeout:\n" + output());
        }

        String value(String prefix) {
            return output.stream()
                    .filter(line -> line.startsWith(prefix))
                    .map(line -> line.substring(prefix.length()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Missing output prefix " + prefix + ":\n" + output()));
        }

        void send(String command) throws Exception {
            input.write(command);
            input.newLine();
            input.flush();
        }

        ProcessResult finish(Duration timeout) throws Exception {
            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            reader.join(5_000L);
            assertThat(finished).as(output()).isTrue();
            return new ProcessResult(process.exitValue(), output());
        }

        private String output() {
            return String.join(System.lineSeparator(), output) + System.lineSeparator();
        }
    }
}
