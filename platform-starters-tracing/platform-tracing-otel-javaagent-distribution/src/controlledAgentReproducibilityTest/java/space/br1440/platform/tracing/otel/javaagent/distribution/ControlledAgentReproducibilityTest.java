package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlledAgentReproducibilityTest {

    private static final String EXTENSION_PROJECT =
            ":platform-starters-tracing:platform-tracing-otel-javaagent-extension";
    private static final String DISTRIBUTION_PROJECT =
            ":platform-starters-tracing:platform-tracing-otel-javaagent-distribution";
    private static final String EXTENSION_TASK = EXTENSION_PROJECT + ":jar";
    private static final String ASSEMBLY_TASK = DISTRIBUTION_PROJECT
            + ":assembleControlledJavaAgent";
    private static final String PUBLISH_TASK = DISTRIBUTION_PROJECT
            + ":publishControlledAgentPublicationToMavenLocal";
    private static final List<String> CLEAN_ASSEMBLY_TASKS = List.of(
            EXTENSION_PROJECT + ":clean",
            DISTRIBUTION_PROJECT + ":clean",
            EXTENSION_TASK,
            ASSEMBLY_TASK);
    private static final List<String> ASSEMBLY_TASKS = List.of(EXTENSION_TASK, ASSEMBLY_TASK);
    private static final String EXTENSION_ENTRY =
            "extensions/platform-tracing-otel-javaagent-extension.jar";
    private static final String CONVENTION_PLUGIN_COORDINATE =
            "space.br1440.platform.conventions:platform-gradle-conventions:";
    private static final List<String> CONVENTION_PLUGIN_IDS = List.of(
            "platform.java-base",
            "platform.java-library",
            "platform.java-library-publish",
            "platform.java-platform-publish",
            "platform.testing");

    @TempDir
    Path temporaryDirectory;

    @Test
    void isolated_builds_with_different_paths_mtimes_locales_and_timezones_are_byte_identical()
            throws IOException, InterruptedException {
        // given
        Path conventionPluginJar = requiredPath("platformTracing.conventionPluginJar");
        String conventionPluginVersion = requiredProperty(
                "platformTracing.conventionPluginVersion");
        Path platformBomModule = requiredPath("platformTracing.platformBomModule");
        String platformBomVersion = requiredProperty("platformTracing.platformBomVersion");
        Fixture first = prepareFixture(
                "first-checkout",
                Instant.parse("2020-01-02T03:04:05Z"),
                "en_US.UTF-8",
                "en",
                "US",
                "UTC",
                conventionPluginJar,
                conventionPluginVersion,
                platformBomModule,
                platformBomVersion);
        Fixture second = prepareFixture(
                "second-checkout-with-different-path",
                Instant.parse("2035-06-07T08:09:10Z"),
                "tr_TR.UTF-8",
                "tr",
                "TR",
                "Pacific/Auckland",
                conventionPluginJar,
                conventionPluginVersion,
                platformBomModule,
                platformBomVersion);
        assertThat(findBuildDirectories(first.checkout())).isEmpty();
        assertThat(findBuildDirectories(second.checkout())).isEmpty();
        assertThat(directoryEntries(first.gradleHome())).isEmpty();
        assertThat(directoryEntries(second.gradleHome())).isEmpty();
        assertThat(first.readOnlyDependencyCacheRoot())
                .isEqualTo(second.readOnlyDependencyCacheRoot());
        assertThat(first.readOnlyDependencyCacheRoot().startsWith(first.gradleHome())).isFalse();
        assertThat(second.readOnlyDependencyCacheRoot().startsWith(second.gradleHome())).isFalse();

        // when
        ProcessResult firstStore = runCheckout(
                first, "configuration-cache-store-clean", true, CLEAN_ASSEMBLY_TASKS);
        ProcessResult firstReuseAndExecute = runCheckout(
                first, "configuration-cache-reuse-clean", true, CLEAN_ASSEMBLY_TASKS);
        ProcessResult firstStoreAndUpToDate = runCheckout(
                first, "configuration-cache-store-up-to-date", true, ASSEMBLY_TASKS);
        ProcessResult firstSecondReuseAndUpToDate = runCheckout(
                first, "configuration-cache-reuse-up-to-date", true, ASSEMBLY_TASKS);
        ProcessResult secondCleanBuild = runCheckout(
                second, "isolated-clean-build", true, CLEAN_ASSEMBLY_TASKS);
        ProcessResult firstPublish = runCheckout(first, "publish", false, List.of(PUBLISH_TASK));
        ProcessResult firstRoundTrip = runConsumer(first);
        ProcessResult secondPublish = runCheckout(second, "publish", false, List.of(PUBLISH_TASK));
        ProcessResult secondRoundTrip = runConsumer(second);

        // then
        assertSuccessful(firstStore, "First configuration-cache store build");
        assertSuccessful(firstReuseAndExecute, "Configuration-cache reuse and execute build");
        assertSuccessful(firstStoreAndUpToDate, "Configuration-cache store and UP-TO-DATE build");
        assertSuccessful(
                firstSecondReuseAndUpToDate,
                "Second configuration-cache UP-TO-DATE build");
        assertSuccessful(secondCleanBuild, "Second checkout clean build");
        assertSuccessful(firstPublish, "First isolated publication");
        assertSuccessful(firstRoundTrip, "First isolated publication resolution");
        assertSuccessful(secondPublish, "Second isolated publication");
        assertSuccessful(secondRoundTrip, "Second isolated publication resolution");

        assertThat(firstStore.output()).contains("Configuration cache entry stored.");
        assertTaskExecuted(firstStore, EXTENSION_TASK);
        assertTaskExecuted(firstStore, ASSEMBLY_TASK);
        assertThat(firstReuseAndExecute.output()).contains("Reusing configuration cache.");
        assertTaskExecuted(firstReuseAndExecute, EXTENSION_TASK);
        assertTaskExecuted(firstReuseAndExecute, ASSEMBLY_TASK);
        assertThat(firstStoreAndUpToDate.output()).contains("Configuration cache entry stored.");
        assertTaskUpToDate(firstStoreAndUpToDate, EXTENSION_TASK);
        assertTaskUpToDate(firstStoreAndUpToDate, ASSEMBLY_TASK);
        assertThat(firstSecondReuseAndUpToDate.output()).contains("Reusing configuration cache.");
        assertTaskUpToDate(firstSecondReuseAndUpToDate, EXTENSION_TASK);
        assertTaskUpToDate(firstSecondReuseAndUpToDate, ASSEMBLY_TASK);
        assertTaskExecuted(secondCleanBuild, EXTENSION_TASK);
        assertTaskExecuted(secondCleanBuild, ASSEMBLY_TASK);
        assertTaskExecuted(firstPublish, PUBLISH_TASK);
        assertTaskExecuted(secondPublish, PUBLISH_TASK);
        assertTaskExecuted(firstRoundTrip, ":resolvePublishedControlledAgent");
        assertTaskExecuted(secondRoundTrip, ":resolvePublishedControlledAgent");

        assertArtifactOnlyPublication(first);
        assertArtifactOnlyPublication(second);
        byte[] firstOutput = Files.readAllBytes(first.output());
        byte[] secondOutput = Files.readAllBytes(second.output());
        byte[] firstExtension = Files.readAllBytes(first.extension());
        byte[] secondExtension = Files.readAllBytes(second.extension());
        byte[] firstInner = readEntry(first.output(), EXTENSION_ENTRY);
        byte[] secondInner = readEntry(second.output(), EXTENSION_ENTRY);
        byte[] firstPublished = Files.readAllBytes(first.publishedJar());
        byte[] secondPublished = Files.readAllBytes(second.publishedJar());
        byte[] firstResolved = Files.readAllBytes(singleJar(first.roundTripDirectory()));
        byte[] secondResolved = Files.readAllBytes(singleJar(second.roundTripDirectory()));

        assertThat(first.checkout()).isNotEqualTo(second.checkout());
        assertThat(Files.getLastModifiedTime(first.checkout().resolve("build.gradle")))
                .isNotEqualTo(Files.getLastModifiedTime(second.checkout().resolve("build.gradle")));
        assertThat(first.locale()).isNotEqualTo(second.locale());
        assertThat(first.timeZone()).isNotEqualTo(second.timeZone());
        assertThat(firstInner).isEqualTo(firstExtension);
        assertThat(secondInner).isEqualTo(secondExtension);
        assertThat(firstInner).isEqualTo(secondInner);
        assertThat(sha256Hex(firstInner)).isEqualTo(sha256Hex(secondInner));
        assertThat(firstOutput).isEqualTo(secondOutput);
        assertThat(sha256Hex(firstOutput)).isEqualTo(sha256Hex(secondOutput));
        assertThat(firstPublished).isEqualTo(firstOutput).isEqualTo(firstResolved);
        assertThat(secondPublished).isEqualTo(secondOutput).isEqualTo(secondResolved);
        assertThat(firstResolved).isEqualTo(secondResolved);
        assertThat(sha256Hex(firstResolved)).isEqualTo(sha256Hex(secondResolved));
    }

    private Fixture prepareFixture(
            String name,
            Instant sourceMtime,
            String locale,
            String language,
            String country,
            String timeZone,
            Path conventionPluginJar,
            String conventionPluginVersion,
            Path platformBomModule,
            String platformBomVersion
    ) throws IOException {
        String safeConventionPluginVersion = groovyString(conventionPluginVersion);
        String safePlatformBomVersion = groovyString(platformBomVersion);
        String platformBomFileName =
                "platform-bom-" + safePlatformBomVersion + ".module";
        if (!platformBomModule.getFileName().toString().equals(platformBomFileName)) {
            throw new IllegalStateException(
                    "Unexpected platform BOM module file: " + platformBomModule);
        }
        Path fixtureRoot = temporaryDirectory.resolve(name);
        Path checkout = fixtureRoot.resolve("repository");
        copyCheckout(repositoryRoot(), checkout);
        setTreeMtime(checkout, FileTime.from(sourceMtime));
        Path gradleHome = fixtureRoot.resolve("gradle-home");
        Files.createDirectories(gradleHome);
        Path consumer = fixtureRoot.resolve("consumer");
        Files.createDirectories(consumer);
        Path conventionRepository = fixtureRoot.resolve("convention-plugin-repository");
        Files.createDirectories(conventionRepository);
        Path localConventionPlugin = conventionRepository.resolve(
                "platform-gradle-conventions-" + safeConventionPluginVersion + ".jar");
        Files.copy(conventionPluginJar, localConventionPlugin);
        Files.setLastModifiedTime(localConventionPlugin, FileTime.from(sourceMtime));
        Path dependencyRepository = fixtureRoot.resolve("bootstrap-dependency-repository");
        Path localPlatformBomModule = dependencyRepository
                .resolve("space/br1440/platform/platform-bom")
                .resolve(safePlatformBomVersion)
                .resolve(platformBomFileName);
        Files.createDirectories(localPlatformBomModule.getParent());
        Files.copy(platformBomModule, localPlatformBomModule);
        Files.setLastModifiedTime(localPlatformBomModule, FileTime.from(sourceMtime));
        Path initScript = fixtureRoot.resolve("isolated-build.init.gradle");
        Files.writeString(
                initScript,
                isolatedBuildInitScript(
                        conventionRepository,
                        safeConventionPluginVersion,
                        dependencyRepository,
                        safePlatformBomVersion),
                StandardCharsets.UTF_8);
        String version = requiredProperty("platformTracing.distributionVersion");
        Path readOnlyDependencyCacheRoot = requiredDependencyCacheRoot(
                "platformTracing.readOnlyDependencyCacheRoot");
        Files.writeString(
                consumer.resolve("settings.gradle"),
                "rootProject.name = 'controlled-agent-round-trip-consumer'\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                consumer.resolve("build.gradle"),
                consumerBuildScript(version),
                StandardCharsets.UTF_8);

        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.remove("GRADLE_USER_HOME");
        environment.remove("JAVA_TOOL_OPTIONS");
        environment.remove("_JAVA_OPTIONS");
        environment.remove("JDK_JAVA_OPTIONS");
        environment.put("JAVA_HOME", Path.of(System.getProperty("java.home")).toString());
        environment.put("GRADLE_RO_DEP_CACHE", readOnlyDependencyCacheRoot.toString());
        environment.put("LANG", locale);
        environment.put("LC_ALL", locale);
        environment.put("TZ", timeZone);
        environment.put(
                "GRADLE_OPTS",
                "-Duser.language=" + language
                        + " -Duser.country=" + country
                        + " -Duser.timezone=" + timeZone);
        return new Fixture(
                fixtureRoot,
                checkout,
                gradleHome,
                fixtureRoot.resolve("maven-repository"),
                consumer,
                initScript,
                readOnlyDependencyCacheRoot,
                version,
                locale,
                language,
                country,
                timeZone,
                Map.copyOf(environment));
    }

    private static ProcessResult runCheckout(
            Fixture fixture,
            String invocation,
            boolean configurationCache,
            List<String> requestedTasks
    ) throws IOException, InterruptedException {
        return runGradle(
                fixture,
                fixture.checkout(),
                invocation,
                configurationCache,
                false,
                requestedTasks,
                fixture.version());
    }

    private static ProcessResult runConsumer(Fixture fixture)
            throws IOException, InterruptedException {
        return runGradle(
                fixture,
                fixture.consumer(),
                "resolve-published",
                false,
                true,
                List.of("resolvePublishedControlledAgent"),
                null);
    }

    private static ProcessResult runGradle(
            Fixture fixture,
            Path projectDirectory,
            String invocation,
            boolean configurationCache,
            boolean offline,
            List<String> requestedTasks,
            String releaseVersion
    ) throws IOException, InterruptedException {
        Path wrapper = fixture.checkout().resolve(isWindows() ? "gradlew.bat" : "gradlew");
        if (!Files.isRegularFile(wrapper)) {
            throw new IllegalStateException("Gradle wrapper is missing: " + wrapper);
        }
        List<String> command = new ArrayList<>();
        if (isWindows()) {
            String commandProcessor = System.getenv("ComSpec");
            command.add(commandProcessor == null || commandProcessor.isBlank()
                    ? "cmd.exe"
                    : commandProcessor);
            command.add("/d");
            command.add("/c");
        } else {
            command.add("sh");
        }
        command.add(wrapper.toString());
        command.add("--no-daemon");
        command.add("--no-build-cache");
        command.add("--dependency-verification=strict");
        command.add("--configure-on-demand");
        command.add("--gradle-user-home");
        command.add(fixture.gradleHome().toString());
        command.add("--console=plain");
        command.add("--stacktrace");
        command.add("--init-script");
        command.add(fixture.initScript().toString());
        command.add(configurationCache ? "--configuration-cache" : "--no-configuration-cache");
        if (configurationCache) {
            command.add("--configuration-cache-problems=fail");
        }
        if (offline) {
            command.add("--offline");
        }
        command.add("-Duser.language=" + fixture.language());
        command.add("-Duser.country=" + fixture.country());
        command.add("-Duser.timezone=" + fixture.timeZone());
        command.add("-Dmaven.repo.local=" + fixture.mavenRepository());
        if (releaseVersion != null) {
            command.add("-PreleaseVersion=" + releaseVersion);
        }
        command.add("-p");
        command.add(projectDirectory.toString());
        command.addAll(requestedTasks);

        Path logDirectory = fixture.fixtureRoot().resolve("logs");
        Files.createDirectories(logDirectory);
        Path log = logDirectory.resolve(invocation + ".log");
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.environment().clear();
        processBuilder.environment().putAll(fixture.environment());
        processBuilder.environment().put("GRADLE_USER_HOME", fixture.gradleHome().toString());
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(log.toFile());
        Process process = processBuilder.start();
        boolean finished = process.waitFor(15, TimeUnit.MINUTES);
        if (!finished) {
            process.toHandle().descendants().toList().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);
        if (!finished) {
            output += System.lineSeparator() + "Gradle invocation timed out after 15 minutes";
        }
        return new ProcessResult(finished ? process.exitValue() : -1, output);
    }

    private static void copyCheckout(Path sourceRoot, Path targetRoot) throws IOException {
        if (!Files.isRegularFile(sourceRoot.resolve("settings.gradle"))) {
            throw new IllegalStateException("Repository settings are missing: " + sourceRoot);
        }
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                Path relative = sourceRoot.relativize(directory);
                if (containsExcludedSegment(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(targetRoot.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Path relative = sourceRoot.relativize(file);
                if (containsExcludedSegment(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                if (!attributes.isRegularFile()) {
                    throw new IOException("Unsupported checkout entry: " + file);
                }
                Files.copy(file, targetRoot.resolve(relative));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean containsExcludedSegment(Path relativePath) {
        for (Path segment : relativePath) {
            String name = segment.toString();
            if (name.equals(".git") || name.equals(".gradle") || name.equals("build")) {
                return true;
            }
        }
        return false;
    }

    private static void setTreeMtime(Path root, FileTime timestamp) throws IOException {
        List<Path> paths;
        try (var tree = Files.walk(root)) {
            paths = tree.toList();
        }
        for (int index = paths.size() - 1; index >= 0; index--) {
            Files.setLastModifiedTime(paths.get(index), timestamp);
        }
    }

    private static List<Path> findBuildDirectories(Path checkout) throws IOException {
        try (var tree = Files.walk(checkout)) {
            return tree.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().equals("build"))
                    .map(checkout::relativize)
                    .toList();
        }
    }

    private static List<Path> directoryEntries(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.toList();
        }
    }

    private static String consumerBuildScript(String version) {
        return """
                plugins {
                    id 'base'
                }

                def repositoryPath = providers.systemProperty('maven.repo.local').orNull
                if (repositoryPath == null || repositoryPath.isBlank()) {
                    throw new GradleException('maven.repo.local is required')
                }

                def publishedAgent = configurations.create('publishedAgent') {
                    canBeConsumed = false
                    canBeResolved = true
                    transitive = false
                }

                repositories {
                    maven {
                        url = uri(repositoryPath)
                        metadataSources {
                            mavenPom()
                            artifact()
                        }
                    }
                }

                dependencies {
                    add(publishedAgent.name,
                            'space.br1440.platform.starters.tracing:platform-tracing-javaagent:%s')
                }

                tasks.register('resolvePublishedControlledAgent', Sync) {
                    from(publishedAgent)
                    into(layout.buildDirectory.dir('round-trip'))
                    doLast {
                        List<File> jars = destinationDir.listFiles()
                                .findAll { it.name.endsWith('.jar') }
                        if (jars.size() != 1) {
                            throw new GradleException(
                                    'Expected exactly one resolved controlled-agent JAR, got ' + jars)
                        }
                    }
                }
                """.formatted(groovyString(version));
    }

    private static String isolatedBuildInitScript(
            Path conventionRepository,
            String conventionPluginVersion,
            Path dependencyRepository,
            String platformBomVersion
    ) {
        String allowedPluginIds = CONVENTION_PLUGIN_IDS.stream()
                .map(id -> "'" + id + "'")
                .collect(Collectors.joining(", "));
        String conventionRepositoryUri = groovySingleQuoted(
                conventionRepository.toUri().toASCIIString());
        String dependencyRepositoryUri = groovySingleQuoted(
                dependencyRepository.toUri().toASCIIString());
        String safeConventionPluginVersion = groovyString(conventionPluginVersion);
        String safePlatformBomVersion = groovyString(platformBomVersion);
        return """
                settingsEvaluated { settings ->
                    settings.pluginManagement {
                        repositories {
                            clear()
                            flatDir {
                                dirs new File(new URI('%s'))
                            }
                        }
                        resolutionStrategy {
                            eachPlugin {
                                if (requested.id.id.startsWith('platform.')) {
                                    def allowedIds = [%s] as Set
                                    if (!allowedIds.contains(requested.id.id)) {
                                        throw new GradleException(
                                                'Unexpected platform convention plugin: '
                                                        + requested.id.id)
                                    }
                                    if (requested.version != null && requested.version != '%s') {
                                        throw new GradleException(
                                                'Unexpected platform convention plugin version: '
                                                        + requested.version)
                                    }
                                    useModule('%s%s')
                                }
                            }
                        }
                    }
                    settings.dependencyResolutionManagement {
                        repositories {
                            clear()
                            exclusiveContent {
                                forRepository {
                                    maven {
                                        name = 'reproducibilityBootstrap'
                                        url = new URI('%s')
                                        metadataSources {
                                            gradleMetadata()
                                        }
                                    }
                                }
                                filter {
                                    includeVersion(
                                            'space.br1440.platform',
                                            'platform-bom',
                                            '%s')
                                }
                            }
                            mavenCentral()
                        }
                    }
                }
                """.formatted(
                conventionRepositoryUri,
                allowedPluginIds,
                safeConventionPluginVersion,
                CONVENTION_PLUGIN_COORDINATE,
                safeConventionPluginVersion,
                dependencyRepositoryUri,
                safePlatformBomVersion);
    }

    private static void assertSuccessful(ProcessResult result, String description) {
        assertThat(result.exitCode())
                .as("%s failed: %s", description, result.output())
                .isZero();
    }

    private static void assertTaskExecuted(ProcessResult result, String taskPath) {
        assertTaskLine(result, taskPath, "");
    }

    private static void assertTaskUpToDate(ProcessResult result, String taskPath) {
        assertTaskLine(result, taskPath, " UP-TO-DATE");
    }

    private static void assertTaskLine(
            ProcessResult result,
            String taskPath,
            String expectedOutcome
    ) {
        String prefix = "> Task " + taskPath;
        List<String> taskLines = result.output().lines()
                .filter(line -> line.equals(prefix) || line.startsWith(prefix + " "))
                .toList();
        assertThat(taskLines)
                .as("Unexpected outcome for %s in:%n%s", taskPath, result.output())
                .containsExactly(prefix + expectedOutcome);
    }

    private static void assertArtifactOnlyPublication(Fixture fixture) throws IOException {
        List<String> files;
        try (var entries = Files.list(fixture.publishedVersionDirectory())) {
            files = entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertThat(files).containsExactly(
                "maven-metadata-local.xml",
                "platform-tracing-javaagent-" + fixture.version() + ".jar",
                "platform-tracing-javaagent-" + fixture.version() + ".pom");
        String pom = Files.readString(fixture.publishedPom(), StandardCharsets.UTF_8);
        assertThat(pom)
                .doesNotContain("<dependencies>")
                .doesNotContain("<dependencyManagement>")
                .doesNotContain("do_not_remove: published-with-gradle-metadata");
    }

    private static Path singleJar(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("Round-trip directory is missing: " + directory);
        }
        try (var files = Files.list(directory)) {
            List<Path> jars = files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .toList();
            if (jars.size() != 1) {
                throw new IllegalStateException("Expected exactly one round-trip JAR, got: " + jars);
            }
            return jars.getFirst();
        }
    }

    private static byte[] readEntry(Path archive, String name) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                throw new IllegalStateException("Required ZIP entry is missing: " + name);
            }
            return zip.getInputStream(entry).readAllBytes();
        }
    }

    private static Path repositoryRoot() {
        return requiredPath("platformTracing.assemblyScript").getParent().getParent();
    }

    private static Path requiredPath(String name) {
        Path path = Path.of(requiredProperty(name));
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Required test file is missing: " + path);
        }
        return path;
    }

    private static Path requiredDependencyCacheRoot(String name) {
        Path path = Path.of(requiredProperty(name)).toAbsolutePath().normalize();
        if (!Files.isDirectory(path) || !Files.isDirectory(path.resolve("modules-2"))) {
            throw new IllegalStateException("Required Gradle dependency cache is invalid: " + path);
        }
        return path;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required test system property is missing: " + name);
        }
        return value;
    }

    private static String groovyString(String value) {
        if (!value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Unsupported distribution version: " + value);
        }
        return value;
    }

    private static String groovySingleQuoted(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private record Fixture(
            Path fixtureRoot,
            Path checkout,
            Path gradleHome,
            Path mavenRepository,
            Path consumer,
            Path initScript,
            Path readOnlyDependencyCacheRoot,
            String version,
            String locale,
            String language,
            String country,
            String timeZone,
            Map<String, String> environment
    ) {

        private Path output() {
            return checkout.resolve(
                    "platform-starters-tracing/platform-tracing-otel-javaagent-distribution"
                            + "/build/libs/platform-tracing-javaagent-" + version + ".jar");
        }

        private Path extension() {
            return checkout.resolve(
                    "platform-starters-tracing/platform-tracing-otel-javaagent-distribution"
                            + "/build/controlled-agent/"
                            + "platform-tracing-otel-javaagent-extension.jar");
        }

        private Path publishedVersionDirectory() {
            return mavenRepository.resolve(
                    "space/br1440/platform/starters/tracing/platform-tracing-javaagent/" + version);
        }

        private Path publishedJar() {
            return publishedVersionDirectory().resolve(
                    "platform-tracing-javaagent-" + version + ".jar");
        }

        private Path publishedPom() {
            return publishedVersionDirectory().resolve(
                    "platform-tracing-javaagent-" + version + ".pom");
        }

        private Path roundTripDirectory() {
            return consumer.resolve("build/round-trip");
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
