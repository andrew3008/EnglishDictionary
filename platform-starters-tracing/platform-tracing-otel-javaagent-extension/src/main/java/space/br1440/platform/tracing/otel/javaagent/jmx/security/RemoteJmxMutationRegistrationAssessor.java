package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class RemoteJmxMutationRegistrationAssessor {

    static final String MANAGEMENT_PROPERTY_PREFIX = "-Dcom.sun.management";
    static final String MANAGEMENT_SERVER_ARGUMENT = "-XX:+ManagementServer";
    static final String REMEDIATION_MANAGEMENT_AGENT = "remove arguments starting with -Dcom.sun.management and -XX:+ManagementServer";
    static final String REMEDIATION_CAPTURE_UNAVAILABLE = "unable to inspect JVM startup arguments; verify runtime access";

    private final Supplier<List<String>> startupArguments;

    public static RemoteJmxMutationRegistrationAssessor fromCurrentProcess() {
        return new RemoteJmxMutationRegistrationAssessor(
                () -> ManagementFactory.getRuntimeMXBean().getInputArguments()
        );
    }

    RemoteJmxMutationRegistrationAssessor(Supplier<List<String>> startupArguments) {
        this.startupArguments = Objects.requireNonNull(
                startupArguments,
                "Startup arguments supplier must not be null"
        );
    }

    public RemoteJmxRegistrationAssessment assess() {
        List<String> arguments;
        try {
            arguments = startupArguments.get();
        } catch (RuntimeException ignored) {
            return captureUnavailable();
        }

        if (arguments == null) {
            return captureUnavailable();
        }

        return classify(arguments);
    }

    private static RemoteJmxRegistrationAssessment classify(List<String> arguments) {
        Set<String> managementNames = new LinkedHashSet<>();
        for (String argument : arguments) {
            if (argument != null && isManagementAgentArgument(argument)) {
                managementNames.add(argumentName(argument));
            }
        }

        if (managementNames.isEmpty()) {
            return allowed();
        }

        return denied(
                RemoteJmxDenialReason.REMOTE_JMX_MANAGEMENT_AGENT_CONFIGURED,
                List.copyOf(managementNames),
                REMEDIATION_MANAGEMENT_AGENT
        );
    }

    private static boolean isManagementAgentArgument(String argument) {
        return argument.startsWith(MANAGEMENT_PROPERTY_PREFIX)
                || MANAGEMENT_SERVER_ARGUMENT.equals(argument);
    }

    private static String argumentName(String argument) {
        if (!argument.startsWith("-D")) {
            return argument;
        }

        int equals = argument.indexOf('=');
        return (equals < 0) ? argument : argument.substring(0, equals);
    }

    private static RemoteJmxRegistrationAssessment allowed() {
        return new RemoteJmxRegistrationAssessment(
                true,
                RemoteJmxDenialReason.NONE,
                List.of(),
                ""
        );
    }

    private static RemoteJmxRegistrationAssessment denied(RemoteJmxDenialReason failureCode,
                                                          List<String> offendingArguments,
                                                          String remediation) {
        return new RemoteJmxRegistrationAssessment(
                false,
                failureCode,
                offendingArguments,
                remediation
        );
    }

    private static RemoteJmxRegistrationAssessment captureUnavailable() {
        return denied(
                RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN,
                List.of(),
                REMEDIATION_CAPTURE_UNAVAILABLE
        );
    }
}
