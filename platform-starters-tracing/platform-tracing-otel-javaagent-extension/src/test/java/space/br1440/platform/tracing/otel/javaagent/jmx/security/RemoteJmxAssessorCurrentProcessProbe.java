package space.br1440.platform.tracing.otel.javaagent.jmx.security;

public final class RemoteJmxAssessorCurrentProcessProbe {

    public static final String ALLOWS_PREFIX = "REMOTE_JMX_GATE_ALLOWS ";
    public static final String FAILURE_CODE_PREFIX = "REMOTE_JMX_GATE_FAILURE_CODE ";
    public static final String OFFENDING_PREFIX = "REMOTE_JMX_GATE_OFFENDING ";
    public static final String REMEDIATION_PREFIX = "REMOTE_JMX_GATE_REMEDIATION ";

    private RemoteJmxAssessorCurrentProcessProbe() {
        throw new AssertionError("No instances");
    }

    public static void main(String[] args) {
        RemoteJmxRegistrationAssessment assessment = RemoteJmxMutationRegistrationAssessor.fromCurrentProcess().assess();

        StringBuilder report = new StringBuilder()
                .append(ALLOWS_PREFIX)
                .append(assessment.allowsLocalMutationRegistration())
                .append(System.lineSeparator())
                .append(FAILURE_CODE_PREFIX)
                .append(assessment.failureCode().name())
                .append(System.lineSeparator())
                .append(OFFENDING_PREFIX)
                .append(String.join(",", assessment.offendingArguments()))
                .append(System.lineSeparator())
                .append(REMEDIATION_PREFIX)
                .append(assessment.remediation());

        System.out.println(report);
    }
}
