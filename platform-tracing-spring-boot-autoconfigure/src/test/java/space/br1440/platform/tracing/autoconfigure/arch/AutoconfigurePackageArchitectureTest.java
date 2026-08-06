package space.br1440.platform.tracing.autoconfigure.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Блокирующие проверки package tree и ограниченных межмодульных контрактов autoconfigure.
 */
class AutoconfigurePackageArchitectureTest {

    private static final String BASE_PACKAGE = "space.br1440.platform.tracing.autoconfigure";
    private static final String AUTOCONFIGURE_MODULE = "platform-tracing-spring-boot-autoconfigure";
    private static final String SOURCE_ROOT = AUTOCONFIGURE_MODULE + "/src/main/java";
    private static final String REQUEST_ENCODER = BASE_PACKAGE
            + ".control.jmx.protocol.PrivateControlRequestEncoder";
    private static final String ACCEPTED_DECODER = BASE_PACKAGE
            + ".control.jmx.protocol.AcceptedCompleteSnapshotDecoder";
    private static final String LOCATOR = BASE_PACKAGE + ".control.jmx.PrivateControlLocator";
    private static final String ACCEPTED = BASE_PACKAGE
            + ".control.policy.read.AcceptedSnapshotWithSequence";
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([^;]+);");
    private static final Pattern PUBLIC_TYPE = Pattern.compile(
            "(?m)^public\\s+(?:(?:sealed|non-sealed|final|abstract)\\s+)*(?:class|record|interface|enum)\\s+([A-Za-z0-9_]+)");

    @Test
    void targetPackageTreeAndDependencyDirectionsAreEnforced() throws Exception {
        Path root = repositoryRoot();
        Path sourceRoot = root.resolve(SOURCE_ROOT);
        Path packageRoot = sourceRoot.resolve(BASE_PACKAGE.replace('.', '/'));
        List<Path> sources = javaSources(packageRoot);
        assertThat(sources).isNotEmpty();

        Set<String> rootTypes = new TreeSet<>();
        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            String relative = normalized(packageRoot.relativize(source));
            String text = Files.readString(source, StandardCharsets.UTF_8);
            if (!relative.contains("/")) {
                rootTypes.add(relative);
            }
            if (relative.matches("(^|.*/)(support|common|util|misc|configuration)/.*")) {
                violations.add(relative + ": запрещённый пакет общего назначения");
            }
            if ((relative.startsWith("control/policy/")
                    || relative.startsWith("control/jmx/protocol/"))
                    && text.contains("org.springframework")) {
                violations.add(relative + ": domain/protocol пакет зависит от Spring");
            }
            if (text.contains("org.springframework.web") || text.contains("jakarta.servlet")) {
                violations.add(relative + ": нейтральный модуль зависит от web/servlet API");
            }
            if (relative.startsWith("bootstrap/")
                    && !relative.endsWith("TracingBootstrapSettingsResolver.java")
                    && !relative.endsWith("TracingBootstrapSettingsRegistrar.java")
                    && text.contains("org.springframework")) {
                violations.add(relative + ": bootstrap value type зависит от Spring");
            }
            if (relative.startsWith("status/")
                    && (text.contains("TracingPolicySnapshotApplier")
                    || text.contains("CompleteTracingPolicyReader"))) {
                violations.add(relative + ": status получил write/complete capability");
            }
        }

        assertThat(rootTypes)
                .containsExactly("TracingProperties.java", "TracingRuntimeAutoConfiguration.java");
        assertThat(violations).as("Package-boundary violations").isEmpty();

        String build = Files.readString(root.resolve(AUTOCONFIGURE_MODULE + "/build.gradle"));
        assertThat(build).doesNotContain("api project(':platform-tracing-otel')");
    }

    @Test
    void publicSurfaceMatchesReviewedAllowlist() throws Exception {
        Path root = repositoryRoot();
        Set<String> actual = new TreeSet<>();
        for (Path source : javaSources(root.resolve(SOURCE_ROOT))) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            Matcher type = PUBLIC_TYPE.matcher(text);
            if (type.find()) {
                Matcher packageMatcher = PACKAGE.matcher(text);
                assertThat(packageMatcher.find()).as(source.toString()).isTrue();
                actual.add(packageMatcher.group(1) + "." + type.group(1));
            }
        }

        Set<String> expected = new TreeSet<>(Files.readAllLines(
                root.resolve("docs/architecture/platform-tracing-autoconfigure-public-type-allowlist.txt"),
                StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList());
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void internalCrossModuleConsumersStayWithinAllowlist() throws Exception {
        Path root = repositoryRoot();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> normalized(root.relativize(path)).contains("/src/main/"))
                    .forEach(path -> inspectConsumer(root, path, violations));
        }
        assertThat(violations).as("Internal contract consumer violations").isEmpty();
    }

    private static void inspectConsumer(Path root, Path source, List<String> violations) {
        String relative = normalized(root.relativize(source));
        String text;
        try {
            text = Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read " + source, exception);
        }
        if (text.contains(REQUEST_ENCODER)
                && !relative.startsWith(AUTOCONFIGURE_MODULE + "/")
                && !relative.startsWith("platform-tracing-perf-tests/")) {
            violations.add(relative + ": consumer of " + REQUEST_ENCODER);
        }
        if (text.contains(ACCEPTED_DECODER)
                && !relative.startsWith(AUTOCONFIGURE_MODULE + "/")
                && !relative.startsWith("platform-tracing-bench/")) {
            violations.add(relative + ": consumer of " + ACCEPTED_DECODER);
        }
        if (text.contains(LOCATOR)
                && !relative.startsWith(AUTOCONFIGURE_MODULE + "/")
                && !relative.startsWith("platform-tracing-perf-tests/")) {
            violations.add(relative + ": consumer of " + LOCATOR);
        }
        if (text.contains(ACCEPTED)
                && !relative.startsWith(AUTOCONFIGURE_MODULE + "/")
                && !relative.startsWith("platform-tracing-perf-tests/")
                && !relative.startsWith("platform-tracing-bench/")) {
            violations.add(relative + ": consumer of " + ACCEPTED);
        }
    }

    private static List<Path> javaSources(Path sourceRoot) throws IOException {
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
    }

    private static String normalized(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.exists(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Repository root with settings.gradle not found");
        }
        return current;
    }
}
