package space.br1440.platform.tracing.bench.acceptedstate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Informational / release evidence for exact owner/access-site topology.
 * <p>
 * Excluded from ordinary {@code :platform-tracing-bench:test}. Run via
 * {@code s01OwnerInventoryEvidenceVerify}. Safe renames of Spring delivery adapters must not
 * block aggregate {@code test}; they fail this evidence task until the inventory JSON is updated.
 */
@Tag("s01-inventory-evidence")
@DisplayName("S-01 owner inventory topology drift (evidence/release)")
class S01OwnerInventoryDriftTest {

    private static final Path EVIDENCE = Paths.get(
            "docs", "research", "evidence", "accepted-state-primitive", "repository-symbol-inventory.json");

    private static JsonNode corrective;

    @BeforeAll
    static void loadEvidence() throws IOException {
        Path file = repositoryRoot().resolve(EVIDENCE);
        assertThat(Files.isRegularFile(file)).isTrue();
        JsonNode root = new ObjectMapper().readTree(file.toFile());
        corrective = root.get("correctiveEvidence");
        assertThat(corrective).isNotNull();
    }

    @Test
    @DisplayName("exact delivery-path FQCNs remain inventoried until deliberately updated")
    void exactDeliveryPathTopologyIsRecorded() {
        assertSite("SafeSpanExporter", "PlatformExportControl", "getSafeExporterMetrics");
        assertSite("SafeSpanExporter", "PlatformExportControlMBean", "getSafeExporterMetrics");
        assertSite("SafeSpanExporter", "PlatformTracingJmxClient", "getExportMetrics");
        assertSite("SafeSpanExporter", "LegacyTracingDiagnosticsEndpoint", "tracing()");
        assertSite("SafeSpanExporter", "ConfigServerTracingPolicyAdapter", "onEnvironmentChange");
        assertSite("PlatformPropagationGate", "ConfigServerTracingPolicyAdapter", "onEnvironmentChange");
        assertSite("PlatformLogControl", "ConfigServerTracingPolicyAdapter", "onEnvironmentChange");
    }

    @Test
    @DisplayName("committed inventory JSON remains parseable corrective evidence")
    void committedInventoryIsPresentAndNonEmpty() {
        assertThat(corrective.get("accessSites").size()).isGreaterThan(0);
        assertThat(corrective.get("mutableOwners").size()).isGreaterThan(0);
        assertThat(corrective.get("acceptedRuntimeEnvelopeLeaves").size()).isEqualTo(8);
    }

    private static void assertSite(String owner, String className, String methodFragment) {
        List<JsonNode> matches = new ArrayList<>();
        corrective.get("accessSites").forEach(site -> {
            if (site.get("owner").asText().equals(owner)
                    && site.get("class").asText().equals(className)
                    && site.get("method").asText().contains(methodFragment)) {
                matches.add(site);
            }
        });
        assertThat(matches)
                .as("%s must inventory %s.%s exactly once (update inventory on intentional rename)",
                        owner, className, methodFragment)
                .hasSize(1);
    }

    private static Path repositoryRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new UncheckedIOException(new IOException(
                "repository root with settings.gradle not found above " + Paths.get("").toAbsolutePath()));
    }
}
