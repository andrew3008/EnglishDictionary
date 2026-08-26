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

/**
 * Fail-closed policy для допуска локальной mutation-плоскости при возможном remote JMX.
 *
 * <p>Policy намеренно не считает встроенный {@code jmxremote} защищённым: подтвердить remote
 * observation может только platform-owned connector с установленным authorization forwarder.</p>
 */
public final class RemoteJmxExposurePolicy {

    private static final Object MUTATION_REGISTRATION_PERMIT_SEAL = new Object();

    public static final String FAILURE_NONE = "NONE";
    public static final String FAILURE_EVIDENCE_UNKNOWN = "REMOTE_JMX_EVIDENCE_UNKNOWN";
    public static final String FAILURE_INSECURE_CONFIGURATION = "REMOTE_JMX_INSECURE_CONFIGURATION";
    public static final String FAILURE_UNCERTIFIED_STARTUP_CONFIGURATION =
            "REMOTE_JMX_UNCERTIFIED_STARTUP_CONFIGURATION";
    public static final String FAILURE_DYNAMIC_OR_PROGRAMMATIC_POSSIBLE =
            "REMOTE_JMX_DYNAMIC_OR_PROGRAMMATIC_POSSIBLE";
    public static final String FAILURE_FORWARDER_NOT_CONFIRMED = "REMOTE_JMX_FORWARDER_NOT_CONFIRMED";
    public static final String FAILURE_LISTENER_NOT_CONFIRMED = "REMOTE_JMX_LISTENER_NOT_CONFIRMED";
    public static final String FAILURE_CONNECTOR_NOT_PLATFORM_OWNED =
            "REMOTE_JMX_CONNECTOR_NOT_PLATFORM_OWNED";
    public static final String FAILURE_DEPLOYMENT_ATTESTATION_UNVERIFIED =
            "REMOTE_JMX_DEPLOYMENT_ATTESTATION_UNVERIFIED";
    public static final String FAILURE_ATTACH_MECHANISM_NOT_DISABLED =
            "REMOTE_JMX_ATTACH_MECHANISM_NOT_DISABLED";

    private static final Set<String> BOOLEAN_SECURITY_PROPERTIES = Set.of(
            "com.sun.management.jmxremote.authenticate",
            "com.sun.management.jmxremote.ssl",
            "com.sun.management.jmxremote.registry.ssl");

    private static final Set<String> PORT_PROPERTIES = Set.of(
            "com.sun.management.jmxremote.port",
            "com.sun.management.jmxremote.rmi.port",
            "com.sun.management.jmxremote.local.port");

    private final RemoteJmxDeploymentDeclaration deploymentDeclaration;
    private final Supplier<RemoteJmxExposureInputs> currentProcessInputs;
    private final AtomicReference<RemoteJmxExposureAssessment> stickyFailure = new AtomicReference<>();

    /**
     * Создаёт fail-closed policy без deployment attestation.
     */
    public RemoteJmxExposurePolicy() {
        this(RemoteJmxDeploymentDeclaration.undeclared());
    }

    public RemoteJmxExposurePolicy(RemoteJmxDeploymentDeclaration deploymentDeclaration) {
        this(
                deploymentDeclaration,
                () -> RemoteJmxExposureInputs.currentProcess(deploymentDeclaration));
    }

    RemoteJmxExposurePolicy(
            RemoteJmxDeploymentDeclaration deploymentDeclaration,
            Supplier<RemoteJmxExposureInputs> currentProcessInputs) {
        this.deploymentDeclaration = Objects.requireNonNull(
                deploymentDeclaration,
                "Deployment JMX declaration must not be null");
        this.currentProcessInputs = Objects.requireNonNull(
                currentProcessInputs,
                "Remote JMX current-process input supplier must not be null");
    }

    /**
     * Создаёт production policy только из неизменяемых после старта JVM-аргументов.
     */
    public static RemoteJmxExposurePolicy fromCurrentProcessStartupArguments() {
        try {
            List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
            RemoteJmxDeploymentDeclaration declaration =
                    RemoteJmxDeploymentDeclaration.fromStartupArguments(arguments);
            return new RemoteJmxExposurePolicy(declaration);
        } catch (RuntimeException failure) {
            return new RemoteJmxExposurePolicy(RemoteJmxDeploymentDeclaration.unknown());
        }
    }

