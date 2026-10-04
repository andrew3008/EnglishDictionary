package space.br1440.platform.tracing.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlatformTracingJibExtensionFunctionalTest extends FunctionalTestSupport {

    private static final String JIB_PINNED = "3.5.3";
    private static final String JIB_FLOOR = "3.3.2";
    private static final String JIB_SOLUTION = "3.4.4";
    private static final String JIB_PLUGIN_ID = "com.google.cloud.tools.jib";
    private static final String PLATFORM_EXTENSION_CLASS = "space.br1440.platform.tracing.gradle.PlatformTracingJibExtension";
    private static final String REGISTRATION_VIOLATION = "jib.pluginExtensions must contain exactly one registration of " + PLATFORM_EXTENSION_CLASS;
    private static final String IMAGE_TAR = "build/jib-image.tar";
    private static final String IMAGE_AGENT_PATH = "/opt/platform-tracing/agent.jar";
    private static final String CONSUMER_JVM_FLAG = "-Xms32m";

    @ParameterizedTest
    @ValueSource(strings = {"jib-first", "tracing-first"})
    void image_receives_the_agent_layer_and_the_managed_flags_after_the_java_launcher(String pluginOrder) throws IOException {
        // given
        Path project = jibConsumer("merge-" + pluginOrder, JIB_PINNED, pluginOrder, """
                jib {
                    from { image = 'scratch' }
                    to { image = 'example/unused' }
                    container {
                        mainClass = 'example.Main'
                        jvmFlags = ['%s']
                        environment = [APP_MODE: 'test']
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
                """.formatted(CONSUMER_JVM_FLAG));

        // when
        BuildResult result = runOnline(project, "jibBuildTar");

        // then
        assertThat(outcomeOf(result, VERIFY_TASK)).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(outcomeOf(result, ":jibBuildTar")).isEqualTo(TaskOutcome.SUCCESS);

        ImageTarInspector image = ImageTarInspector.read(project.resolve(IMAGE_TAR));
        List<String> entrypoint = image.entrypoint();
        assertThat(entrypoint)
                .startsWith("java", CONSUMER_JVM_FLAG, "-javaagent:" + IMAGE_AGENT_PATH, "-Dplatform.tracing.controlled-agent.expected-sha256=" + pinnedSha256())
                .contains("-cp", "example.Main");
        assertThat(entrypoint.stream().filter(token -> token.startsWith("-javaagent:")).toList()).hasSize(1);
        assertThat(entrypoint).noneMatch(token -> token.startsWith("-Dcom.sun.management") || token.equals("-XX:+ManagementServer"));
        assertThat(image.environment()).contains("APP_MODE=test");
        assertThat(image.fileInLayers(IMAGE_AGENT_PATH).orElseThrow())
                .as("the agent layer carries the verified bytes")
                .isEqualTo(Files.readAllBytes(CONTROLLED_AGENT_JAR));
        assertThat(image.layerContaining(IMAGE_AGENT_PATH)).isEqualTo(image.layers().getLast());
        assertThat(Sha256Digest.hexOf(writeLayerFile(project, image))).isEqualTo(pinnedSha256());
        assertThat(image.fileInLayers("/app/resources/keep.txt"))
                .as("consumer extra directories survive the extension")
                .isPresent();
    }

    @Test
    void floor_jib_version_receives_the_same_contract() throws IOException {
        // given
        Path project = jibConsumer("merge-floor", JIB_FLOOR, "tracing-first", minimalJibBlock(""));

        // when
        BuildResult result = runOnline(project, "jibBuildTar");

        // then
        assertThat(outcomeOf(result, ":jibBuildTar")).isEqualTo(TaskOutcome.SUCCESS);
        ImageTarInspector image = ImageTarInspector.read(project.resolve(IMAGE_TAR));
        assertThat(image.entrypoint())
                .startsWith("java", "-javaagent:" + IMAGE_AGENT_PATH, "-Dplatform.tracing.controlled-agent.expected-sha256=" + pinnedSha256());
        assertThat(image.fileInLayers(IMAGE_AGENT_PATH).orElseThrow()).isEqualTo(Files.readAllBytes(CONTROLLED_AGENT_JAR));
        assertThat(image.layerContaining(IMAGE_AGENT_PATH)).isEqualTo(image.layers().getLast());
        assertThat(Sha256Digest.hexOf(writeLayerFile(project, image))).isEqualTo(pinnedSha256());
    }

    @Test
    void agent_layer_is_added_exactly_once() throws IOException {
        // given
        Path withoutAgent = jibConsumerWithoutTracing("layers-baseline", JIB_PINNED);
        Path withAgent = jibConsumer("layers-agent", JIB_PINNED, "tracing-first", minimalJibBlock(""));

        // when
        runOnline(withoutAgent, "jibBuildTar");
        runOnline(withAgent, "jibBuildTar");

        // then
        int baseline = ImageTarInspector.read(withoutAgent.resolve(IMAGE_TAR)).layerCount();
        assertThat(ImageTarInspector.read(withAgent.resolve(IMAGE_TAR)).layerCount()).isEqualTo(baseline + 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-XX:+DisableAttachMechanism",
            "-XX:-DisableAttachMechanism"
    })
    void consumer_owned_flags_are_preserved_verbatim(String flag) throws IOException {
        // given
        Path project = jibConsumer("preserved-" + Integer.toHexString(flag.hashCode()), JIB_PINNED, "tracing-first",
                minimalJibBlock("jvmFlags = ['%s']".formatted(flag)));

        // when
        runOnline(project, "jibBuildTar");

        // then
        List<String> entrypoint = ImageTarInspector.read(project.resolve(IMAGE_TAR)).entrypoint();
        assertThat(entrypoint.stream().filter(flag::equals))
                .as("the flag is owned by the consumer and must be neither added nor removed")
                .hasSize(1);
    }

    @Test
    void plugin_without_jib_does_not_touch_jib_tasks() throws IOException {
        // given
        Path project = consumer("no-jib", """
                plugins {
                    id '%s' version '%s'
                }
                """.formatted(PLUGIN_ID, PLUGIN_VERSION));

        // when
        BuildResult result = run(project, "prepareControlledAgent", "tasks", "--all");

        // then
        assertThat(outcomeOf(result, PREPARE_TASK)).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.getOutput()).doesNotContain("jibBuildTar");
    }

    @Test
    void image_tasks_are_wired_to_the_verified_agent_only_when_jib_is_applied() throws IOException {
        // given
        Path project = jibConsumer("graph", JIB_PINNED, "jib-first", minimalJibBlock(""));

        // when
        BuildResult result = runOnline(project, "jibBuildTar", "--dry-run");

        // then
        assertThat(result.getOutput()).contains(VERIFY_TASK, ":jibBuildTar");
    }

    @Test
    void solution_jib_344_wires_verify_before_the_image_task() throws IOException {
        // given
        Path project = jibConsumer("graph-344", JIB_SOLUTION, "jib-first", minimalJibBlock(""));

        // when
        BuildResult result = runOnline(project, "jibBuildTar", "--dry-run");

        // then
        assertThat(result.getOutput()).contains(VERIFY_TASK, ":jibBuildTar");
    }

    @Test
    void solution_jib_344_packages_the_verified_agent_bytes() throws IOException {
        // given
        Path project = jibConsumer("image-344", JIB_SOLUTION, "tracing-first", minimalJibBlock(""));

        // when
        BuildResult result = runOnline(project, "jibBuildTar");

        // then
        assertThat(outcomeOf(result, VERIFY_TASK)).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(outcomeOf(result, ":jibBuildTar")).isEqualTo(TaskOutcome.SUCCESS);
        ImageTarInspector image = ImageTarInspector.read(project.resolve(IMAGE_TAR));
        List<String> agents = image.entrypoint().stream()
                .filter(token -> token.startsWith("-javaagent:"))
                .toList();
        assertThat(agents).containsExactly("-javaagent:" + IMAGE_AGENT_PATH);
        String expectedShaPrefix = "-Dplatform.tracing.controlled-agent.expected-sha256=";
        String expectedSha = image.entrypoint().stream()
                .filter(token -> token.startsWith(expectedShaPrefix))
                .map(token -> token.substring(expectedShaPrefix.length()))
                .findFirst()
                .orElseThrow();
        String packagedSha = Sha256Digest.hexOf(writeLayerFile(project, image));
        String verifiedSha = Sha256Digest.hexOf(CONTROLLED_AGENT_JAR);
        assertThat(packagedSha).isEqualTo(verifiedSha).isEqualTo(expectedSha);
    }

    @Test
    void inherit_entrypoint_fails_closed_when_the_image_is_built() throws IOException {
        // given
        Path project = jibConsumer("inherit-entrypoint", JIB_PINNED, "tracing-first", minimalJibBlock("entrypoint = 'INHERIT'"));
        assertHelpSucceeds(project);

        // when
        BuildResult result = runOnlineAndFail(project, "jibBuildTar");

        // then
        assertThat(result.getOutput()).contains("entrypoint is INHERIT");
        assertThat(project.resolve(IMAGE_TAR)).doesNotExist();
    }

    @Test
    void custom_entrypoint_fails_closed_when_the_image_is_built() throws IOException {
        // given
        Path project = jibConsumer("custom-entrypoint", JIB_PINNED, "tracing-first", minimalJibBlock("entrypoint = ['/custom-entry']"));

        // when
        BuildResult result = runOnlineAndFail(project, "jibBuildTar");

        // then
        assertThat(result.getOutput()).contains("not a Jib Java classpath launch").contains("/custom-entry");
        assertThat(project.resolve(IMAGE_TAR)).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "OTEL_SERVICE_NAME", "OTEL_JAVAAGENT_EXTENSIONS"})
    void reserved_image_environment_fails_closed(String name) throws IOException {
        // given
        Path project = jibConsumer("env-" + name.toLowerCase(), JIB_PINNED, "tracing-first",
                minimalJibBlock("environment = [%s: 'value']".formatted(name)));

        // when
        BuildResult result = runOnlineAndFail(project, "jibBuildTar");

        // then
        assertThat(result.getOutput()).contains("jib.container.environment must not set " + name);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-javaagent:/opt/platform-tracing/agent.jar",
            "-javaagent:/opt/other/agent.jar",
            "-XX:+ManagementServer",
            "-Dcom.sun.management.jmxremote.port=9010",
            "-Dcom.sun.management.config.file=/etc/management.properties",
            "-Dcom.sun.managementX=1",
            "-Dotel.propagators=tracecontext",
            "-Dotel.propagators",
            "-Dotel.javaagent.configuration-file=/app/resources/otel.properties",
            "-Dotel.javaagent.extensions=/app/ext.jar",
            "-Dplatform.tracing.controlled-agent.expected-sha256=0000"
    })
    void conflicting_jvm_flag_fails_closed(String flag) throws IOException {
        // given
        Path project = jibConsumer("flag-" + Integer.toHexString(flag.hashCode()), JIB_PINNED, "tracing-first",
                minimalJibBlock("jvmFlags = ['%s']".formatted(flag)));

        // when
        BuildResult result = runOnlineAndFail(project, "jibBuildTar");

        // then
        assertThat(result.getOutput()).contains("Jib launch contract violated").contains(flag);
        assertThat(project.resolve(IMAGE_TAR)).doesNotExist();
    }

    @Test
    void consumer_side_duplicate_registration_of_the_platform_extension_fails_closed() throws IOException {
        // given
        Path project = jibConsumer("duplicate-registration", JIB_PINNED, "tracing-first",
                minimalJibBlock("") + pluginExtensionBlock(PLATFORM_EXTENSION_CLASS));

        // when
        BuildResult result = runOnlineAndFail(project, "jibBuildTar");

        // then
        assertThat(result.getOutput()).contains(REGISTRATION_VIOLATION);
        assertThat(project.resolve(IMAGE_TAR)).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jib-first", "tracing-first"})
    void foreign_plugin_extension_fails_closed_regardless_of_its_position(String pluginOrder) throws IOException {
        // given
        String foreign = "example.jib.ForeignJibExtension";
        Path project = jibConsumer("foreign-extension-" + pluginOrder, JIB_PINNED, pluginOrder,
                minimalJibBlock("") + pluginExtensionBlock(foreign));

        // when
        BuildResult result = runOnlineAndFail(project, "jibBuildTar");

        // then
        assertThat(result.getOutput()).contains(REGISTRATION_VIOLATION).contains(foreign);
        assertThat(project.resolve(IMAGE_TAR)).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jib", "jibDockerBuild", "jibBuildTar"})
    void cleared_plugin_extensions_fail_closed_before_any_image_task_runs(String imageTask) throws IOException {
        // given
        Path project = jibConsumer("cleared-registrations-" + imageTask, JIB_PINNED, "tracing-first", minimalJibBlock("") + """
                jib.pluginExtensions = []
                """);

        // when
        BuildResult result = runOnlineAndFail(project, imageTask);

        // then
        assertThat(outcomeOf(result, ":" + imageTask)).isEqualTo(TaskOutcome.FAILED);
        assertThat(result.getOutput()).contains(REGISTRATION_VIOLATION).contains("found []");
        assertThat(project.resolve(IMAGE_TAR)).doesNotExist();
    }

    @Test
    void extra_directory_inside_the_platform_namespace_fails_closed() throws IOException {
        // given
        Path project = jibConsumer("namespace-collision", JIB_PINNED, "tracing-first", """
                jib {
                    from { image = 'scratch' }
                    to { image = 'example/unused' }
                    container { mainClass = 'example.Main' }
                    extraDirectories {
                        paths {
                            path {
                                from = file('extra')
                                into = '/opt/platform-tracing'
                            }
                        }
                    }
                }
                """);

        // when
        BuildResult result = runOnlineAndFail(project, "jibBuildTar");

        // then
        assertThat(result.getOutput())
                .contains("/opt/platform-tracing/keep.txt")
                .contains("owned by platform.tracing-jib");
        assertThat(project.resolve(IMAGE_TAR)).doesNotExist();
    }

    @Test
    void agent_tampered_after_verify_fails_the_image_build_on_a_clean_project() throws IOException {
        // given
        Path project = jibConsumer("tamper-clean", JIB_PINNED, "tracing-first", minimalJibBlock("") + tamperAgentTask());

        // when
        BuildResult result = runOnlineAndFail(project, "jibBuildTar", "-PtamperAgent");

        // then
        assertThat(outcomeOf(result, VERIFY_TASK)).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(outcomeOf(result, ":tamperAgent")).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(outcomeOf(result, ":jibBuildTar")).isEqualTo(TaskOutcome.FAILED);
        assertThat(result.getOutput()).contains("does not match the pinned SHA-256");
        assertThat(project.resolve(IMAGE_TAR)).doesNotExist();
    }

    @Test
    void agent_tampered_after_verify_is_detected_on_an_incremental_build_with_a_preserved_tar() throws IOException {
        // given
        Path project = jibConsumer("tamper-incremental", JIB_PINNED, "tracing-first", minimalJibBlock("") + tamperAgentTask());
        BuildResult first = runOnline(project, "jibBuildTar");
        assertThat(outcomeOf(first, ":tamperAgent")).isEqualTo(TaskOutcome.SKIPPED);
        assertThat(outcomeOf(first, ":jibBuildTar")).isEqualTo(TaskOutcome.SUCCESS);
        byte[] firstTar = Files.readAllBytes(project.resolve(IMAGE_TAR));

        // when
        BuildResult second = runOnlineAndFail(project, "jibBuildTar", "-PtamperAgent");

        // then
        assertThat(outcomeOf(second, VERIFY_TASK)).isEqualTo(TaskOutcome.UP_TO_DATE);
        assertThat(outcomeOf(second, ":tamperAgent")).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(outcomeOf(second, ":jibBuildTar")).isEqualTo(TaskOutcome.FAILED);
        assertThat(second.getOutput()).contains("does not match the pinned SHA-256");
        assertThat(Files.readAllBytes(project.resolve(IMAGE_TAR))).isEqualTo(firstTar);
    }

    @Test
    void image_build_is_up_to_date_when_nothing_changed() throws IOException {
        // given
        Path project = jibConsumer("image-up-to-date", JIB_PINNED, "tracing-first", minimalJibBlock(""));
        runOnline(project, "jibBuildTar");
        List<String> firstLayers = ImageTarInspector.read(project.resolve(IMAGE_TAR)).layers();

        // when
        BuildResult second = runOnline(project, "jibBuildTar");

        // then
        assertThat(outcomeOf(second, VERIFY_TASK)).isEqualTo(TaskOutcome.UP_TO_DATE);
        assertThat(outcomeOf(second, ":jibBuildTar")).isEqualTo(TaskOutcome.UP_TO_DATE);
        assertThat(ImageTarInspector.read(project.resolve(IMAGE_TAR)).layers()).isEqualTo(firstLayers);
    }

    @Test
    void packaged_mode_keeps_the_java_classpath_launch() throws IOException {
        // given
        Path project = jibConsumer("packaged", JIB_PINNED, "tracing-first", """
                jib {
                    containerizingMode = 'packaged'
                    from { image = 'scratch' }
                    to { image = 'example/unused' }
                    container { mainClass = 'example.Main' }
                }
                """);

        // when
        BuildResult result = runOnline(project, "jibBuildTar");

        // then
        assertThat(outcomeOf(result, ":jibBuildTar")).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(ImageTarInspector.read(project.resolve(IMAGE_TAR)).entrypoint())
                .contains("java", "-cp", "example.Main");
    }

    @Test
    void war_without_a_custom_base_image_is_rejected_as_a_jar_launch() throws IOException {
        // given
        String jibPlugin = "    id '%s' version '%s'".formatted(JIB_PLUGIN_ID, JIB_PINNED);
        String platformPlugin = "    id '%s' version '%s'".formatted(PLUGIN_ID, PLUGIN_VERSION);
        Path project = jibProject("war-default").create(temporaryDirectory, repository, """
                plugins {
                    id 'war'
                %s
                %s
                }
                jib {
                    to { image = 'example/unused' }
                }
                """.formatted(jibPlugin, platformPlugin));

        // when
        BuildResult result = runOnlineAndFail(project, "jibBuildTar");

        // then
        assertThat(result.getOutput())
                .contains("not a Jib Java classpath launch")
                .contains("/usr/local/jetty/start.jar")
                .doesNotContain("entrypoint is INHERIT");
        assertThat(project.resolve(IMAGE_TAR)).doesNotExist();
    }

    @Test
    void extra_directory_that_shadows_the_classpath_file_fails_closed() throws IOException {
        // given
        Path project = jibConsumer("classpath-shadow", JIB_PINNED, "tracing-first", """
                jib {
                    from { image = 'scratch' }
                    to { image = 'example/unused' }
                    container { mainClass = 'example.Main' }
                    extraDirectories {
                        paths {
                            path {
                                from = file('shadow')
                                into = '/app'
                            }
                        }
                    }
                }
                """);
        Path shadow = project.resolve("shadow");
        Files.createDirectories(shadow);
        Files.writeString(shadow.resolve("jib-classpath-file"), "/opt/platform-tracing/*\n");

        // when
        BuildResult result = runOnlineAndFail(project, "jibBuildTar");

        // then
        assertThat(result.getOutput()).contains("/app/jib-classpath-file");
        assertThat(project.resolve(IMAGE_TAR)).doesNotExist();
    }

    @Test
    void extra_classpath_inside_the_agent_directory_fails_closed() throws IOException {
        // given
        Path project = jibConsumer("extra-classpath", JIB_PINNED, "tracing-first", """
                jib {
                    from { image = 'scratch' }
                    to { image = 'example/unused' }
                    container {
                        mainClass = 'example.Main'
                        extraClasspath = ['/opt/platform-tracing/*']
                    }
                }
                """);

        // when
        BuildResult result = runOnlineAndFail(project, "jibBuildTar");

        // then
        assertThat(result.getOutput()).contains("/opt/platform-tracing");
        assertThat(project.resolve(IMAGE_TAR)).doesNotExist();
    }

    @Test
    void build_directory_changed_after_plugins_still_uses_the_verified_agent() throws IOException {
        // given
        Path project = jibConsumer("late-build-dir", JIB_PINNED, "tracing-first", """
                layout.buildDirectory.set(layout.projectDirectory.dir('custom-build'))
                """ + minimalJibBlock("") + """
                tasks.register('printExtensionProperties') {
                    doLast {
                        jib.pluginExtensions.get().each { parameters ->
                            parameters.properties.each { key, value ->
                                println("EXTENSION_PROPERTY ${key}=${value}")
                            }
                        }
                    }
                }
                """);

        // when
        BuildResult result = runOnline(project, "printExtensionProperties", "jibBuildTar");

        // then
        assertThat(result.getOutput().lines().filter(line -> line.contains("EXTENSION_PROPERTY")).toList())
                .anyMatch(line -> line.contains("EXTENSION_PROPERTY expectedSha256=" + pinnedSha256()))
                .noneMatch(line -> line.contains("custom-build") || line.contains(":\\") || line.contains(":/"));
        assertThat(project.resolve("build/platform-tracing")).doesNotExist();
        assertThat(project.resolve("custom-build/platform-tracing/agent").resolve(agentFileName())).isRegularFile();
        Path imageTar = Files.exists(project.resolve("custom-build/jib-image.tar"))
                ? project.resolve("custom-build/jib-image.tar")
                : project.resolve(IMAGE_TAR);
        ImageTarInspector image = ImageTarInspector.read(imageTar);
        assertThat(Sha256Digest.hexOf(writeLayerFile(project, image))).isEqualTo(pinnedSha256());
    }

    @Test
    void direct_image_build_fails_when_the_agent_is_an_implementation_dependency() throws IOException {
        // given
        Path project = jibConsumer("runtime-guard", JIB_PINNED, "tracing-first", minimalJibBlock("") + """
                dependencies {
                    implementation '%s:%s:%s'
                }
                """.formatted(PLUGIN_GROUP, AGENT_ARTIFACT, PLUGIN_VERSION));

        // when
        BuildResult result = runOnlineAndFail(project, "jibBuildTar");

        // then
        assertThat(outcomeOf(result, ":verifyAgentNotOnRuntimeClasspath")).isEqualTo(TaskOutcome.FAILED);
        assertThat(result.getOutput()).contains("must not be an application dependency");
        assertThat(project.resolve(IMAGE_TAR)).doesNotExist();
    }

    private static Path writeLayerFile(Path project, ImageTarInspector image) throws IOException {
        Path extracted = project.resolve("extracted-agent.jar");
        Files.write(extracted, image.fileInLayers(IMAGE_AGENT_PATH).orElseThrow());
        return extracted;
    }

    private static String pluginExtensionBlock(String implementation) {
        return """
                jib {
                    pluginExtensions {
                        pluginExtension {
                            implementation = '%s'
                        }
                    }
                }
                """.formatted(implementation);
    }

    private static String tamperAgentTask() {
        return """
                tasks.register('tamperAgent') {
                    mustRunAfter 'verifyControlledAgent'
                    def enabled = providers.gradleProperty('tamperAgent')
                    def agentJar = layout.buildDirectory.file('%s/%s')
                    outputs.file(agentJar)
                    onlyIf { enabled.isPresent() }
                    doLast { agentJar.get().asFile.text = 'tampered-after-verify' }
                }
                tasks.named('jibBuildTar') { dependsOn 'tamperAgent' }
                """.formatted(AGENT_DIRECTORY.substring("build/".length()), agentFileName());
    }

    private void assertHelpSucceeds(Path project) {
        BuildResult result = runOnline(project, "help");
        assertThat(outcomeOf(result, ":help")).isEqualTo(TaskOutcome.SUCCESS);
    }

    private static String minimalJibBlock(String containerLine) {
        return """
                jib {
                    from { image = 'scratch' }
                    to { image = 'example/unused' }
                    container {
                        mainClass = 'example.Main'
                        %s
                    }
                }
                """.formatted(containerLine);
    }

    private Path jibConsumer(String name, String jibVersion, String pluginOrder, String jibBlock) throws IOException {
        String jibPluginLine = "    id '%s' version '%s'".formatted(JIB_PLUGIN_ID, jibVersion);
        String platformPluginLine = "    id '%s' version '%s'".formatted(PLUGIN_ID, PLUGIN_VERSION);

        List<String> pluginLines = "jib-first".equals(pluginOrder)
                ? List.of(jibPluginLine, platformPluginLine)
                : List.of(platformPluginLine, jibPluginLine);

        String plugins = """
            plugins {
                id 'java'
            %s
            }
            """.formatted(String.join("\n", pluginLines));

        return jibProject(name).create(temporaryDirectory, repository, plugins + jibBlock);
    }

    @SuppressWarnings("SameParameterValue")
    private Path jibConsumerWithoutTracing(String name, String jibVersion) throws IOException {
        String buildScript = """
                plugins {
                    id 'java'
                    id '%s' version '%s'
                }
                """.formatted(JIB_PLUGIN_ID, jibVersion) + minimalJibBlock("");
        return jibProject(name).create(temporaryDirectory, repository, buildScript);
    }

    private static ConsumerProject jibProject(String name) {
        return ConsumerProject.groovy(name).withPublicJibRepositories().withExtraDirectory();
    }
}
