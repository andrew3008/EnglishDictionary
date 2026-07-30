package space.br1440.platform.tracing.bench.jmxdelta;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Post-P6.6 Gate E7 evidence consistency: live domain baseline is read-only,
 * Control projection is read-only, and the sole mutation remains private
 * {@code applySnapshot}.
 */
class S07JmxReadOnlyDeltaEvidenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final File EVIDENCE_ROOT = new File(
            System.getProperty(
                    "s07.evidence.root",
                    "docs/research/evidence/jmx-read-only-delta"));

    private static final File BASELINE_FIXTURE = new File(
            System.getProperty(
                    "s07.baseline.fixture",
                    "platform-tracing-otel-javaagent-extension/src/test/resources/"
                            + "jmx-management-contract-baseline.json"));

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

    private static final Set<String> BASELINE_DISPOSITIONS = Set.of(
            "RETAIN_EXACT",
            "RETAIN_SEMANTICS_CLARIFIED",
            "RETAIN_RENAMED",
            "MOVE_TO_PRIVATE_ONLY",
            "DELETE_MUTATION",
            "DELETE_OBSOLETE_READ",
            "REPLACE_WITH_AGGREGATE_READ",
            "E7_BLOCKED_EXTERNAL_OWNER_REVIEW");

    @Test
    @DisplayName("all S-07 evidence files and the authoritative baseline parse")
    void evidenceFilesExist() throws IOException {
        assertThat(EVIDENCE_ROOT.isDirectory()).isTrue();
        assertThat(BASELINE_FIXTURE).exists();
        MAPPER.readTree(BASELINE_FIXTURE);
        for (String name : REQUIRED) {
            File file = new File(EVIDENCE_ROOT, name);
            assertThat(file).as(name).exists();
            MAPPER.readTree(file);
        }
    }

    @Test
    @DisplayName("baseline reproduction reports the exact live post-P6.6 domain contract")
    void baselineReproductionPass() throws IOException {
        JsonNode node = read("baseline-reproduction.json");
        assertThat(node.path("result").asText()).isEqualTo("PASS");
        assertThat(node.path("liveMatchesCommittedFixture").asBoolean()).isTrue();
        assertThat(node.path("fixtureCounts").path("objectNameCount").asInt()).isEqualTo(7);
        assertThat(node.path("fixtureCounts").path("memberCount").asInt()).isEqualTo(52);
        assertThat(node.path("fixtureCounts").path("readOnlyMemberCount").asInt()).isEqualTo(52);
        assertThat(node.path("fixtureCounts").path("mutatingMemberCount").asInt()).isEqualTo(0);

        BaselineCounts counts = countBaselineMembers(readBaseline());
        assertThat(counts.objectNames()).isEqualTo(7);
        assertThat(counts.members()).isEqualTo(52);
        assertThat(counts.strictReads()).isEqualTo(52);
        assertThat(counts.writableAttributes()).isEqualTo(0);
        assertThat(counts.mutatingOperations()).isEqualTo(0);
    }

    @Test
    @DisplayName("52 inventory rows form an exact identity and signature bijection with baseline")
    void memberInventoryMatchesBaselineExactly() throws IOException {
        JsonNode inventory = read("member-inventory.json");
        JsonNode rows = inventory.path("members");
        assertThat(inventory.path("memberCount").asInt()).isEqualTo(52);
        assertThat(rows).hasSize(52);

        Map<String, JsonNode> expected = flattenBaseline(readBaseline());
        Set<String> ids = new HashSet<>();
        Set<String> identities = new HashSet<>();
        for (JsonNode member : rows) {
            String id = member.path("baselineMemberId").asText();
            assertThat(ids.add(id)).as("duplicate baseline ID %s", id).isTrue();
            assertThat(BASELINE_DISPOSITIONS).contains(member.path("targetDisposition").asText());

            JsonNode baseline = expected.get(id);
            assertThat(baseline).as("missing baseline identity for %s", id).isNotNull();
            assertThat(member.path("objectName").asText()).isEqualTo(baseline.path("objectName").asText());
            assertThat(member.path("kind").asText()).isEqualTo(baseline.path("kind").asText());
            assertThat(member.path("name").asText()).isEqualTo(baseline.path("name").asText());
            assertThat(member.path("returnJavaType").asText())
                    .isEqualTo(baseline.path("returnJavaType").asText());
            assertThat(member.path("parameterJavaTypes"))
                    .isEqualTo(baseline.path("parameterJavaTypes"));
            assertThat(member.path("readable").asBoolean()).isEqualTo(baseline.path("readable").asBoolean());
            assertThat(member.path("writable").asBoolean()).isEqualTo(baseline.path("writable").asBoolean());
            assertThat(member.path("returnOpenType").asText()).isNotBlank();
            assertThat(member.path("parameterOpenTypes").size())
                    .isEqualTo(member.path("parameterJavaTypes").size());
            assertThat(member.path("currentRepositoryConsumers")).isNotEmpty();
            assertThat(member.path("migrationOwner").asText()).isNotBlank();
            assertThat(member.path("zeroReferenceProof").asText()).isNotBlank();

            String identity = member.path("objectName").asText()
                    + "|" + member.path("kind").asText()
                    + "|" + member.path("name").asText()
                    + "|" + member.path("parameterJavaTypes");
            assertThat(identities.add(identity)).as("duplicate member identity %s", identity).isTrue();
        }
        assertThat(ids).containsExactlyInAnyOrderElementsOf(expected.keySet());
    }

    @Test
    @DisplayName("post-P6.6 platform domain has zero semantic mutations; private applySnapshot remains")
    void zeroPlatformMutationsMapped() throws IOException {
        JsonNode map = read("mutation-deletion-map.json");
        assertThat(map.path("requiredSemanticMutations").asInt()).isEqualTo(0);
        assertThat(map.path("writableAttributeCount").asInt()).isEqualTo(0);
        assertThat(map.path("mutatingOperationCount").asInt()).isEqualTo(0);
        assertThat(map.path("mutations")).isEmpty();

        Set<String> expectedMutationIds = baselineMutationIds(readBaseline());
        assertThat(expectedMutationIds).isEmpty();

        JsonNode legacy = map.path("legacyPartialProtocol");
        assertThat(legacy.path("disposition").asText()).isEqualTo("DELETE_MUTATION");
        assertThat(legacy.path("relocated").asBoolean()).isFalse();
        assertThat(legacy.path("replacementTargetMemberId").asText()).isEqualTo("PRIV-W01");
    }

    @Test
    @DisplayName("before and after arithmetic is independently reproducible from exact rows")
    void beforeAfterArithmetic() throws IOException {
        JsonNode inventory = read("member-inventory.json");
        JsonNode target = read("target-read-contract.json");
        JsonNode delta = read("before-after-delta.json");

        Map<String, Integer> dispositions = new HashMap<>();
        for (JsonNode member : inventory.path("members")) {
            dispositions.merge(member.path("targetDisposition").asText(), 1, Integer::sum);
        }
        assertThat(dispositions).containsEntry("RETAIN_EXACT", 52);
        assertThat(dispositions.getOrDefault("MOVE_TO_PRIVATE_ONLY", 0)).isZero();
        assertThat(dispositions.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(52);

        long convertedWritable = target.path("platformMembers").findValuesAsText("source").stream()
                .filter("CONVERTED_WRITABLE_ATTRIBUTE"::equals)
                .count();
        long replacementReads = target.path("platformMembers").findValuesAsText("source").stream()
                .filter("REPLACEMENT_READ"::equals)
                .count();
        long addedReads = target.path("platformMembers").findValuesAsText("source").stream()
                .filter("ADD_NEW_READ"::equals)
                .count();

        assertThat(convertedWritable).isZero();
        assertThat(replacementReads).isZero();
        assertThat(addedReads).isEqualTo(8);
        assertThat(target.path("platformMembers")).hasSize(60);
        assertThat(delta.path("after").path("platformMembers").asInt()).isEqualTo(60);
        assertThat(delta.path("after").path("privateReadMembers").asInt()).isEqualTo(7);
        assertThat(delta.path("after").path("platformMutations").asInt()).isZero();
    }

    @Test
    @DisplayName("target platform contract has exactly 60 unique read-only Open-Type members")
    void targetPlatformContractIsExact() throws IOException {
        JsonNode contract = read("target-read-contract.json");
        JsonNode platformMembers = contract.path("platformMembers");
        assertThat(contract.path("platformMutationsAfterP6").asInt()).isZero();
        assertThat(platformMembers).hasSize(60);

        Set<String> targetIds = new HashSet<>();
        Set<String> identities = new HashSet<>();
        Set<String> objectNames = new HashSet<>();
        for (JsonNode member : platformMembers) {
            assertThat(targetIds.add(member.path("targetMemberId").asText())).isTrue();
            assertThat(member.path("server").asText()).isEqualTo("PLATFORM");
            assertThat(member.path("readable").asBoolean()).isTrue();
            assertThat(member.path("writable").asBoolean()).isFalse();
            assertThat(member.path("returnOpenType").asText()).isNotBlank();
            assertThat(member.path("source").asText()).isIn(
                    "RETAINED_BASELINE_READ",
                    "CLARIFIED_BASELINE_READ",
                    "CONVERTED_WRITABLE_ATTRIBUTE",
                    "REPLACEMENT_READ",
                    "ADD_NEW_READ");
            objectNames.add(member.path("objectName").asText());
            String identity = member.path("objectName").asText()
                    + "|" + member.path("kind").asText()
                    + "|" + member.path("name").asText()
                    + "|" + member.path("parameterJavaTypes");
            assertThat(identities.add(identity)).as("duplicate target identity %s", identity).isTrue();
        }
        assertThat(objectNames).hasSize(8);
        assertThat(objectNames).contains(
                "space.br1440.platform.tracing:name=PlatformControlProtocol,type=Control");
    }

    @Test
    @DisplayName("private contract is seven private reads plus one applySnapshot writer")
    void privateContractIsNewAndExact() throws IOException {
        JsonNode contract = read("target-read-contract.json");
        JsonNode privateMembers = contract.path("privateMembers");
        assertThat(privateMembers).hasSize(8);

        long reads = privateMembers.findValuesAsText("source").stream()
                .filter("ADD_PRIVATE_READ"::equals)
                .count();
        long mutations = privateMembers.findValuesAsText("source").stream()
                .filter("ADD_PRIVATE_MUTATION"::equals)
                .count();
        Set<String> objectNames = new HashSet<>();
        for (JsonNode member : privateMembers) {
            objectNames.add(member.path("objectName").asText());
        }
        assertThat(reads).isEqualTo(7);
        assertThat(mutations).isEqualTo(1);
        assertThat(objectNames).hasSize(2);

        JsonNode writer = contract.path("privateWriterContract");
        assertThat(writer.path("targetMemberId").asText()).isEqualTo("PRIV-W01");
        assertThat(writer.path("operationName").asText()).isEqualTo("applySnapshot");
        assertThat(writer.path("legacyPartialApplyPolicyDeleted").asBoolean()).isTrue();
        assertThat(writer.path("completeSnapshotSemantics").asText()).contains("patch/partial apply is rejected");
        assertThat(writer.path("requiredCategoryALeaves")).hasSize(8);
        assertThat(writer.path("unknownFieldBehavior").asText()).isEqualTo("REJECT");
        assertThat(writer.path("optimisticConcurrencyMetadata"))
                .containsExactly(
                        MAPPER.getNodeFactory().textNode("runtimeInstanceId"),
                        MAPPER.getNodeFactory().textNode("expectedAcceptedSequence"));
        assertThat(writer.path("exactCompositeTypeAuthority")).hasSize(2);
    }

    @Test
    @DisplayName("ObjectName accounting equals exact target rows on each server")
    void objectNameAccountingIsReproducible() throws IOException {
        JsonNode contract = read("target-read-contract.json");
        JsonNode decisions = read("objectname-decision.json");
        assertThat(decisions.path("platformObjectNameCount").asInt()).isEqualTo(8);
        assertThat(decisions.path("privateObjectNameCount").asInt()).isEqualTo(2);
        assertThat(decisions.path("objectNames")).hasSize(10);

        for (JsonNode decision : decisions.path("objectNames")) {
            String server = decision.path("server").asText();
            String objectName = decision.path("objectName").asText();
            JsonNode rows = "PLATFORM".equals(server)
                    ? contract.path("platformMembers")
                    : contract.path("privateMembers");
            long memberCount = 0;
            long readCount = 0;
            long mutationCount = 0;
            for (JsonNode row : rows) {
                if (!objectName.equals(row.path("objectName").asText())) {
                    continue;
                }
                memberCount++;
                if (row.path("readable").asBoolean()) {
                    readCount++;
                }
                if ("ADD_PRIVATE_MUTATION".equals(row.path("source").asText())) {
                    mutationCount++;
                }
            }
            assertThat(decision.path("memberCount").asLong()).isEqualTo(memberCount);
            assertThat(decision.path("readCount").asLong()).isEqualTo(readCount);
            assertThat(decision.path("mutationCount").asLong()).isEqualTo(mutationCount);
        }
    }

    @Test
    @DisplayName("consumer map accounts for every baseline and newly added target member")
    void consumerMapIsMemberComplete() throws IOException {
        JsonNode consumerMap = read("consumer-map.json");
        JsonNode target = read("target-read-contract.json");
        assertThat(consumerMap.path("baselineMembers")).hasSize(52);

        Set<String> baselineIds = new HashSet<>();
        for (JsonNode member : consumerMap.path("baselineMembers")) {
            baselineIds.add(member.path("baselineMemberId").asText());
            assertThat(member.path("repositoryConsumers")).isNotEmpty();
            assertThat(member.path("externalConsumerRisk").asText()).isNotBlank();
            assertThat(member.path("migrationOwner").asText()).isNotBlank();
            assertThat(member.path("zeroReferenceProof").asText()).isNotBlank();
        }
        assertThat(baselineIds).hasSize(52);

        long expectedAdded = target.path("platformMembers").findValuesAsText("source").stream()
                .filter("ADD_NEW_READ"::equals)
                .count()
                + target.path("privateMembers").size();
        assertThat(consumerMap.path("addedTargetMembers")).hasSize((int) expectedAdded);

        JsonNode externalRisk = read("external-consumer-risk.json");
        assertThat(externalRisk.path("e7BlockedExternalOwnerReview").asBoolean()).isFalse();
        assertThat(externalRisk.path("productionRolloutBlockers")).isNotEmpty();
    }

    @Test
    @DisplayName("decision summary reflects the independently verified post-P6.6 contract")
    void decisionSummary() throws IOException {
        JsonNode summary = read("decision-summary.json");
        assertThat(summary.path("verdict").asText()).isEqualTo("APPROVE_EXACT_JMX_DELTA");
        assertThat(summary.path("e7Recommendation").asText()).isEqualTo("READY_TO_CLOSE_E7");
        assertThat(summary.path("selectedReadContractOption").asText()).isEqualTo("R3");
        assertThat(summary.path("productionCodeChanged").asBoolean()).isFalse();
        assertThat(summary.path("target").path("platformReadOnlyMembers").asInt()).isEqualTo(60);
        assertThat(summary.path("target").path("privateReadMembers").asInt()).isEqualTo(7);
        assertThat(summary.path("target").path("platformMutations").asInt()).isZero();
        assertThat(summary.path("privateCompletePolicyReader").path("targetMemberId").asText())
                .isEqualTo("PRIV-R07");
        assertThat(summary.path("privateCompletePolicyReader").path("status").asText())
                .isEqualTo("APPROVED");
        assertThat(summary.path("privateWriter").path("newReplacementContract").asBoolean()).isTrue();
        assertThat(summary.path("privateWriter").path("legacyPartialProtocolRelocated").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("candidate scorecard still selects R3 with highest weighted score")
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

    private static BaselineCounts countBaselineMembers(JsonNode baseline) {
        int objectNames = baseline.path("objectNames").size();
        int members = 0;
        int strictReads = 0;
        int writableAttributes = 0;
        int mutatingOperations = 0;
        for (JsonNode objectName : baseline.path("objectNames")) {
            for (JsonNode attribute : objectName.path("attributes")) {
                members++;
                if (attribute.path("writable").asBoolean()) {
                    writableAttributes++;
                } else if (attribute.path("readable").asBoolean()) {
                    strictReads++;
                }
            }
            for (JsonNode operation : objectName.path("operations")) {
                members++;
                if (operation.path("mutating").asBoolean()) {
                    mutatingOperations++;
                } else {
                    strictReads++;
                }
            }
        }
        return new BaselineCounts(objectNames, members, strictReads, writableAttributes, mutatingOperations);
    }

    private static Map<String, JsonNode> flattenBaseline(JsonNode baseline) {
        Map<String, JsonNode> result = new HashMap<>();
        int sequence = 0;
        for (JsonNode objectName : baseline.path("objectNames")) {
            for (JsonNode attribute : objectName.path("attributes")) {
                sequence++;
                ObjectNode row = MAPPER.createObjectNode();
                row.put("objectName", objectName.path("objectName").asText());
                row.put("kind", "ATTRIBUTE");
                row.put("name", attribute.path("name").asText());
                row.put("returnJavaType", attribute.path("javaType").asText());
                row.put("readable", attribute.path("readable").asBoolean());
                row.put("writable", attribute.path("writable").asBoolean());
                row.set("parameterJavaTypes", MAPPER.createArrayNode());
                result.put(memberId(sequence), row);
            }
            for (JsonNode operation : objectName.path("operations")) {
                sequence++;
                ArrayNode parameters = MAPPER.createArrayNode();
                for (JsonNode argument : operation.path("arguments")) {
                    parameters.add(argument.path("javaType").asText());
                }
                ObjectNode row = MAPPER.createObjectNode();
                row.put("objectName", objectName.path("objectName").asText());
                row.put("kind", "OPERATION");
                row.put("name", operation.path("name").asText());
                row.put("returnJavaType", operation.path("returnJavaType").asText());
                row.put("readable", !operation.path("mutating").asBoolean());
                row.put("writable", false);
                row.set("parameterJavaTypes", parameters);
                result.put(memberId(sequence), row);
            }
        }
        return result;
    }

    private static Set<String> baselineMutationIds(JsonNode baseline) {
        Set<String> ids = new HashSet<>();
        int sequence = 0;
        for (JsonNode objectName : baseline.path("objectNames")) {
            for (JsonNode attribute : objectName.path("attributes")) {
                sequence++;
                if (attribute.path("writable").asBoolean()) {
                    ids.add(memberId(sequence));
                }
            }
            for (JsonNode operation : objectName.path("operations")) {
                sequence++;
                if (operation.path("mutating").asBoolean()) {
                    ids.add(memberId(sequence));
                }
            }
        }
        return ids;
    }

    private static String memberId(int sequence) {
        return "M%02d".formatted(sequence);
    }

    private static JsonNode read(String name) throws IOException {
        return MAPPER.readTree(new File(EVIDENCE_ROOT, name));
    }

    private static JsonNode readBaseline() throws IOException {
        return MAPPER.readTree(BASELINE_FIXTURE);
    }

    private record BaselineCounts(
            int objectNames,
            int members,
            int strictReads,
            int writableAttributes,
            int mutatingOperations) {
    }
}
