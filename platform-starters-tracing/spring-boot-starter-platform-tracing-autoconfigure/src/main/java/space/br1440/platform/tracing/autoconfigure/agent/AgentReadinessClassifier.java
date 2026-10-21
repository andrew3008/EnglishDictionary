package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import lombok.experimental.UtilityClass;

@UtilityClass
final class AgentReadinessClassifier {

    private static final String INITIALIZING = "INITIALIZING";
    private static final String READY = "READY";
    private static final String FAILED = "FAILED";
    private static final String FAILED_ROLLBACK_INCOMPLETE = "FAILED_ROLLBACK_INCOMPLETE";
    private static final String NO_REASON = "";

    private static final String BOOTSTRAP_LOADER_LABEL = "bootstrap";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern LOADER_EVIDENCE = Pattern.compile("bootstrap|.+@[0-9a-f]+");
    private static final Set<String> REQUIRED_CAPABILITIES = Set.copyOf(JmxWireContract.REQUIRED_CAPABILITIES);

    record Decision(AgentRuntimeState state, String reasonCode) {
        Decision {
            Objects.requireNonNull(state, "state");
            reasonCode = (reasonCode == null) ? NO_REASON : reasonCode;
        }
    }

    static Decision decide(AgentReadinessSnapshot snapshot, String applicationVersion) {
        Objects.requireNonNull(applicationVersion, "applicationVersion");

        Decision decision = classify(snapshot);
        if (decision.state() == AgentRuntimeState.AGENT_READY
                && !snapshot.distributionIdentity().extensionVersion().equals(applicationVersion)) {
            return incompatible(AgentNotReadyReasonCodes.EXTENSION_VERSION_MISMATCH);
        }
        return decision;
    }

    static Decision classify(AgentReadinessSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        Optional<Decision> schemaViolation = checkSchema(snapshot.contractIdentity());
        if (schemaViolation.isPresent()) {
            return schemaViolation.get();
        }

        AgentReadinessSnapshot.LifecycleState lifecycleState = snapshot.lifecycleState();
        Set<String> recomputedMissing = missingRequired(lifecycleState.capabilities());
        if (!recomputedMissing.equals(lifecycleState.missingRequired())) {
            return incompatible(AgentNotReadyReasonCodes.READINESS_MISSING_REQUIRED_MISMATCH);
        }

        if (snapshot.anchorIdentity().evidenceGeneration() < 0L) {
            return incompatible(AgentNotReadyReasonCodes.READINESS_GENERATION_INVALID);
        }

        return switch (lifecycleState.lifecycle()) {
            case FAILED -> classifyFailed(lifecycleState, snapshot.anchorIdentity());
            case INITIALIZING -> classifyInitializing(lifecycleState, snapshot.anchorIdentity());
            case READY -> classifyReady(snapshot, recomputedMissing);
            default -> incompatible(AgentNotReadyReasonCodes.EXTENSION_LIFECYCLE_UNEXPECTED);
        };
    }

    static boolean sticky(AgentRuntimeState state) {
        return switch (state) {
            case EXTENSION_INCOMPATIBLE, EXTENSION_FAILED, CAPABILITY_MISSING, DUAL_SDK_DETECTED -> true;
            default -> false;
        };
    }

    private static Optional<Decision> checkSchema(AgentReadinessSnapshot.ContractIdentity contractIdentity) {
        if (!JmxWireContract.SCHEMA_ID.equals(contractIdentity.contractId())) {
            return Optional.of(incompatible(AgentNotReadyReasonCodes.READINESS_SCHEMA_ID_MISMATCH));
        }

        if (!JmxWireContract.SCHEMA_DIGEST_SHA256.equals(contractIdentity.schemaDigest())) {
            return Optional.of(incompatible(AgentNotReadyReasonCodes.READINESS_SCHEMA_DIGEST_MISMATCH));
        }

        return Optional.empty();
    }

    private static Decision classifyFailed(AgentReadinessSnapshot.LifecycleState lifecycleState,
                                           AgentReadinessSnapshot.AnchorIdentity anchorIdentity) {
        String effectiveFailureCode = lifecycleState.failureCode();
        String primaryFailureCode = lifecycleState.primaryFailureCode();
        if (effectiveFailureCode.isBlank() && primaryFailureCode.isBlank()) {
            return incompatible(AgentNotReadyReasonCodes.EXTENSION_FAILED_WITHOUT_CODE);
        }

        if (!isConsistentFailurePair(primaryFailureCode, effectiveFailureCode)) {
            return incompatible(AgentNotReadyReasonCodes.EXTENSION_FAILED_CODE_PAIR_INCONSISTENT);
        }

        if (!hasConsistentFailedAnchor(anchorIdentity)) {
            return incompatible(AgentNotReadyReasonCodes.READINESS_GENERATION_INCONSISTENT);
        }

        return new Decision(AgentRuntimeState.EXTENSION_FAILED, effectiveFailureCode);
    }

