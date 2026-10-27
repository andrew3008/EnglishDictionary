package space.br1440.platform.tracing.gradle.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlledAgentDistributionContractTest {

    private static final Pattern TESTS = Pattern.compile("tests=\"(\\d+)\"");
    private static final Pattern FAILURES = Pattern.compile("failures=\"(\\d+)\"");
    private static final Pattern ERRORS = Pattern.compile("errors=\"(\\d+)\"");
    private static final Pattern SKIPPED = Pattern.compile("skipped=\"(\\d+)\"");
    private static final String UPSTREAM_GROUP = "io.opentelemetry.javaagent";
    private static final String UPSTREAM_MODULE = "synthetic-agent";
    private static final String UPSTREAM_VERSION = "1.0.0";
    private static final String DISTRIBUTION_VERSION = "1.2.3";

    @TempDir
    Path root;

    @Test
    void check_executes_both_suites_without_run_tests() throws IOException {
        // given
        writeFixture(FixtureOptions.passing());

        // when
        BuildResult result = gradle("check");

        // then
        assertThat(outcome(result, ":distribution:controlledAgentArtifactTest")).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(outcome(result, ":distribution:controlledAgentRuntimeTest")).isEqualTo(TaskOutcome.SUCCESS);
        assertCleanSuite(root.resolve("distribution/build/test-results/controlledAgentArtifactTest"));
        assertCleanSuite(root.resolve("distribution/build/test-results/controlledAgentRuntimeTest"));
    }

    @Test
    void check_reuses_configuration_cache() throws IOException {
        // given
        writeFixture(FixtureOptions.passing());

        // when
        BuildResult first = gradle(
                "--configuration-cache",
                "--configuration-cache-problems=fail",
                "--no-build-cache",
                "check");
        BuildResult second = gradle(
                "--configuration-cache",
                "--configuration-cache-problems=fail",
                "--no-build-cache",
                "check");

        // then
        assertThat(first.getOutput()).contains("Configuration cache entry stored");
        assertThat(second.getOutput()).contains("Reusing configuration cache");
        assertThat(outcome(first, ":distribution:controlledAgentArtifactTest")).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(outcome(first, ":distribution:controlledAgentRuntimeTest")).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(outcome(second, ":distribution:controlledAgentArtifactTest")).isEqualTo(TaskOutcome.UP_TO_DATE);
        assertThat(outcome(second, ":distribution:controlledAgentRuntimeTest")).isEqualTo(TaskOutcome.UP_TO_DATE);
    }

    @Test
    void extension_bytes_invalidate_distribution_and_suites_with_configuration_cache() throws IOException {
        // given
        writeFixture(FixtureOptions.passing());
        BuildResult first = cachedGradle("check", ":distribution:writeControlledAgentPin");
        Path agent = localAgentJar();
        String firstAgentSha256 = sha256(agent);
        String firstExtensionSha256 = distributionIdentity(agent).getProperty("extension.sha256");
        assertConsistentDistribution(root);

        // when
        Files.writeString(root.resolve("extension/src/main/resources/extension-content.txt"),
                "changed extension bytes, same project coordinate\n", StandardCharsets.UTF_8);
        BuildResult changed = cachedGradle("check", ":distribution:writeControlledAgentPin");

        // then
        assertThat(first.getOutput()).contains("Configuration cache entry stored");
        assertThat(changed.getOutput()).contains("Reusing configuration cache");
        for (String task : List.of("prepareControlledAgentIdentity", "assembleControlledJavaAgentJar",
                "promoteControlledAgent", "writeControlledAgentPin", "controlledAgentArtifactTest", "controlledAgentRuntimeTest")) {
            assertThat(outcome(changed, ":distribution:" + task)).as(task).isEqualTo(TaskOutcome.SUCCESS);
        }
        assertThat(sha256(agent)).isNotEqualTo(firstAgentSha256);
        assertThat(distributionIdentity(agent).getProperty("extension.sha256")).isNotEqualTo(firstExtensionSha256);
        assertConsistentDistribution(root);
        assertCleanSuite(root.resolve("distribution/build/test-results/controlledAgentArtifactTest"));
        assertCleanSuite(root.resolve("distribution/build/test-results/controlledAgentRuntimeTest"));
    }

    @Test
    void unreviewed_upstream_bytes_fail_before_distribution_outputs() throws IOException {
        // given
        writeFixture(FixtureOptions.passing());
        String reviewedSha256 = sha256(upstreamJar());
        writeUpstreamJar("unreviewed upstream bytes");

        // when
        BuildResult result = gradleAndFail(":distribution:writeControlledAgentPin");

        // then
        assertThat(result.getOutput()).contains("does not match reviewed pin", reviewedSha256, sha256(upstreamJar()));
        assertThat(outcome(result, ":distribution:prepareControlledAgentIdentity")).isEqualTo(TaskOutcome.FAILED);
        assertThat(result.task(":distribution:assembleControlledJavaAgentJar")).isNull();
        assertThat(result.task(":distribution:promoteControlledAgent")).isNull();
        assertThat(result.task(":distribution:writeControlledAgentPin")).isNull();
        assertThat(publishedJars(root.resolve("distribution/build/libs"))).isEmpty();
        assertThat(root.resolve("distribution/build/controlled-agent/controlled-agent-pin.properties")).doesNotExist();
    }

    @Test
    void independent_builds_produce_identical_jar_and_pin_without_build_cache(@TempDir Path independentRoot) throws IOException {
        // given
        writeFixture(FixtureOptions.passing());
        copyFixtureTo(independentRoot);

        // when
        BuildResult first = gradle("--no-build-cache", ":distribution:writeControlledAgentPin");
        BuildResult independent = runner(independentRoot, "--no-build-cache", ":distribution:writeControlledAgentPin").build();

        // then
        assertThat(outcome(first, ":distribution:assembleControlledJavaAgentJar")).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(outcome(independent, ":distribution:assembleControlledJavaAgentJar")).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(localAgentJar(independentRoot)).hasSameBinaryContentAs(localAgentJar());
        assertThat(independentRoot.resolve("distribution/build/controlled-agent/controlled-agent-pin.properties"))
                .hasSameBinaryContentAs(root.resolve("distribution/build/controlled-agent/controlled-agent-pin.properties"));
        assertConsistentDistribution(root);
        assertConsistentDistribution(independentRoot);
    }

    @Test
    void runtime_suite_owns_its_jmx_source_directory() throws IOException {
        // given
        writeFixture(FixtureOptions.passing());
        writeJmxSourceProducer();
        Path distributionBuild = root.resolve("distribution/build.gradle");
        Files.writeString(distributionBuild, Files.readString(distributionBuild, StandardCharsets.UTF_8) + """

                def runtimeSourceDirectories = sourceSets.controlledAgentRuntimeTest.java.srcDirs
                    .collect { directory -> directory.toPath().toAbsolutePath().normalize().toString() }
                    .sort()
                tasks.register('reportRuntimeSourceDirectories') {
                    inputs.property('runtimeSourceDirectories', runtimeSourceDirectories)
                    outputs.file(layout.buildDirectory.file('reports/runtime-source-directories.txt'))
                    doLast { task ->
                        def report = task.outputs.files.singleFile
                        report.parentFile.mkdirs()
                        report.setText(task.inputs.properties.runtimeSourceDirectories.join('\\n') + '\\n', 'UTF-8')
                    }
                }
                """, StandardCharsets.UTF_8);

        // when
        cachedGradle(":distribution:reportRuntimeSourceDirectories");

        // then
        List<Path> sourceDirectories = Files.readAllLines(
                        root.resolve("distribution/build/reports/runtime-source-directories.txt"), StandardCharsets.UTF_8)
                .stream()
                .map(Path::of)
                .toList();
        assertThat(sourceDirectories).isNotEmpty()
                .contains(root.resolve("distribution/build/generated/controlled-agent-runtime-test/jmx-contract")
                        .toAbsolutePath().normalize());
        Path producerBuild = root.resolve("jmx-producer/build").toAbsolutePath().normalize();
        assertThat(sourceDirectories).noneMatch(directory -> directory.startsWith(producerBuild));
    }

    @Test
    void generated_jmx_directory_tracks_creation_changes_deletion_and_relocation(@TempDir Path relocatedRoot) throws IOException {
        // given
        writeFixture(FixtureOptions.passing());
        writeJmxSourceProducer();

        // when
        BuildResult first = cachedGradle(":distribution:controlledAgentRuntimeTest");
        Path classes = root.resolve("distribution/build/classes/java/controlledAgentRuntimeTest/fixture");
        String firstClassSha256 = sha256(classes.resolve("GeneratedJmxContract.class"));
        writeJmxContract("changed");
        Path addedSource = root.resolve("jmx-producer/contracts/fixture/AddedJmxContract.java");
        Files.writeString(addedSource, "package fixture; public class AddedJmxContract {}\n", StandardCharsets.UTF_8);
        BuildResult changed = cachedGradle(":distribution:controlledAgentRuntimeTest");

        // then
        assertThat(first.getOutput()).contains("Configuration cache entry stored");
        assertJmxProducerAndRuntimeExecuted(first);
        assertThat(changed.getOutput()).contains("Reusing configuration cache");
        assertJmxProducerAndRuntimeExecuted(changed);
        assertThat(sha256(classes.resolve("GeneratedJmxContract.class"))).isNotEqualTo(firstClassSha256);
        assertThat(classes.resolve("AddedJmxContract.class")).isRegularFile();
        assertCleanSuite(root.resolve("distribution/build/test-results/controlledAgentRuntimeTest"));

        // when
        Files.delete(addedSource);
        BuildResult deleted = cachedGradle(":distribution:controlledAgentRuntimeTest");
        BuildResult unchanged = cachedGradle(":distribution:controlledAgentRuntimeTest");
        copyFixtureTo(relocatedRoot);
        BuildResult relocated = runner(relocatedRoot, "--no-build-cache", ":distribution:controlledAgentRuntimeTest").build();

        // then
        assertThat(deleted.getOutput()).contains("Reusing configuration cache");
        assertThat(classes.resolve("AddedJmxContract.class")).doesNotExist();
        Path syncedSources = root.resolve("distribution/build/generated/controlled-agent-runtime-test/jmx-contract/fixture");
        assertThat(syncedSources.resolve("AddedJmxContract.java")).doesNotExist();
        assertThat(syncedSources.resolve("GeneratedJmxContract.java")).isRegularFile();
        assertThat(outcome(deleted, ":jmx-producer:generateJmxContractSources")).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(outcome(deleted, ":distribution:compileControlledAgentRuntimeTestJava"))
                .isIn(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE);
        assertThat(outcome(deleted, ":distribution:controlledAgentRuntimeTest")).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(unchanged.getOutput()).contains("Reusing configuration cache");
        for (String task : List.of(":jmx-producer:generateJmxContractSources",
                ":distribution:compileControlledAgentRuntimeTestJava", ":distribution:controlledAgentRuntimeTest")) {
            assertThat(outcome(unchanged, task)).as(task).isEqualTo(TaskOutcome.UP_TO_DATE);
        }
        assertJmxProducerAndRuntimeExecuted(relocated);
        assertThat(relocatedRoot.resolve("distribution/build/classes/java/controlledAgentRuntimeTest/fixture/GeneratedJmxContract.class"))
                .hasSameBinaryContentAs(classes.resolve("GeneratedJmxContract.class"));
        assertThat(relocatedRoot.resolve("distribution/build/classes/java/controlledAgentRuntimeTest/fixture/AddedJmxContract.class"))
                .doesNotExist();
        assertCleanSuite(relocatedRoot.resolve("distribution/build/test-results/controlledAgentRuntimeTest"));
    }

    @Test
    void java_library_consumer_cannot_resolve_distribution() throws IOException {
        // given
        writeFixture(FixtureOptions.passing());
        Files.writeString(
                root.resolve("settings.gradle"),
                Files.readString(root.resolve("settings.gradle"), StandardCharsets.UTF_8)
                        + "include 'library-consumer'\n",
                StandardCharsets.UTF_8);
        Path source = root.resolve("library-consumer/src/main/java/fixture/Placeholder.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package fixture;

                class Placeholder {
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("library-consumer/build.gradle"), """
                plugins {
                    id 'java'
                }

                dependencies {
                    implementation project(':distribution')
                }
                """, StandardCharsets.UTF_8);

        // when
        BuildResult result = gradleAndFail(":library-consumer:compileJava");

        // then
        assertThat(result.getOutput()).contains("No matching variant of project :distribution");
    }

    @Test
    void consumer_resolves_jar_and_pin_without_assemble() throws IOException {
        // given
        writeFixture(FixtureOptions.passing());

        // when
        BuildResult result = gradle(":consumer:resolveControlledAgent", ":consumer:resolveControlledAgentPin");

        // then
        assertThat(outcome(result, ":distribution:promoteControlledAgent"))
                .isIn(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE);
        Path agent = singleResolvedFile(root.resolve("consumer/build/resolved-agent.txt"));
        Path pin = singleResolvedFile(root.resolve("consumer/build/resolved-pin.txt"));
        assertThat(agent).isRegularFile();
        assertThat(pin).isRegularFile();
        assertThat(Files.readString(pin, StandardCharsets.UTF_8))
                .isEqualTo("agent.sha256=%s\nagent.version=%s\n".formatted(sha256(agent), DISTRIBUTION_VERSION));
    }

    @Test
    void pin_only_consumer_matches_jar_sha_and_version() throws IOException {
        // given
        writeFixture(FixtureOptions.passing());

        // when
        BuildResult result = gradle(":consumer:resolveControlledAgentPin");

        // then
        assertThat(result.task(":consumer:resolveControlledAgent")).isNull();
        assertThat(outcome(result, ":distribution:assembleControlledJavaAgentJar"))
                .isIn(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE);
        assertThat(outcome(result, ":distribution:promoteControlledAgent"))
                .isIn(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE);
        assertThat(outcome(result, ":distribution:writeControlledAgentPin"))
                .isIn(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE);
        Path agent = localAgentJar();
        Properties pin = new Properties();
        try (Reader reader = Files.newBufferedReader(singleResolvedFile(root.resolve("consumer/build/resolved-pin.txt")), StandardCharsets.UTF_8)) {
            pin.load(reader);
        }
        assertThat(pin.getProperty("agent.version")).isEqualTo(DISTRIBUTION_VERSION);
        assertThat(pin.getProperty("agent.sha256")).isEqualTo(sha256(agent));
    }

    @Test
    void test_repository_publication_matches_final_jar() throws IOException {
        // given
        writeFixture(FixtureOptions.passing());

        // when
        gradle(":distribution:publishMavenJavaPublicationToTestRepositoryRepository");

        // then
        Path published = publishedJar(root.resolve("distribution/build/test-repository"));
        Path local = localAgentJar();
        assertThat(published).hasSameBinaryContentAs(local);
    }

    @Test
    void failing_suite_blocks_release_publication() throws IOException {
        // given
        writeFixture(FixtureOptions.failingArtifactSuite());

        // when
        BuildResult result = gradleAndFail(":distribution:publishMavenJavaPublicationToReleaseRepository");

        // then
        assertThat(outcome(result, ":distribution:controlledAgentArtifactTest")).isEqualTo(TaskOutcome.FAILED);
        assertThat(result.task(":distribution:publishMavenJavaPublicationToReleaseRepository")).isNull();
        assertThat(publishedJars(root.resolve("distribution/build/release-repository"))).isEmpty();
    }

    @Test
    void failing_runtime_suite_blocks_release_publication() throws IOException {
        // given
        writeFixture(FixtureOptions.failingRuntimeSuite());

        // when
        BuildResult result = gradleAndFail(":distribution:publishMavenJavaPublicationToReleaseRepository");

        // then
        assertThat(outcome(result, ":distribution:controlledAgentRuntimeTest")).isEqualTo(TaskOutcome.FAILED);
        assertThat(result.task(":distribution:publishMavenJavaPublicationToReleaseRepository")).isNull();
        assertThat(publishedJars(root.resolve("distribution/build/release-repository"))).isEmpty();
    }

    private BuildResult gradle(String... tasks) throws IOException {
        return runner(tasks).build();
    }

    private BuildResult gradleAndFail(String... tasks) throws IOException {
        return runner(tasks).buildAndFail();
    }

    private BuildResult cachedGradle(String... tasks) throws IOException {
        return gradle(Stream.concat(Stream.of("--configuration-cache", "--configuration-cache-problems=fail", "--no-build-cache"),
                Stream.of(tasks)).toArray(String[]::new));
    }

    private static Path testKitHome() throws IOException {
        return Files.createDirectories(Path.of("build", "controlled-agent-contract-test-kit").toAbsolutePath());
    }

    private GradleRunner runner(String... tasks) throws IOException {
        return runner(root, tasks);
    }

    private static GradleRunner runner(Path projectRoot, String... tasks) throws IOException {
        ArrayList<String> arguments = new ArrayList<>();
        arguments.add("--offline");
        arguments.add("--stacktrace");
        arguments.addAll(List.of(tasks));
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.keySet().removeIf(name -> name.equalsIgnoreCase("ORG_GRADLE_PROJECT_runTests")
                || name.equalsIgnoreCase("GRADLE_USER_HOME"));

        return GradleRunner.create()
                .withProjectDir(projectRoot.toFile())
                .withTestKitDir(testKitHome().toFile())
                .withPluginClasspath()
                .withArguments(arguments)
                .withEnvironment(environment)
                .forwardOutput();
    }

    private void writeFixture(FixtureOptions options) throws IOException {
        String upstreamSha256 = writeUpstreamRepository();
        copyJunitJars();
        writeSettings();
        writeVersionCatalog();
        writeExtension(upstreamSha256);
        writeDistribution(options);
        writeConsumer();
    }

    private String writeUpstreamRepository() throws IOException {
        writeUpstreamJar("reviewed upstream bytes");
        String sha256 = sha256(upstreamJar());
        Files.writeString(
                root.resolve(
                        "repo/io/opentelemetry/javaagent/" + UPSTREAM_MODULE + "/" + UPSTREAM_VERSION
                                + "/" + UPSTREAM_MODULE + "-" + UPSTREAM_VERSION + ".pom"),
                """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <project>
                          <modelVersion>4.0.0</modelVersion>
                          <groupId>%s</groupId>
                          <artifactId>%s</artifactId>
                          <version>%s</version>
                        </project>
                        """.formatted(UPSTREAM_GROUP, UPSTREAM_MODULE, UPSTREAM_VERSION),
                StandardCharsets.UTF_8);
        return sha256;
    }

    private Path upstreamJar() {
        return upstreamJar(root);
    }

    private static Path upstreamJar(Path projectRoot) {
        return projectRoot.resolve("repo/io/opentelemetry/javaagent/" + UPSTREAM_MODULE + "/" + UPSTREAM_VERSION
                + "/" + UPSTREAM_MODULE + "-" + UPSTREAM_VERSION + ".jar");
    }

    private void writeUpstreamJar(String content) throws IOException {
        Path jar = upstreamJar();
        Files.createDirectories(jar.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            JarEntry manifestEntry = new JarEntry("META-INF/MANIFEST.MF");
            manifestEntry.setTime(0);
            output.putNextEntry(manifestEntry);
            manifest.write(output);
            output.closeEntry();
            JarEntry payload = new JarEntry("upstream-content.txt");
            payload.setTime(0);
            output.putNextEntry(payload);
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private void writeSettings() throws IOException {
        Files.writeString(root.resolve("settings.gradle"), """
                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        maven { url = uri('repo') }
                    }
                }

                rootProject.name = 'controlled-agent-fixture'
                include 'distribution', 'extension', 'consumer'
                """, StandardCharsets.UTF_8);
    }

    private void writeVersionCatalog() throws IOException {
        Path catalog = root.resolve("gradle/libs.versions.toml");
        Files.createDirectories(catalog.getParent());
        Files.writeString(catalog, """
                [versions]
                bom-opentelemetry = "1.66.0"
                """, StandardCharsets.UTF_8);
    }

    private void writeExtension(String upstreamSha256) throws IOException {
        Path pin = root.resolve("extension/src/main/resources/META-INF/platform-tracing/otel-javaagent-acp-pin.properties");
        Files.createDirectories(pin.getParent());
        Files.writeString(pin, "otel.javaagent.sha256=" + upstreamSha256 + "\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("extension/build.gradle"), """
                plugins {
                    id 'java'
                }

                group = 'fixture.extension'
                version = '1.0.0'

                tasks.named('jar', Jar) {
                    preserveFileTimestamps = false
                    reproducibleFileOrder = true
                }

                def packaged = configurations.create('packagedJavaAgentExtensionElements') {
                    canBeConsumed = true
                    canBeResolved = false
                    attributes {
                        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_RUNTIME))
                        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, Category.LIBRARY))
                        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling, Bundling.SHADOWED))
                        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, 'javaagent-extension-jar'))
                    }
                }

                artifacts {
                    add(packaged.name, tasks.named('jar'))
                }
                """, StandardCharsets.UTF_8);
    }

    private void writeDistribution(FixtureOptions options) throws IOException {
        String plugins = """
                plugins {
                    id 'platform.javaagent-distribution'
                }
                """;
        String releaseRepository = options.releaseRepository()
                ? """
                publishing {
                    repositories {
                        maven {
                            name = 'release'
                            url = layout.buildDirectory.dir('release-repository')
                        }
                    }
                }
                """
                : "";
        String dependencies = """
                def junitJars = fileTree('junit') { include '*.jar' }

                dependencies {
                    otelJavaAgent 'io.opentelemetry.javaagent:synthetic-agent:1.0.0'
                    packagedJavaAgentExtension project(path: ':extension', configuration: 'packagedJavaAgentExtensionElements')

                    controlledAgentArtifactTestImplementation junitJars
                    controlledAgentRuntimeTestImplementation junitJars
                }
                """;
        Files.createDirectories(root.resolve("distribution"));
        Files.writeString(root.resolve("distribution/build.gradle"), plugins + """
                group = 'space.br1440.platform.starters.tracing'
                version = '%s'

                """.formatted(DISTRIBUTION_VERSION) + dependencies + releaseRepository, StandardCharsets.UTF_8);
        writeProbe(
                "distribution/src/controlledAgentArtifactTest/java/fixture/ArtifactProbeTest.java",
                options.failArtifactSuite());
        writeProbe(
                "distribution/src/controlledAgentRuntimeTest/java/fixture/RuntimeProbeTest.java",
                options.failRuntimeSuite());
    }

    private void writeProbe(String relativePath, boolean fail) throws IOException {
        Path source = root.resolve(relativePath);
        Files.createDirectories(source.getParent());
        String className = source.getFileName().toString().replace(".java", "");
        String body = fail
                ? """
                package fixture;

                import static org.junit.jupiter.api.Assertions.fail;

                import org.junit.jupiter.api.Test;

                class %s {

                    @Test
                    void fails_admission() {
                        fail("admission suite failed");
                    }
                }
                """.formatted(className)
                : """
                package fixture;

                import static org.junit.jupiter.api.Assertions.assertFalse;

                import org.junit.jupiter.api.Test;

                class %s {

                    @Test
                    void receives_controlled_agent_inputs() {
                        assertFalse(System.getProperty("platformTracing.controlledAgentJar").isBlank());
                        assertFalse(System.getProperty("platformTracing.extensionJar").isBlank());
                        assertFalse(System.getProperty("platformTracing.otelJavaAgentJar").isBlank());
                        assertFalse(System.getProperty("platformTracing.distributionVersion").isBlank());
                        assertFalse(System.getProperty("platformTracing.expectedOtelSdkVersion").isBlank());
                        assertFalse(System.getProperty("platformTracing.expectedUpstreamCoordinate").isBlank());
                    }
                }
                """.formatted(className);
        Files.writeString(source, body, StandardCharsets.UTF_8);
    }

    private void writeJmxSourceProducer() throws IOException {
        Path settings = root.resolve("settings.gradle");
        Files.writeString(settings, Files.readString(settings, StandardCharsets.UTF_8) + "include 'jmx-producer'\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("jmx-producer"));
        Files.writeString(root.resolve("jmx-producer/build.gradle"), """
                plugins {
                    id 'java-library'
                }

                def generatedDirectory = layout.buildDirectory.dir('generated/jmx-contract')
                def generateSources = tasks.register('generateJmxContractSources', Sync) {
                    from(layout.projectDirectory.dir('contracts'))
                    into(generatedDirectory)
                }
                def sources = configurations.create('jmxApplicationContractSourcesElements') {
                    canBeConsumed = true
                    canBeResolved = false
                    canBeDeclared = false
                    attributes {
                        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, 'platform-jmx-application-contract'))
                        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, Category.DOCUMENTATION))
                        attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType, DocsType.SOURCES))
                    }
                }
                artifacts {
                    add(sources.name, generatedDirectory) {
                        builtBy(generateSources)
                        type = 'directory'
                    }
                }
                """, StandardCharsets.UTF_8);
        Path distributionBuild = root.resolve("distribution/build.gradle");
        Files.writeString(distributionBuild, Files.readString(distributionBuild, StandardCharsets.UTF_8) + """

                dependencies {
                    jmxApplicationContractSources project(':jmx-producer')
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("distribution/src/controlledAgentRuntimeTest/java/fixture/JmxContractProbeTest.java"), """
                package fixture;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import java.io.IOException;
                import java.io.InputStream;
                import java.nio.charset.StandardCharsets;

                import org.junit.jupiter.api.Test;

                class JmxContractProbeTest {

                    @Test
                    void reads_generated_contract() throws IOException {
                        // given
                        String expected;
                        try (InputStream input = getClass().getResourceAsStream("/expected-jmx-value.txt")) {
                            expected = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                        }

                        // when
                        String actual = GeneratedJmxContract.value();

                        // then
                        assertEquals(expected, actual);
                    }
                }
                """, StandardCharsets.UTF_8);
        writeJmxContract("created");
    }

    private void writeJmxContract(String value) throws IOException {
        Path source = root.resolve("jmx-producer/contracts/fixture/GeneratedJmxContract.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package fixture;

                public class GeneratedJmxContract {

                    public static String value() {
                        return "%s";
                    }
                }
                """.formatted(value), StandardCharsets.UTF_8);
        Path expected = root.resolve("distribution/src/controlledAgentRuntimeTest/resources/expected-jmx-value.txt");
        Files.createDirectories(expected.getParent());
        Files.writeString(expected, value, StandardCharsets.UTF_8);
    }

    private static void assertJmxProducerAndRuntimeExecuted(BuildResult result) {
        for (String task : List.of(":jmx-producer:generateJmxContractSources",
                ":distribution:compileControlledAgentRuntimeTestJava", ":distribution:controlledAgentRuntimeTest")) {
            assertThat(outcome(result, task)).as(task).isEqualTo(TaskOutcome.SUCCESS);
        }
    }

    private static TaskOutcome outcome(BuildResult result, String taskPath) {
        return Objects.requireNonNull(result.task(taskPath), () -> "Gradle task is absent: " + taskPath).getOutcome();
    }

    private void copyFixtureTo(Path destination) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = root.relativize(file);
                if (isBuildOutput(relative)) {
                    continue;
                }
                Path target = destination.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
    }

    private static boolean isBuildOutput(Path relative) {
        for (Path segment : relative) {
            if (segment.toString().equals("build") || segment.toString().equals(".gradle")) {
                return true;
            }
        }
        return false;
    }

    private void writeConsumer() throws IOException {
        Files.createDirectories(root.resolve("consumer"));
        Files.writeString(root.resolve("consumer/build.gradle"), """
                plugins {
                    id 'base'
                }

                configurations {
                    controlledAgent {
                        canBeConsumed = false
                        canBeResolved = true
                    }
                    controlledAgentPin {
                        canBeConsumed = false
                        canBeResolved = true
                    }
                }

                dependencies {
                    controlledAgent project(path: ':distribution', configuration: 'controlledAgentElements')
                    controlledAgentPin project(path: ':distribution', configuration: 'controlledAgentPinElements')
                }

                tasks.register('resolveControlledAgent') {
                    def files = configurations.controlledAgent
                    inputs.files(files)
                    doLast {
                        def resolved = files.files
                        if (resolved.size() != 1) {
                            throw new GradleException("controlled agent resolved to ${resolved.size()} files")
                        }
                        def output = layout.buildDirectory.file('resolved-agent.txt').get().asFile
                        output.parentFile.mkdirs()
                        output.text = resolved[0].absolutePath
                    }
                }

                tasks.register('resolveControlledAgentPin') {
                    def files = configurations.controlledAgentPin
                    inputs.files(files)
                    doLast {
                        def resolved = files.files
                        if (resolved.size() != 1) {
                            throw new GradleException("controlled agent pin resolved to ${resolved.size()} files")
                        }
                        def output = layout.buildDirectory.file('resolved-pin.txt').get().asFile
                        output.parentFile.mkdirs()
                        output.text = resolved[0].absolutePath
                    }
                }
                """, StandardCharsets.UTF_8);
    }

    private void copyJunitJars() throws IOException {
        String raw = System.getProperty("fixture.junitJars");
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("fixture.junitJars is not set");
        }
        Path directory = root.resolve("distribution/junit");
        Files.createDirectories(directory);
        for (String entry : raw.split(File.pathSeparator)) {
            Path source = Path.of(entry);
            Path target = directory.resolve(source.getFileName());
            if (Files.exists(target)) {
                throw new IllegalStateException("Duplicate JUnit fixture jar name: " + target.getFileName());
            }
            Files.copy(source, target);
        }
    }

    private Path localAgentJar() throws IOException {
        return localAgentJar(root);
    }

    private static Path localAgentJar(Path projectRoot) throws IOException {
        try (Stream<Path> files = Files.list(projectRoot.resolve("distribution/build/libs"))) {
            List<Path> jars = files.filter(path -> path.getFileName().toString().startsWith("platform-tracing-javaagent-"))
                    .toList();
            assertThat(jars).hasSize(1);
            return jars.getFirst();
        }
    }

    private static Properties distributionIdentity(Path agent) throws IOException {
        try (JarFile jar = new JarFile(agent.toFile());
                InputStream input = jar.getInputStream(jar.getJarEntry("META-INF/platform-tracing/javaagent-distribution.properties"))) {
            Properties identity = new Properties();
            identity.load(input);
            return identity;
        }
    }

    private static void assertConsistentDistribution(Path projectRoot) throws IOException {
        Path agent = localAgentJar(projectRoot);
        Path extension = projectRoot.resolve("extension/build/libs/extension-1.0.0.jar");
        Properties identity = distributionIdentity(agent);
        assertThat(identity.getProperty("distribution.version")).isEqualTo(DISTRIBUTION_VERSION);
        assertThat(identity.getProperty("upstream.coordinate"))
                .isEqualTo(UPSTREAM_GROUP + ":" + UPSTREAM_MODULE + ":" + UPSTREAM_VERSION);
        assertThat(identity.getProperty("upstream.sha256")).isEqualTo(sha256(upstreamJar(projectRoot)));
        assertThat(identity.getProperty("extension.sha256")).isEqualTo(sha256(extension));
        try (JarFile jar = new JarFile(agent.toFile());
                InputStream embedded = jar.getInputStream(jar.getJarEntry(identity.getProperty("extension.path")))) {
            assertThat(embedded.readAllBytes()).isEqualTo(Files.readAllBytes(extension));
        }
        assertThat(Files.readString(projectRoot.resolve("distribution/build/controlled-agent/controlled-agent-pin.properties"),
                StandardCharsets.UTF_8)).isEqualTo("agent.sha256=%s\nagent.version=%s\n".formatted(sha256(agent), DISTRIBUTION_VERSION));
    }

    private static Path publishedJar(Path repository) throws IOException {
        List<Path> jars = publishedJars(repository);
        assertThat(jars).hasSize(1);
        return jars.getFirst();
    }

    private static List<Path> publishedJars(Path repository) throws IOException {
        if (!Files.isDirectory(repository)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(repository)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".jar")).toList();
        }
    }

    private static Path singleResolvedFile(Path pointer) throws IOException {
        return Path.of(Files.readString(pointer, StandardCharsets.UTF_8).trim());
    }

    private static void assertCleanSuite(Path resultsDirectory) throws IOException {
        JunitReport report = junitReport(resultsDirectory);
        assertThat(report.tests()).isPositive();
        assertThat(report.failures()).isZero();
        assertThat(report.errors()).isZero();
        assertThat(report.skipped()).isZero();
    }

    private static JunitReport junitReport(Path resultsDirectory) throws IOException {
        if (!Files.isDirectory(resultsDirectory)) {
            return new JunitReport(0, 0, 0, 0);
        }
        int tests = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        try (Stream<Path> files = Files.list(resultsDirectory)) {
            for (Path report : files.filter(path -> path.getFileName().toString().endsWith(".xml")).toList()) {
                String xml = Files.readString(report, StandardCharsets.UTF_8);
                tests += firstCount(xml, TESTS);
                failures += firstCount(xml, FAILURES);
                errors += firstCount(xml, ERRORS);
                skipped += firstCount(xml, SKIPPED);
            }
        }
        return new JunitReport(tests, failures, errors, skipped);
    }

    private static int firstCount(String xml, Pattern pattern) {
        Matcher matcher = pattern.matcher(xml);
        if (!matcher.find()) {
            return 0;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private record JunitReport(int tests, int failures, int errors, int skipped) {
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is mandatory for every Java SE platform", exception);
        }
    }

    private record FixtureOptions(boolean failArtifactSuite, boolean failRuntimeSuite, boolean releaseRepository) {

        private static FixtureOptions passing() {
            return new FixtureOptions(false, false, false);
        }

        private static FixtureOptions failingArtifactSuite() {
            return new FixtureOptions(true, false, true);
        }

        private static FixtureOptions failingRuntimeSuite() {
            return new FixtureOptions(false, true, true);
        }
    }
}
