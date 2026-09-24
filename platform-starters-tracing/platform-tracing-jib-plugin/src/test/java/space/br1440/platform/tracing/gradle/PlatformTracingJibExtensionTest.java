package space.br1440.platform.tracing.gradle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.api.buildplan.ContainerBuildPlan;
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer;
import com.google.cloud.tools.jib.api.buildplan.LayerObject;
import com.google.cloud.tools.jib.gradle.JibExtension;
import com.google.cloud.tools.jib.gradle.extension.GradleData;
import com.google.cloud.tools.jib.plugins.extension.ExtensionLogger;
import com.google.cloud.tools.jib.plugins.extension.JibPluginExtensionException;

class PlatformTracingJibExtensionTest {

    private static final String PLATFORM_EXTENSION = PlatformTracingJibExtension.class.getName();
    private static final List<String> GENERATED_ENTRYPOINT = List.of("java", "-Xms32m", "-cp", "@/app/jib-classpath-file", "example.Main");
    private static final ExtensionLogger SILENT_LOGGER = (level, message) -> { };

    @TempDir
    Path temporaryDirectory;

    private Project project;
    private JibExtension jib;
    private Path agentJar;
    private String agentSha256;
    private final PlatformTracingJibExtension extension = new PlatformTracingJibExtension();

    @BeforeEach
    void setUp() throws IOException {
        project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.resolve("project").toFile()).build();
        jib = project.getExtensions().create("jib", JibExtension.class, project);
        registerPlatformExtension();

