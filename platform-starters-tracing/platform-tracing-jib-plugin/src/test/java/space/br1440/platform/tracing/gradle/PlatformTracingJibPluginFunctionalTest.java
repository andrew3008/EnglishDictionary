package space.br1440.platform.tracing.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlatformTracingJibPluginFunctionalTest extends FunctionalTestSupport {

    private static final String PLUGIN_IMPLEMENTATION_CLASS = "space.br1440.platform.tracing.gradle.PlatformTracingJibPlugin";
    private static final String PLUGIN_DESCRIPTOR = "/META-INF/gradle-plugins/" + PLUGIN_ID + ".properties";
    private static final String JIB_EXTENSION_SERVICE = "/META-INF/services/com.google.cloud.tools.jib.gradle.extension.JibGradlePluginExtension";
    private static final String JIB_EXTENSION_CLASS = "space.br1440.platform.tracing.gradle.PlatformTracingJibExtension";
    private static final Duration LAUNCHER_PROBE_TIMEOUT = Duration.ofSeconds(30);

    @Test
    void plugin_descriptor_pin_and_jib_service_are_packaged_in_the_release() throws IOException {
        // given
        Properties descriptor = PluginRelease.loadProperties(PluginRelease.requiredResource(PLUGIN_DESCRIPTOR));

        // when
        Properties pin = RELEASE.pin();
        String jibService;
        try (InputStream stream = PluginRelease.requiredResource(JIB_EXTENSION_SERVICE).openStream()) {
            jibService = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        // then
        assertThat(descriptor.getProperty("implementation-class")).isEqualTo(PLUGIN_IMPLEMENTATION_CLASS);
        assertThat(pin.getProperty("agent.version")).isEqualTo(PLUGIN_VERSION);
        assertThat(pin.getProperty("agent.sha256")).matches("[0-9a-f]{64}");
        assertThat(jibService.strip()).isEqualTo(JIB_EXTENSION_CLASS);
    }

    @Test
    void published_plugin_resolves_by_id_from_the_real_publication() throws IOException {
        // given
        Path implementation = RELEASE.publishedArtifactDirectory(PLUGIN_GROUP, PLUGIN_ARTIFACT);
        Path marker = RELEASE.publishedArtifactDirectory(PLUGIN_ID, PLUGIN_ID + ".gradle.plugin");
        Path project = ConsumerProject.groovy("published-plugin")
                .withLeadingRepository(RELEASE.publishedRepository())
                .create(temporaryDirectory, repository, javaConsumer());

        // when
        BuildResult result = run(project, "prepareControlledAgent");

        // then
        assertThat(outcomeOf(result, VERIFY_TASK)).isEqualTo(TaskOutcome.SUCCESS);

        String implementationPom = Files.readString(implementation.resolve(PLUGIN_ARTIFACT + "-" + PLUGIN_VERSION + ".pom"), StandardCharsets.UTF_8);
        assertThat(implementationPom)
                .doesNotContain("<dependencyManagement>")
                .doesNotContain("<dependencies>");

        String markerPom = Files.readString(marker.resolve(PLUGIN_ID + ".gradle.plugin-" + PLUGIN_VERSION + ".pom"), StandardCharsets.UTF_8);
        assertThat(markerPom)
                .contains("<groupId>" + PLUGIN_GROUP + "</groupId>")
                .contains("<artifactId>" + PLUGIN_ARTIFACT + "</artifactId>")
                .contains("<version>" + PLUGIN_VERSION + "</version>");

        String module = Files.readString(implementation.resolve(PLUGIN_ARTIFACT + "-" + PLUGIN_VERSION + ".module"), StandardCharsets.UTF_8);
        assertThat(module)
                .contains("\"name\": \"apiElements\"")
                .contains("\"name\": \"runtimeElements\"")
                .doesNotContain("\"dependencies\"")
                .doesNotContain("\"dependencyConstraints\"");
        assertThat(implementation.resolve(PLUGIN_ARTIFACT + "-" + PLUGIN_VERSION + "-sources.jar")).isRegularFile();
        assertThat(implementation.resolve(PLUGIN_ARTIFACT + "-" + PLUGIN_VERSION + "-javadoc.jar")).isRegularFile();
    }

    @Test
    void prepare_writes_the_verified_agent_and_the_local_argfile() throws IOException {
        // given
        Path project = consumer("prepare-happy", javaConsumer());

        // when
        BuildResult result = run(project, "prepareControlledAgent");

        // then
        assertThat(outcomeOf(result, VERIFY_TASK)).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(outcomeOf(result, ARG_FILE_TASK)).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(agentJarIn(project)).isRegularFile();
        assertThat(Files.readAllBytes(agentJarIn(project))).isEqualTo(Files.readAllBytes(CONTROLLED_AGENT_JAR));
        assertThat(localArgsFileIn(project)).isRegularFile();
        assertThat(project.resolve("build/platform-tracing/docker-build-args.env")).doesNotExist();
    }

    @Test
    void tampered_agent_bytes_fail_closed_and_leave_no_outputs() throws IOException {
        // given
        installAgent(Files.writeString(temporaryDirectory.resolve("tampered.jar"), "not-the-controlled-agent", StandardCharsets.UTF_8));
        Path project = consumer("prepare-tampered", javaConsumer());

        // when
        BuildResult result = runAndFail(project, "prepareControlledAgent");

        // then
        assertThat(result.getOutput())
                .contains("does not match the SHA-256 pinned")
                .contains("expected: " + pinnedSha256());
        assertThat(agentJarIn(project)).doesNotExist();
        assertThat(localArgsFileIn(project)).doesNotExist();
    }

    @Test
    void stale_verified_copy_is_replaced_when_the_resolved_agent_changes() throws IOException {
        // given
        Path project = consumer("prepare-stale-copy", javaConsumer());
        run(project, "verifyControlledAgent");
        Files.writeString(agentJarIn(project), "stale-copy", StandardCharsets.UTF_8);

        // when
        BuildResult result = run(project, "verifyControlledAgent");

        // then
        assertThat(outcomeOf(result, VERIFY_TASK)).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(Files.readAllBytes(agentJarIn(project))).isEqualTo(Files.readAllBytes(CONTROLLED_AGENT_JAR));
    }

    @Test
    void re_executed_verify_removes_foreign_files_from_the_owned_agent_directory() throws IOException {
        // given
        Path project = consumer("prepare-owned-directory", javaConsumer());
        run(project, "verifyControlledAgent");
        Files.delete(agentJarIn(project));
        Path stale = Files.writeString(project.resolve(AGENT_DIRECTORY).resolve("stale.jar"), "stale", StandardCharsets.UTF_8);

        // when
        BuildResult result = run(project, "verifyControlledAgent");

        // then
        assertThat(outcomeOf(result, VERIFY_TASK)).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(stale).doesNotExist();
        assertThat(Files.readAllBytes(agentJarIn(project))).isEqualTo(Files.readAllBytes(CONTROLLED_AGENT_JAR));
    }

    @Test
    void second_artifact_in_the_controlled_agent_configuration_fails_closed() throws IOException {
        // given
        installJar(PLUGIN_GROUP, "extra-artifact", CONTROLLED_AGENT_JAR);
        Path project = consumer("controlled-agent-two-files", javaConsumer() + """
                dependencies {
                    controlledAgent '%s:extra-artifact:%s'
                }
                """.formatted(PLUGIN_GROUP, PLUGIN_VERSION));

        // when
        BuildResult result = runAndFail(project, "verifyControlledAgent");

        // then
        assertThat(result.getOutput()).contains("must resolve to exactly one artifact, got 2");
        assertThat(agentJarIn(project)).doesNotExist();
    }

    @Test
    void agent_stays_off_the_application_classpath_of_an_ordinary_consumer() throws IOException {
        // given
        Path project = consumer("classpath-guard-clean", javaConsumer());

        // when
        BuildResult result = run(project, "verifyAgentNotOnApplicationClasspath");

        // then
        assertThat(outcomeOf(result, CLASSPATH_GUARD_TASK)).isEqualTo(TaskOutcome.SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"implementation", "compileOnly", "runtimeOnly", "testImplementation", "testRuntimeOnly"})
    void agent_declared_as_a_dependency_of_any_source_set_fails_closed(String configuration) throws IOException {
        // given
        Path project = consumer("classpath-guard-" + configuration, javaConsumer() + """
                dependencies {
                    %s '%s:%s:%s'
                }
                """.formatted(configuration, PLUGIN_GROUP, AGENT_ARTIFACT, PLUGIN_VERSION));

        // when
        BuildResult result = runAndFail(project, "verifyAgentNotOnApplicationClasspath");

        // then
        assertThat(result.getOutput())
                .contains("must not be an application dependency")
                .contains(PLUGIN_GROUP + ":" + AGENT_ARTIFACT);
    }

    @Test
    void unresolved_application_dependency_fails_the_classpath_guard_closed() throws IOException {
        // given
        Path project = consumer("classpath-guard-unresolved", javaConsumer() + """
                dependencies {
                    implementation 'example.missing:missing-library:1.0'
                }
                """);

        // when
        BuildResult result = runAndFail(project, "verifyAgentNotOnApplicationClasspath");

        // then
        assertThat(result.getOutput())
                .contains("dependency graph is incomplete")
                .contains("example.missing:missing-library:1.0");
    }

    @Test
    void classpath_guard_is_wired_into_check() throws IOException {
        // given
        Path project = consumer("classpath-guard-check", javaConsumer());

        // when
        BuildResult result = run(project, "check", "--dry-run");

        // then
        assertThat(result.getOutput()).contains(CLASSPATH_GUARD_TASK);
    }

    @Test
    void plugin_order_does_not_change_the_boot_run_graph() throws IOException {
        // given
        Path bootFirst = springBootConsumer("order-boot-first", springBootBuildScript());
        Path agentFirst = springBootConsumer("order-agent-first", """
                plugins {
                    id '%s' version '%s'
                    id 'java'
                    id 'org.springframework.boot'
                }
                """.formatted(PLUGIN_ID, PLUGIN_VERSION));

        // when
        String bootFirstGraph = run(bootFirst, "bootRun", "--dry-run").getOutput();
        String agentFirstGraph = run(agentFirst, "bootRun", "--dry-run").getOutput();

        // then
        assertThat(bootFirstGraph).contains(VERIFY_TASK, ":bootRun");
        assertThat(agentFirstGraph).contains(VERIFY_TASK, ":bootRun");
    }

    @Test
    void boot_jar_does_not_resolve_the_agent() throws IOException {
        // given
        Path project = springBootConsumer("boot-jar-isolated", springBootBuildScript());

        // when
        BuildResult result = run(project, "bootJar", "--dry-run");

        // then
        assertThat(result.getOutput())
                .contains(":bootJar")
                .doesNotContain(VERIFY_TASK, PREPARE_TASK, ARG_FILE_TASK);
    }

    @Test
    void prepare_is_up_to_date_on_the_second_run() throws IOException {
        // given
        Path project = consumer("up-to-date", javaConsumer());
        run(project, "prepareControlledAgent");

        // when
        BuildResult second = run(project, "prepareControlledAgent");

        // then
        assertThat(outcomeOf(second, VERIFY_TASK)).isEqualTo(TaskOutcome.UP_TO_DATE);
        assertThat(outcomeOf(second, ARG_FILE_TASK)).isEqualTo(TaskOutcome.UP_TO_DATE);
    }

    @Test
    void prepare_reuses_the_configuration_cache() throws IOException {
        // given
        Path project = consumer("configuration-cache-prepare", javaConsumer());

        // when / then
        assertConfigurationCacheReused(project, "prepareControlledAgent");
    }

    @Test
    void classpath_guard_reuses_the_configuration_cache() throws IOException {
        // given
        Path project = consumer("configuration-cache-guard", javaConsumer());

        // when / then
        assertConfigurationCacheReused(project, "verifyAgentNotOnApplicationClasspath");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bootRun", "bootTestRun"})
    void boot_java_exec_tasks_receive_the_path_of_the_verified_agent(String taskName) throws IOException {
        // given
        Path project = springBootConsumer("boot-args-" + taskName, springBootBuildScript() + captureJvmArgumentsTask(taskName));

        // when
        BuildResult result = run(project, "captureJvmArguments");

        // then
        assertThat(outcomeOf(result, VERIFY_TASK)).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(project.resolve("build/jvm-args.txt"))
                .hasContent("""
                        -javaagent:%s
                        -Dplatform.tracing.controlled-agent.expected-sha256=%s
                        """.formatted(agentJarIn(project).toRealPath(), pinnedSha256()));
    }

    @Test
    void boot_run_preserves_user_supplied_jvm_arguments() throws IOException {
        // given
        Path project = springBootConsumer("boot-run-user-args", springBootBuildScript() + """
                bootRun {
                    jvmArgs '-Xmx256m'
                }
                tasks.register('captureJvmArguments') {
                    dependsOn 'verifyControlledAgent'
                    def bootRun = tasks.named('bootRun', JavaExec)
                    def output = layout.buildDirectory.file('jvm-args.txt')
                    doLast {
                        def task = bootRun.get()
                        def arguments = task.jvmArgumentProviders.collectMany { it.asArguments() } + task.jvmArgs
                        output.get().asFile.text = arguments.join('\\n')
                    }
                }
                """);

        // when
        run(project, "captureJvmArguments");

        // then
        assertThat(project.resolve("build/jvm-args.txt"))
                .content(StandardCharsets.UTF_8)
                .contains("-Xmx256m", "-javaagent:" + agentJarIn(project).toRealPath());
    }

    @Test
    void local_agent_argfile_points_at_the_verified_agent() throws IOException {
        // given
        Path project = consumer("idea-args-created", javaConsumer());

        // when
        run(project, "writeLocalAgentArgFile");

        // then
        String agentPath = agentJarIn(project).toRealPath().toString().replace("\\", "\\\\");
        assertThat(localArgsFileIn(project))
                .hasContent("""
                        "-javaagent:%s"
                        "-Dplatform.tracing.controlled-agent.expected-sha256=%s"
                        """.formatted(agentPath, pinnedSha256()));
    }

    @Test
    void local_agent_argfile_is_restored_after_manual_deletion() throws IOException {
        // given
        Path project = consumer("idea-args-restored", javaConsumer());
        run(project, "writeLocalAgentArgFile");
        Path argsFile = localArgsFileIn(project);
        String originalContent = Files.readString(argsFile, StandardCharsets.UTF_8);
        Files.delete(argsFile);

        // when
        run(project, "writeLocalAgentArgFile");

        // then
        assertThat(argsFile).exists().hasContent(originalContent);
    }

    @Test
    void local_agent_argfile_is_interpreted_correctly_by_a_real_java_launcher()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        Path project = consumer("idea args path with space & sign", javaConsumer());
        run(project, "writeLocalAgentArgFile");
        Path argsFile = localArgsFileIn(project).toRealPath();

        // when
        ChildProcessResult result = runLauncherProbe(argsFile);

        // then
        assertThat(result.exitCode())
                .as("probe must terminate normally; stdout=%s, stderr=%s", result.stdout(), result.stderr())
                .isZero();
        assertThat(result.stdout())
                .contains("MANAGED_AGENT_PROBE_PROPERTY platform.tracing.controlled-agent.expected-sha256=" + pinnedSha256())
                .as("the assessor reads startup arguments delivered through java @argfile")
                .contains("MANAGED_AGENT_PROBE_INPUT_ARGUMENT -Dplatform.tracing.controlled-agent.expected-sha256=" + pinnedSha256());

        List<String> managementInputArguments = result.stdout().lines()
                .filter(line -> line.startsWith("MANAGED_AGENT_PROBE_INPUT_ARGUMENT -Dcom.sun.management")
                        || line.equals("MANAGED_AGENT_PROBE_INPUT_ARGUMENT -XX:+ManagementServer"))
                .toList();
        assertThat(managementInputArguments).isEmpty();

        List<String> javaAgentInputArguments = result.stdout().lines()
                .filter(line -> line.startsWith("MANAGED_AGENT_PROBE_INPUT_ARGUMENT -javaagent:"))
                .toList();
        assertThat(javaAgentInputArguments).hasSize(1);
    }

    @Test
    void kotlin_dsl_consumer_prepares_the_agent_without_configuration() throws IOException {
        // given
        Path project = kotlinConsumer("kotlin-dsl", """
                plugins {
                    java
                    id("%s") version "%s"
                }
                """.formatted(PLUGIN_ID, PLUGIN_VERSION));

        // when
        BuildResult result = run(project, "prepareControlledAgent");

        // then
        assertThat(outcomeOf(result, VERIFY_TASK)).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(Files.readAllBytes(agentJarIn(project))).isEqualTo(Files.readAllBytes(CONTROLLED_AGENT_JAR));
    }

    private ChildProcessResult runLauncherProbe(Path argsFile) throws IOException, InterruptedException, URISyntaxException {
        List<String> command = List.of(
                javaExecutable().toString(),
                "@" + argsFile,
                "-cp", probeClassPath().toString(),
                ManagedAgentJvmFlagsLauncherProbe.class.getName()
        );

        Path stdoutFile = temporaryDirectory.resolve("launcher-probe.stdout.log");
        Path stderrFile = temporaryDirectory.resolve("launcher-probe.stderr.log");

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectOutput(stdoutFile.toFile());
        processBuilder.redirectError(stderrFile.toFile());

        Process process = processBuilder.start();
        boolean finished = process.waitFor(LAUNCHER_PROBE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor(LAUNCHER_PROBE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
        String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
        Integer exitCode = finished ? process.exitValue() : null;
        return new ChildProcessResult(finished, exitCode, stdout, stderr);
    }

    private static Path javaExecutable() {
        String javaCommand = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", javaCommand);
    }

    private static Path probeClassPath() throws URISyntaxException {
        return Path.of(ManagedAgentJvmFlagsLauncherProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private record ChildProcessResult(boolean finished, Integer exitCode, String stdout, String stderr) {
    }

    private static String javaConsumer() {
        return """
                plugins {
                    id 'java'
                    id '%s' version '%s'
                }
                """.formatted(PLUGIN_ID, PLUGIN_VERSION);
    }

    private static String springBootBuildScript() {
        return """
                plugins {
                    id 'java'
                    id 'org.springframework.boot'
                    id '%s' version '%s'
                }
                """.formatted(PLUGIN_ID, PLUGIN_VERSION);
    }

    private static String captureJvmArgumentsTask(String javaExecTaskName) {
        return """
                tasks.register('captureJvmArguments') {
                    dependsOn 'verifyControlledAgent'
                    def javaExec = tasks.named('%s', JavaExec)
                    def output = layout.buildDirectory.file('jvm-args.txt')
                    doLast {
                        def arguments = javaExec.get().jvmArgumentProviders.collectMany { it.asArguments() }
                        output.get().asFile.text = arguments.join('\\n')
                    }
                }
                """.formatted(javaExecTaskName);
    }
}
