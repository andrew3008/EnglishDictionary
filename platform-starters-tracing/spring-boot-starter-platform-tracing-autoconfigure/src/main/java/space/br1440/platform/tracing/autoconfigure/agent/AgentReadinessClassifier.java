package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import lombok.experimental.UtilityClass;

@UtilityClass
final class AgentReadinessClassifier {

    private static final String INITIALIZING = "INITIALIZING";
    private static final String READY = "READY";
    private static final String FAILED = "FAILED";
    private static final String DEVELOPMENT = "development";

    private static final String BOOTSTRAP_LOADER_LABEL = "bootstrap";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern LOADER_EVIDENCE = Pattern.compile("bootstrap|.+@[0-9a-f]+");
    private static final Set<String> REQUIRED_CAPABILITIES = Set.copyOf(new LinkedHashSet<>(JmxWireContract.REQUIRED_CAPABILITIES));

    record Decision(AgentRuntimeState state, String reasonCode) {
        Decision {
            Objects.requireNonNull(state, "state");
            reasonCode = (reasonCode == null) ? "" : reasonCode;
        }
    }

    static Decision decide(AgentReadinessSnapshot snapshot, String applicationVersion) {
        Objects.requireNonNull(snapshot, "snapshot");

        Decision schemaDecision = validateSchema(snapshot);
        if (schemaDecision != null) {
            return schemaDecision;
        }

        AgentReadinessSnapshot.LifecycleState lifecycleState = snapshot.lifecycleState();
        Set<String> recomputedMissing = recomputeMissing(lifecycleState.capabilities());
        if (!recomputedMissing.equals(lifecycleState.missingRequired())) {
            return incompatible("READINESS_MISSING_REQUIRED_MISMATCH");
        }

        if (snapshot.anchorIdentity().evidenceGeneration() < 0L) {
            return incompatible("READINESS_GENERATION_INVALID");
        }

        return switch (lifecycleState.lifecycle()) {
            case FAILED -> classifyFailed(snapshot);
            case INITIALIZING -> classifyInitializing(snapshot);
            case READY -> classifyReady(snapshot, applicationVersion, recomputedMissing);
            default -> incompatible("EXTENSION_LIFECYCLE_UNEXPECTED");
        };
    }

    static boolean sticky(Decision decision) {
        return switch (decision.state()) {
            case EXTENSION_INCOMPATIBLE, EXTENSION_FAILED, CAPABILITY_MISSING, DUAL_SDK_DETECTED -> true;
            default -> false;
        };
    }

    private static Decision validateSchema(AgentReadinessSnapshot snapshot) {
        AgentReadinessSnapshot.ContractIdentity contractIdentity = snapshot.contractIdentity();
        if (!JmxWireContract.SCHEMA_ID.equals(contractIdentity.contractId())) {
            return incompatible("READINESS_SCHEMA_ID_MISMATCH");
        }

        if (!JmxWireContract.SCHEMA_DIGEST_SHA256.equals(contractIdentity.schemaDigest())) {
            return incompatible("READINESS_SCHEMA_DIGEST_MISMATCH");
        }

        return null;
    }

    private static Decision classifyFailed(AgentReadinessSnapshot snapshot) {
        AgentReadinessSnapshot.LifecycleState lifecycleState = snapshot.lifecycleState();
        if (lifecycleState.failureCode().isBlank()) {
            return incompatible("EXTENSION_FAILED_WITHOUT_CODE");
        }

        if (inconsistentGeneration(snapshot, false)) {
            return incompatible("READINESS_GENERATION_INCONSISTENT");
        }

        return new Decision(AgentRuntimeState.EXTENSION_FAILED, lifecycleState.failureCode());
    }

    private static Decision classifyInitializing(AgentReadinessSnapshot snapshot) {
        AgentReadinessSnapshot.LifecycleState lifecycleState = snapshot.lifecycleState();
        AgentReadinessSnapshot.AnchorIdentity anchorIdentity = snapshot.anchorIdentity();
        if (!lifecycleState.failureCode().isBlank()
                || anchorIdentity.evidenceGeneration() != 0L
                || !anchorIdentity.batchId().isBlank()) {
            return incompatible("EXTENSION_INITIALIZING_INCONSISTENT");
        }

        return new Decision(AgentRuntimeState.EXTENSION_INITIALIZING, "");
    }

