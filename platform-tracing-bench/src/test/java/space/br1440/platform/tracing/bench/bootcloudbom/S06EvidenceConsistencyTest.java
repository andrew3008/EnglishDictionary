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
            "resolved-refresh-delivery-graph.json",
            "corporate-bus-readiness.json",
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
    @DisplayName("decision summary approves dependency baseline and refresh delivery contract")
    void decisionSummaryMatchesApprovedBaseline() throws IOException {
        JsonNode summary = MAPPER.readTree(new File(EVIDENCE_ROOT, "decision-summary.json"));
        assertThat(summary.path("verdict").asText())
                .isEqualTo("APPROVE_BOOT_CLOUD_BASELINE_AND_REFRESH_DELIVERY_CONTRACT");
        assertThat(summary.path("selectedBootVersion").asText()).isEqualTo("3.5.5");
        assertThat(summary.path("selectedSpringCloudTrain").asText()).isEqualTo("2025.0.0");
        assertThat(summary.path("e6Recommendation").asText())
                .isEqualTo("READY_TO_CLOSE_E6");
    }

    @Test
    @DisplayName("current inventory records the corrected Spring Cloud BOM publication")
    void currentInventoryDocumentsCorrectedBom() throws IOException {
        JsonNode inventory = MAPPER.readTree(new File(EVIDENCE_ROOT, "current-dependency-inventory.json"));
        JsonNode publication = inventory.path("currentPublication");
        assertThat(publication.path("springBootBaseline").asText()).isEqualTo("3.5.5");
        assertThat(publication.path("springCloudTrain").asText()).isEqualTo("2025.0.0");
        assertThat(publication.path("misaligned").asBoolean()).isFalse();
        assertThat(publication.path("legacyContextConstraintPresent").asBoolean()).isFalse();
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
        assertThat(file).exists();
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
    @DisplayName("exact Config Server and client Bus graphs are fixed to the approved train")
    void refreshDeliveryGraphIsExact() throws IOException {
        JsonNode graph = MAPPER.readTree(
                new File(EVIDENCE_ROOT, "resolved-refresh-delivery-graph.json"));
        for (String side : List.of("configServerDelivery", "configClientDelivery")) {
            JsonNode delivery = graph.path(side);
            assertThat(delivery.path("spring-cloud-bus").asText()).isEqualTo("4.3.0");
            assertThat(delivery.path("spring-cloud-stream").asText()).isEqualTo("4.3.0");
            assertThat(delivery.path("spring-cloud-stream-binder-kafka").asText())
                    .isEqualTo("4.3.0");
            assertThat(delivery.path("spring-kafka").asText()).isEqualTo("3.3.9");
            assertThat(delivery.path("kafka-clients").asText()).isEqualTo("3.9.1");
            assertThat(delivery.path("spring-integration-core").asText()).isEqualTo("6.5.1");
        }
        assertThat(graph.path("ordinaryTracingStartersContainDeliveryGraph").asBoolean()).isFalse();
        assertThat(graph.path("result").asText()).isEqualTo("PASS");
    }

    @Test
    @DisplayName("approved corporate Bus contract closes E6 but retains production checks")
    void approvedExternalDeliveryContractClosesE6DesignGate() throws IOException {
        JsonNode readiness = MAPPER.readTree(
                new File(EVIDENCE_ROOT, "corporate-bus-readiness.json"));
        assertThat(readiness.path("status").asText())
                .isEqualTo("CORPORATE_BUS_CONFIRMED");
        assertThat(readiness.path("e6Status").asText())
                .isEqualTo("READY_TO_CLOSE_E6");
        assertThat(readiness.path("decision").path("approvalState").asText())
                .isEqualTo("APPROVED");
        assertThat(readiness.path("decision").path("approvedBy").asText()).isNotBlank();
        assertThat(readiness.path("decision").path("approvalReference").asText()).isNotBlank();
        Iterator<JsonNode> approvals = readiness.path("requiredApprovals").elements();
        while (approvals.hasNext()) {
            assertThat(approvals.next().asText()).isNotBlank();
        }
        Iterator<JsonNode> monitorControls = readiness.path("monitorSecurity").elements();
        while (monitorControls.hasNext()) {
            assertThat(monitorControls.next().asText()).isNotBlank();
        }
        JsonNode hardening = readiness.path("busHardening");
        assertThat(hardening.path("spring.cloud.bus.ack.enabled").asBoolean()).isFalse();
        assertThat(hardening.path("spring.cloud.bus.trace.enabled").asBoolean()).isFalse();
        assertThat(hardening.path("spring.cloud.bus.env.enabled").asBoolean()).isFalse();
        assertThat(hardening.path("spring.cloud.bus.shutdown.enabled").asBoolean()).isFalse();
        assertThat(hardening.path("spring.cloud.bus.refresh.enabled").asBoolean()).isTrue();
        assertThat(hardening.path("spring.cloud.stream.kafka.binder.autoCreateTopics").asBoolean())
                .isFalse();
        assertThat(hardening.path("spring.cloud.stream.kafka.binder.autoAddPartitions").asBoolean())
                .isFalse();
        assertThat(hardening.path("spring.cloud.stream.kafka.binder.requiredAcks").asText())
                .isEqualTo("all");
        assertThat(hardening.path("producer.enable.idempotence").asBoolean()).isTrue();
        assertThat(readiness.path("verificationRequiredBeforeProduction")).hasSize(7);
    }

    @Test
    @DisplayName("C1–C7 results are PASS for approved-stack verification gates")
    void c1ThroughC7Pass() throws IOException {
        JsonNode results = MAPPER.readTree(new File(EVIDENCE_ROOT, "c1-c7-results.json"));
        JsonNode gateResults = results.path("gates");
        assertThat(gateResults.size()).isEqualTo(7);
        Iterator<String> gates = gateResults.fieldNames();
        while (gates.hasNext()) {
            String gate = gates.next();
            assertThat(gateResults.path(gate).path("status").asText())
                    .as("gate %s", gate)
                    .isEqualTo("PASS");
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
