package space.br1440.platform.tracing.bench.readiness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class E8ImplementationReadinessEvidenceTest {

    private static final File REPO_ROOT = locateRepositoryRoot();

    private static final File EVIDENCE_ROOT = evidenceRoot();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> REQUIRED_EVIDENCE_FILES = Set.of(
            "gate-status.json",
            "cross-gate-consistency.json",
            "remaining-risks.json",
            "implementation-readiness.json",
            "decision-summary.json",
            "p0-wire-schema-contract.json",
            "p0-wire-schema-bounds.json",
            "committee-readiness-checklist.json",
            "scenario-acceptance.json",
            "rollback-acceptance.json",
            "security-debt-acceptance.json");

    private static final List<String> CATEGORY_A_FIELDS = List.of(
            "samplingEnabled",
            "samplingRatio",
            "samplingRouteRatios",
            "validationEnabled",
            "validationStrict",
            "exporterEnabled",
            "traceControlHeadersEnabled",
            "diagnosticsLogLevel");

    private static final List<String> REQUEST_FIELDS = List.of(
            "protocolVersion",
            "expectedRuntimeInstanceId",
            "expectedAcceptedSequence",
            "attemptId",
            "sourceAuthority",
            "applyTrigger",
            "sourceRevision",
            "candidateDigest",
            "runtimePolicy");

    private static final List<String> RESPONSE_FIELDS = List.of(
            "protocolVersion",
            "outcome",
            "attemptId",
            "runtimeInstanceId",
            "acceptedSequence",
            "payloadDigest",
            "restartRequired",
            "restartRequiredKeys",
            "messageCode",
            "violationCodes");

    @Test
    @DisplayName("E8 evidence package is complete and every JSON artifact parses")
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
    @DisplayName("E8 is technically ready for committee review without self-granting GO")
    void decisionSummaryIsReadyForCommittee() throws IOException {
        JsonNode summary = read("decision-summary.json");

        assertThat(summary.path("overallResult").asText()).isEqualTo("PASS");
        assertThat(summary.path("verdict").asText()).isEqualTo("READY_FOR_COMMITTEE_APPROVAL");
        assertThat(summary.path("technicalPackageStatus").asText()).isEqualTo("COMPLETE");
        assertThat(summary.path("implementationGo").asText()).isEqualTo("NOT_GRANTED");
        assertThat(summary.path("legacyDeletionGo").asText()).isEqualTo("BLOCKED");
        assertThat(summary.path("technicalBlockers")).isEmpty();
        assertThat(summary.path("committeeActionsRequired")).hasSize(4);
        assertThat(summary.path("productionCodeChanged").asBoolean()).isFalse();
        assertThat(summary.path("p1Started").asBoolean()).isFalse();
        assertThat(summary.path("p1MayStartNow").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("versioned request, policy and response Open Types are exact")
    void exactWireSchemaIsFrozen() throws IOException {
        JsonNode contract = read("p0-wire-schema-contract.json");

        assertThat(contract.path("contractVersion").asInt()).isEqualTo(1);
        assertThat(contract.path("technicalStatus").asText()).isEqualTo("COMPLETE");
        assertThat(contract.path("approvalStatus").asText())
                .isEqualTo("READY_FOR_COMMITTEE_APPROVAL");
        assertThat(contract.path("operation").path("name").asText()).isEqualTo("applySnapshot");
        assertThat(contract.path("operation").path("capability").asText())
                .isEqualTo("CONFIG_SNAPSHOT_V1");

        assertThat(itemNames(contract.path("request").path("items")))
                .containsExactlyElementsOf(REQUEST_FIELDS);
        assertThat(itemNames(contract.path("runtimePolicy").path("items")))
                .containsExactlyElementsOf(CATEGORY_A_FIELDS);
        assertThat(contract.path("runtimePolicy").path("leafCount").asInt()).isEqualTo(8);
        assertThat(itemNames(contract.path("response").path("items")))
                .containsExactlyElementsOf(RESPONSE_FIELDS);

        assertThat(contract.path("request").path("rejectUnknownItems").asBoolean()).isTrue();
        assertThat(contract.path("request").path("rejectMissingItems").asBoolean()).isTrue();
        assertThat(contract.path("runtimePolicy").path("rejectUnknownItems").asBoolean()).isTrue();
        assertThat(contract.path("runtimePolicy").path("rejectMissingItems").asBoolean()).isTrue();
        assertThat(contract.path("categoryBContract").path("includedInAcceptedState").asBoolean())
                .isFalse();
        assertThat(contract.path("classloaderContract").path("sharedApplicationDto").asBoolean())
                .isFalse();
    }

    @Test
    @DisplayName("route map and payload bounds are numeric, fail-closed and non-placeholder")
    void exactBoundsAreFrozen() throws IOException {
        JsonNode bounds = read("p0-wire-schema-bounds.json");

        assertThat(bounds.path("technicalStatus").asText()).isEqualTo("COMPLETE");
        assertThat(bounds.path("bounds").path("logicalRequestUtf8Bytes")
                .path("maxInclusive").asInt()).isEqualTo(32768);
        assertThat(bounds.path("bounds").path("routeRatioEntries")
                .path("maxInclusive").asInt()).isEqualTo(100);
        assertThat(bounds.path("bounds").path("routePrefixUtf16CodeUnits")
                .path("maxInclusive").asInt()).isEqualTo(255);
        assertThat(bounds.path("numericRules").path("samplingRatio")
                .path("finite").asBoolean()).isTrue();
        assertThat(bounds.path("numericRules").path("routeRatio")
                .path("maxInclusive").asDouble()).isEqualTo(1.0);
        assertThat(bounds.path("failClosed").path("acceptedSequenceOnFailure").asText())
                .isEqualTo("unchanged");

        String serialized = bounds.toString();
        assertThat(serialized).doesNotContain("BOUND_TO_BE_DERIVED");
        assertThat(serialized).contains("New P0 security bound");
    }

    @Test
    @DisplayName("digest, concurrency and outcome semantics preserve E3")
    void wireContractPreservesAcceptedStateSemantics() throws IOException {
        JsonNode contract = read("p0-wire-schema-contract.json");
        JsonNode outcomes = contract.path("outcomeSemantics");

        assertThat(contract.path("canonicalDigest").path("algorithm").asText())
                .isEqualTo("SHA-256");
        assertThat(textArrayToList(contract.path("canonicalDigest").path("fieldOrder")))
                .containsExactlyElementsOf(CATEGORY_A_FIELDS);
        assertThat(outcomes.path("APPLIED").asText()).contains("increments by one");
        assertThat(outcomes.path("UNCHANGED").asText()).contains("No CAS");
        assertThat(outcomes.path("STALE").asText()).contains("no retry or rebase");
        assertThat(outcomes.path("FAILED").asText()).contains("never used for an ordinary stale base");
    }

    @Test
    @DisplayName("gate chain is consistent and only committee decisions remain")
    void gateChainIsReadyForCommitteeDecision() throws IOException {
        JsonNode gateStatus = read("gate-status.json");
        JsonNode consistency = read("cross-gate-consistency.json");
        JsonNode summary = read("decision-summary.json");

        assertThat(gateStatus.path("gates").path("E7").path("status").asText())
                .isEqualTo("READY_TO_CLOSE");
        assertThat(gateStatus.path("gates").path("E8").path("status").asText())
                .isEqualTo("READY_FOR_COMMITTEE_APPROVAL");
        assertThat(consistency.path("overallConsistency").asText())
                .isEqualTo("CONSISTENT_AND_TECHNICALLY_COMPLETE_PENDING_COMMITTEE_DECISION");
        assertThat(consistency.path("materialContradictionsAmongE1ToE7")).isEmpty();

        for (String gate : List.of("E1", "E2", "E3", "E4", "E5", "E6", "E7", "E8", "E9")) {
            assertThat(summary.path("gateStatuses").path(gate).asText())
                    .as(gate)
                    .isEqualTo(gateStatus.path("gates").path(gate).path("status").asText());
        }
        for (String key : List.of(
                "duplicateOwnership",
                "contradictingContracts",
                "twoSourcesOfTruth",
                "legacyProtocolResurrection",
                "runtimeAmbiguity",
                "genericWireMap",
                "sharedCrossClassloaderDto",
                "categoryBInAcceptedState")) {
            assertThat(consistency.path("absenceChecks").path(key).path("result").asText())
                    .as(key)
                    .isEqualTo("ABSENT");
        }
    }

    @Test
    @DisplayName("committee checklist separates evidence completeness from authorization")
    void committeeChecklistDoesNotForgeApproval() throws IOException {
        JsonNode checklist = read("committee-readiness-checklist.json");

        assertThat(checklist.path("technicalPackageStatus").asText())
                .isEqualTo("READY_FOR_COMMITTEE_APPROVAL");
        assertThat(checklist.path("implementationGo").asText()).isEqualTo("NOT_GRANTED");
        assertThat(checklist.path("legacyDeletionGo").asText()).isEqualTo("BLOCKED");
        assertThat(checklist.path("forbiddenUntilGo").toString())
                .contains("P1 production implementation");

        Set<String> statuses = new LinkedHashSet<>();
        for (JsonNode item : checklist.path("items")) {
            statuses.add(item.path("status").asText());
        }
        assertThat(statuses).contains("PASS", "READY_TO_CLOSE", "READY_FOR_APPROVAL",
                "PENDING_COMMITTEE");
    }

    @Test
    @DisplayName("scenario, rollback and security records cover the section 28 handoff")
    void section28HandoffIsComplete() throws IOException {
        JsonNode scenarios = read("scenario-acceptance.json");
        JsonNode rollback = read("rollback-acceptance.json");
        JsonNode security = read("security-debt-acceptance.json");

        assertThat(scenarios.path("scenarios")).hasSize(10);
        assertThat(scenarios.path("scenarios").toString())
                .contains("STALE_BASE", "CONFIG_SERVER_OUTAGE_AFTER_READY", "STRUCTURAL_DRIFT");
        assertThat(rollback.path("units")).hasSize(8);
        assertThat(rollback.path("forbidden").toString())
                .contains("Restoring legacy platform-server writers");
        assertThat(security.path("scope").asText()).contains("Pre-production only");
        assertThat(security.path("mandatoryControls").toString())
                .contains("Production rollout remains forbidden");
        assertThat(security.path("committeeAction").asText()).contains("does not self-approve");
    }

    @Test
    @DisplayName("decision summary declares every required artifact exactly once")
    void readinessPackageIsComplete() throws IOException {
        JsonNode summary = read("decision-summary.json");
        Set<String> required = textArrayToSet(summary.path("requiredEvidenceFiles"));
        assertThat(required).isEqualTo(REQUIRED_EVIDENCE_FILES);

        Set<String> artifacts = textArrayToSet(summary.path("artifacts"));
        assertThat(artifacts).contains("docs/research/implementation-readiness-review-e8.md");
        for (String name : REQUIRED_EVIDENCE_FILES) {
            assertThat(artifacts)
                    .contains("docs/research/evidence/implementation-readiness/" + name);
        }
        assertThat(artifacts).hasSize(REQUIRED_EVIDENCE_FILES.size() + 1);
    }

    private static JsonNode read(String name) throws IOException {
        return MAPPER.readTree(new File(EVIDENCE_ROOT, name));
    }

    private static File locateRepositoryRoot() {
        File configured = new File(System.getProperty("e8.repo.root", "."))
                .getAbsoluteFile()
                .toPath()
                .normalize()
                .toFile();
        if (new File(configured, "docs/research").isDirectory()) {
            return configured;
        }
        File parent = configured.getParentFile();
        if (parent != null && new File(parent, "docs/research").isDirectory()) {
            return parent;
        }
        return configured;
    }

    private static File evidenceRoot() {
        String configured = System.getProperty("e8.evidence.root");
        if (configured != null && !configured.isBlank()) {
            return new File(configured);
        }
        return new File(REPO_ROOT, "docs/research/evidence/implementation-readiness");
    }

    private static List<String> itemNames(JsonNode items) {
        return textValues(items, "name");
    }

    private static List<String> textValues(JsonNode arrayNode, String field) {
        ArrayList<String> values = new ArrayList<>();
        for (JsonNode element : arrayNode) {
            values.add(element.path(field).asText());
        }
        return List.copyOf(values);
    }

    private static List<String> textArrayToList(JsonNode arrayNode) {
        ArrayList<String> values = new ArrayList<>();
        for (JsonNode element : arrayNode) {
            values.add(element.asText());
        }
        return List.copyOf(values);
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