        agentJar = Files.writeString(temporaryDirectory.resolve("agent.jar"), "controlled-agent", StandardCharsets.UTF_8);
        agentSha256 = Sha256Digest.hexOf(agentJar);
        VerifyControlledAgentTask verify = project.getTasks()
                .register(PlatformTracingJibPlugin.TASK_VERIFY_CONTROLLED_AGENT, VerifyControlledAgentTask.class)
                .get();
        verify.getAgentDirectory().set(agentJar.getParent().toFile());
        verify.getAgentFileName().set(agentJar.getFileName().toString());
    }

    @Test
    void agent_layer_is_appended_and_managed_flags_precede_the_classpath_flag() throws Exception {
        // given
        FileEntriesLayer application = layer("application", "/app/classes/Main.class");
        ContainerBuildPlan plan = plan(GENERATED_ENTRYPOINT, Map.of("APP_MODE", "test"), application);

        // when
        ContainerBuildPlan result = extend(plan, properties());

        // then
        assertThat(result.getEntrypoint()).containsExactly(
                "java",
                "-Xms32m",
                "-javaagent:/opt/platform-tracing/agent.jar",
                "-Dplatform.tracing.controlled-agent.expected-sha256=" + agentSha256,
                "-cp",
                "@/app/jib-classpath-file",
                "example.Main"
        );
        assertThat(result.getLayers()).hasSize(3);
        assertThat(result.getLayers().get(2).getName()).isEqualTo("platform-tracing-agent");
        assertThat(((FileEntriesLayer) result.getLayers().get(2)).getEntries())
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getSourceFile()).isEqualTo(agentJar);
                    assertThat(entry.getExtractionPath().toString()).isEqualTo("/opt/platform-tracing/agent.jar");
                });
        assertThat(result.getLayers().get(1)).isSameAs(application);
        assertThat(result.getEnvironment()).containsEntry("APP_MODE", "test");
    }

    @Test
    void missing_property_fails_closed() throws Exception {
        String missing = PlatformTracingJibExtension.EXPECTED_SHA256_PROPERTY;
        // given
        Map<String, String> incomplete = new HashMap<>(properties());
        incomplete.remove(missing);
        ContainerBuildPlan plan = plan(GENERATED_ENTRYPOINT, Map.of());

        // when / then
        assertThatThrownBy(() -> extend(plan, incomplete))
                .isInstanceOf(JibPluginExtensionException.class)
                .hasMessageContaining("Extension property " + missing + " is missing");
    }

    @Test
    void blank_property_fails_closed() {
        // given
        Map<String, String> blank = Map.of(PlatformTracingJibExtension.EXPECTED_SHA256_PROPERTY, " ");

        // when / then
        assertThatThrownBy(() -> extend(plan(GENERATED_ENTRYPOINT, Map.of()), blank))
                .isInstanceOf(JibPluginExtensionException.class)
                .hasMessageContaining(PlatformTracingJibExtension.EXPECTED_SHA256_PROPERTY);
    }

    @Test
    void inherit_entrypoint_fails_closed() throws Exception {
        // given
        ContainerBuildPlan plan = plan(null, Map.of());

        // when / then
        assertThatThrownBy(() -> extend(plan, properties()))
                .isInstanceOf(JibPluginExtensionException.class)
                .hasMessageContaining("INHERIT");
    }

    @Test
    void custom_entrypoint_fails_closed() throws Exception {
        // given
        ContainerBuildPlan plan = plan(List.of("/custom-entry"), Map.of());

        // when / then
        assertThatThrownBy(() -> extend(plan, properties()))
                .isInstanceOf(JibPluginExtensionException.class)
                .hasMessageContaining("not a Jib Java classpath launch");
    }

    @Test
    void tampered_agent_bytes_fail_closed() throws IOException {
        // given
        Files.writeString(agentJar, "tampered", StandardCharsets.UTF_8);

        // when / then
        assertThatThrownBy(() -> extend(plan(GENERATED_ENTRYPOINT, Map.of()), properties()))
                .isInstanceOf(JibPluginExtensionException.class)
                .hasMessageContaining("does not match the pinned SHA-256");
    }

    @Test
    void missing_agent_file_fails_closed() throws IOException {
        // given
        Files.delete(agentJar);

        // when / then
        assertThatThrownBy(() -> extend(plan(GENERATED_ENTRYPOINT, Map.of()), properties()))
                .isInstanceOf(JibPluginExtensionException.class)
                .hasMessageContaining("Verified controlled agent is missing");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/opt/platform-tracing/agent.jar",
            "/opt/platform-tracing/other.jar",
            "/opt",
            "/opt/platform-tracing",
            "/opt/./platform-tracing/agent.jar",
            "/tmp/../opt/platform-tracing/agent.jar",
            "/opt/.wh.platform-tracing",
            "/.wh..wh..opq",
            "/app/.wh.config.yml"
    })
    void layer_entry_that_can_replace_or_delete_the_agent_fails_closed(String extractionPath) throws Exception {
        // given
        ContainerBuildPlan plan = plan(GENERATED_ENTRYPOINT, Map.of(), layer("consumer", extractionPath));

        // when / then
        assertThatThrownBy(() -> extend(plan, properties()))
                .isInstanceOf(JibPluginExtensionException.class)
                .hasMessageContaining("layer 'consumer'")
                .hasMessageContaining(extractionPath);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/opt/platform-tracing-other/agent.jar",
            "/opt/platform",
            "/app/resources/config.yml",
            "/app/libs/platform-tracing.jar"
    })
    void layer_entry_outside_the_platform_namespace_is_accepted(String extractionPath) {
        // given
        // when
        Optional<String> violation = PlatformTracingJibExtension.extractionPathViolation(AbsoluteUnixPath.get(extractionPath));

        // then
        assertThat(violation).isEmpty();
    }

    @Test
    void classpath_file_without_a_layer_entry_fails_closed() throws Exception {
        // given
        List<String> entrypoint = List.of("java", "-cp", "@/app/absent-classpath", "example.Main");
        ContainerBuildPlan plan = plan(entrypoint, Map.of());

        // when / then
        assertThatThrownBy(() -> extend(plan, properties()))
                .isInstanceOf(JibPluginExtensionException.class)
                .hasMessageContaining("exactly one image entry, found 0")
                .hasMessageContaining("/app/absent-classpath");
    }

    @Test
    void classpath_file_with_two_layer_entries_fails_closed() throws Exception {
        // given
        ContainerBuildPlan plan = plan(
                GENERATED_ENTRYPOINT,
                Map.of(),
                layer("shadow", "/app/jib-classpath-file"));

        // when / then
        assertThatThrownBy(() -> extend(plan, properties()))
                .isInstanceOf(JibPluginExtensionException.class)
                .hasMessageContaining("exactly one image entry, found 2")
                .hasMessageContaining("/app/jib-classpath-file");
    }

    @Test
    void opaque_layer_fails_closed() throws Exception {
        // given
        ContainerBuildPlan plan = ContainerBuildPlan.builder()
                .setBaseImage("scratch")
                .setEntrypoint(GENERATED_ENTRYPOINT)
                .addLayer(classpathLayer())
                .addLayer(new OpaqueLayer())
                .build();

        // when / then
        assertThatThrownBy(() -> extend(plan, properties()))
                .isInstanceOf(JibPluginExtensionException.class)
                .hasMessageContaining("layer 'opaque'")
                .hasMessageContaining("unsupported type");
    }

    @Test
    void all_layer_violations_are_reported_together_with_entrypoint_violations() throws Exception {
        // given
        ContainerBuildPlan plan = plan(
                List.of("java", "-XX:+ManagementServer", "-cp", "cp", "Main"),
                Map.of("JAVA_TOOL_OPTIONS", "-Xmx1g"),
                layer("first", "/opt/platform-tracing/agent.jar"),
                layer("second", "/opt/.wh.platform-tracing")
        );

        // when / then
        assertThatThrownBy(() -> extend(plan, properties()))
                .isInstanceOf(JibPluginExtensionException.class)
                .hasMessageContaining("-XX:+ManagementServer")
                .hasMessageContaining("JAVA_TOOL_OPTIONS")
                .hasMessageContaining("layer 'first'")
                .hasMessageContaining("layer 'second'");
    }

    @Test
    void duplicate_platform_registration_fails_closed() {
        // given
        registerPlatformExtension();

        // when / then
        assertThatThrownBy(() -> extend(plan(GENERATED_ENTRYPOINT, Map.of()), properties()))
                .isInstanceOf(JibPluginExtensionException.class)
                .hasMessageContaining("exactly one registration of " + PLATFORM_EXTENSION);
    }

    @Test
    void foreign_registration_fails_closed() {
        // given
        jib.pluginExtensions(spec -> spec.pluginExtension(parameters -> parameters.setImplementation("example.jib.Foreign")));

        // when / then
        assertThatThrownBy(() -> extend(plan(GENERATED_ENTRYPOINT, Map.of()), properties()))
                .isInstanceOf(JibPluginExtensionException.class)
                .hasMessageContaining("example.jib.Foreign");
    }

    @Test
    void registration_violation_requires_exactly_the_platform_entry() {
        // given
        // when / then
        assertThat(PlatformTracingJibExtension.registrationViolation(List.of(PLATFORM_EXTENSION))).isEmpty();
        assertThat(PlatformTracingJibExtension.registrationViolation(List.of())).isPresent();
        assertThat(PlatformTracingJibExtension.registrationViolation(List.of(PLATFORM_EXTENSION, PLATFORM_EXTENSION))).isPresent();
        assertThat(PlatformTracingJibExtension.registrationViolation(List.of("example.jib.Foreign", PLATFORM_EXTENSION))).isPresent();
        assertThat(PlatformTracingJibExtension.registrationViolation(List.of(PLATFORM_EXTENSION, "example.jib.Foreign"))).isPresent();
    }

    private ContainerBuildPlan extend(ContainerBuildPlan plan, Map<String, String> properties) throws JibPluginExtensionException {
        GradleData gradleData = () -> project;
        return extension.extendContainerBuildPlan(plan, properties, Optional.empty(), gradleData, SILENT_LOGGER);
    }

    private Map<String, String> properties() {
        return Map.of(PlatformTracingJibExtension.EXPECTED_SHA256_PROPERTY, agentSha256);
    }

    private void registerPlatformExtension() {
        jib.pluginExtensions(spec -> spec.pluginExtension(parameters -> parameters.setImplementation(PLATFORM_EXTENSION)));
    }

    private ContainerBuildPlan plan(List<String> entrypoint, Map<String, String> environment, FileEntriesLayer... layers) throws IOException {
        ContainerBuildPlan.Builder builder = ContainerBuildPlan.builder()
                .setBaseImage("scratch")
                .setEntrypoint(entrypoint)
                .setEnvironment(environment);
        builder.addLayer(classpathLayer());

        for (FileEntriesLayer layer : layers) {
            builder.addLayer(layer);
        }

        return builder.build();
    }

    private FileEntriesLayer classpathLayer() throws IOException {
        Path classpathFile = temporaryDirectory.resolve("jib-classpath-file");
        if (Files.notExists(classpathFile)) {
            Files.writeString(classpathFile, "/app/libs/*", StandardCharsets.UTF_8);
        }

        return FileEntriesLayer.builder()
                .setName("jvm arg files")
                .addEntry(classpathFile, AbsoluteUnixPath.get("/app/jib-classpath-file"))
                .build();
    }

    private static final class OpaqueLayer implements LayerObject {

        @Override
        public Type getType() {
            return Type.FILE_ENTRIES;
        }

        @Override
        public String getName() {
            return "opaque";
        }
    }

    private FileEntriesLayer layer(String name, String extractionPath) {
        return FileEntriesLayer.builder()
                .setName(name)
                .addEntry(temporaryDirectory.resolve("source.bin"), AbsoluteUnixPath.get(extractionPath))
                .build();
    }
}
