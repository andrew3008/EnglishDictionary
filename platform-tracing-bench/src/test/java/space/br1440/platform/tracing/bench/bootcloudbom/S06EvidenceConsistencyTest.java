package space.br1440.platform.tracing.bench.bootcloudbom;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class S06EvidenceConsistencyTest {

    private static final File EVIDENCE_ROOT = new File(
            System.getProperty("s06.evidence.root", "docs/research/evidence/boot-cloud-bom-refresh"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> REQUIRED_EVIDENCE_FILES = Set.of(
            "current-dependency-inventory.json",
            "candidate-baselines.json",
            "c1-c7-results.json",
            "published-metadata-summary.json",
            "refresh-delivery-decision.json",
            "decision-summary.json");

    @Test
    @DisplayName("all committed S-06 machine-readable evidence files exist and parse as JSON")
    void evidenceFilesExistAndParse() throws IOException {
        assertThat(EVIDENCE_ROOT.isDirectory()).isTrue();
        for (String name : REQUIRED_EVIDENCE_FILES) {
            File file = new File(EVIDENCE_ROOT, name);
            assertThat(file).exists();
            MAPPER.readTree(file);
        }
    }

    @Test
    @DisplayName("decision summary recommends APPROVE_BOOT_CLOUD_BASELINE with Boot 3.5.5 and Cloud 2025.0.0")
    void decisionSummaryMatchesApprovedBaseline() throws IOException {
        JsonNode summary = MAPPER.readTree(new File(EVIDENCE_ROOT, "decision-summary.json"));
        assertThat(summary.path("verdict").asText()).isEqualTo("APPROVE_BOOT_CLOUD_BASELINE");
        assertThat(summary.path("selectedBootVersion").asText()).isEqualTo("3.5.5");
        assertThat(summary.path("selectedSpringCloudTrain").asText()).isEqualTo("2025.0.0");
        assertThat(summary.path("e6Recommendation").asText()).isEqualTo("READY_TO_CLOSE_E6");
    }

    @Test
    @DisplayName("current inventory documents misaligned spring-cloud-context 4.1.5 BOM defect")
    void currentInventoryDocumentsBomDefect() throws IOException {
        JsonNode inventory = MAPPER.readTree(new File(EVIDENCE_ROOT, "current-dependency-inventory.json"));
        JsonNode defect = inventory.path("currentPublicationDefect");
        assertThat(defect.path("springCloudContextConstraintVersion").asText()).isEqualTo("4.1.5");
        assertThat(defect.path("springBootBaseline").asText()).isEqualTo("3.5.5");
        assertThat(defect.path("misaligned").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("approved BOM contract removes 4.1.5 and imports Northfields train")
    void approvedBomContractExcludes415() throws IOException {
        JsonNode summary = MAPPER.readTree(new File(EVIDENCE_ROOT, "published-metadata-summary.json"));
        JsonNode approved = summary.path("approvedPublicationContract");
        assertThat(approved.path("removeConstraints").isArray()).isTrue();
        boolean removes415 = false;
        for (JsonNode node : approved.path("removeConstraints")) {
            if ("org.springframework.cloud:spring-cloud-context:4.1.5".equals(node.asText())) {
                removes415 = true;
            }
        }
        assertThat(removes415).isTrue();
        assertThat(approved.path("importPlatforms").toString()).contains("2025.0.0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"published-servlet-consumer-dependencies.txt", "published-reactive-consumer-dependencies.txt"})
    @DisplayName("published consumer graphs exclude Config Client and Agent extension")
    void publishedConsumersExcludeConfigClientAndExtension(String fileName) throws IOException {
        File file = new File(EVIDENCE_ROOT, fileName);
        if (!file.exists()) {
            return;
        }
        List<String> lines = java.nio.file.Files.readAllLines(file.toPath());
        assertThat(lines).doesNotContain("spring-cloud-config-client");
        assertThat(lines).doesNotContain("spring-cloud-starter-config");
        assertThat(lines).doesNotContain("spring-cloud-context");
        assertThat(lines).doesNotContain("platform-tracing-otel-javaagent-extension");
    }

    @Test
    @DisplayName("refresh delivery selects Bus+Monitor without application HTTP mutation or polling")
    void refreshDeliveryContract() throws IOException {
        JsonNode decision = MAPPER.readTree(new File(EVIDENCE_ROOT, "refresh-delivery-decision.json"));
        assertThat(decision.path("selectedMechanism").asText())
                .isEqualTo("SPRING_CLOUD_CONFIG_MONITOR_AND_BUS");
        assertThat(decision.path("customPollingClient").asBoolean()).isFalse();
        assertThat(decision.path("applicationFacingHttpMutation").asBoolean()).isFalse();
        assertThat(decision.path("environmentChangeEventAsLocalTrigger").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("C1–C7 results are PASS for approved-stack verification gates")
    void c1ThroughC7Pass() throws IOException {
        JsonNode results = MAPPER.readTree(new File(EVIDENCE_ROOT, "c1-c7-results.json"));
        Iterator<String> gates = results.fieldNames();
        while (gates.hasNext()) {
            String gate = gates.next();
            if (gate.startsWith("C")) {
                assertThat(results.path(gate).path("status").asText())
                        .as("gate %s", gate)
                        .isEqualTo("PASS");
            }
        }
    }

    @Test
    @DisplayName("candidate baselines score Candidate A highest")
    void candidateAScoresHighest() throws IOException {
        JsonNode baselines = MAPPER.readTree(new File(EVIDENCE_ROOT, "candidate-baselines.json"));
        double scoreA = baselines.path("candidates").path(0).path("weightedScore").asDouble();
        double scoreB = baselines.path("candidates").path(1).path("weightedScore").asDouble();
        assertThat(scoreA).isGreaterThan(scoreB);
        assertThat(baselines.path("selectedCandidateId").asText()).isEqualTo("A");
    }
}
