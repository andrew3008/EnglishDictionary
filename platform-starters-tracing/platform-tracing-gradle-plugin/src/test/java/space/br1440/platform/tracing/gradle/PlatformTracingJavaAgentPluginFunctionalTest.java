package space.br1440.platform.tracing.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.common.util.StringUtils;

/**
 * Проверяет zero-config контракт плагина на реальных сборках-потребителях.
 *
 * <p>Плагин ставится из собранного JAR в изолированный Maven-репозиторий, поэтому тест видит
 * то же, что увидит сервис: pin внутри релиза, marker publication и резолв агента по группе и имени артефакта.
 * Сборки-потребители работают в offline и только с изолированным репозиторием — сеть и
 * состояние рабочей станции на результат не влияют.
 */
class PlatformTracingJavaAgentPluginFunctionalTest {

    private static final String PLUGIN_ID = "platform.tracing-javaagent";
    private static final String PLUGIN_GROUP = "space.br1440.platform.starters.tracing";
    private static final String PLUGIN_ARTIFACT = "platform-tracing-gradle-plugin";
    private static final String MARKER_ARTIFACT = PLUGIN_ID + ".gradle.plugin";
    private static final String AGENT_ARTIFACT = "platform-tracing-javaagent";
    private static final String COMMON_UTIL_GROUP = "space.br1440.platform.starters";
    private static final String COMMON_UTIL_ARTIFACT = "platform-starters-common-util";
    private static final String PLUGIN_IMPLEMENTATION_CLASS = "space.br1440.platform.tracing.gradle.PlatformTracingJavaAgentPlugin";
    private static final String PIN_RESOURCE = "/META-INF/platform-tracing/controlled-agent-pin.properties";
    private static final String PLUGIN_DESCRIPTOR = "/META-INF/gradle-plugins/" + PLUGIN_ID + ".properties";

    private static final String MATERIALIZE_TASK = ":materializeControlledAgent";
    private static final String VERIFY_TASK = ":verifyControlledAgent";
    private static final String DOCKER_BUILD_ARGS_TASK = ":generateAgentDockerBuildArgs";
    private static final String PREPARE_TASK = ":prepareControlledAgent";
    private static final String CLASSPATH_GUARD_TASK = ":verifyAgentNotOnApplicationClasspath";
    private static final String AGENT_DIRECTORY = "build/platform-tracing/agent";
    private static final String DOCKER_BUILD_ARGS_FILE = "build/platform-tracing/docker-build-args.env";

    private static final String PLUGIN_VERSION = requiredProperty("platformTracing.pluginVersion");
    private static final Path PLUGIN_JAR = requiredPath("platformTracing.pluginJar");
    private static final Path COMMON_UTIL_JAR = requiredPath("platformTracing.commonUtilJar");
    private static final Path CONTROLLED_AGENT_JAR = requiredPath("platformTracing.controlledAgentJar");

    private static final Path TEST_KIT_DIRECTORY = Path.of(requiredProperty("platformTracing.testKitDirectory"));

    @TempDir
    Path temporaryDirectory;

    private Path isolatedRepository;

    @BeforeEach
    void setUp() throws IOException {
        isolatedRepository = temporaryDirectory.resolve("m2");
        installPlugin();
        installAgent(CONTROLLED_AGENT_JAR);
    }

    @Test
    void plugin_descriptor_and_agent_pin_are_packaged_in_the_release() throws IOException {
        // given
        Properties descriptor = loadProperties(requiredResource(PLUGIN_DESCRIPTOR));

        // when
        Properties pin = loadPin();

        // then
        assertThat(descriptor.getProperty("implementation-class"))
                .isEqualTo(PLUGIN_IMPLEMENTATION_CLASS);
        assertThat(pin.getProperty("agent.version")).isEqualTo(PLUGIN_VERSION);
        assertThat(pin.getProperty("agent.sha256")).matches("[0-9a-f]{64}");
    }

    @Test
    void prepare_materializes_the_verified_agent_and_the_docker_build_arg() throws IOException {
        // given
        Path project = consumer("prepare-happy", javaConsumer());

        // when
        BuildResult result = run(project, "prepareControlledAgent");

        // then
        assertThat(agentJarIn(project)).isRegularFile();
        assertThat(Files.readAllBytes(agentJarIn(project)))
                .isEqualTo(Files.readAllBytes(CONTROLLED_AGENT_JAR));
        assertThat(project.resolve(DOCKER_BUILD_ARGS_FILE))
                .hasContent("PLATFORM_AGENT_JAR=" + agentFileName() + "\n");
        assertThat(outcomeOf(result, VERIFY_TASK)).isEqualTo(TaskOutcome.SUCCESS);
    }

