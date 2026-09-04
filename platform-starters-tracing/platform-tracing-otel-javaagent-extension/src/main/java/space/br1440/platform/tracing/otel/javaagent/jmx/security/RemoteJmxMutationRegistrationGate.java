package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class RemoteJmxMutationRegistrationGate {

    private static final Set<String> BOOLEAN_SECURITY_PROPERTIES = Set.of(
            "com.sun.management.jmxremote.authenticate",
            "com.sun.management.jmxremote.ssl",
            "com.sun.management.jmxremote.registry.ssl");

    private static final Set<String> PORT_PROPERTIES = Set.of(
            "com.sun.management.jmxremote.port",
            "com.sun.management.jmxremote.rmi.port",
            "com.sun.management.jmxremote.local.port");

    private final RemoteJmxDeploymentDeclaration deploymentDeclaration;
    private final Supplier<RemoteJmxExposureEvidence> currentProcessEvidence;
    private final AtomicReference<RemoteJmxExposureAssessment> stickyFailure = new AtomicReference<>();

    public RemoteJmxMutationRegistrationGate() {
        this(RemoteJmxDeploymentDeclaration.undeclared());
    }

    public RemoteJmxMutationRegistrationGate(RemoteJmxDeploymentDeclaration deploymentDeclaration) {
        this(deploymentDeclaration,
                () -> RemoteJmxExposureEvidence.currentProcess(deploymentDeclaration));
    }

    RemoteJmxMutationRegistrationGate(RemoteJmxDeploymentDeclaration deploymentDeclaration,
                                      Supplier<RemoteJmxExposureEvidence> currentProcessEvidence) {
        this.deploymentDeclaration = Objects.requireNonNull(
                deploymentDeclaration,
                "Deployment JMX declaration must not be null"
        );

        this.currentProcessEvidence = Objects.requireNonNull(
                currentProcessEvidence,
                "Remote JMX current-process evidence supplier must not be null"
        );
    }

    public static RemoteJmxMutationRegistrationGate fromCurrentProcessStartupArguments() {
        try {
            List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
            RemoteJmxDeploymentDeclaration declaration = RemoteJmxDeploymentDeclaration.fromStartupArguments(arguments);
            return new RemoteJmxMutationRegistrationGate(declaration);
        } catch (RuntimeException failure) {
            return new RemoteJmxMutationRegistrationGate(RemoteJmxDeploymentDeclaration.unknown());
        }
    }

    static RemoteJmxMutationRegistrationGate fromStartupArguments(List<String> arguments) {
        List<String> startupArguments = List.copyOf(Objects.requireNonNull(arguments, "JVM startup arguments must not be null"));
        RemoteJmxDeploymentDeclaration declaration = RemoteJmxDeploymentDeclaration.fromStartupArguments(startupArguments);
        return new RemoteJmxMutationRegistrationGate(
                declaration,
                () -> RemoteJmxExposureEvidence.of(Map.of(), startupArguments, declaration)
        );
    }

    public RemoteJmxExposureAssessment evaluateCurrentProcess() {
        return evaluate(captureCurrentProcessEvidence());
    }

    public void requireCurrentProcessPermit() {
        requirePermit(captureCurrentProcessEvidence());
    }

    void requirePermit(RemoteJmxExposureEvidence captured) {
        RemoteJmxExposureAssessment assessment = evaluate(captured);
        if (!assessment.allowsLocalMutationRegistration()) {
            throw new SecurityException(
                    "Remote JMX mutation registration is not authorized: "
                            + assessment.failureCode());
        }
    }

    RemoteJmxExposureAssessment evaluate(RemoteJmxExposureEvidence captured) {
        Objects.requireNonNull(captured, "Remote JMX exposure evidence must not be null");

        RemoteJmxExposureAssessment existingFailure = stickyFailure.get();
        if (existingFailure != null) {
            return existingFailure;
        }

        RemoteJmxExposureAssessment candidate = (captured.deploymentDeclaration() == deploymentDeclaration)
                ? classify(captured)
                : denied(RemoteJmxExposureState.UNKNOWN, RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN, Set.of("deployment-input:mismatch"));

        RemoteJmxExposureAssessment published = stickyFailure.updateAndGet(current -> {
            if (current != null) {
                return current;
            }

            return candidate.allowsLocalMutationRegistration() ? null : candidate.asStickyFailure();
        });

        return (published == null) ? candidate : published;
    }

    private static RemoteJmxExposureAssessment classify(RemoteJmxExposureEvidence captured) {
        Set<String> evidence = new LinkedHashSet<>();
        evidence.add("deployment:" + captured.deploymentDeclaration().mode().name());
        evidence.add("deployment-attestation:" + captured.deploymentDeclaration().attestationSource().name());

        if (!captured.captureOutcome().complete()) {
            evidence.add("capture-failure:" + captured.captureOutcome().name());
            return denied(
                    RemoteJmxExposureState.UNKNOWN,
                    RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN,
                    evidence
            );
        }

        RemoteJmxExposureAssessment startupDenial = denyStartupJmxremoteIfPresent(captured, evidence);
        if (startupDenial != null) {
            return startupDenial;
        }

        RemoteJmxDeploymentDeclaration declaration = captured.deploymentDeclaration();
        if (!declaration.hasVerifiedDeploymentSource()) {
            return denied(
                    RemoteJmxExposureState.UNKNOWN,
                    RemoteJmxDenialReason.REMOTE_JMX_DEPLOYMENT_ATTESTATION_UNVERIFIED,
                    evidence
            );
        }

        if (declaration.mode() == RemoteJmxDeploymentDeclaration.Mode.UNKNOWN) {
            return denied(
                    RemoteJmxExposureState.UNKNOWN,
                    RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN,
                    evidence
            );
        }

        RemoteJmxExposureAssessment attachDenial = denyUnlessAttachMechanismDisabled(captured, evidence);
        if (attachDenial != null) {
            return attachDenial;
        }

        return switch (declaration.mode()) {
            case REMOTE_PROHIBITED -> classifyProhibitedDeclaration(declaration, evidence);
            case PLATFORM_OWNED_CERTIFIED_CONNECTOR -> classifyPlatformConnector(declaration, evidence);
            case EXTERNAL_OR_BUILT_IN_CONNECTOR -> classifyExternalConnector(declaration, evidence);
            case UNDECLARED -> denied(
                    RemoteJmxExposureState.POSSIBLE_DYNAMIC_OR_PROGRAMMATIC,
                    RemoteJmxDenialReason.REMOTE_JMX_DYNAMIC_OR_PROGRAMMATIC_POSSIBLE,
                    evidence);
            case UNKNOWN -> denied(
                    RemoteJmxExposureState.UNKNOWN,
                    RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN,
                    evidence);
        };
    }

    private static RemoteJmxExposureAssessment denyStartupJmxremoteIfPresent(
            RemoteJmxExposureEvidence captured,
            Set<String> evidence) {
        Map<String, String> merged = new LinkedHashMap<>();
        boolean unknown = false;

        for (Map.Entry<String, String> property : captured.systemProperties().entrySet()) {
            if (!isRemoteProperty(property.getKey())) {
                continue;
            }
            evidence.add("system-property:" + property.getKey());
            unknown |= mergeProperty(merged, property.getKey(), property.getValue());
        }

        for (String argument : captured.jvmArguments()) {
            ParsedArgument parsed = parseArgument(argument);
            if (parsed == null) {
                continue;
            }
            evidence.add("jvm-argument:" + parsed.name());
            unknown |= mergeProperty(merged, parsed.name(), parsed.value());
        }

        boolean insecure = false;
        for (String property : BOOLEAN_SECURITY_PROPERTIES) {
            if (!merged.containsKey(property)) {
                continue;
            }
            String value = merged.get(property).trim();
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                unknown = true;
            } else if (value.equalsIgnoreCase("false")) {
                insecure = true;
                evidence.add("insecure-property:" + property);
            }
        }

        for (String property : PORT_PROPERTIES) {
            if (!merged.containsKey(property)) {
                continue;
            }
            if (!isValidPort(merged.get(property))) {
                insecure = true;
                evidence.add("invalid-port-property:" + property);
            }
        }

        if (unknown) {
            return denied(
                    RemoteJmxExposureState.UNKNOWN,
                    RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN,
                    evidence);
        }
        if (insecure) {
            return denied(
                    RemoteJmxExposureState.INSECURE_CONFIGURATION,
                    RemoteJmxDenialReason.REMOTE_JMX_INSECURE_CONFIGURATION,
                    evidence);
        }
        if (merged.isEmpty()) {
            return null;
        }
        return denied(
                RemoteJmxExposureState.CONFIGURED_AT_STARTUP,
                RemoteJmxDenialReason.REMOTE_JMX_UNCERTIFIED_STARTUP_CONFIGURATION,
                evidence);
    }

    private static RemoteJmxExposureAssessment denyUnlessAttachMechanismDisabled(
            RemoteJmxExposureEvidence captured,
            Set<String> evidence) {
        boolean disabled = false;
        boolean enabled = false;
        for (String argument : captured.jvmArguments()) {
            if (RemoteJmxExposureEvidence.DISABLE_ATTACH_MECHANISM_ARGUMENT.equals(argument)) {
                disabled = true;
            } else if (RemoteJmxExposureEvidence.ENABLE_ATTACH_MECHANISM_ARGUMENT.equals(argument)) {
                enabled = true;
            }
        }

        if (disabled && enabled) {
            evidence.add("attach-mechanism:conflicting");
            return denied(
                    RemoteJmxExposureState.UNKNOWN,
                    RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN,
                    evidence);
        }
        if (disabled) {
            evidence.add("attach-mechanism:disabled");
            return null;
        }
        if (enabled) {
            evidence.add("attach-mechanism:enabled");
            return denied(
                    RemoteJmxExposureState.POSSIBLE_DYNAMIC_OR_PROGRAMMATIC,
                    RemoteJmxDenialReason.REMOTE_JMX_ATTACH_MECHANISM_NOT_DISABLED,
                    evidence);
        }
        evidence.add("attach-mechanism:unconfirmed");
        return denied(
                RemoteJmxExposureState.POSSIBLE_DYNAMIC_OR_PROGRAMMATIC,
                RemoteJmxDenialReason.REMOTE_JMX_ATTACH_MECHANISM_NOT_DISABLED,
                evidence);
    }

    private static boolean mergeProperty(Map<String, String> merged, String name, String value) {
        String previous = merged.putIfAbsent(name, value);
        return previous != null && !previous.equals(value);
    }

    private static ParsedArgument parseArgument(String argument) {
        String prefix = "-D";
        if (!argument.startsWith(prefix + RemoteJmxExposureEvidence.JMX_REMOTE_PREFIX)) {
            return null;
        }

        String assignment = argument.substring(prefix.length());
        int equals = assignment.indexOf('=');
        String name = (equals < 0) ? assignment : assignment.substring(0, equals);
        if (isRemoteProperty(name)) {
            String value = (equals < 0) ? "" : assignment.substring(equals + 1);
            return new ParsedArgument(name, value);
        }

        return null;
    }

    private static boolean isRemoteProperty(String name) {
        return name.equals(RemoteJmxExposureEvidence.JMX_REMOTE_PREFIX)
                || name.startsWith(RemoteJmxExposureEvidence.JMX_REMOTE_PREFIX + ".");
    }

    private static boolean isValidPort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            return port > 0 && port <= 65_535;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static RemoteJmxExposureAssessment classifyProhibitedDeclaration(RemoteJmxDeploymentDeclaration declaration,
                                                                             Set<String> evidence) {
        if (!declaration.dynamicConnectorProhibited()) {
            return denied(
                    RemoteJmxExposureState.UNKNOWN,
                    RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN,
                    evidence);
        }
        return allowed(RemoteJmxExposureState.ABSENT, false, evidence);
    }

    private static RemoteJmxExposureAssessment classifyPlatformConnector(RemoteJmxDeploymentDeclaration declaration,
                                                                         Set<String> evidence) {
        if (!declaration.listenerConfirmed()) {
            return denied(
                    RemoteJmxExposureState.CONFIGURED_AT_STARTUP,
                    RemoteJmxDenialReason.REMOTE_JMX_LISTENER_NOT_CONFIRMED,
                    evidence);
        }

        evidence.add("listener:confirmed");
        if (!declaration.authorizationForwarderInstalled()) {
            return denied(
                    RemoteJmxExposureState.CONFIRMED_LISTENER,
                    RemoteJmxDenialReason.REMOTE_JMX_FORWARDER_NOT_CONFIRMED,
                    evidence);
        }

        evidence.add("forwarder:confirmed");
        return allowed(RemoteJmxExposureState.CONFIRMED_LISTENER, true, evidence);
    }

    private static RemoteJmxExposureAssessment classifyExternalConnector(RemoteJmxDeploymentDeclaration declaration,
                                                                         Set<String> evidence) {
        if (declaration.listenerConfirmed()) {
            evidence.add("listener:confirmed");
            return denied(
                    RemoteJmxExposureState.CONFIRMED_LISTENER,
                    RemoteJmxDenialReason.REMOTE_JMX_CONNECTOR_NOT_PLATFORM_OWNED,
                    evidence);
        }

        return denied(
                RemoteJmxExposureState.POSSIBLE_DYNAMIC_OR_PROGRAMMATIC,
                RemoteJmxDenialReason.REMOTE_JMX_DYNAMIC_OR_PROGRAMMATIC_POSSIBLE,
                evidence
        );
    }

    private static RemoteJmxExposureAssessment allowed(RemoteJmxExposureState state,
                                                       boolean allowsRemoteObservation,
                                                       Set<String> evidence) {
        return new RemoteJmxExposureAssessment(
                state,
                true,
                allowsRemoteObservation,
                RemoteJmxDenialReason.NONE,
                false,
                evidence
        );
    }

    private static RemoteJmxExposureAssessment denied(RemoteJmxExposureState state,
                                                      RemoteJmxDenialReason failureCode,
                                                      Set<String> evidence) {
        return new RemoteJmxExposureAssessment(
                state,
                false,
                false,
                failureCode,
                false,
                evidence
        );
    }

    private RemoteJmxExposureEvidence captureCurrentProcessEvidence() {
        try {
            RemoteJmxExposureEvidence captured = currentProcessEvidence.get();
            if (captured != null) {
                return captured;
            }
        } catch (RuntimeException ignored) {
        }

        return RemoteJmxExposureEvidence.incomplete(
                Map.of(),
                List.of(),
                deploymentDeclaration,
                EvidenceCaptureOutcome.CURRENT_PROCESS_EVIDENCE_UNAVAILABLE
        );
    }

    private record ParsedArgument(String name, String value) {
    }
}