    /**
     * Создаёт детерминированный policy для тестирования startup JVM evidence.
     */
    static RemoteJmxExposurePolicy fromStartupArguments(List<String> arguments) {
        List<String> captured = List.copyOf(
                Objects.requireNonNull(arguments, "JVM startup arguments must not be null"));
        RemoteJmxDeploymentDeclaration declaration =
                RemoteJmxDeploymentDeclaration.fromStartupArguments(captured);
        return new RemoteJmxExposurePolicy(
                declaration,
                () -> RemoteJmxExposureInputs.of(Map.of(), captured, declaration));
    }

    /**
     * Снимает evidence текущего процесса и возвращает sticky assessment.
     */
    public RemoteJmxExposureAssessment evaluateCurrentProcess() {
        return evaluate(captureCurrentProcessInputs());
    }

    /**
     * Выполняет fail-closed оценку текущего процесса и выдаёт capability только при успешном результате.
     */
    public MutationRegistrationAuthorization authorizeCurrentProcess() {
        return authorize(captureCurrentProcessInputs());
    }

    MutationRegistrationAuthorization authorize(RemoteJmxExposureInputs inputs) {
        RemoteJmxExposureAssessment assessment = evaluate(inputs);
        MutationRegistrationPermit permit = assessment.allowsLocalMutationRegistration()
                ? new MutationRegistrationPermit(MUTATION_REGISTRATION_PERMIT_SEAL)
                : null;
        return new MutationRegistrationAuthorization(assessment, permit);
    }

    /**
     * Классифицирует переданные evidence. Первый отказ остаётся sticky для экземпляра policy.
     */
    RemoteJmxExposureAssessment evaluate(RemoteJmxExposureInputs inputs) {
        Objects.requireNonNull(inputs, "Remote JMX exposure inputs must not be null");
        RemoteJmxExposureAssessment existingFailure = stickyFailure.get();
        if (existingFailure != null) {
            return existingFailure;
        }

        RemoteJmxExposureAssessment candidate = inputs.deploymentDeclaration() == deploymentDeclaration
                ? classify(inputs)
                : denied(
                        RemoteJmxExposureState.UNKNOWN,
                        FAILURE_EVIDENCE_UNKNOWN,
                        Set.of("deployment-input:mismatch"));
        if (candidate.allowsLocalMutationRegistration()) {
            existingFailure = stickyFailure.get();
            return existingFailure == null ? candidate : existingFailure;
        }

        RemoteJmxExposureAssessment stickyCandidate = candidate.asStickyFailure();
        if (stickyFailure.compareAndSet(null, stickyCandidate)) {
            return stickyCandidate;
        }
        return stickyFailure.get();
    }

    private static RemoteJmxExposureAssessment classify(RemoteJmxExposureInputs inputs) {
        Set<String> evidence = new LinkedHashSet<>();
        evidence.add("deployment:" + inputs.deploymentDeclaration().mode().name());
        evidence.add(
                "deployment-attestation:"
                        + inputs.deploymentDeclaration().attestationSource().name());

        if (!inputs.captureComplete()) {
            evidence.add("capture-failure:" + inputs.captureFailureCode());
            return denied(
                    RemoteJmxExposureState.UNKNOWN,
                    FAILURE_EVIDENCE_UNKNOWN,
                    evidence);
        }

        StartupEvidence startup = inspectStartupEvidence(inputs, evidence);
        if (startup.unknown()) {
            return denied(
                    RemoteJmxExposureState.UNKNOWN,
                    FAILURE_EVIDENCE_UNKNOWN,
                    evidence);
        }
        if (startup.insecure()) {
            return denied(
                    RemoteJmxExposureState.INSECURE_CONFIGURATION,
                    FAILURE_INSECURE_CONFIGURATION,
                    evidence);
        }
        if (startup.configured()) {
            return denied(
                    RemoteJmxExposureState.CONFIGURED_AT_STARTUP,
                    FAILURE_UNCERTIFIED_STARTUP_CONFIGURATION,
                    evidence);
        }

        RemoteJmxDeploymentDeclaration declaration = inputs.deploymentDeclaration();
        if (!declaration.hasVerifiedDeploymentSource()) {
            return denied(
                    RemoteJmxExposureState.UNKNOWN,
                    FAILURE_DEPLOYMENT_ATTESTATION_UNVERIFIED,
                    evidence);
        }
        if (declaration.mode() == RemoteJmxDeploymentDeclaration.Mode.UNKNOWN) {
            return denied(
                    RemoteJmxExposureState.UNKNOWN,
                    FAILURE_EVIDENCE_UNKNOWN,
                    evidence);
        }

        AttachMechanismEvidence attachMechanism = inspectAttachMechanism(inputs, evidence);
        if (attachMechanism.unknown()) {
            return denied(
                    RemoteJmxExposureState.UNKNOWN,
                    FAILURE_EVIDENCE_UNKNOWN,
                    evidence);
        }
        if (!attachMechanism.disabled()) {
            return denied(
                    RemoteJmxExposureState.POSSIBLE_DYNAMIC_OR_PROGRAMMATIC,
                    FAILURE_ATTACH_MECHANISM_NOT_DISABLED,
                    evidence);
        }

        return switch (declaration.mode()) {
            case REMOTE_PROHIBITED -> classifyProhibitedDeclaration(declaration, evidence);
            case PLATFORM_OWNED_CERTIFIED_CONNECTOR -> classifyPlatformConnector(declaration, evidence);
            case EXTERNAL_OR_BUILT_IN_CONNECTOR -> classifyExternalConnector(declaration, evidence);
            case UNDECLARED -> denied(
                    RemoteJmxExposureState.POSSIBLE_DYNAMIC_OR_PROGRAMMATIC,
                    FAILURE_DYNAMIC_OR_PROGRAMMATIC_POSSIBLE,
                    evidence);
            case UNKNOWN -> denied(
                    RemoteJmxExposureState.UNKNOWN,
                    FAILURE_EVIDENCE_UNKNOWN,
                    evidence);
        };
    }

