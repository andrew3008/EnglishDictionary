package space.br1440.platform.tracing.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

abstract class FunctionalTestSupport {

    static final PluginRelease RELEASE = PluginRelease.fromSystemProperties();
    static final String PLUGIN_ID = PluginRelease.PLUGIN_ID;
    static final String PLUGIN_GROUP = PluginRelease.PLUGIN_GROUP;
    static final String PLUGIN_ARTIFACT = PluginRelease.PLUGIN_ARTIFACT;
    static final String AGENT_ARTIFACT = PluginRelease.AGENT_ARTIFACT;
    static final String PLUGIN_VERSION = RELEASE.version();
    static final Path CONTROLLED_AGENT_JAR = RELEASE.agentJar();

    static final String VERIFY_TASK = ":verifyControlledAgent";
    static final String ARG_FILE_TASK = ":writeLocalAgentArgFile";
    static final String PREPARE_TASK = ":prepareControlledAgent";
    static final String CLASSPATH_GUARD_TASK = ":verifyAgentNotOnApplicationClasspath";
    static final String AGENT_DIRECTORY = "build/platform-tracing/agent";

    private static final String LOCAL_AGENT_ARGS_FILE = "build/platform-tracing/idea-run/local-agent.args";

    @TempDir
    Path temporaryDirectory;

    IsolatedMavenRepository repository;

    @BeforeEach
    void installRelease() throws IOException {
        repository = new IsolatedMavenRepository(temporaryDirectory.resolve("m2"), PLUGIN_VERSION);
        repository.installRelease(RELEASE);
        repository.installAgent(CONTROLLED_AGENT_JAR);
    }

    Path consumer(String name, String buildScript) throws IOException {
        return ConsumerProject.groovy(name).create(temporaryDirectory, repository, buildScript);
    }

    @SuppressWarnings("SameParameterValue")
    Path kotlinConsumer(String name, String buildScript) throws IOException {
        return ConsumerProject.kotlin(name).create(temporaryDirectory, repository, buildScript);
    }

    Path springBootConsumer(String name, String buildScript) throws IOException {
        return ConsumerProject.groovy(name).withSpringBootSubstitute().create(temporaryDirectory, repository, buildScript);
    }

    void installAgent(Path jar) throws IOException {
        repository.installAgent(jar);
    }

    @SuppressWarnings("SameParameterValue")
    void installJar(String group, String artifact, Path jar) throws IOException {
        repository.installJar(group, artifact, jar);
    }

    BuildResult run(Path project, String... arguments) {
        return runner(project, BuildMode.OFFLINE_STRICT, arguments).build();
    }

    BuildResult runAndFail(Path project, String... arguments) {
        return runner(project, BuildMode.OFFLINE_STRICT, arguments).buildAndFail();
    }

    BuildResult runOnline(Path project, String... arguments) {
        return runner(project, BuildMode.ONLINE, arguments).build();
    }

    BuildResult runOnlineAndFail(Path project, String... arguments) {
        return runner(project, BuildMode.ONLINE, arguments).buildAndFail();
    }

    void assertConfigurationCacheReused(Path project, String task) {
        BuildResult first = run(project, task, "--configuration-cache");
        BuildResult second = run(project, task, "--configuration-cache");

        assertThat(first.getOutput()).contains("Configuration cache entry stored");
        assertThat(second.getOutput()).contains("Reusing configuration cache");
    }

    static TaskOutcome outcomeOf(BuildResult result, String taskPath) {
        BuildTask task = result.task(taskPath);
        if (task == null) {
            throw new IllegalStateException("Build did not run the expected task: " + taskPath);
        }

        return task.getOutcome();
    }

    static String agentFileName() {
        return RELEASE.agentFileName();
    }

    static Path agentJarIn(Path project) {
        return project.resolve(AGENT_DIRECTORY).resolve(agentFileName());
    }

    static Path localArgsFileIn(Path project) {
        return project.resolve(LOCAL_AGENT_ARGS_FILE);
    }

    static String pinnedSha256() {
        return RELEASE.pinnedSha256();
    }

    private static GradleRunner runner(Path project, BuildMode mode, String... arguments) {
        List<String> command = new ArrayList<>(List.of(arguments));
        command.addAll(mode.arguments);
        command.add("--no-build-cache");
        command.add("--stacktrace");

        return GradleRunner.create()
                .withTestKitDir(testKitDirectory().toFile())
                .withProjectDir(project.toFile())
                .withArguments(command)
                .forwardOutput();
    }

    private static Path testKitDirectory() {
        try {
            return Files.createDirectories(RELEASE.testKitDirectory());
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot create the shared TestKit directory: " + RELEASE.testKitDirectory(), failure);
        }
    }

    private enum BuildMode {
        OFFLINE_STRICT(List.of("--offline", "--warning-mode=fail")),
        ONLINE(List.of());

        private final List<String> arguments;

        BuildMode(List<String> arguments) {
            this.arguments = arguments;
        }
    }
}
