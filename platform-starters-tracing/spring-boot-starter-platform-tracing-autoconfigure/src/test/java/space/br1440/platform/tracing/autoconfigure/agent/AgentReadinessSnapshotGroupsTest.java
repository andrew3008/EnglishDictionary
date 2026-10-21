package space.br1440.platform.tracing.autoconfigure.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AgentReadinessSnapshotGroupsTest {

    private static final AgentReadinessSnapshot.ContractIdentity CONTRACT_IDENTITY =
            new AgentReadinessSnapshot.ContractIdentity("contract-1", "digest-1");

    private static final AgentReadinessSnapshot.AnchorIdentity ANCHOR_IDENTITY =
            new AgentReadinessSnapshot.AnchorIdentity(ReadinessTestFixtures.AGENT_INSTANCE_ID, 1L, "batch-1");

    private static final AgentReadinessSnapshot.LifecycleState LIFECYCLE_STATE =
            new AgentReadinessSnapshot.LifecycleState("READY", "", "", Set.of("CAP"), Set.of());

    private static final AgentReadinessSnapshot.DistributionIdentity DISTRIBUTION_IDENTITY =
            new AgentReadinessSnapshot.DistributionIdentity("a".repeat(64), "2.30.0", "1.2.3");

    private static final AgentReadinessSnapshot.ClassloaderTopology CLASSLOADER_TOPOLOGY =
            new AgentReadinessSnapshot.ClassloaderTopology("agent-loader@1", "sdk-loader@2");

    @Test
    void rejectsNullContractIdentity() {
        // given
        // when
        // then
        assertThatNullPointerException()
                .isThrownBy(() -> new AgentReadinessSnapshot(
                        null, ANCHOR_IDENTITY, LIFECYCLE_STATE, DISTRIBUTION_IDENTITY, CLASSLOADER_TOPOLOGY))
                .withMessageContaining("contractIdentity");
    }

    @Test
    void rejectsNullAnchorIdentity() {
        // given
        // when
        // then
        assertThatNullPointerException()
                .isThrownBy(() -> new AgentReadinessSnapshot(
                        CONTRACT_IDENTITY, null, LIFECYCLE_STATE, DISTRIBUTION_IDENTITY, CLASSLOADER_TOPOLOGY))
                .withMessageContaining("anchorIdentity");
    }

    @Test
    void rejectsNullLifecycleState() {
        // given
        // when
        // then
        assertThatNullPointerException()
                .isThrownBy(() -> new AgentReadinessSnapshot(
                        CONTRACT_IDENTITY, ANCHOR_IDENTITY, null, DISTRIBUTION_IDENTITY, CLASSLOADER_TOPOLOGY))
                .withMessageContaining("lifecycleState");
    }

    @Test
    void rejectsNullDistributionIdentity() {
        // given
        // when
        // then
        assertThatNullPointerException()
                .isThrownBy(() -> new AgentReadinessSnapshot(
                        CONTRACT_IDENTITY, ANCHOR_IDENTITY, LIFECYCLE_STATE, null, CLASSLOADER_TOPOLOGY))
                .withMessageContaining("distributionIdentity");
    }

    @Test
    void rejectsNullClassloaderTopology() {
        // given
        // when
        // then
        assertThatNullPointerException()
                .isThrownBy(() -> new AgentReadinessSnapshot(
                        CONTRACT_IDENTITY, ANCHOR_IDENTITY, LIFECYCLE_STATE, DISTRIBUTION_IDENTITY, null))
                .withMessageContaining("classloaderTopology");
    }

    @Test
    void contractIdentityRejectsNullContractId() {
        // given
        // when
        // then
        assertThatNullPointerException()
                .isThrownBy(() -> new AgentReadinessSnapshot.ContractIdentity(null, "digest-1"))
                .withMessageContaining("contractId");
    }

    @Test
    void anchorIdentityRejectsNullAgentInstanceId() {
        // given
        // when
        // then
        assertThatThrownBy(() -> new AgentReadinessSnapshot.AnchorIdentity(null, 1L, "batch-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentInstanceId");
    }

    @Test
    void lifecycleStateRejectsNullFailureCode() {
        // given
        // when
        // then
        assertThatNullPointerException()
                .isThrownBy(() -> new AgentReadinessSnapshot.LifecycleState(
                        "READY", null, "", Set.of(), Set.of()))
                .withMessageContaining("failureCode");
    }

    @Test
    void lifecycleStateRejectsNullPrimaryFailureCode() {
        // given
        // when
        // then
        assertThatNullPointerException()
                .isThrownBy(() -> new AgentReadinessSnapshot.LifecycleState(
                        "READY", "", null, Set.of(), Set.of()))
                .withMessageContaining("primaryFailureCode");
    }

    @Test
    void distributionIdentityRejectsNullUpstreamAgentVersion() {
        // given
        // when
        // then
        assertThatNullPointerException()
                .isThrownBy(() -> new AgentReadinessSnapshot.DistributionIdentity(
                        "a".repeat(64), null, "1.2.3"))
                .withMessageContaining("upstreamAgentVersion");
    }

    @Test
    void classloaderTopologyRejectsNullSdkDefiningLoader() {
        // given
        // when
        // then
        assertThatNullPointerException()
                .isThrownBy(() -> new AgentReadinessSnapshot.ClassloaderTopology("agent-loader@1", null))
                .withMessageContaining("sdkDefiningLoader");
    }

    @Test
    void lifecycleStateDefensivelyCopiesCapabilities() {
        // given
        Set<String> mutableCapabilities = new HashSet<>(Set.of("CAP_A"));

        // when
        AgentReadinessSnapshot.LifecycleState lifecycleState =
                new AgentReadinessSnapshot.LifecycleState(
                        "READY", "", "", mutableCapabilities, Set.of());
        mutableCapabilities.add("CAP_B");

        // then
        assertThat(lifecycleState.capabilities()).containsExactly("CAP_A");
    }

    @Test
    void lifecycleStateDefensivelyCopiesMissingRequired() {
        // given
        Set<String> mutableMissingRequired = new HashSet<>(Set.of("CAP_A"));

        // when
        AgentReadinessSnapshot.LifecycleState lifecycleState =
                new AgentReadinessSnapshot.LifecycleState(
                        "READY", "", "", Set.of(), mutableMissingRequired);
        mutableMissingRequired.add("CAP_B");

        // then
        assertThat(lifecycleState.missingRequired()).containsExactly("CAP_A");
    }
}
