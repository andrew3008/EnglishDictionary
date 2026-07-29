package space.br1440.platform.tracing.bench.priv07;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Machine-readable proposal-evidence consistency proof for PROPOSED_PRIV_R07
 * (P4 remaining-blocker closure item #3).
 * <p>
 * Verifies PRIV-R07 committee decision evidence after Architecture Committee APPROVE
 * (2026-07-29), including companion decisions REQUIRE_EXPLICIT_BOUND and RECONCILE_NOW.
 */
class Priv07ProposalEvidenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final File REPO_ROOT = locateRepositoryRoot();

    private static final File EVIDENCE_FILE = new File(
            REPO_ROOT, "docs/research/evidence/priv-r07-proposal/decision-summary.json");

    @Test
    @DisplayName("PRIV-R07 evidence records Architecture Committee APPROVE with companion decisions")
    void statusIsApprovedByCommittee() throws IOException {
        JsonNode node = read();
        assertThat(node.path("proposalId").asText()).isEqualTo("PROPOSED_PRIV_R07");
        assertThat(node.path("status").asText()).isEqualTo("APPROVED");
        JsonNode decision = node.path("humanDecisionRecord");
        assertThat(decision.path("decision").asText()).isEqualTo("APPROVE");
        assertThat(decision.path("decidedBy").asText()).isEqualTo("Architecture Committee");
        assertThat(decision.path("decidedAt").asText()).isEqualTo("2026-07-29");
        assertThat(decision.path("payloadBoundDecision").asText()).isEqualTo("REQUIRE_EXPLICIT_BOUND");
        assertThat(decision.path("s07Reconcile").asText()).isEqualTo("RECONCILE_NOW");
    }

    @Test
    @DisplayName("exact arithmetic is internally consistent: after = before + delta")
    void arithmeticIsInternallyConsistent() throws IOException {
        JsonNode arithmetic = read().path("exactArithmetic");
        JsonNode before = arithmetic.path("before");
        JsonNode after = arithmetic.path("after");

        assertThat(before.path("privateMembers").asInt()).isEqualTo(7);
        assertThat(before.path("privateReadMembers").asInt()).isEqualTo(6);
        assertThat(before.path("privateMutations").asInt()).isEqualTo(1);
        assertThat(before.path("completePolicyReadOperations").asInt()).isEqualTo(0);

        assertThat(after.path("privateMembers").asInt())
                .isEqualTo(before.path("privateMembers").asInt() + 1);
        assertThat(after.path("privateReadMembers").asInt())
                .isEqualTo(before.path("privateReadMembers").asInt() + 1);
        assertThat(after.path("privateMutations").asInt())
                .isEqualTo(before.path("privateMutations").asInt());
        assertThat(after.path("completePolicyReadOperations").asInt())
                .isEqualTo(before.path("completePolicyReadOperations").asInt() + 1);

        assertThat(arithmetic.path("applySnapshotRemainsSoleMutation").asBoolean()).isTrue();
        assertThat(arithmetic.path("readAcceptedCompleteIsSoleNewCompletePolicyRead").asBoolean()).isTrue();

        JsonNode platform = arithmetic.path("platformSurfaceUnaffected");
        assertThat(platform.path("platformMembersBefore").asInt())
                .isEqualTo(platform.path("platformMembersAfter").asInt());
        assertThat(platform.path("platformMutationsBefore").asInt()).isZero();
        assertThat(platform.path("platformMutationsAfter").asInt()).isZero();
    }

    @Test
    @DisplayName("target member declares the exact ObjectName, operation and exposure contract")
    void targetMemberContractIsExact() throws IOException {
        JsonNode member = read().path("targetMember");
        assertThat(member.path("targetMemberId").asText()).isEqualTo("PRIV-R07");
        assertThat(member.path("objectName").asText())
                .isEqualTo("space.br1440.platform.tracing:name=PlatformControlProtocol,type=Control");
        assertThat(member.path("operation").asText()).isEqualTo("readAcceptedComplete");
        assertThat(member.path("kind").asText()).isEqualTo("READ_OPERATION");
        assertThat(member.path("parameters")).isEmpty();
        assertThat(member.path("returnJavaType").asText())
                .isEqualTo("javax.management.openmbean.CompositeData");
        assertThat(member.path("compositeType").asText()).isEqualTo("AcceptedCompleteSnapshot/v1");
        assertThat(member.path("impact").asText()).isEqualTo("INFO");
        assertThat(member.path("exposure").asText()).isEqualTo("PRIVATE_MBEAN_SERVER_ONLY");
        assertThat(member.path("mutation").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("wire schema evidence declares exact field count, bounds units, and explicit 32768-byte payload bound")
    void wireSchemaEvidenceIsExact() throws IOException {
        JsonNode schema = read().path("wireSchema");
        assertThat(schema.path("rootCompositeTypeName").asText()).isEqualTo("AcceptedCompleteSnapshot/v1");
        assertThat(schema.path("fieldCount").asInt()).isEqualTo(10);
        assertThat(schema.path("fields")).hasSize(10);
        assertThat(schema.path("routeTabularType").path("maxEntries").asInt()).isEqualTo(100);
        assertThat(schema.path("routeTabularType").path("routeKeyMaxLength").asInt()).isEqualTo(255);
        assertThat(schema.path("routeTabularType").path("routeKeyLengthSemantics").asText())
                .contains("UTF-16 code units")
                .contains("String#length()");
        assertThat(schema.path("diagnosticsLogLevelClosedSet")).hasSize(6);
        assertThat(schema.path("maximumLogicalPayloadBound").asText())
                .startsWith("EXPLICITLY_BOUNDED: 32768");
    }

    @Test
    @DisplayName("evidence references the real verification artifacts by repository path")
    void referencesRealVerificationArtifacts() throws IOException {
        JsonNode verification = read().path("verification");
        for (String field : new String[]{
                "crossModuleWireParityTest",
                "dynamicMBeanInvocationContractTest",
                "privateRegistrationTest"}) {
            String path = verification.path(field).asText();
            assertThat(new File(REPO_ROOT, path)).as("evidence path: %s", path).exists();
        }
    }

    private static JsonNode read() throws IOException {
        assertThat(EVIDENCE_FILE).exists();
        return MAPPER.readTree(EVIDENCE_FILE);
    }

    private static File locateRepositoryRoot() {
        File configured = new File(System.getProperty("priv07.repo.root", "."))
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
        throw new IllegalStateException(
                "Cannot locate repository root from priv07.repo.root=" + configured);
    }
}