    private static RemoteJmxExposureAssessment classifyProhibitedDeclaration(
            RemoteJmxDeploymentDeclaration declaration,
            Set<String> evidence) {
        if (!declaration.dynamicConnectorProhibited()) {
            return denied(
                    RemoteJmxExposureState.UNKNOWN,
                    FAILURE_EVIDENCE_UNKNOWN,
                    evidence);
        }
        return allowed(RemoteJmxExposureState.ABSENT, false, evidence);
    }

    private static RemoteJmxExposureAssessment classifyPlatformConnector(
            RemoteJmxDeploymentDeclaration declaration,
            Set<String> evidence) {
        if (!declaration.listenerConfirmed()) {
            return denied(
                    RemoteJmxExposureState.CONFIGURED_AT_STARTUP,
                    FAILURE_LISTENER_NOT_CONFIRMED,
                    evidence);
        }
        evidence.add("listener:confirmed");
        if (!declaration.authorizationForwarderInstalled()) {
            return denied(
                    RemoteJmxExposureState.CONFIRMED_LISTENER,
                    FAILURE_FORWARDER_NOT_CONFIRMED,
                    evidence);
        }
        evidence.add("forwarder:confirmed");
        return allowed(RemoteJmxExposureState.CONFIRMED_LISTENER, true, evidence);
    }

    private static RemoteJmxExposureAssessment classifyExternalConnector(
            RemoteJmxDeploymentDeclaration declaration,
            Set<String> evidence) {
        if (declaration.listenerConfirmed()) {
            evidence.add("listener:confirmed");
            return denied(
                    RemoteJmxExposureState.CONFIRMED_LISTENER,
                    FAILURE_CONNECTOR_NOT_PLATFORM_OWNED,
                    evidence);
        }
        return denied(
                RemoteJmxExposureState.POSSIBLE_DYNAMIC_OR_PROGRAMMATIC,
                FAILURE_DYNAMIC_OR_PROGRAMMATIC_POSSIBLE,
                evidence);
    }

