package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.Set;
import java.util.function.Supplier;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.autoconfigure.support.ReadinessJmxTestFixtures;

final class ReadinessTestFixtures {

    static final String APPLICATION_VERSION = "1.2.3";
    static final String AGENT_INSTANCE_ID = "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3a";
    static final String BATCH_ID = "batch-1";
    static final String SHA256 = "a".repeat(64);
    static final Set<String> COMPLETE_CAPABILITIES = Set.copyOf(JmxWireContract.REQUIRED_CAPABILITIES);

    private ReadinessTestFixtures() {
    }

    static AgentReadinessSnapshot readySnapshot() {
        return new AgentReadinessSnapshot(
                new AgentReadinessSnapshot.ContractIdentity(JmxWireContract.SCHEMA_ID, JmxWireContract.SCHEMA_DIGEST_SHA256),
                new AgentReadinessSnapshot.AnchorIdentity(AGENT_INSTANCE_ID, 1L, BATCH_ID),
                new AgentReadinessSnapshot.LifecycleState("READY", "", "", COMPLETE_CAPABILITIES, Set.of()),
                new AgentReadinessSnapshot.DistributionIdentity(SHA256, "2.30.0", APPLICATION_VERSION),
                new AgentReadinessSnapshot.ClassloaderTopology("agent-loader@1", "sdk-loader@2"));
    }

    static AgentReadinessSnapshot initializingSnapshot() {
        return new AgentReadinessSnapshot(
                new AgentReadinessSnapshot.ContractIdentity(JmxWireContract.SCHEMA_ID, JmxWireContract.SCHEMA_DIGEST_SHA256),
                new AgentReadinessSnapshot.AnchorIdentity(AGENT_INSTANCE_ID, 0L, ""),
                new AgentReadinessSnapshot.LifecycleState("INITIALIZING", "", "", Set.of(), COMPLETE_CAPABILITIES),
                new AgentReadinessSnapshot.DistributionIdentity("", "", APPLICATION_VERSION),
                new AgentReadinessSnapshot.ClassloaderTopology("", ""));
    }

    static AgentReadinessSnapshot failedSnapshot(String failureCode) {
        return failedSnapshot(failureCode, failureCode);
    }

    static AgentReadinessSnapshot failedSnapshot(String primaryFailureCode,
                                                 String effectiveFailureCode) {
        AgentReadinessSnapshot initializing = initializingSnapshot();
        AgentReadinessSnapshot.LifecycleState initializingLifecycle = initializing.lifecycleState();

        return toBuilder(initializing)
                .lifecycleState(new AgentReadinessSnapshot.LifecycleState(
                        "FAILED",
                        effectiveFailureCode,
                        primaryFailureCode,
                        initializingLifecycle.capabilities(),
                        initializingLifecycle.missingRequired())
                )
                .build();
    }

    static Builder toBuilder(AgentReadinessSnapshot source) {
        return new Builder(source);
    }

    static CompositeData composite(AgentReadinessSnapshot snapshot) {
        AgentReadinessSnapshot.ContractIdentity contractIdentity = snapshot.contractIdentity();
        AgentReadinessSnapshot.AnchorIdentity anchorIdentity = snapshot.anchorIdentity();
        AgentReadinessSnapshot.LifecycleState lifecycleState = snapshot.lifecycleState();
        AgentReadinessSnapshot.DistributionIdentity distributionIdentity = snapshot.distributionIdentity();
        AgentReadinessSnapshot.ClassloaderTopology classloaderTopology = snapshot.classloaderTopology();

        return ReadinessJmxTestFixtures.composite(
                contractIdentity.contractId(),
                contractIdentity.schemaDigest(),
                anchorIdentity.agentInstanceId(),
                anchorIdentity.evidenceGeneration(),
                anchorIdentity.batchId(),
                lifecycleState.lifecycle(),
                lifecycleState.capabilities(),
                lifecycleState.missingRequired(),
                lifecycleState.failureCode(),
                lifecycleState.primaryFailureCode(),
                distributionIdentity.agentDistributionSha256(),
                distributionIdentity.upstreamAgentVersion(),
                distributionIdentity.extensionVersion(),
                classloaderTopology.extensionDefiningLoader(),
                classloaderTopology.sdkDefiningLoader()
        );
    }

    static void register(MBeanServer server, Supplier<Object> snapshotSupplier) {
        try {
            server.registerMBean(
                    ReadinessJmxTestFixtures.endpoint(snapshotSupplier),
                    JmxWireContract.EXTENSION_READINESS_OBJECT_NAME
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot register readiness test MBean", exception);
        }
    }

    static final class Builder {

        private AgentReadinessSnapshot.ContractIdentity contractIdentity;
        private AgentReadinessSnapshot.AnchorIdentity anchorIdentity;
        private AgentReadinessSnapshot.LifecycleState lifecycleState;
        private AgentReadinessSnapshot.DistributionIdentity distributionIdentity;
        private AgentReadinessSnapshot.ClassloaderTopology classloaderTopology;

        private Builder(AgentReadinessSnapshot source) {
            contractIdentity = source.contractIdentity();
            anchorIdentity = source.anchorIdentity();
            lifecycleState = source.lifecycleState();
            distributionIdentity = source.distributionIdentity();
            classloaderTopology = source.classloaderTopology();
        }

        Builder contractIdentity(AgentReadinessSnapshot.ContractIdentity value) {
            contractIdentity = value;
            return this;
        }

        Builder anchorIdentity(AgentReadinessSnapshot.AnchorIdentity value) {
            anchorIdentity = value;
            return this;
        }

        Builder lifecycleState(AgentReadinessSnapshot.LifecycleState value) {
            lifecycleState = value;
            return this;
        }

        Builder distributionIdentity(AgentReadinessSnapshot.DistributionIdentity value) {
            distributionIdentity = value;
            return this;
        }

        Builder classloaderTopology(AgentReadinessSnapshot.ClassloaderTopology value) {
            classloaderTopology = value;
            return this;
        }

        AgentReadinessSnapshot build() {
            return new AgentReadinessSnapshot(
                    contractIdentity, anchorIdentity, lifecycleState, distributionIdentity, classloaderTopology);
        }
    }
}
