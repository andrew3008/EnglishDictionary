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
import java.util.stream.Collectors;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.common.util.StringUtils;

class JibAgentIntegrationFunctionalTest {

    private static final String PLUGIN_ID = "platform.tracing-javaagent";
    private static final String PLUGIN_GROUP = "space.br1440.platform.starters.tracing";
    private static final String PLUGIN_ARTIFACT = "platform-tracing-gradle-plugin";
    private static final String MARKER_ARTIFACT = PLUGIN_ID + ".gradle.plugin";
    private static final String AGENT_ARTIFACT = "platform-tracing-javaagent";
    private static final String COMMON_UTIL_GROUP = "space.br1440.platform.starters";
    private static final String COMMON_UTIL_ARTIFACT = "platform-starters-common-util";
    private static final String PIN_RESOURCE = "/META-INF/platform-tracing/controlled-agent-pin.properties";

    private static final String PLUGIN_VERSION = requiredProperty("platformTracing.pluginVersion");
    private static final Path PLUGIN_JAR = requiredPath("platformTracing.pluginJar");
    private static final Path COMMON_UTIL_JAR = requiredPath("platformTracing.commonUtilJar");
    private static final Path CONTROLLED_AGENT_JAR = requiredPath("platformTracing.controlledAgentJar");
    private static final Path TEST_KIT_DIRECTORY = Path.of(requiredProperty("platformTracing.testKitDirectory"));

    private static final String JDWP_FLAG = "-agentlib:jdwp=transport=dt_socket,address=*:5005,server=y,suspend=n";
    private static final String GRADLE_PLUGIN_PORTAL = "https://plugins.gradle.org/m2/";
    private static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2";

    @TempDir
    Path temporaryDirectory;

    private Path isolatedRepository;

    @BeforeEach
    void setUp() throws IOException {
        isolatedRepository = temporaryDirectory.resolve("m2");
        installPlugin();
        installAgent(CONTROLLED_AGENT_JAR);
    }

    @ParameterizedTest
    @CsvSource({
            "3.3.2, jib-first",
            "3.3.2, tracing-first",
            "3.4.4, jib-first",
            "3.4.4, tracing-first"
    })
    void late_assignment_in_jib_block_merges_consumer_flags_and_extra_directories(String jibVersion,
                                                                                  String pluginOrder) throws IOException {
        Path project = jibConsumer(
                "merge-" + jibVersion + "-" + pluginOrder,
                jibVersion,
                pluginOrder,
                """
                jib {
                    to { image = 'example/unused' }
                    container {
                        jvmFlags = ['%s']
                    }
                    extraDirectories {
                        paths {
                            path {
                                from = file('extra')
                                into = '/app/resources'
                            }
                        }
                    }
                }
                """.formatted(JDWP_FLAG)
        );

        BuildResult result = run(project, "captureJibLaunchContract");

        assertThat(outcomeOf(result, ":verifyJibLaunchContract")).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(outcomeOf(result, ":stageControlledAgentForJib")).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(Files.readAllBytes(stagedAgentJar(project)))
                .isEqualTo(Files.readAllBytes(CONTROLLED_AGENT_JAR));
        assertThat(Files.readAllBytes(versionedAgentJar(project)))
                .isEqualTo(Files.readAllBytes(CONTROLLED_AGENT_JAR));

        String captured = Files.readString(project.resolve("build/jib-launch-contract.txt"), StandardCharsets.UTF_8);
        assertThat(captured).contains(JDWP_FLAG);
        assertThat(captured).contains("-javaagent:/opt/platform-tracing/agent.jar");
        assertThat(captured).contains("-Dplatform.tracing.controlled-agent.expected-sha256=%s".formatted(pinSha256()));
        assertThat(captured).doesNotContain("-Dcom.sun.management");
        assertThat(captured).doesNotContain("-XX:+ManagementServer");
        assertThat(captured).doesNotContain("DisableAttachMechanism");
        assertThat(captured).contains("/app/resources");
        assertThat(captured).contains("/opt/platform-tracing");
    }

