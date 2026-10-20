package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import java.util.List;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class RemoteJmxAssessorTestFixtures {

    public static final String EXPLODING_SUPPLIER_MESSAGE = "simulated remote JMX evidence capture failure";
    public static final String REMEDIATION_MANAGEMENT_AGENT = RemoteJmxMutationRegistrationAssessor.REMEDIATION_MANAGEMENT_AGENT;
    public static final String REMEDIATION_CAPTURE_UNAVAILABLE = RemoteJmxMutationRegistrationAssessor.REMEDIATION_CAPTURE_UNAVAILABLE;

    public static List<String> allowingArguments() {
        return List.of();
    }

    public static RemoteJmxMutationRegistrationAssessor assessor(List<String> startupArguments) {
        List<String> captured = List.copyOf(startupArguments);
        return new RemoteJmxMutationRegistrationAssessor(() -> captured);
    }

    public static RemoteJmxMutationRegistrationAssessor allowingAssessor() {
        return assessor(allowingArguments());
    }

    public static RemoteJmxMutationRegistrationAssessor managementAgentAssessor() {
        return assessor(List.of("-Dcom.sun.management.jmxremote.port=9010"));
    }

    public static RemoteJmxMutationRegistrationAssessor captureUnavailableAssessor() {
        return new RemoteJmxMutationRegistrationAssessor(() -> {
            throw new IllegalStateException("simulated startup argument capture failure");
        });
    }

    public static RemoteJmxMutationRegistrationAssessor explodingAssessor() {
        return new RemoteJmxMutationRegistrationAssessor(() -> {
            throw new Error(EXPLODING_SUPPLIER_MESSAGE);
        });
    }
}
