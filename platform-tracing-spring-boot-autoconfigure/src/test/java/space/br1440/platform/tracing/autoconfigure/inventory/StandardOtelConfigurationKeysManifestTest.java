package space.br1440.platform.tracing.autoconfigure.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Governance gate for the test-owned manifest of standard OpenTelemetry configuration keys
 * ({@code tracing-inventory/standard-otel-configuration-keys.txt}).
 * <p>
 * The manifest is the golden source consumed by {@link TracingConfigurationInventoryGeneratorTest}
 * for {@code STANDARD_OTEL_KEY} inventory entries. It replaced a reflective read of the now-deleted
 * {@code OtelEffectiveConfigSnapshot#EXPOSED_PROPERTIES} constant (see
 * {@code ADR-remove-dual-channel-diagnostics.md}). No production class is read via reflection here,
 * and this manifest must never be read by production code.
 */
class StandardOtelConfigurationKeysManifestTest {

    private static final String RESOURCE_PATH = "tracing-inventory/standard-otel-configuration-keys.txt";
    private static final Pattern CANONICAL_KEY = Pattern.compile("^otel(\\.[a-z][a-z0-9]*)+$");
    private static final Pattern CANONICAL_ENV_NAME = Pattern.compile("^OTEL(_[A-Z0-9]+)+$");

    @Test
    void keysAreNonEmptyUniqueSortedAndCanonical() throws IOException {
        List<String> keys = readKeys();

        assertThat(keys).isNotEmpty();
        assertThat(keys).as("every key must match the canonical otel.* system-property format")
                .allMatch(key -> CANONICAL_KEY.matcher(key).matches());
        assertThat(keys).as("manifest must not contain duplicate keys").doesNotHaveDuplicates();
        assertThat(keys).as("manifest must be sorted lexicographically for stable diffs")
                .isSorted();
    }

    @Test
    void keysConvertToCanonicalOtelEnvironmentVariableNames() throws IOException {
        for (String key : readKeys()) {
            String env = key.replace('.', '_').toUpperCase(Locale.ROOT);
            assertThat(env).as("OTEL_* conversion of %s", key).matches(CANONICAL_ENV_NAME);
        }
    }

    @Test
    void parserIgnoresCommentsAndBlankLines() throws IOException {
        try (InputStream stream = resourceStream()) {
            List<String> rawLines = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .lines()
                    .toList();
            assertThat(rawLines).as("manifest must contain at least one comment/header line")
                    .anyMatch(line -> line.strip().startsWith("#"));
        }
        assertThat(readKeys()).noneMatch(key -> key.isBlank() || key.startsWith("#"));
    }

    @Test
    void generatorDoesNotReflectOnDeletedProductionSnapshot() throws IOException {
        Path generatorSource = repositoryRoot().resolve(
                "platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/"
                        + "autoconfigure/inventory/TracingConfigurationInventoryGeneratorTest.java");
        String source = Files.readString(generatorSource, StandardCharsets.UTF_8);

        assertThat(source).as("generator must not reflect on the deleted OtelEffectiveConfigSnapshot class")
                .doesNotContain("OtelEffectiveConfigSnapshot");
        assertThat(source).as("generator must not reflect on the deleted ResourceEffectiveSnapshot class")
                .doesNotContain("ResourceEffectiveSnapshot");
    }

    private static List<String> readKeys() throws IOException {
        try (InputStream stream = resourceStream()) {
            return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }
    }

    private static InputStream resourceStream() {
        InputStream stream = StandardOtelConfigurationKeysManifestTest.class.getClassLoader()
                .getResourceAsStream(RESOURCE_PATH);
        assertThat(stream).as("classpath resource %s must exist", RESOURCE_PATH).isNotNull();
        return stream;
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