    @Test
    void unmanaged_otel_prefixed_key_is_preserved_not_rejected() throws IOException {
        String flag = "-Dotel.propagators.extra=value";
        Path project = jibConsumer(
                "otel-extra-key",
                "3.4.4",
                "tracing-first",
                """
                jib {
                    to { image = 'example/unused' }
                    container {
                        jvmFlags = ['%s']
                    }
                }
                """.formatted(flag)
        );

        BuildResult result = run(project, "captureJibLaunchContract");

        assertThat(outcomeOf(result, ":verifyJibLaunchContract")).isEqualTo(TaskOutcome.SUCCESS);
        String captured = Files.readString(project.resolve("build/jib-launch-contract.txt"), StandardCharsets.UTF_8);
        assertThat(captured).contains(flag);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-XX:+DisableAttachMechanism", "-XX:-DisableAttachMechanism"})
    void user_supplied_attach_flag_is_preserved(String attachFlag) throws IOException {
        Path project = jibConsumer(
                "attach-flag-" + Integer.toHexString(attachFlag.hashCode()),
                "3.4.4",
                "tracing-first",
                """
                jib {
                    to { image = 'example/unused' }
                    container {
                        jvmFlags = ['%s']
                    }
                }
                """.formatted(attachFlag)
        );

        BuildResult result = run(project, "captureJibLaunchContract");

        assertThat(outcomeOf(result, ":verifyJibLaunchContract")).isEqualTo(TaskOutcome.SUCCESS);
        String captured = Files.readString(project.resolve("build/jib-launch-contract.txt"), StandardCharsets.UTF_8);
        assertThat(captured.lines().filter(attachFlag::equals))
                .as("the attach flag is owned by the consumer and must be neither added nor removed")
                .hasSize(1);
    }

    @Test
    void plugin_without_jib_does_not_register_jib_tasks() throws IOException {
        Path project = consumerWithoutJib("no-jib");

        BuildResult result = runOffline(project, "prepareControlledAgent", "tasks", "--all");

        assertThat(outcomeOf(result, ":prepareControlledAgent")).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.getOutput()).doesNotContain("verifyJibLaunchContract");
        assertThat(versionedAgentJar(project)).isRegularFile();
        assertThat(project.resolve("build/platform-tracing/jib-agent")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.3.2", "3.4.4"})
    void after_evaluate_reassignment_of_jvm_flags_fails_closed(String jibVersion) throws IOException {
        Path project = jibConsumer(
                "replace-flags-" + jibVersion,
                jibVersion,
                "tracing-first",
                """
                jib {
                    to { image = 'example/unused' }
                }
                afterEvaluate {
                    jib.container.jvmFlags = ['%s']
                }
                """.formatted(JDWP_FLAG)
        );

        BuildResult result = runAndFail(project, "verifyJibLaunchContract");

        assertThat(result.getOutput())
                .contains("no longer contains the platform-tracing launch contract")
                .contains("-javaagent:/opt/platform-tracing/agent.jar");
    }

    @Test
    void competing_otel_propagators_system_property_fails_closed() throws IOException {
        Path project = jibConsumer(
                "otel-propagators",
                "3.4.4",
                "tracing-first",
                """
                jib {
                    to { image = 'example/unused' }
                    container {
                        jvmFlags = ['-Dotel.propagators=tracecontext']
                    }
                }
                """
        );

        assertHelpSucceedsWithoutValidatingJibConfiguration(project);

        BuildResult result = runAndFail(project, "verifyJibLaunchContract");

        assertThat(result.getOutput()).contains("otel.propagators");
    }

    @Test
    void otel_configuration_file_system_property_fails_closed() throws IOException {
        Path project = jibConsumer(
                "otel-config-file",
                "3.4.4",
                "tracing-first",
                """
                jib {
                    to { image = 'example/unused' }
                    container {
                        jvmFlags = ['-Dotel.javaagent.configuration-file=/app/resources/otel.properties']
                    }
                }
                """
        );

        assertHelpSucceedsWithoutValidatingJibConfiguration(project);

        BuildResult result = runAndFail(project, "verifyJibLaunchContract");

        assertThat(result.getOutput()).contains("otel.javaagent.configuration-file");
    }

    @Test
    void inherit_entrypoint_fails_closed_only_when_the_image_is_actually_built() throws IOException {
        Path project = jibConsumer(
                "inherit-entrypoint",
                "3.4.4",
                "tracing-first",
                """
                jib {
                    to { image = 'example/unused' }
                    container {
                        entrypoint = 'INHERIT'
                    }
                }
                """
        );

        assertHelpSucceedsWithoutValidatingJibConfiguration(project);

        BuildResult result = runAndFail(project, "jibBuildTar");

        assertThat(outcomeOf(result, ":verifyJibLaunchContract")).isEqualTo(TaskOutcome.FAILED);
        assertThat(result.getOutput()).contains("entrypoint").contains("INHERIT");
    }

    @Test
    void java_tool_options_in_image_environment_fails_closed() throws IOException {
        Path project = jibConsumer(
                "image-env",
                "3.4.4",
                "tracing-first",
                """
                jib {
                    to { image = 'example/unused' }
                    container {
                        environment = [JAVA_TOOL_OPTIONS: '-Xmx128m']
                    }
                }
                """
        );

        assertHelpSucceedsWithoutValidatingJibConfiguration(project);

        BuildResult result = runAndFail(project, "verifyJibLaunchContract");

        assertThat(result.getOutput()).contains("JAVA_TOOL_OPTIONS");
    }

    @Test
    void non_empty_plugin_extensions_fail_closed() throws IOException {
        Path project = jibConsumer(
                "plugin-extensions",
                "3.4.4",
                "tracing-first",
                """
                jib {
                    to { image = 'example/unused' }
                    pluginExtensions {
                        pluginExtension {
                            implementation = 'space.br1440.platform.tracing.gradle.testfixtures.NoopJibExtension'
                        }
                    }
                }
                """
        );

        assertHelpSucceedsWithoutValidatingJibConfiguration(project);

        BuildResult result = runAndFail(project, "verifyJibLaunchContract");

        assertThat(result.getOutput())
                .contains("jib.pluginExtensions")
                .contains("space.br1440.platform.tracing.gradle.testfixtures.NoopJibExtension");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-Dotel.propagators=tracecontext",
            "-Dotel.propagators",
            "-Dotel.propagators=tracecontext,baggage,b3",
            "-Dcom.sun.management.jmxremote.port=9010",
            "-Dcom.sun.management.config.file=/etc/management.properties",
            "-Dcom.sun.managementX=1"
    })
    void managed_or_uncertified_system_property_key_fails_closed_regardless_of_form(String flag) throws IOException {
        Path project = jibConsumer(
                "flag-conflict-" + Integer.toHexString(flag.hashCode()),
                "3.4.4",
                "tracing-first",
                """
                jib {
                    to { image = 'example/unused' }
                    container {
                        jvmFlags = ['%s']
                    }
                }
                """.formatted(flag)
        );

        BuildResult result = runAndFail(project, "verifyJibLaunchContract");

        assertThat(result.getOutput()).contains(flag);
    }

    @Test
    void duplicate_managed_javaagent_flag_fails_closed() throws IOException {
        Path project = jibConsumer(
                "duplicate-javaagent",
                "3.4.4",
                "tracing-first",
                """
                jib {
                    to { image = 'example/unused' }
                    container {
                        jvmFlags = ['-javaagent:/opt/platform-tracing/agent.jar']
                    }
                }
                """
        );

        BuildResult result = runAndFail(project, "verifyJibLaunchContract");

        assertThat(result.getOutput())
                .contains("more than once")
                .contains("-javaagent:/opt/platform-tracing/agent.jar");
    }

    @Test
    void foreign_javaagent_flag_fails_closed() throws IOException {
        Path project = jibConsumer(
                "foreign-javaagent",
                "3.4.4",
                "tracing-first",
                """
                jib {
                    to { image = 'example/unused' }
                    container {
                        jvmFlags = ['-javaagent:/opt/other/agent.jar']
                    }
                }
                """
        );

        BuildResult result = runAndFail(project, "verifyJibLaunchContract");

        assertThat(result.getOutput())
                .contains("second -javaagent")
                .contains("-javaagent:/opt/other/agent.jar");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-XX:+ManagementServer"})
    void forbidden_non_property_flag_fails_closed(String flag) throws IOException {
        Path project = jibConsumer(
                "forbidden-flag-" + Integer.toHexString(flag.hashCode()),
                "3.4.4",
                "tracing-first",
                """
                jib {
                    to { image = 'example/unused' }
                    container {
                        jvmFlags = ['%s']
                    }
                }
                """.formatted(flag)
        );

        BuildResult result = runAndFail(project, "verifyJibLaunchContract");

        assertThat(result.getOutput()).contains(flag);
    }

    private void assertHelpSucceedsWithoutValidatingJibConfiguration(Path project) {
        BuildResult result = run(project, "help");
        assertThat(outcomeOf(result, ":help")).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.getOutput()).doesNotContain(":verifyJibLaunchContract");
    }

    private Path jibConsumer(String name, String jibVersion, String pluginOrder, String jibBlock) throws IOException {
        String plugins = "jib-first".equals(pluginOrder)
                ? """
                plugins {
                    id 'java'
                    id 'com.google.cloud.tools.jib' version '%s'
                    id '%s' version '%s'
                }
                """.formatted(jibVersion, PLUGIN_ID, PLUGIN_VERSION)
                : """
                plugins {
                    id 'java'
                    id '%s' version '%s'
                    id 'com.google.cloud.tools.jib' version '%s'
                }
                """.formatted(PLUGIN_ID, PLUGIN_VERSION, jibVersion);

        Path project = consumer(name, plugins + jibBlock + """
                tasks.register('captureJibLaunchContract') {
                    dependsOn 'verifyJibLaunchContract'
                    doLast {
                        def flags = jib.container.jvmFlags.join('\\n')
                        def extras = jib.extraDirectories.paths.collect { it.into }.join('\\n')
                        file('build/jib-launch-contract.txt').text = flags + '\\n' + extras + '\\n'
                    }
                }
                """, true);

        Files.createDirectories(project.resolve("extra"));
        Files.writeString(project.resolve("extra/keep.txt"), "consumer-extra\n", StandardCharsets.UTF_8);
        return project;
    }

    @SuppressWarnings("SameParameterValue")
    private Path consumerWithoutJib(String name) throws IOException {
        return consumer(name, """
                plugins {
                    id '%s' version '%s'
                }
                """.formatted(PLUGIN_ID, PLUGIN_VERSION), false);
    }

    private Path consumer(String name, String buildScript, boolean jibRepositories) throws IOException {
        Path project = temporaryDirectory.resolve(name);
        Files.createDirectories(project);

        String isolated = groovyUri(isolatedRepository);

        List<String> pluginRepositoryUris = jibRepositories
                ? List.of(isolated, GRADLE_PLUGIN_PORTAL, MAVEN_CENTRAL)
                : List.of(isolated);

        List<String> projectRepositoryUris = jibRepositories
                ? List.of(isolated, MAVEN_CENTRAL, GRADLE_PLUGIN_PORTAL)
                : List.of(isolated);

        Files.writeString(project.resolve("settings.gradle"), """
                pluginManagement {
                    repositories {
                        %s
                    }
                }
                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        %s
                    }
                }
                rootProject.name = '%s'
                """.formatted(repositoryBlock(pluginRepositoryUris), repositoryBlock(projectRepositoryUris), name),
                StandardCharsets.UTF_8
        );

        Files.writeString(project.resolve("build.gradle"), buildScript, StandardCharsets.UTF_8);
        return project;
    }

    private static String repositoryBlock(List<String> uris) {
        return uris.stream()
                .map("maven { url = uri('%s') }"::formatted)
                .collect(Collectors.joining("\n"));
    }

    private BuildResult run(Path project, String... arguments) {
        return runner(project, false, arguments).build();
    }

    private BuildResult runOffline(Path project, String... arguments) {
        return runner(project, true, arguments).build();
    }

    private BuildResult runAndFail(Path project, String... arguments) {
        return runner(project, false, arguments).buildAndFail();
    }

    private GradleRunner runner(Path project, boolean offline, String... arguments) {
        List<String> command = new ArrayList<>(List.of(arguments));
        if (offline) {
            command.add("--offline");
        }

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
            String message = "Cannot create the shared TestKit directory: %s".formatted(TEST_KIT_DIRECTORY);
            throw new IllegalStateException(message, failure);
        }
    }

