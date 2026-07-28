package space.br1440.platform.tracing.bench.readiness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class E8ImplementationReadinessEvidenceTest {

    private static final File EVIDENCE_ROOT = new File(
            System.getProperty(
                    "e8.evidence.root",
                    "docs/research/evidence/implementation-readiness"));

    private static final File REPO_ROOT = new File(
            System.getProperty("e8.repo.root", "."));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> REQUIRED_EVIDENCE_FILES = Set.of(
            "gate-status.json",
            "cross-gate-consistency.json",
            "remaining-risks.json",
            "implementation-readiness.json",
            "decision-summary.json");

    private static final List<String> PRIOR_GATE_DECISION_SUMMARIES = List.of(
            "docs/research/evidence/accepted-state-primitive/decision-summary.json",
            "docs/research/evidence/boot-cloud-bom-refresh/decision-summary.json",
            "docs/research/evidence/jmx-read-only-delta/decision-summary.json");

    @Test
    @DisplayName("all E8 readiness package evidence files exist and parse as JSON")
    void readinessPackageFilesExistAndParse() throws IOException {
        assertThat(EVIDENCE_ROOT.isDirectory()).isTrue();
        for (String name : REQUIRED_EVIDENCE_FILES) {
            File file = new File(EVIDENCE_ROOT, name);
            assertThat(file).as(name).exists();
            MAPPER.readTree(file);
        }
        File report = new File(REPO_ROOT, "docs/research/implementation-readiness-review-e8.md");
        assertThat(report).exists();
        assertThat(Files.size(report.toPath())).isPositive();
    }

    @Test
    @DisplayName("decision summary records IMPLEMENTATION_NOT_READY and NOT_GRANTED GO")
    void decisionSummaryIsNotReady() throws IOException {
        JsonNode summary = MAPPER.readTree(new File(EVIDENCE_ROOT, "decision-summary.json"));
        assertThat(summary.path("verdict").asText()).isEqualTo("IMPLEMENTATION_NOT_READY");
        assertThat(summary.path("implementationGo").asText()).isEqualTo("NOT_GRANTED");
        assertThat(summary.path("legacyDeletionGo").asText()).isEqualTo("BLOCKED");
        assertThat(summary.path("productionCodeChanged").asBoolean()).isFalse();
        assertThat(summary.path("p1Started").asBoolean()).isFalse();
        assertThat(summary.path("p1MayStartNow").asBoolean()).isFalse();
        assertThat(summary.path("additionalTopologyResearchRequired").asBoolean()).isFalse();
        assertThat(summary.path("materialContradictionsAmongE1ToE7").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("gate-status matches expected closed/ready states and decision-summary")
    void gateStatusMatchesExpectedAndDecisionSummary() throws IOException {
        JsonNode gateStatus = MAPPER.readTree(new File(EVIDENCE_ROOT, "gate-status.json"));
        JsonNode summary = MAPPER.readTree(new File(EVIDENCE_ROOT, "decision-summary.json"));
        JsonNode expected = gateStatus.path("expectedIncomingStatus");

        assertThat(gateStatus.path("gates").path("E1").path("status").asText()).isEqualTo("CLOSED");
        assertThat(gateStatus.path("gates").path("E2").path("status").asText()).isEqualTo("CLOSED");
        assertThat(gateStatus.path("gates").path("E3").path("status").asText()).isEqualTo("CLOSED");
        assertThat(gateStatus.path("gates").path("E4").path("status").asText()).isEqualTo("CLOSED");
        assertThat(gateStatus.path("gates").path("E5").path("status").asText()).isEqualTo("CLOSED");
        assertThat(gateStatus.path("gates").path("E6").path("status").asText()).isEqualTo("CLOSED");
        assertThat(gateStatus.path("gates").path("E7").path("status").asText())
                .isEqualTo("READY_TO_CLOSE");
        assertThat(gateStatus.path("gates").path("E8").path("status").asText()).isEqualTo("OPEN");
        assertThat(gateStatus.path("gates").path("E9").path("status").asText()).isEqualTo("CLOSED");

        assertThat(expected.path("E7").asText()).isEqualTo("READY_TO_CLOSE");
        assertThat(expected.path("implementationGo").asText()).isEqualTo("NOT_GRANTED");

        JsonNode summaryGates = summary.path("gateStatuses");
        for (String gate : List.of("E1", "E2", "E3", "E4", "E5", "E6", "E7", "E8", "E9")) {
            assertThat(summaryGates.path(gate).asText())
                    .as(gate)
                    .isEqualTo(gateStatus.path("gates").path(gate).path("status").asText());
        }
    }

    @Test
    @DisplayName("prior E3/E6/E7 decision summaries exist and do not contradict E8 chain")
    void priorGateEvidenceIsPresentAndConsistent() throws IOException {
        for (String relative : PRIOR_GATE_DECISION_SUMMARIES) {
            File file = new File(REPO_ROOT, relative);
            assertThat(file).as(relative).exists();
            MAPPER.readTree(file);
        }

        JsonNode e3 = MAPPER.readTree(new File(
                REPO_ROOT,
                "docs/research/evidence/accepted-state-primitive/decision-summary.json"));
        JsonNode e6 = MAPPER.readTree(new File(
                REPO_ROOT,
                "docs/research/evidence/boot-cloud-bom-refresh/decision-summary.json"));
        JsonNode e7 = MAPPER.readTree(new File(
                REPO_ROOT,
                "docs/research/evidence/jmx-read-only-delta/decision-summary.json"));

        assertThat(e3.path("verdict").asText()).isEqualTo("IMPLEMENT_AGENT_SPECIFIC_PRIMITIVE");
        assertThat(e3.path("implementationGo").asText()).isEqualTo("NOT_GRANTED");
        assertThat(e6.path("verdict").asText())
                .isEqualTo("APPROVE_BOOT_CLOUD_BASELINE_AND_REFRESH_DELIVERY_CONTRACT");
        assertThat(e6.path("selectedBootVersion").asText()).isEqualTo("3.5.5");
        assertThat(e6.path("selectedSpringCloudTrain").asText()).isEqualTo("2025.0.0");
        assertThat(e7.path("verdict").asText()).isEqualTo("APPROVE_EXACT_JMX_DELTA");
        assertThat(e7.path("e7Recommendation").asText()).isEqualTo("READY_TO_CLOSE_E7");
        assertThat(e7.path("selectedReadContractOption").asText()).isEqualTo("R3");
        assertThat(e7.path("target").path("platformMutations").asInt()).isZero();
        assertThat(e7.path("target").path("privateMutations").asInt()).isEqualTo(1);

        // Committed S-07 records wire-schema as an E8 blocker; later refinements may place
        // the same authority on privateWriter.exactPayloadSchemaAuthority instead.
        boolean wireSchemaDeferred =
                e7.path("openBlockersForE8").toString().toLowerCase().contains("wire schema")
                        || e7.path("privateWriter")
                                .path("exactPayloadSchemaAuthority")
                                .asText()
                                .toLowerCase()
                                .contains("wire-schema");
        assertThat(wireSchemaDeferred).isTrue();
    }

    @Test
    @DisplayName("cross-gate consistency reports no material contradictions and open schema gap")
    void crossGateConsistencyHasNoMaterialContradictions() throws IOException {
        JsonNode consistency = MAPPER.readTree(
                new File(EVIDENCE_ROOT, "cross-gate-consistency.json"));
        assertThat(consistency.path("overallConsistency").asText())
                .isEqualTo("CONSISTENT_WITH_OPEN_P0_SCHEMA_GAP");
        assertThat(consistency.path("materialContradictionsAmongE1ToE7").isArray()).isTrue();
        assertThat(consistency.path("materialContradictionsAmongE1ToE7")).isEmpty();

        JsonNode absence = consistency.path("absenceChecks");
        for (String key : List.of(
                "duplicateOwnership",
                "contradictingContracts",
                "twoSourcesOfTruth",
                "runtimeAmbiguity")) {
            assertThat(absence.path(key).path("result").asText()).as(key).isEqualTo("ABSENT");
        }
        String legacyResult = absence.path("legacyProtocolResurrection").path("result").asText();
        assertThat(legacyResult).isIn("ABSENT", "ABSENT_ON_PLATFORM");

        boolean wireSchemaOpen = false;
        for (JsonNode stage : consistency.path("ownershipChain")) {
            if ("wireSchema".equals(stage.path("stage").asText())) {
                assertThat(stage.path("status").asText()).isEqualTo("OPEN");
                wireSchemaOpen = true;
            }
        }
        assertThat(wireSchemaOpen).isTrue();
    }

    @Test
    @DisplayName("implementation-readiness and remaining-risks agree with decision-summary")
    void readinessAndRisksAgreeWithDecisionSummary() throws IOException {
        JsonNode summary = MAPPER.readTree(new File(EVIDENCE_ROOT, "decision-summary.json"));
        JsonNode readiness = MAPPER.readTree(
                new File(EVIDENCE_ROOT, "implementation-readiness.json"));
        JsonNode risks = MAPPER.readTree(new File(EVIDENCE_ROOT, "remaining-risks.json"));

        assertThat(readiness.path("verdict").asText())
                .isEqualTo(summary.path("verdict").asText());
        assertThat(readiness.path("implementationGo").asText())
                .isEqualTo(summary.path("implementationGo").asText());
        assertThat(readiness.path("p1MayStartWithoutNewResearchAdrOrArchitectureDecisions")
                .asBoolean())
                .isFalse();
        assertThat(readiness.path("scores").path("implementationReadiness").asInt())
                .isEqualTo(summary.path("scores").path("implementationReadiness").asInt());

        assertThat(risks.path("architectureRisks").isArray()).isTrue();
        assertThat(risks.path("implementationRisks").isArray()).isTrue();
        assertThat(risks.path("operationalRolloutRisks").isArray()).isTrue();
        assertThat(risks.path("architectureRisks")).isNotEmpty();
        assertThat(risks.path("implementationRisks")).isNotEmpty();
        assertThat(risks.path("operationalRolloutRisks")).isNotEmpty();

        Set<String> summaryBlockers = textArrayToSet(summary.path("architectureBlockers"));
        assertThat(summaryBlockers).contains("p0-wire-schema-contract");
        assertThat(summaryBlockers).contains("p0-wire-schema-bounds");
        assertThat(summaryBlockers).contains("formal E7 closure");
    }

    @Test
    @DisplayName("readiness package declares complete artifact set with no duplicates")
    void readinessPackageIsComplete() throws IOException {
        JsonNode summary = MAPPER.readTree(new File(EVIDENCE_ROOT, "decision-summary.json"));
        Set<String> required = textArrayToSet(summary.path("requiredEvidenceFiles"));
        assertThat(required).isEqualTo(REQUIRED_EVIDENCE_FILES);

        Set<String> artifacts = textArrayToSet(summary.path("artifacts"));
        assertThat(artifacts).contains(
                "docs/research/implementation-readiness-review-e8.md");
        for (String name : REQUIRED_EVIDENCE_FILES) {
            assertThat(artifacts)
                    .contains("docs/research/evidence/implementation-readiness/" + name);
        }
        assertThat(artifacts.size()).isEqualTo(new LinkedHashSet<>(artifacts).size());
    }

    private static Set<String> textArrayToSet(JsonNode arrayNode) {
        Set<String> values = new LinkedHashSet<>();
        Iterator<JsonNode> elements = arrayNode.elements();
        while (elements.hasNext()) {
            values.add(elements.next().asText());
        }
        return values;
    }
}
