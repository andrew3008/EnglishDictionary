package space.br1440.platform.tracing.autoconfigure.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * P6 legacy-name fail-fast detector (task {@code p6-legacy-name-fail-fast}).
 * <p>
 * Fails when an exact legacy configuration name from
 * {@code docs/research/evidence/p6-cutover/legacy-names-v1.json} appears in production
 * main sources. Evidence paths, changelog, and migration docs are out of scope for this scan.
 */
class P6LegacyNameFailFastTest {

    private static final String LEGACY_NAMES_V1 =
            "docs/research/evidence/p6-cutover/legacy-names-v1.json";
    private static final String SPRING_METADATA_PREFIX =
            "platform-tracing-spring-boot-autoconfigure/src/main/resources/";
    private static final Set<String> TEXT_EXTENSIONS = Set.of(".java", ".json", ".properties", ".yml", ".yaml", ".xml");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void committedLegacyNamesInventoryIsDeterministic() throws Exception {
        Path root = repositoryRoot();
        LegacyNamesInventory inventory = LegacyNamesInventory.load(root.resolve(LEGACY_NAMES_V1));

        assertThat(inventory.legacyNames()).hasSize(12);
        assertThat(inventory.legacyNames().stream().map(LegacyNameEntry::exactName).toList())
                .containsExactly(
                        "platform.tracing.actuator.mutation-enabled",
                        "platform.tracing.baggage.allowlist-keys",
                        "platform.tracing.baggage.deny-patterns",
                        "platform.tracing.baggage.enabled",
                        "platform.tracing.policy.version",
                        "platform.tracing.propagation.baggage.allowed-keys",
                        "platform.tracing.propagation.baggage.deny-patterns",
                        "platform.tracing.propagation.baggage.enabled",
                        "platform.tracing.propagation.w3c.diagnostics-only",
                        "platform.tracing.sampling.drop-paths",
                        "platform.tracing.sampling.force-record-header-values",
                        "platform.tracing.sampling.force-record-values");

        List<String> exactNames = inventory.legacyNames().stream()
                .map(LegacyNameEntry::exactName)
                .toList();
        assertThat(exactNames).isSortedAccordingTo(Comparator.naturalOrder());
    }

    @Test
    void productionSourcesContainNoBannedLegacyNames() throws Exception {
        Path root = repositoryRoot();
        LegacyNamesInventory inventory = LegacyNamesInventory.load(root.resolve(LEGACY_NAMES_V1));
        List<Violation> violations = scanProductionSources(root, inventory);

        assertThat(violations)
                .as("Legacy names must not appear in production main sources after P6 cutover.%n"
                        + "Update to final names (see legacy-names-v1.json replacement diagnostics).%n"
                        + "Violations:%n%s", formatViolations(violations))
                .isEmpty();
    }

    private static List<Violation> scanProductionSources(Path root, LegacyNamesInventory inventory)
            throws IOException {
        List<Violation> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> isProductionScanTarget(root, path))
                    .sorted(Comparator.comparing(path -> normalize(root.relativize(path))))
                    .forEach(path -> scanFile(root, path, inventory, violations));
        }
        violations.sort(Comparator.comparing(Violation::path).thenComparingInt(Violation::lineNumber)
                .thenComparing(Violation::exactName));
        return violations;
    }

    private static void scanFile(
            Path root,
            Path path,
            LegacyNamesInventory inventory,
            List<Violation> violations) {
        String relative = normalize(root.relativize(path));
        if (isDetectorDataFile(relative)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return;
        }
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            for (LegacyNameEntry entry : inventory.legacyNames()) {
                if (matchesLegacyName(line, entry)) {
                    violations.add(new Violation(
                            relative,
                            index + 1,
                            entry.exactName(),
                            entry.replacementOrDeletionDiagnostic(),
                            line.trim()));
                }
            }
        }
    }

    private static boolean matchesLegacyName(String line, LegacyNameEntry entry) {
        if (!line.contains(entry.exactName())) {
            return false;
        }
        if (entry.literalOnly()) {
            return line.contains("\"" + entry.exactName() + "\"");
        }
        return true;
    }

    private static boolean isProductionScanTarget(Path root, Path path) {
        String relative = normalize(root.relativize(path));
        if (relative.contains("/build/") || relative.contains("/.git/")) {
            return false;
        }
        if (relative.contains("/src/main/") && relative.endsWith(".java")) {
            return true;
        }
        if (relative.startsWith(SPRING_METADATA_PREFIX)) {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            return TEXT_EXTENSIONS.stream().anyMatch(name::endsWith)
                    || relative.contains("/META-INF/");
        }
        return false;
    }

    private static boolean isDetectorDataFile(String relativePath) {
        return relativePath.equals(LEGACY_NAMES_V1)
                || relativePath.equals("docs/research/evidence/p6-cutover/cutover-manifest.json");
    }

    private static String formatViolations(List<Violation> violations) {
        StringBuilder builder = new StringBuilder();
        for (Violation violation : violations) {
            builder.append("  ")
                    .append(violation.path())
                    .append(':')
                    .append(violation.lineNumber())
                    .append(" [")
                    .append(violation.exactName())
                    .append("] -> ")
                    .append(violation.replacementOrDeletionDiagnostic())
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

    private record LegacyNamesInventory(List<LegacyNameEntry> legacyNames) {

        static LegacyNamesInventory load(Path path) throws IOException {
            JsonNode root = JSON.readTree(path.toFile());
            JsonNode names = root.get("legacyNames");
            if (names == null || !names.isArray()) {
                throw new IllegalStateException("legacyNames array missing in " + path);
            }
            Map<String, LegacyNameEntry> entries = new LinkedHashMap<>();
            for (JsonNode node : names) {
                LegacyNameEntry entry = LegacyNameEntry.from(node);
                entries.putIfAbsent(entry.exactName(), entry);
            }
            List<LegacyNameEntry> sorted = entries.values().stream()
                    .sorted(Comparator.comparing(LegacyNameEntry::exactName))
                    .toList();
            return new LegacyNamesInventory(sorted);
        }
    }

    private record LegacyNameEntry(
            String exactName,
            String disposition,
            String replacementOrDeletionDiagnostic,
            boolean literalOnly) {

        static LegacyNameEntry from(JsonNode node) {
            String exactName = requiredText(node, "exactName");
            String disposition = requiredText(node, "disposition");
            String diagnostic = requiredText(node, "replacementOrDeletionDiagnostic");
            boolean literalOnly = node.path("literalOnly").asBoolean(false);
            return new LegacyNameEntry(exactName, disposition, diagnostic, literalOnly);
        }

        private static String requiredText(JsonNode node, String field) {
            JsonNode value = node.get(field);
            if (value == null || value.asText().isBlank()) {
                throw new IllegalStateException("Missing " + field + " in legacy-names entry: " + node);
            }
            return value.asText();
        }
    }

    private record Violation(
            String path,
            int lineNumber,
            String exactName,
            String replacementOrDeletionDiagnostic,
            String line) {
    }
}