    private static TaskOutcome outcomeOf(BuildResult result, String taskPath) {
        var task = result.task(taskPath);
        if (task == null) {
            throw new IllegalStateException("Build did not run the expected task: %s".formatted(taskPath));
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

    @SuppressWarnings("SameParameterValue")
    private void installAgent(Path jar) throws IOException {
        Path agentDirectory = mavenDirectory(PLUGIN_GROUP, AGENT_ARTIFACT);
        Files.copy(jar, agentDirectory.resolve(agentFileName()), StandardCopyOption.REPLACE_EXISTING);
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

    private static String mavenPom(String group, String artifact, String packaging, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                    <packaging>%s</packaging>
                    %s
                </project>
                """.formatted(group, artifact, PLUGIN_VERSION, packaging, body);
    }

    private static String agentFileName() {
        return AGENT_ARTIFACT + "-" + PLUGIN_VERSION + ".jar";
    }

    private static Path versionedAgentJar(Path project) {
        return project.resolve("build/platform-tracing/agent").resolve(agentFileName());
    }

    private static Path stagedAgentJar(Path project) {
        return project.resolve("build/platform-tracing/jib-agent/agent.jar");
    }

    private static String pinSha256() throws IOException {
        return loadPin().getProperty("agent.sha256");
    }

    private static Properties loadPin() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = requiredResource(PIN_RESOURCE).openStream()) {
            properties.load(stream);
        }

        return properties;
    }

    @SuppressWarnings("SameParameterValue")
    private static URL requiredResource(String name) {
        URL resource = JibAgentIntegrationFunctionalTest.class.getResource(name);
        if (resource == null) {
            throw new IllegalStateException("Plugin release is missing the resource: %s".formatted(name));
        }

        return resource;
    }

    private static String groovyUri(Path path) {
        return path.toAbsolutePath().normalize().toUri().toString().replace("'", "\\'");
    }

    private static Path requiredPath(String name) {
        Path path = Path.of(requiredProperty(name));
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Required test file is missing: %s".formatted(path));
        }

        return path;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (StringUtils.isNullOrBlank(value)) {
            throw new IllegalStateException("Required test system property is missing: %s".formatted(name));
        }

        return value;
    }
}
