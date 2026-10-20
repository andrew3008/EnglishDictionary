package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class RemoteJmxAssessorCurrentProcessProbe {

    public static final String ALLOWS_PREFIX = "REMOTE_JMX_GATE_ALLOWS ";
    public static final String FAILURE_CODE_PREFIX = "REMOTE_JMX_GATE_FAILURE_CODE ";
    public static final String OFFENDING_PREFIX = "REMOTE_JMX_GATE_OFFENDING ";
    public static final String REMEDIATION_PREFIX = "REMOTE_JMX_GATE_REMEDIATION ";

    public static void main(String[] args) {
        RemoteJmxRegistrationAssessment assessment = RemoteJmxMutationRegistrationAssessor.fromCurrentProcess().assess();

        String report = ALLOWS_PREFIX +
                assessment.allowsLocalMutationRegistration() +
                System.lineSeparator() +
                FAILURE_CODE_PREFIX +
                assessment.failureCode().name() +
                System.lineSeparator() +
                OFFENDING_PREFIX +
                String.join(",", assessment.offendingArguments()) +
                System.lineSeparator() +
                REMEDIATION_PREFIX +
                assessment.remediation();

        System.out.println(report);
    }
}
