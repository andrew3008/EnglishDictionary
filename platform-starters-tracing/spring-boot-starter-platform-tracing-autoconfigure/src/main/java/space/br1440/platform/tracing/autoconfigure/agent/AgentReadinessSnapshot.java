package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.Objects;
import java.util.Set;

record AgentReadinessSnapshot(ContractIdentity contractIdentity,
                              AnchorIdentity anchorIdentity,
                              LifecycleState lifecycleState,
                              DistributionIdentity distributionIdentity,
                              ClassloaderTopology classloaderTopology) {

    AgentReadinessSnapshot {
        Objects.requireNonNull(contractIdentity, "contractIdentity");
        Objects.requireNonNull(anchorIdentity, "anchorIdentity");
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(distributionIdentity, "distributionIdentity");
        Objects.requireNonNull(classloaderTopology, "classloaderTopology");
    }

    record ContractIdentity(String contractId, String schemaDigest) {
        ContractIdentity {
            Objects.requireNonNull(contractId, "contractId");
            Objects.requireNonNull(schemaDigest, "schemaDigest");
        }
    }

    record AnchorIdentity(String runtimeEpoch, long evidenceGeneration, String batchId) {
        AnchorIdentity {
            Objects.requireNonNull(runtimeEpoch, "runtimeEpoch");
            Objects.requireNonNull(batchId, "batchId");
        }
    }

    record LifecycleState(String lifecycle,
                          String failureCode,
                          Set<String> capabilities,
                          Set<String> missingRequired) {
        LifecycleState {
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(failureCode, "failureCode");
            capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
            missingRequired = Set.copyOf(Objects.requireNonNull(missingRequired, "missingRequired"));
        }
    }

    record DistributionIdentity(String agentDistributionSha256,
                                String upstreamAgentVersion,
                                String extensionVersion) {
        DistributionIdentity {
            Objects.requireNonNull(agentDistributionSha256, "agentDistributionSha256");
            Objects.requireNonNull(upstreamAgentVersion, "upstreamAgentVersion");
            Objects.requireNonNull(extensionVersion, "extensionVersion");
        }
    }

    record ClassloaderTopology(String extensionDefiningLoader,
                               String sdkDefiningLoader) {
        ClassloaderTopology {
            Objects.requireNonNull(extensionDefiningLoader, "extensionDefiningLoader");
            Objects.requireNonNull(sdkDefiningLoader, "sdkDefiningLoader");
        }
    }
}
