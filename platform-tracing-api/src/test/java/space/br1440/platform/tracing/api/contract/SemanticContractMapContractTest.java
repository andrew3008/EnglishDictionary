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
 * Контракт {@code docs/contracts/semantic-contract-map.yaml}.
 * <p>
 * Semantic Contract Map — версионируемые данные Week 02, связанные с Public Contract Inventory.
 */
class SemanticContractMapContractTest {

    private static final Path MAP_YAML =
            Path.of("..", "docs", "contracts", "semantic-contract-map.yaml");
    private static final Path INVENTORY_YAML =
            Path.of("..", "docs", "contracts", "public-contract-inventory.yaml");

    private static final Set<String> ALLOWED_ROLES = Set.of(
            "entity", "value", "operation", "policy", "state", "result", "view");

    private static final Set<String> REQUIRED_CONCEPT_IDS = Set.of(
            "trace-operations-facade",
            "active-trace-context-view",
            "request-trace-context-snapshot",
            "span-specification",
            "span-handle",
            "manual-span-factory",
            "attribute-scrubbing-rule"
    );

    private static Map<String, Object> root;
    private static Map<String, Object> apiMap;
    private static Set<String> inventoryEntryIds;

    @BeforeAll
    static void loadYaml() {
        root = loadFileYaml(MAP_YAML);
        Map<String, Object> inventoryRoot = loadFileYaml(INVENTORY_YAML);
        apiMap = maps().stream()
                .filter(map -> "platform-tracing-api".equals(map.get("module")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("platform-tracing-api map not found"));

        Object entries = inventories(inventoryRoot).stream()
                .filter(inventory -> "platform-tracing-api".equals(inventory.get("module")))
                .findFirst()
                .orElseThrow()
                .get("entries");
        inventoryEntryIds = entriesList(entries).stream()
                .map(entry -> (String) entry.get("id"))
                .collect(Collectors.toSet());
    }

    @Test
    void корень_содержит_обязательные_поля() {
        assertThat(root)
                .containsKeys("version", "methodology", "updatedAt", "publicContractInventory", "maps");
        assertThat(root.get("version")).isEqualTo(1);
        assertThat(Files.exists(Path.of("..", (String) root.get("publicContractInventory")))).isTrue();
    }

    @Test
    void каждый_concept_содержит_обязательные_поля() {
        for (Map<String, Object> concept : concepts(apiMap)) {
            assertThat(concept)
                    .containsKeys(
                            "id", "canonicalTerm", "definition", "role", "invariants", "lifecycle",
                            "javaExpressions", "configurationExpressions", "springExpressions",
                            "operationalExpressions", "exclusions", "evolutionRule", "inventoryEntryIds");

            assertThat(ALLOWED_ROLES).contains((String) concept.get("role"));
            assertThat((List<?>) concept.get("invariants")).isNotEmpty();
            assertThat((List<?>) concept.get("exclusions")).isNotEmpty();
        }
    }

    @Test
    void platform_tracing_api_map_содержит_обязательные_concepts() {
        Set<String> conceptIds = concepts(apiMap).stream()
                .map(concept -> (String) concept.get("id"))
                .collect(Collectors.toSet());

        assertThat(conceptIds).containsAll(REQUIRED_CONCEPT_IDS);
        assertThat(concepts(apiMap).size()).isGreaterThanOrEqualTo(12);
    }

    @Test
    void inventory_entry_ids_ссылаются_на_public_contract_inventory() {
        for (Map<String, Object> concept : concepts(apiMap)) {
            for (String entryId : stringList(concept.get("inventoryEntryIds"))) {
                assertThat(inventoryEntryIds)
                        .as("inventory entry for concept %s", concept.get("id"))
                        .contains(entryId);
            }
        }

        for (Map<String, Object> gap : translationGaps(apiMap)) {
            String conceptId = (String) gap.get("conceptId");
            assertThat(concepts(apiMap).stream().map(c -> c.get("id")).collect(Collectors.toSet()))
                    .contains(conceptId);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps() {
        return (List<Map<String, Object>>) root.get("maps");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> concepts(Map<String, Object> moduleMap) {
        return (List<Map<String, Object>>) moduleMap.get("concepts");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> translationGaps(Map<String, Object> moduleMap) {
        Object value = moduleMap.get("translationGaps");
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadFileYaml(Path path) {
        LoaderOptions loaderOptions = new LoaderOptions();
        Yaml yaml = new Yaml(new SafeConstructor(loaderOptions));
        try (var input = Files.newInputStream(path)) {
            return (Map<String, Object>) yaml.load(input);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load " + path, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> inventories(Map<String, Object> inventoryRoot) {
        return (List<Map<String, Object>>) inventoryRoot.get("inventories");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entriesList(Object entries) {
        return (List<Map<String, Object>>) entries;
    }
}