    private static StartupEvidence inspectStartupEvidence(
            RemoteJmxExposureInputs inputs,
            Set<String> evidence) {
        Map<String, String> merged = new LinkedHashMap<>();
        boolean unknown = false;

        for (Map.Entry<String, String> property : inputs.systemProperties().entrySet()) {
            if (!isRemoteProperty(property.getKey())) {
                continue;
            }
            evidence.add("system-property:" + property.getKey());
            unknown |= mergeProperty(merged, property.getKey(), property.getValue());
        }

        for (String argument : inputs.jvmArguments()) {
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

        return new StartupEvidence(!merged.isEmpty(), insecure, unknown);
    }

    /**
     * Проверяет startup-флаг Attach API. Этот флаг является обязательным, но недостаточным:
     * он не доказывает отсутствие программно созданного JMX-коннектора внутри приложения.
     */
    private static AttachMechanismEvidence inspectAttachMechanism(
            RemoteJmxExposureInputs inputs,
            Set<String> evidence) {
        boolean disabled = false;
        boolean enabled = false;
        for (String argument : inputs.jvmArguments()) {
            if (RemoteJmxExposureInputs.DISABLE_ATTACH_MECHANISM_ARGUMENT.equals(argument)) {
                disabled = true;
            } else if (RemoteJmxExposureInputs.ENABLE_ATTACH_MECHANISM_ARGUMENT.equals(argument)) {
                enabled = true;
            }
        }

        if (disabled && enabled) {
            evidence.add("attach-mechanism:conflicting");
            return new AttachMechanismEvidence(false, true);
        }
        if (disabled) {
            evidence.add("attach-mechanism:disabled");
            return new AttachMechanismEvidence(true, false);
        }
        evidence.add(enabled
                ? "attach-mechanism:enabled"
                : "attach-mechanism:unconfirmed");
        return new AttachMechanismEvidence(false, false);
    }

    private static boolean mergeProperty(Map<String, String> merged, String name, String value) {
        String previous = merged.putIfAbsent(name, value);
        return previous != null && !previous.equals(value);
    }

    private static ParsedArgument parseArgument(String argument) {
        String prefix = "-D";
        if (!argument.startsWith(prefix + RemoteJmxExposureInputs.JMX_REMOTE_PREFIX)) {
            return null;
        }
        String assignment = argument.substring(prefix.length());
        int equals = assignment.indexOf('=');
        String name = equals < 0 ? assignment : assignment.substring(0, equals);
        if (!isRemoteProperty(name)) {
            return null;
        }
        String value = equals < 0 ? "" : assignment.substring(equals + 1);
        return new ParsedArgument(name, value);
    }

    private static boolean isRemoteProperty(String name) {
        return name.equals(RemoteJmxExposureInputs.JMX_REMOTE_PREFIX)
                || name.startsWith(RemoteJmxExposureInputs.JMX_REMOTE_PREFIX + ".");
    }

    private static boolean isValidPort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            return port > 0 && port <= 65_535;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static RemoteJmxExposureAssessment allowed(
            RemoteJmxExposureState state,
            boolean allowsRemoteObservation,
            Set<String> evidence) {
        return new RemoteJmxExposureAssessment(
                state,
                true,
                allowsRemoteObservation,
                FAILURE_NONE,
                false,
                evidence);
    }

    private static RemoteJmxExposureAssessment denied(
            RemoteJmxExposureState state,
            String failureCode,
            Set<String> evidence) {
        return new RemoteJmxExposureAssessment(
                state,
                false,
                false,
                failureCode,
                false,
                evidence);
    }

    private RemoteJmxExposureInputs captureCurrentProcessInputs() {
        try {
            RemoteJmxExposureInputs inputs = currentProcessInputs.get();
            if (inputs != null) {
                return inputs;
            }
        } catch (RuntimeException ignored) {
            // Ошибка supplier-а становится bounded machine evidence без переноса произвольного Throwable.
        }
        return RemoteJmxExposureInputs.incomplete(
                Map.of(),
                List.of(),
                deploymentDeclaration,
                "CURRENT_PROCESS_EVIDENCE_UNAVAILABLE");
    }

    /**
     * Неизменяемый результат security assessment, который не позволяет получить permit при отказе.
     */
    public static final class MutationRegistrationAuthorization {

        private final RemoteJmxExposureAssessment assessment;
        private final MutationRegistrationPermit permit;

        private MutationRegistrationAuthorization(
                RemoteJmxExposureAssessment assessment,
                MutationRegistrationPermit permit) {
            this.assessment = Objects.requireNonNull(
                    assessment,
                    "Remote JMX exposure assessment must not be null");
            this.permit = permit;
        }

        public RemoteJmxExposureAssessment assessment() {
            return assessment;
        }

        public boolean authorized() {
            return permit != null;
        }

        public MutationRegistrationPermit requirePermit() {
            if (permit == null) {
                throw new SecurityException(
                        "Remote JMX mutation registration is not authorized: "
                                + assessment.failureCode());
            }
            permit.verify();
            return permit;
        }
    }

    /**
     * Неподделываемый capability-объект для регистрации runtime- и mutation-MBeans.
     */
    public static final class MutationRegistrationPermit {

        private final Object seal;

        private MutationRegistrationPermit(Object seal) {
            this.seal = seal;
        }

        public void verify() {
            if (seal != MUTATION_REGISTRATION_PERMIT_SEAL) {
                throw new SecurityException("Invalid remote JMX mutation registration permit");
            }
        }
    }

    private record StartupEvidence(boolean configured, boolean insecure, boolean unknown) {
    }

    private record AttachMechanismEvidence(boolean disabled, boolean unknown) {
    }

    private record ParsedArgument(String name, String value) {
    }
}
