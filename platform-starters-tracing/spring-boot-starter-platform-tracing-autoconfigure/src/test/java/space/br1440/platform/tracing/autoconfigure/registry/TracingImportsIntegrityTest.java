package space.br1440.platform.tracing.autoconfigure.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Проверяет, что Spring registries указывают только на существующие production-типы.
 */
class TracingImportsIntegrityTest {

    private static final List<String> REGISTRIES = List.of(
            "platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure/src/main/resources/META-INF/spring/"
                    + "org.springframework.boot.autoconfigure.AutoConfiguration.imports",
            "platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure-webmvc/src/main/resources/META-INF/spring/"
                    + "org.springframework.boot.autoconfigure.AutoConfiguration.imports",
            "platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure-webflux/src/main/resources/META-INF/spring/"
                    + "org.springframework.boot.autoconfigure.AutoConfiguration.imports");

    private static final List<String> PRODUCTION_SOURCE_ROOTS = List.of(
            "platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure/src/main/java",
            "platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure-webmvc/src/main/java",
            "platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure-webflux/src/main/java");

    @Test
    void registriesContainUniqueExistingProductionTypes() throws Exception {
        Path root = repositoryRoot();
        List<String> violations = new ArrayList<>();

        for (String registryName : REGISTRIES) {
            verifyRegistry(root, registryName, violations);
        }

        assertThat(violations)
                .as("Spring registry integrity violations:%n%s", String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    private static void verifyRegistry(Path root, String registryName, List<String> violations)
            throws IOException {
        Path registry = root.resolve(registryName);
        if (!Files.isRegularFile(registry)) {
            violations.add(registryName + ": registry file is missing");
            return;
        }

        List<String> entries = Files.readAllLines(registry, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        if (entries.isEmpty()) {
            violations.add(registryName + ": registry must not be empty");
            return;
        }

        Set<String> unique = new HashSet<>();
        for (String entry : entries) {
            if (!unique.add(entry)) {
                violations.add(registryName + ": duplicate entry " + entry);
            }
            if (!productionTypeExists(root, entry)) {
                violations.add(registryName + ": production type does not exist: " + entry);
            }
        }
    }

    private static boolean productionTypeExists(Path root, String fqcn) {
        String relative = fqcn.replace('.', '/') + ".java";
        return PRODUCTION_SOURCE_ROOTS.stream()
                .map(root::resolve)
                .map(sourceRoot -> sourceRoot.resolve(relative))
                .anyMatch(Files::isRegularFile);
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