    @Test
    void tampered_agent_bytes_fail_closed() throws IOException {
        // given
        installAgent(Files.writeString(
                temporaryDirectory.resolve("tampered.jar"),
                "not-the-controlled-agent",
                StandardCharsets.UTF_8));
        Path project = consumer("prepare-tampered", javaConsumer());

        // when
        BuildResult result = runAndFail(project, "verifyControlledAgent");

        // then
        assertThat(result.getOutput())
                .contains("does not match the SHA-256 pinned")
                .contains("expected: " + loadPin().getProperty("agent.sha256"));
        assertThat(project.resolve(DOCKER_BUILD_ARGS_FILE)).doesNotExist();
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
    @ValueSource(strings = {"implementation", "compileOnly", "runtimeOnly"})
    void agent_declared_as_an_application_dependency_fails_closed(String configuration)
            throws IOException {
        // given
        Path project = consumer("classpath-guard-" + configuration, """
                plugins {
                    id 'java'
                    id '%s' version '%s'
                }
                dependencies {
                    %s '%s:%s:%s'
                }
                """.formatted(
                PLUGIN_ID, PLUGIN_VERSION,
                configuration, PLUGIN_GROUP, AGENT_ARTIFACT, PLUGIN_VERSION));

        // when
        BuildResult result = runAndFail(project, "verifyAgentNotOnApplicationClasspath");

        // then
        assertThat(result.getOutput())
                .contains("must not be an application dependency")
                .contains(PLUGIN_GROUP + ":" + AGENT_ARTIFACT);
    }

    @Test
    void plugin_order_does_not_change_the_boot_run_graph() throws IOException {
        // given
        Path bootFirst = consumer("order-boot-first", springBootConsumer(), true);
        Path agentFirst = consumer("order-agent-first", """
                plugins {
                    id '%s' version '%s'
                    id 'java'
                    id 'org.springframework.boot'
                }
                """.formatted(PLUGIN_ID, PLUGIN_VERSION), true);

        // when
        String bootFirstGraph = run(bootFirst, "bootRun", "--dry-run").getOutput();
        String agentFirstGraph = run(agentFirst, "bootRun", "--dry-run").getOutput();

        // then
        assertThat(bootFirstGraph).contains(MATERIALIZE_TASK, VERIFY_TASK, ":bootRun");
        assertThat(agentFirstGraph).contains(MATERIALIZE_TASK, VERIFY_TASK, ":bootRun");
    }

    @Test
    void boot_jar_does_not_resolve_the_agent() throws IOException {
        // given
        Path project = consumer("boot-jar-isolated", springBootConsumer(), true);

        // when
        BuildResult result = run(project, "bootJar", "--dry-run");

        // then
        assertThat(result.getOutput())
                .contains(":bootJar")
                .doesNotContain(MATERIALIZE_TASK, VERIFY_TASK, PREPARE_TASK);
    }

    @Test
    void prepare_is_up_to_date_on_the_second_run() throws IOException {
        // given
        Path project = consumer("up-to-date", javaConsumer());
        run(project, "prepareControlledAgent");

        // when
        BuildResult second = run(project, "prepareControlledAgent");

        // then
        assertThat(outcomeOf(second, MATERIALIZE_TASK)).isEqualTo(TaskOutcome.UP_TO_DATE);
        assertThat(outcomeOf(second, DOCKER_BUILD_ARGS_TASK)).isEqualTo(TaskOutcome.UP_TO_DATE);
        assertThat(outcomeOf(second, VERIFY_TASK)).isIn(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE);
    }

    @Test
    void prepare_reuses_the_configuration_cache() throws IOException {
        // given
        Path project = consumer("configuration-cache", javaConsumer());
        BuildResult first = run(project, "prepareControlledAgent", "--configuration-cache");

        // when
        BuildResult second = run(project, "prepareControlledAgent", "--configuration-cache");

        // then
        assertThat(first.getOutput()).contains("Configuration cache entry stored");
        assertThat(second.getOutput()).contains("Reusing configuration cache");
    }

    @Test
    void boot_run_receives_the_path_of_the_verified_agent() throws IOException {
        // given
        Path project = consumer("boot-run-args", springBootConsumer() + """
                tasks.register('captureBootRunAgent') {
                    dependsOn 'verifyControlledAgent'
                    doLast {
                        def bootRun = tasks.named('bootRun', JavaExec).get()
                        def arguments = bootRun.jvmArgumentProviders.collectMany { it.asArguments() }
                        file('build/boot-run-args.txt').text = arguments.join('\\n')
                    }
                }
                """, true);

        // when
        run(project, "captureBootRunAgent");

        // then
        assertThat(project.resolve("build/boot-run-args.txt"))
                .hasContent("-javaagent:" + agentJarIn(project).toRealPath());
    }

    private String javaConsumer() {
        return """
                plugins {
                    id 'java'
                    id '%s' version '%s'
                }
                """.formatted(PLUGIN_ID, PLUGIN_VERSION);
    }

    private String springBootConsumer() {
        return """
                plugins {
                    id 'java'
                    id 'org.springframework.boot'
                    id '%s' version '%s'
                }
                """.formatted(PLUGIN_ID, PLUGIN_VERSION);
    }

    private Path consumer(String name, String buildScript) throws IOException {
        return consumer(name, buildScript, false);
    }

    private Path consumer(String name, String buildScript, boolean springBootSubstitute) throws IOException {
        Path project = temporaryDirectory.resolve(name);
        Files.createDirectories(project);
        if (springBootSubstitute) {
            writeSpringBootSubstitute(project);
        }

        Files.writeString(project.resolve("settings.gradle"), """
                        pluginManagement {
                            repositories {
                                maven { url = uri('%s') }
                            }
                        }
                        dependencyResolutionManagement {
                            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                            repositories {
                                maven { url = uri('%s') }
                            }
                        }
                        rootProject.name = '%s'
                        """.formatted(
                        groovyUri(isolatedRepository), groovyUri(isolatedRepository), name),
                StandardCharsets.UTF_8);
        Files.writeString(project.resolve("build.gradle"), buildScript, StandardCharsets.UTF_8);
        return project;
    }

    private static void writeSpringBootSubstitute(Path project) throws IOException {
        Path buildSrc = project.resolve("buildSrc");
        Files.createDirectories(buildSrc.resolve("src/main/java"));
        Files.createDirectories(buildSrc.resolve("src/main/resources/META-INF/gradle-plugins"));

        Files.writeString(buildSrc.resolve("build.gradle"), """
                plugins {
                    id 'java'
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(
                buildSrc.resolve(
                        "src/main/resources/META-INF/gradle-plugins/"
                                + "org.springframework.boot.properties"),
                "implementation-class=SpringBootTaskSubstitutePlugin\n",
                StandardCharsets.UTF_8);

        Files.writeString(buildSrc.resolve("src/main/java/SpringBootTaskSubstitutePlugin.java"), """
                import org.gradle.api.Plugin;
                import org.gradle.api.Project;
                import org.gradle.api.tasks.JavaExec;
                import org.gradle.api.tasks.bundling.Jar;

                public final class SpringBootTaskSubstitutePlugin implements Plugin<Project> {
                    @Override
                    public void apply(Project project) {
                        project.getTasks().register("bootRun", JavaExec.class);
                        project.getTasks().register("bootJar", Jar.class);
                    }
                }
                """, StandardCharsets.UTF_8);
    }

    private BuildResult run(Path project, String... arguments) {
        return runner(project, arguments).build();
    }

    private BuildResult runAndFail(Path project, String... arguments) {
        return runner(project, arguments).buildAndFail();
    }

    private GradleRunner runner(Path project, String... arguments) {
        List<String> command = new ArrayList<>(List.of(arguments));
        command.add("--offline");
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
            return Files.createDirectories(TEST_KIT_DIRECTORY);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot create the shared TestKit directory: " + TEST_KIT_DIRECTORY, failure);
        }
    }

    private static TaskOutcome outcomeOf(BuildResult result, String taskPath) {
        var task = result.task(taskPath);
        if (task == null) {
            throw new IllegalStateException("Build did not run the expected task: " + taskPath);
        }

        return task.getOutcome();
    }

    private void installPlugin() throws IOException {
        Path pluginDirectory = mavenDirectory(PLUGIN_GROUP, PLUGIN_ARTIFACT);
        Files.copy(
                PLUGIN_JAR,
                pluginDirectory.resolve(PLUGIN_ARTIFACT + "-" + PLUGIN_VERSION + ".jar"),
                StandardCopyOption.REPLACE_EXISTING
        );

        Files.writeString(
                pluginDirectory.resolve(PLUGIN_ARTIFACT + "-" + PLUGIN_VERSION + ".pom"),
                mavenPom(PLUGIN_GROUP, PLUGIN_ARTIFACT, "jar", """
                          <dependencies>
                            <dependency>
                              <groupId>%s</groupId>
                              <artifactId>%s</artifactId>
                              <version>%s</version>
                              <scope>runtime</scope>
                            </dependency>
                          </dependencies>
                        """.formatted(COMMON_UTIL_GROUP, COMMON_UTIL_ARTIFACT, PLUGIN_VERSION)),
                StandardCharsets.UTF_8
        );

        installCommonUtil();

        Path markerDirectory = mavenDirectory(PLUGIN_ID, MARKER_ARTIFACT);
        Files.writeString(
                markerDirectory.resolve(MARKER_ARTIFACT + "-" + PLUGIN_VERSION + ".pom"),
                mavenPom(PLUGIN_ID, MARKER_ARTIFACT, "pom", """
                          <dependencies>
                            <dependency>
                              <groupId>%s</groupId>
                              <artifactId>%s</artifactId>
                              <version>%s</version>
                            </dependency>
                          </dependencies>
                        """.formatted(PLUGIN_GROUP, PLUGIN_ARTIFACT, PLUGIN_VERSION)),
                StandardCharsets.UTF_8
        );
    }

    private void installCommonUtil() throws IOException {
        Path commonUtilDirectory = mavenDirectory(COMMON_UTIL_GROUP, COMMON_UTIL_ARTIFACT);

        Files.copy(
                COMMON_UTIL_JAR,
                commonUtilDirectory.resolve(COMMON_UTIL_ARTIFACT + "-" + PLUGIN_VERSION + ".jar"),
                StandardCopyOption.REPLACE_EXISTING
        );

        Files.writeString(
                commonUtilDirectory.resolve(COMMON_UTIL_ARTIFACT + "-" + PLUGIN_VERSION + ".pom"),
                mavenPom(COMMON_UTIL_GROUP, COMMON_UTIL_ARTIFACT, "jar", ""),
                StandardCharsets.UTF_8
        );
    }

    private void installAgent(Path jar) throws IOException {
        Path agentDirectory = mavenDirectory(PLUGIN_GROUP, AGENT_ARTIFACT);

        Files.copy(
                jar,
                agentDirectory.resolve(agentFileName()),
                StandardCopyOption.REPLACE_EXISTING
        );

        Files.writeString(
                agentDirectory.resolve(AGENT_ARTIFACT + "-" + PLUGIN_VERSION + ".pom"),
                mavenPom(PLUGIN_GROUP, AGENT_ARTIFACT, "jar", ""),
                StandardCharsets.UTF_8
        );
    }

    private Path mavenDirectory(String group, String artifact) throws IOException {
        Path directory = isolatedRepository
                .resolve(group.replace('.', '/'))
                .resolve(artifact)
                .resolve(PLUGIN_VERSION);
        Files.createDirectories(directory);
        return directory;
    }

    private static String mavenPom(String group,
                                   String artifact,
                                   String packaging,
                                   String body
    ) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <packaging>%s</packaging>
                %s</project>
                """.formatted(group, artifact, PLUGIN_VERSION, packaging, body);
    }

    private static String agentFileName() {
        return AGENT_ARTIFACT + "-" + PLUGIN_VERSION + ".jar";
    }

    private static Path agentJarIn(Path project) {
        return project
                .resolve(AGENT_DIRECTORY)
                .resolve(agentFileName());
    }

    private static Properties loadPin() throws IOException {
        return loadProperties(requiredResource(PIN_RESOURCE));
    }

    private static Properties loadProperties(URL resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = resource.openStream()) {
            properties.load(stream);
        }

        return properties;
    }

    private static URL requiredResource(String name) {
        URL resource = PlatformTracingJavaAgentPluginFunctionalTest.class.getResource(name);
        if (resource == null) {
            throw new IllegalStateException("Plugin release is missing the resource: " + name);
        }

        return resource;
    }

    private static String groovyUri(Path path) {
        return path.toAbsolutePath().normalize().toUri().toString().replace("'", "\\'");
    }

    private static Path requiredPath(String name) {
        Path path = Path.of(requiredProperty(name));
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Required test file is missing: " + path);
        }

        return path;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (StringUtils.isNullOrBlank(value)) {
            throw new IllegalStateException("Required test system property is missing: " + name);
        }

        return value;
    }
}