    private static boolean isConsistentFailurePair(String primaryFailureCode, String effectiveFailureCode) {
        if (primaryFailureCode.isBlank() || effectiveFailureCode.isBlank()) {
            return false;
        }

        return effectiveFailureCode.equals(primaryFailureCode)
                || FAILED_ROLLBACK_INCOMPLETE.equals(effectiveFailureCode);
    }

    private static boolean hasConsistentFailedAnchor(AgentReadinessSnapshot.AnchorIdentity anchorIdentity) {
        return (anchorIdentity.evidenceGeneration() == 0L) == anchorIdentity.batchId().isBlank();
    }

    private static Decision classifyInitializing(AgentReadinessSnapshot.LifecycleState lifecycleState,
                                                 AgentReadinessSnapshot.AnchorIdentity anchorIdentity) {
        if (!lifecycleState.failureCode().isBlank()
                || !lifecycleState.primaryFailureCode().isBlank()
                || anchorIdentity.evidenceGeneration() != 0L
                || !anchorIdentity.batchId().isBlank()) {
            return incompatible(AgentNotReadyReasonCodes.EXTENSION_INITIALIZING_INCONSISTENT);
        }

        return new Decision(AgentRuntimeState.EXTENSION_INITIALIZING, NO_REASON);
    }

    private static Decision classifyReady(AgentReadinessSnapshot snapshot,
                                          Set<String> recomputedMissing) {
        AgentReadinessSnapshot.LifecycleState lifecycleState = snapshot.lifecycleState();
        if (!lifecycleState.failureCode().isBlank() || !lifecycleState.primaryFailureCode().isBlank()) {
            return incompatible(AgentNotReadyReasonCodes.EXTENSION_READY_WITH_FAILURE_CODE);
        }

        if (!recomputedMissing.isEmpty()) {
            return new Decision(AgentRuntimeState.CAPABILITY_MISSING, AgentNotReadyReasonCodes.REQUIRED_CAPABILITY_MISSING);
        }

        if (!hasPublishedAnchor(snapshot.anchorIdentity())) {
            return incompatible(AgentNotReadyReasonCodes.READINESS_GENERATION_INCONSISTENT);
        }

        return checkDistribution(snapshot.distributionIdentity())
                .or(() -> checkClassloaders(snapshot.classloaderTopology()))
                .orElseGet(() -> new Decision(AgentRuntimeState.AGENT_READY, NO_REASON));
    }

    private static boolean hasPublishedAnchor(AgentReadinessSnapshot.AnchorIdentity anchorIdentity) {
        return anchorIdentity.evidenceGeneration() > 0L && !anchorIdentity.batchId().isBlank();
    }

    private static Optional<Decision> checkDistribution(AgentReadinessSnapshot.DistributionIdentity distributionIdentity) {
        if (!SHA256.matcher(distributionIdentity.agentDistributionSha256()).matches()
                || distributionIdentity.upstreamAgentVersion().isBlank()
                || distributionIdentity.extensionVersion().isBlank()) {
            return Optional.of(incompatible(AgentNotReadyReasonCodes.DISTRIBUTION_EVIDENCE_INVALID));
        }

        return Optional.empty();
    }

    private static Optional<Decision> checkClassloaders(AgentReadinessSnapshot.ClassloaderTopology classloaderTopology) {
        if (!LOADER_EVIDENCE.matcher(classloaderTopology.extensionDefiningLoader()).matches()
                || !LOADER_EVIDENCE.matcher(classloaderTopology.sdkDefiningLoader()).matches()) {
            return Optional.of(incompatible(AgentNotReadyReasonCodes.CLASSLOADER_TOPOLOGY_INVALID));
        }

        String applicationLoader = describeLoader(AgentReadinessClassifier.class.getClassLoader());
        if (classloaderTopology.sdkDefiningLoader().equals(applicationLoader)) {
            return Optional.of(new Decision(AgentRuntimeState.DUAL_SDK_DETECTED, AgentNotReadyReasonCodes.DUAL_SDK_COLLISION));
        }

        if (classloaderTopology.extensionDefiningLoader().equals(applicationLoader)) {
            return Optional.of(incompatible(AgentNotReadyReasonCodes.EXTENSION_LINKAGE_ERROR));
        }

        return Optional.empty();
    }

    private static String describeLoader(ClassLoader loader) {
        if (loader == null) {
            return BOOTSTRAP_LOADER_LABEL;
        }

        String name = loader.getName();
        String stableName = (name == null || name.isBlank()) ? loader.getClass().getName() : name;
        return stableName + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

    private static Set<String> missingRequired(Set<String> capabilities) {
        Set<String> missing = new LinkedHashSet<>(REQUIRED_CAPABILITIES);
        missing.removeAll(capabilities);
        return Set.copyOf(missing);
    }

    private static Decision incompatible(String reasonCode) {
        return new Decision(AgentRuntimeState.EXTENSION_INCOMPATIBLE, reasonCode);
    }
}
