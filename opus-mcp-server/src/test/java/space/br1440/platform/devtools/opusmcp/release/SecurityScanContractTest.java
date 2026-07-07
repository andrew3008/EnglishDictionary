package space.br1440.platform.devtools.opusmcp.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.testsupport.ProjectPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 6B: validates the corporate CVE / security-tooling integration contract (docs, suppression
 * example, build wiring, and — when present — the generated inventory JSON). Deterministic and
 * network-free; the generated-inventory check is skipped when the report has not been produced.
 */
class SecurityScanContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String read(String relative) throws IOException {
        Path p = ProjectPaths.resolve(relative);
        assertThat(Files.exists(p)).as("expected file %s", p).isTrue();
        return Files.readString(p);
    }

    @Test
    void buildDeclaresMachineReadableInventoryAndHandoffTask() throws IOException {
        String build = read("build.gradle");
        assertThat(build).contains("runtime-dependencies.json");
        assertThat(build).contains("runtime-dependencies.txt");
        assertThat(build).contains("tasks.register('securityHandoff')");
        assertThat(build).contains("tasks.register('dependencySecurityReport')");
    }

    @Test
    void contractDocClarifiesInventoryVsVerdictAndPolicy() throws IOException {
        String doc = read("docs/SECURITY-SCAN-CONTRACT.md");
        assertThat(doc.toLowerCase()).contains("inventory");
        assertThat(doc).contains("not a").contains("verdict");
        assertThat(doc).contains("FAIL").contains("WARN").contains("PASS");
        assertThat(doc).contains("suppression");
        assertThat(doc).contains("runtime-dependencies.json");
    }

    @Test
    void suppressionExampleIsValidJsonWithRequiredFields() throws IOException {
        JsonNode root = MAPPER.readTree(read("docs/security-suppressions.example.json"));
        assertThat(root.path("schemaVersion").asInt()).isEqualTo(1);
        JsonNode suppressions = root.path("suppressions");
        assertThat(suppressions.isArray()).isTrue();
        assertThat(suppressions).isNotEmpty();
        for (JsonNode s : suppressions) {
            for (String field : new String[] {"id", "coordinates", "advisory", "reason", "owner", "expiresOn"}) {
                assertThat(s.hasNonNull(field))
                        .as("suppression must declare %s", field)
                        .isTrue();
                assertThat(s.get(field).asText()).as("%s must be non-blank", field).isNotBlank();
            }
        }
    }

    @Test
    void generatedInventoryJsonHasExpectedShapeWhenPresent() throws IOException {
        Path json = ProjectPaths.resolve("build/reports/supply-chain/runtime-dependencies.json");
        assumeTrue(Files.exists(json), "inventory not generated yet (run dependencySecurityReport)");

        JsonNode root = MAPPER.readTree(Files.readString(json));
        assertThat(root.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(root.path("configuration").asText()).isEqualTo("runtimeClasspath");
        assertThat(root.path("project").path("name").asText()).isEqualTo("opus-mcp-server");

        JsonNode deps = root.path("dependencies");
        assertThat(deps.isArray()).isTrue();
        assertThat(deps).isNotEmpty();

        Set<String> seen = new HashSet<>();
        String raw = Files.readString(json);
        for (JsonNode d : deps) {
            for (String field : new String[] {"group", "name", "version", "coordinates"}) {
                assertThat(d.get(field).asText()).as("dependency %s", field).isNotBlank();
            }
            String coords = d.get("coordinates").asText();
            assertThat(seen.add(coords)).as("duplicate coordinates %s", coords).isTrue();
        }
        // No path / user / env leakage in the machine-readable inventory.
        assertThat(raw).doesNotContain("\\Users\\").doesNotContain("/home/");
        assertThat(raw).doesNotContain("OPUS_API_KEY");
    }
}
