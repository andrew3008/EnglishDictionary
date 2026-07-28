package space.br1440.platform.tracing.bench.jmxdelta;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class S07JmxReadOnlyDeltaEvidenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final File EVIDENCE_ROOT = new File(
            System.getProperty(
                    "s07.evidence.root",
                    "docs/research/evidence/jmx-read-only-delta"));

    private static final String[] REQUIRED = {
            "baseline-reproduction.json",
            "member-inventory.json",
            "objectname-decision.json",
            "consumer-map.json",
            "external-consumer-risk.json",
            "mutation-deletion-map.json",
            "target-read-contract.json",
            "before-after-delta.json",
            "candidate-scorecard.json",
            "decision-summary.json"
    };

    private static final Set<String> DISPOSITIONS = Set.of(
            "RETAIN_EXACT",
            "RETAIN_SEMANTICS_CLARIFIED",
            "RETAIN_RENAMED",
            "MOVE_TO_PRIVATE_ONLY",
            "DELETE_MUTATION",
            "DELETE_OBSOLETE_READ",
            "REPLACE_WITH_AGGREGATE_READ",
            "ADD_NEW_READ",
            "E7_BLOCKED_EXTERNAL_OWNER_REVIEW");

    private static final String[] REQUIRED_MEMBER_FIELDS = {
            "baselineMemberId",
            "objectName",
            "kind",
            "name",
            "readable",
            "writable",
            "returnOpenType",
            "parameterOpenTypes",
            "currentSemanticMeaning",
            "currentImplementationOwner",
            "currentRepositoryConsumers",
            "externalConsumerStatus",
            "targetDisposition",
            "targetSemanticMeaning",
            "migrationRequirement",
            "reason",
            "verification"
    };

    @Test
    @DisplayName("all S-07 evidence files exist and parse")
    void evidenceFilesExist() throws IOException {
        assertThat(EVIDENCE_ROOT.isDirectory()).isTrue();
        for (String name : REQUIRED) {
            File file = new File(EVIDENCE_ROOT, name);
            assertThat(file).as(name).exists();
            MAPPER.readTree(file);
        }
    }

    @Test
    @DisplayName("baseline reproduction reports PASS with exact counts")
    void baselineReproductionPass() throws IOException {
        JsonNode node = read("baseline-reproduction.json");
        assertThat(node.path("result").asText()).isEqualTo("PASS");
        assertThat(node.path("liveMatchesCommittedFixture").asBoolean()).isTrue();
        assertThat(node.path("fixtureCounts").path("objectNameCount").asInt()).isEqualTo(8);
        assertThat(node.path("fixtureCounts").path("memberCount").asInt()).isEqualTo(63);
        assertThat(node.path("fixtureCounts").path("readOnlyMemberCount").asInt()).isEqualTo(47);
        assertThat(node.path("fixtureCounts").path("mutatingMemberCount").asInt()).isEqualTo(16);
    }

    @Test
    @DisplayName("member inventory has exactly 63 members with required fields and dispositions")
    void memberInventoryComplete() throws IOException {
        JsonNode inv = read("member-inventory.json");
        assertThat(inv.path("memberCount").asInt()).isEqualTo(63);
        assertThat(inv.path("members")).hasSize(63);
        Set<String> ids = new HashSet<>();
        for (JsonNode member : inv.path("members")) {
            for (String field : REQUIRED_MEMBER_FIELDS) {
                assertThat(member.has(field)).as("%s missing %s", member.path("baselineMemberId"), field).isTrue();
            }
            String disposition = member.path("targetDisposition").asText();
            assertThat(DISPOSITIONS).contains(disposition);
            assertThat(ids.add(member.path("baselineMemberId").asText())).isTrue();
        }
    }

    @Test
    @DisplayName("exactly 16 baseline mutations are mapped for platform deletion or private-only")
    void sixteenMutationsMapped() throws IOException {
        JsonNode map = read("mutation-deletion-map.json");
        assertThat(map.path("requiredSemanticMutations").asInt()).isEqualTo(16);
        assertThat(map.path("mutations")).hasSize(16);
        int privateOnly = 0;
        int deleted = 0;
        for (JsonNode mutation : map.path("mutations")) {
            String disposition = mutation.path("disposition").asText();
            assertThat(disposition).isIn("DELETE_MUTATION", "MOVE_TO_PRIVATE_ONLY", "DELETE_OBSOLETE_READ",
                    "RETAIN_SEMANTICS_CLARIFIED");
            if ("MOVE_TO_PRIVATE_ONLY".equals(disposition)) {
                privateOnly++;
                assertThat(mutation.path("name").asText()).isEqualTo("applyPolicy");
            }
            if ("DELETED".equals(mutation.path("platformAfterP6").asText())
                    || "PRIVATE_ONLY".equals(mutation.path("platformAfterP6").asText())
                    || "ABSENT_OR_READ_ONLY".equals(mutation.path("platformAfterP6").asText())) {
                deleted++;
            }
        }
        assertThat(privateOnly).isEqualTo(1);
        assertThat(deleted).isEqualTo(16);
    }

    @Test
    @DisplayName("before/after arithmetic is exact and platform mutations are zero")
    void beforeAfterArithmetic() throws IOException {
        JsonNode delta = read("before-after-delta.json");
        assertThat(delta.path("before").path("objectNames").asInt()).isEqualTo(8);
        assertThat(delta.path("before").path("members").asInt()).isEqualTo(63);
        assertThat(delta.path("before").path("readOnly").asInt()).isEqualTo(47);
        assertThat(delta.path("before").path("mutations").asInt()).isEqualTo(16);
        assertThat(delta.path("after").path("platformObjectNames").asInt()).isEqualTo(8);
        assertThat(delta.path("after").path("platformMutations").asInt()).isEqualTo(0);
        assertThat(delta.path("after").path("platformMembers").asInt()).isEqualTo(56);
        assertThat(delta.path("after").path("platformReadOnly").asInt()).isEqualTo(56);
        assertThat(delta.path("after").path("privateMutations").asInt()).isEqualTo(1);
        assertThat(delta.path("after").path("addedReads").asInt()).isEqualTo(9);
        assertThat(delta.path("arithmeticCheck").path("check").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("decision summary approves exact delta and selects R3")
    void decisionSummary() throws IOException {
        JsonNode summary = read("decision-summary.json");
        assertThat(summary.path("verdict").asText()).isEqualTo("APPROVE_EXACT_JMX_DELTA");
        assertThat(summary.path("e7Recommendation").asText()).isEqualTo("READY_TO_CLOSE_E7");
        assertThat(summary.path("selectedReadContractOption").asText()).isEqualTo("R3");
        assertThat(summary.path("productionCodeChanged").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("candidate scorecard selects R3 with highest weighted score")
    void scorecardSelectsR3() throws IOException {
        JsonNode scorecard = read("candidate-scorecard.json");
        assertThat(scorecard.path("selectedOption").asText()).isEqualTo("R3");
        double best = -1;
        String bestId = null;
        for (JsonNode candidate : scorecard.path("candidates")) {
            double score = candidate.path("weightedScore").asDouble();
            if (score > best) {
                best = score;
                bestId = candidate.path("id").asText();
            }
        }
        assertThat(bestId).isEqualTo("R3");
    }

    @Test
    @DisplayName("all eight ObjectNames have a decision")
    void eightObjectNameDecisions() throws IOException {
        JsonNode decisions = read("objectname-decision.json");
        assertThat(decisions.path("objectNames")).hasSize(8);
        Set<String> allowed = Set.of(
                "RETAIN_EXACT",
                "RETAIN_PRIVATE_AND_PLATFORM_SPLIT",
                "DELETE",
                "RENAME");
        for (JsonNode on : decisions.path("objectNames")) {
            assertThat(allowed).contains(on.path("decision").asText());
        }
    }

    @Test
    @DisplayName("external-consumer risk does not block E7")
    void externalRiskDoesNotBlock() throws IOException {
        JsonNode risk = read("external-consumer-risk.json");
        assertThat(risk.path("e7BlockedExternalOwnerReview").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("target read contract forbids secrets and keeps platform mutations at zero")
    void targetReadContractConstraints() throws IOException {
        JsonNode contract = read("target-read-contract.json");
        assertThat(contract.path("platformMutationsAfterP6").asInt()).isEqualTo(0);
        assertThat(contract.path("acceptedStateVisibility").path("forbidden").toString())
                .contains("raw secrets");
        assertThat(contract.path("addedReads")).hasSize(9);
        assertThat(contract.path("counterReset").path("resetSamplerCounters").asText())
                .isEqualTo("DELETE_MUTATION");
    }

    private static JsonNode read(String name) throws IOException {
        return MAPPER.readTree(new File(EVIDENCE_ROOT, name));
    }
}
