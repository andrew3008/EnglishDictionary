package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

public final class RemoteJmxExposureEvidence {

    static final String JMX_REMOTE_PREFIX = "com.sun.management.jmxremote";
    static final String DISABLE_ATTACH_MECHANISM_ARGUMENT = "-XX:+DisableAttachMechanism";
    static final String ENABLE_ATTACH_MECHANISM_ARGUMENT = "-XX:-DisableAttachMechanism";

    private final Map<String, String> systemProperties;
    private final List<String> jvmArguments;
    private final RemoteJmxDeploymentDeclaration deploymentDeclaration;
    private final EvidenceCaptureOutcome captureOutcome;

    private RemoteJmxExposureEvidence(Map<String, String> systemProperties,
                                      List<String> jvmArguments,
                                      RemoteJmxDeploymentDeclaration deploymentDeclaration,
                                      EvidenceCaptureOutcome captureOutcome) {
        this.systemProperties = Map.copyOf(systemProperties);
        this.jvmArguments = List.copyOf(jvmArguments);

        this.deploymentDeclaration = Objects.requireNonNull(
                deploymentDeclaration,
                "Deployment JMX declaration must not be null"
        );

        this.captureOutcome = Objects.requireNonNull(
                captureOutcome,
                "Evidence capture outcome must not be null"
        );
    }

    public static RemoteJmxExposureEvidence of(Map<String, String> systemProperties,
                                               List<String> jvmArguments,
                                               RemoteJmxDeploymentDeclaration deploymentDeclaration) {
        Objects.requireNonNull(systemProperties, "System properties must not be null");
        Objects.requireNonNull(jvmArguments, "JVM arguments must not be null");

        return new RemoteJmxExposureEvidence(
                systemProperties,
                jvmArguments,
                deploymentDeclaration,
                EvidenceCaptureOutcome.COMPLETE);
    }

    public static RemoteJmxExposureEvidence incomplete(Map<String, String> systemProperties,
                                                       List<String> jvmArguments,
                                                       RemoteJmxDeploymentDeclaration deploymentDeclaration,
                                                       EvidenceCaptureOutcome captureOutcome) {
        Objects.requireNonNull(systemProperties, "System properties must not be null");
        Objects.requireNonNull(jvmArguments, "JVM arguments must not be null");

        if (Objects.requireNonNull(captureOutcome, "Evidence capture outcome must not be null")
                .complete()) {
            throw new IllegalArgumentException("Incomplete capture must not report COMPLETE");
        }

        return new RemoteJmxExposureEvidence(
                systemProperties,
                jvmArguments,
                deploymentDeclaration,
                captureOutcome
        );
    }

    public static RemoteJmxExposureEvidence currentProcess(RemoteJmxDeploymentDeclaration deploymentDeclaration) {
        Map<String, String> properties = new LinkedHashMap<>();
        List<String> arguments = List.of();
        try {
            Properties current = System.getProperties();
            for (String name : current.stringPropertyNames()) {
                if (name.startsWith(JMX_REMOTE_PREFIX)) {
                    properties.put(name, current.getProperty(name, ""));
                }
            }
        } catch (RuntimeException exception) {
            return incomplete(
                    properties,
                    arguments,
                    deploymentDeclaration,
                    EvidenceCaptureOutcome.SYSTEM_PROPERTIES_UNAVAILABLE
            );
        }

        try {
            arguments = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                    .filter(RemoteJmxExposureEvidence::isRelevantArgument)
                    .toList();
        } catch (RuntimeException exception) {
            return incomplete(
                    properties,
                    arguments,
                    deploymentDeclaration,
                    EvidenceCaptureOutcome.JVM_ARGUMENTS_UNAVAILABLE);
        }

        return of(properties, arguments, deploymentDeclaration);
    }

    public Map<String, String> systemProperties() {
        return systemProperties;
    }

    public List<String> jvmArguments() {
        return jvmArguments;
    }

    public RemoteJmxDeploymentDeclaration deploymentDeclaration() {
        return deploymentDeclaration;
    }

    public EvidenceCaptureOutcome captureOutcome() {
        return captureOutcome;
    }

    private static boolean isRelevantArgument(String argument) {
        return argument.startsWith("-D" + JMX_REMOTE_PREFIX)
                || argument.equals(DISABLE_ATTACH_MECHANISM_ARGUMENT)
                || argument.equals(ENABLE_ATTACH_MECHANISM_ARGUMENT);
    }
}
