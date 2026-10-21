package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.Objects;
import java.util.Set;

public record AgentReadinessSnapshot(ContractIdentity contractIdentity,
                                     AnchorIdentity anchorIdentity,
                                     LifecycleState lifecycleState,
                                     DistributionIdentity distributionIdentity,
                                     ClassloaderTopology classloaderTopology) {

    public AgentReadinessSnapshot {
        Objects.requireNonNull(contractIdentity, "contractIdentity");
        Objects.requireNonNull(anchorIdentity, "anchorIdentity");
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(distributionIdentity, "distributionIdentity");
        Objects.requireNonNull(classloaderTopology, "classloaderTopology");
    }

    public record ContractIdentity(String contractId, String schemaDigest) {

        public ContractIdentity {
            Objects.requireNonNull(contractId, "contractId");
            Objects.requireNonNull(schemaDigest, "schemaDigest");
        }
    }

    public record AnchorIdentity(String agentInstanceId, long evidenceGeneration, String batchId) {

        public AnchorIdentity {
            agentInstanceId = AgentInstanceIdValidator.requireCanonical(agentInstanceId, "agentInstanceId");
            Objects.requireNonNull(batchId, "batchId");
        }
    }

    public record LifecycleState(String lifecycle,
                                 String failureCode,
                                 String primaryFailureCode,
                                 Set<String> capabilities,
                                 Set<String> missingRequired) {

        public LifecycleState {
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(failureCode, "failureCode");
            Objects.requireNonNull(primaryFailureCode, "primaryFailureCode");
            capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
            missingRequired = Set.copyOf(Objects.requireNonNull(missingRequired, "missingRequired"));
        }
    }

    public record DistributionIdentity(String agentDistributionSha256,
                                       String upstreamAgentVersion,
                                       String extensionVersion) {

        public DistributionIdentity {
            Objects.requireNonNull(agentDistributionSha256, "agentDistributionSha256");
            Objects.requireNonNull(upstreamAgentVersion, "upstreamAgentVersion");
            Objects.requireNonNull(extensionVersion, "extensionVersion");
        }
    }

    public record ClassloaderTopology(String extensionDefiningLoader, String sdkDefiningLoader) {

        public ClassloaderTopology {
            Objects.requireNonNull(extensionDefiningLoader, "extensionDefiningLoader");
            Objects.requireNonNull(sdkDefiningLoader, "sdkDefiningLoader");
        }
    }
}