    private static Decision classifyReady(AgentReadinessSnapshot snapshot,
                                          String applicationVersion,
                                          Set<String> recomputedMissing) {
        if (!snapshot.lifecycleState().failureCode().isBlank()) {
            return incompatible("EXTENSION_READY_WITH_FAILURE_CODE");
        }

        if (!recomputedMissing.isEmpty()) {
            return new Decision(AgentRuntimeState.CAPABILITY_MISSING, "REQUIRED_CAPABILITY_MISSING");
        }

        if (inconsistentGeneration(snapshot, true)) {
            return incompatible("READINESS_GENERATION_INCONSISTENT");
        }

        Decision evidenceDecision = validateReadyEvidence(snapshot, applicationVersion);
        return evidenceDecision == null
                ? new Decision(AgentRuntimeState.AGENT_READY, "")
                : evidenceDecision;
    }

    private static Decision validateReadyEvidence(AgentReadinessSnapshot snapshot, String applicationVersion) {
        AgentReadinessSnapshot.DistributionIdentity distributionIdentity = snapshot.distributionIdentity();
        if (!SHA256.matcher(distributionIdentity.agentDistributionSha256()).matches()
                || distributionIdentity.upstreamAgentVersion().isBlank()
                || DEVELOPMENT.equalsIgnoreCase(distributionIdentity.upstreamAgentVersion())) {
            return incompatible("DISTRIBUTION_EVIDENCE_INVALID");
        }

        if (distributionIdentity.extensionVersion().isBlank()
                || DEVELOPMENT.equalsIgnoreCase(distributionIdentity.extensionVersion())) {
            return incompatible("DEVELOPMENT_VERSION_FORBIDDEN");
        }

        if (!distributionIdentity.extensionVersion().equals(applicationVersion)) {
            return incompatible("EXTENSION_VERSION_MISMATCH");
        }

        AgentReadinessSnapshot.ClassloaderTopology classloaderTopology = snapshot.classloaderTopology();
        if (!LOADER_EVIDENCE.matcher(classloaderTopology.extensionDefiningLoader()).matches()
                || !LOADER_EVIDENCE.matcher(classloaderTopology.sdkDefiningLoader()).matches()) {
            return incompatible("CLASSLOADER_TOPOLOGY_INVALID");
        }

        String applicationLoader = describeLoader(AgentReadinessClassifier.class.getClassLoader());
        if (classloaderTopology.sdkDefiningLoader().equals(applicationLoader)) {
            return new Decision(AgentRuntimeState.DUAL_SDK_DETECTED, "DUAL_SDK_COLLISION");
        }

        if (classloaderTopology.extensionDefiningLoader().equals(applicationLoader)) {
            return incompatible("EXTENSION_LINKAGE_ERROR");
        }

        return null;
    }

    private static String describeLoader(ClassLoader loader) {
        if (loader == null) {
            return BOOTSTRAP_LOADER_LABEL;
        }

        String name = loader.getName();
        String stableName = (name == null || name.isBlank()) ? loader.getClass().getName() : name;
        return stableName + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

    private static boolean inconsistentGeneration(AgentReadinessSnapshot snapshot, boolean requirePublishedBatch) {
        AgentReadinessSnapshot.AnchorIdentity anchorIdentity = snapshot.anchorIdentity();
        if (requirePublishedBatch) {
            return anchorIdentity.agentInstanceId().isBlank()
                    || anchorIdentity.evidenceGeneration() <= 0L
                    || anchorIdentity.batchId().isBlank();
        }

        if (anchorIdentity.evidenceGeneration() == 0L) {
            return !anchorIdentity.batchId().isBlank();
        }

        return anchorIdentity.agentInstanceId().isBlank() || anchorIdentity.batchId().isBlank();
    }

    private static Set<String> recomputeMissing(Set<String> capabilities) {
        Set<String> missing = new LinkedHashSet<>(REQUIRED_CAPABILITIES);
        missing.removeAll(capabilities);
        return Set.copyOf(missing);
    }

    private static Decision incompatible(String reasonCode) {
        return new Decision(AgentRuntimeState.EXTENSION_INCOMPATIBLE, reasonCode);
    }
}
