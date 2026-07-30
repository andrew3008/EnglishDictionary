package space.br1440.platform.tracing.api.contract;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт {@code docs/contracts/public-contract-inventory.yaml}.
 * <p>
 * Public Contract Inventory — версионируемые данные (policy-файл), а не Java-код.
 * Тест машинно гарантирует целостность governance-контакта Week 01:
 * <ol>
 *   <li>обязательные поля корня и module inventory присутствуют;</li>
 *   <li>каждая entry содержит обязательные поля и допустимые enum-значения;</li>
 *   <li>inventory для {@code platform-tracing-api} содержит минимальный набор entry id;</li>
 *   <li>все {@code relatedEntryIds} и cross-surface ссылки указывают на существующие entry id.</li>
 * </ol>
 */
class PublicContractInventoryContractTest {

    private static final Path INVENTORY_YAML =
            Path.of("..", "docs", "contracts", "public-contract-inventory.yaml");

    private static final Set<String> ALLOWED_SURFACES = Set.of(
            "java-api", "spi", "wire", "operations", "dependency", "configuration", "spring", "evolution");
    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "supported-api", "supported-spi", "experimental", "incidental", "internal", "deprecated");
    private static final Set<String> ALLOWED_EFFECTIVE_USAGE = Set.of("match", "wider", "narrower", "unknown");
    private static final Set<String> ALLOWED_DISPOSITIONS = Set.of(
            "none", "promote", "replace", "restrict", "deprecate", "remove", "decision-required");

    private static final Set<String> REQUIRED_API_ENTRY_IDS = Set.of(
            "trace-operations",
            "span-factory",
            "span-handle",
            "span-spec-model",
            "span-attribute-scrubbing-rule",
            "platform-headers",
            "platform-attributes",
            "tracing-mdc-keys",
            "no-otel-compile-surface"
    );

    private static Map<String, Object> root;
    private static Map<String, Object> apiInventory;

    @BeforeAll
    static void loadYaml() {
        root = loadFileYaml(INVENTORY_YAML);
        apiInventory = inventories().stream()
                .filter(inventory -> "platform-tracing-api".equals(inventory.get("module")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("platform-tracing-api inventory not found"));
    }

    @Test
    void корень_содержит_обязательные_поля() {
        assertThat(root)
                .containsKeys("version", "methodology", "updatedAt", "inventories");
        assertThat(root.get("version")).isEqualTo(1);
    }

    @Test
    void каждый_module_inventory_содержит_обязательные_поля() {
        for (Map<String, Object> inventory : inventories()) {
            assertThat(inventory)
                    .containsKeys("module", "artifact", "phase", "scope", "consumers", "entries");
        }
    }

    @Test
    void каждая_entry_содержит_обязательные_поля_и_допустимые_значения() {
        for (Map<String, Object> entry : entries(apiInventory)) {
            assertThat(entry)
                    .containsKeys(
                            "id", "surface", "element", "elementKind", "consumers",
                            "status", "intendedStatus", "effectiveUsage",
                            "guarantees", "evidence", "compatibility", "owner", "verification", "disposition");

            assertThat(ALLOWED_SURFACES).contains((String) entry.get("surface"));
            assertThat(ALLOWED_STATUSES).contains((String) entry.get("status"));
            assertThat(ALLOWED_STATUSES).contains((String) entry.get("intendedStatus"));
            assertThat(ALLOWED_EFFECTIVE_USAGE).contains((String) entry.get("effectiveUsage"));
            assertThat(ALLOWED_DISPOSITIONS).contains((String) entry.get("disposition"));

            assertThat((List<?>) entry.get("guarantees")).isNotEmpty();
            assertThat((List<?>) entry.get("evidence")).isNotEmpty();
            assertThat((List<?>) entry.get("verification")).isNotEmpty();
        }
    }

    @Test
    void platform_tracing_api_inventory_содержит_обязательные_entry_id() {
        Set<String> entryIds = entries(apiInventory).stream()
                .map(entry -> (String) entry.get("id"))
                .collect(Collectors.toSet());

        assertThat(entryIds).containsAll(REQUIRED_API_ENTRY_IDS);
        assertThat(entries(apiInventory).size()).isGreaterThanOrEqualTo(20);
    }

    @Test
    void related_entry_ids_и_cross_surface_ссылки_существуют() {
        Set<String> entryIds = entries(apiInventory).stream()
                .map(entry -> (String) entry.get("id"))
                .collect(Collectors.toSet());

        for (Map<String, Object> contradiction : contradictions(apiInventory)) {
            for (String relatedId : stringList(contradiction.get("relatedEntryIds"))) {
                assertThat(entryIds).contains(relatedId);
            }
        }

        for (Map<String, Object> decision : openDecisions(apiInventory)) {
            for (String relatedId : stringList(decision.get("relatedEntryIds"))) {
                assertThat(entryIds).contains(relatedId);
            }
        }

        for (Map<String, Object> crossRef : crossSurfaceReferences(apiInventory)) {
            assertThat(entryIds).contains((String) crossRef.get("entryId"));
        }
    }

    @Test
    void evidence_файлы_существуют_для_platform_tracing_api() {
        for (Map<String, Object> entry : entries(apiInventory)) {
            for (String evidencePath : stringList(entry.get("evidence"))) {
                if (evidencePath.startsWith("rule:")) {
                    continue;
                }
                Path path = Path.of("..", evidencePath);
                assertThat(Files.exists(path))
                        .as("evidence path for entry %s: %s", entry.get("id"), evidencePath)
                        .isTrue();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> inventories() {
        return (List<Map<String, Object>>) root.get("inventories");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> inventory) {
        return (List<Map<String, Object>>) inventory.get("entries");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> contradictions(Map<String, Object> inventory) {
        Object value = inventory.get("contradictions");
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> openDecisions(Map<String, Object> inventory) {
        Object value = inventory.get("openDecisions");
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> crossSurfaceReferences(Map<String, Object> inventory) {
        Object value = inventory.get("crossSurfaceReferences");
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadFileYaml(Path path) {
        LoaderOptions loaderOptions = new LoaderOptions();
        Yaml yaml = new Yaml(new SafeConstructor(loaderOptions));
        try (var input = Files.newInputStream(path)) {
            Object loaded = yaml.load(input);
            assertThat(loaded).isInstanceOf(Map.class);
            return (Map<String, Object>) loaded;
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load " + path, ex);
        }
    }
}
