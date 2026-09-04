package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.Objects;
import java.util.Set;

public record TracingDeploymentDiagnostics(boolean tracingEnabled,
                                           boolean agentMarkerVisible,
                                           AgentRuntimeState runtimeState,
                                           AgentReadiness agentReadiness) {

    public TracingDeploymentDiagnostics {
        Objects.requireNonNull(runtimeState, "runtimeState");
        Objects.requireNonNull(agentReadiness, "agentReadiness");
    }

    public static TracingDeploymentDiagnostics withoutAgentData(boolean tracingEnabled,
                                                                boolean agentMarkerVisible,
                                                                AgentRuntimeState runtimeState,
                                                                String failureCode) {
        return new TracingDeploymentDiagnostics(
                tracingEnabled,
                agentMarkerVisible,
                runtimeState,
                AgentReadiness.unavailable(failureCode)
        );
    }

    /**
     * Идентичность JMX-контракта, с которым агент предъявил readiness-снимок.
     */
    public record ContractIdentity(String contractId, String schemaDigest) {

        public ContractIdentity {
            contractId = normalize(contractId);
            schemaDigest = normalize(schemaDigest);
        }
    }

    /**
     * Якорь поколения evidence: эпоха, монотонная генерация и идентификатор батча.
     */
    public record ReadinessAnchor(String runtimeEpoch, long evidenceGeneration, String batchId) {

        public ReadinessAnchor {
            runtimeEpoch = normalize(runtimeEpoch);
            batchId = normalize(batchId);
        }
    }

    /**
     * Состояние жизненного цикла extension и объявленные capability.
     */
    public record LifecycleState(String lifecycle,
                                 String failureCode,
                                 Set<String> capabilities,
                                 Set<String> missingRequired) {

        public LifecycleState {
            lifecycle = normalize(lifecycle);
            failureCode = normalize(failureCode);
            capabilities = (capabilities == null) ? Set.of() : Set.copyOf(capabilities);
            missingRequired = (missingRequired == null) ? Set.of() : Set.copyOf(missingRequired);
        }
    }

    /**
     * Идентичность дистрибутива Controlled Platform Agent и версии extension.
     */
    public record DistributionIdentity(String agentDistributionSha256,
                                       String upstreamAgentVersion,
                                       String extensionVersion) {

        public DistributionIdentity {
            agentDistributionSha256 = normalize(agentDistributionSha256);
            upstreamAgentVersion = normalize(upstreamAgentVersion);
            extensionVersion = normalize(extensionVersion);
        }
    }

    /**
     * Наблюдаемая топология classloader'ов extension и SDK.
     */
    public record ClassloaderTopology(String extensionDefiningLoader, String sdkDefiningLoader) {

        public ClassloaderTopology {
            extensionDefiningLoader = normalize(extensionDefiningLoader);
            sdkDefiningLoader = normalize(sdkDefiningLoader);
        }
    }

    public record AgentReadiness(boolean endpointPresent,
                                 ContractIdentity contractIdentity,
                                 ReadinessAnchor readinessAnchor,
                                 LifecycleState lifecycleState,
                                 DistributionIdentity distributionIdentity,
                                 ClassloaderTopology classloaderTopology) {

        public AgentReadiness {
            Objects.requireNonNull(contractIdentity, "contractIdentity");
            Objects.requireNonNull(readinessAnchor, "readinessAnchor");
            Objects.requireNonNull(lifecycleState, "lifecycleState");
            Objects.requireNonNull(distributionIdentity, "distributionIdentity");
            Objects.requireNonNull(classloaderTopology, "classloaderTopology");
        }

        public static AgentReadiness unavailable(String failureCode) {
            return new AgentReadiness(
                    false,
                    new ContractIdentity("", ""),
                    new ReadinessAnchor("", -1L, ""),
                    new LifecycleState("", failureCode, Set.of(), Set.of()),
                    new DistributionIdentity("", "", ""),
                    new ClassloaderTopology("", ""));
        }
    }

    private static String normalize(String value) {
        return (value == null) ? "" : value;
    }
}
