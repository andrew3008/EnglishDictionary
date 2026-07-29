package space.br1440.platform.tracing.autoconfigure.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.configuration.configserver.ConfigServerTracingPolicyAdapter;

/**
 * P6.3 proof ({@code p6-delete-refreshscope-mutation}): RefreshScope reconcile path deleted;
 * production main sources must not reference the removed mutation wiring.
 */
class P6RefreshScopeMutationDeletionTest {

    private static final String AUTO_CONFIGURATION_IMPORTS =
            "platform-tracing-spring-boot-autoconfigure/src/main/resources/"
                    + "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private static final List<String> BANNED_PRODUCTION_SYMBOLS = List.of(
            "RuntimeConfigApplier",
            "TracingRefreshScopeAutoConfiguration",
            "tracingRuntimeConfigApplier",
            "tracingRefreshListener",
            "refreshableTracingProperties");

    @Test
    void productionMainSourcesContainNoRefreshScopeMutationSymbols() throws Exception {
        Path root = repositoryRoot();
        List<Violation> violations = scanProductionMainSources(root);

        assertThat(violations)
                .as("RefreshScope mutation wiring must be fully removed from production main sources.%n"
                        + "Violations:%n%s", formatViolations(violations))
                .isEmpty();
    }

    @Test
    void autoConfigurationImportsDoNotRegisterRefreshScopeMutationConfiguration() throws Exception {
        Path imports = repositoryRoot().resolve(AUTO_CONFIGURATION_IMPORTS);
        String content = Files.readString(imports, StandardCharsets.UTF_8);

        assertThat(content)
                .as("TracingRefreshScopeAutoConfiguration must not be registered")
                .doesNotContain("TracingRefreshScopeAutoConfiguration");
    }

    @Test
    void configServerAdapterRemainsEnvironmentChangeEventTriggerOnly() {
        assertThat(ConfigServerTracingPolicyAdapter.class).isNotNull();
        assertThat(ConfigServerTracingPolicyAdapter.class.getMethods())
                .anyMatch(method -> "onEnvironmentChange".equals(method.getName()));
    }

    private static List<Violation> scanProductionMainSources(Path root) throws IOException {
        List<Violation> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> isProductionMainJava(root, path))
                    .sorted(Comparator.comparing(path -> normalize(root.relativize(path))))
                    .forEach(path -> scanFile(root, path, violations));
        }
        violations.sort(Comparator.comparing(Violation::path).thenComparingInt(Violation::lineNumber)
                .thenComparing(Violation::symbol));
        return violations;
    }

    private static void scanFile(Path root, Path path, List<Violation> violations) {
        String relative = normalize(root.relativize(path));
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return;
        }
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            for (String symbol : BANNED_PRODUCTION_SYMBOLS) {
                if (line.contains(symbol)) {
                    violations.add(new Violation(relative, index + 1, symbol, line.trim()));
                }
            }
        }
    }

    private static boolean isProductionMainJava(Path root, Path path) {
        String relative = normalize(root.relativize(path));
        return relative.contains("/src/main/") && relative.endsWith(".java");
    }

    private static String formatViolations(List<Violation> violations) {
        StringBuilder builder = new StringBuilder();
        for (Violation violation : violations) {
            builder.append("  ")
                    .append(violation.path())
                    .append(':')
                    .append(violation.lineNumber())
                    .append(" [")
                    .append(violation.symbol())
                    .append(']')
                    .append(System.lineSeparator())
                    .append("    ")
                    .append(violation.line())
                    .append(System.lineSeparator());
        }
        return builder.toString();
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

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private record Violation(String path, int lineNumber, String symbol, String line) {
    }
}
