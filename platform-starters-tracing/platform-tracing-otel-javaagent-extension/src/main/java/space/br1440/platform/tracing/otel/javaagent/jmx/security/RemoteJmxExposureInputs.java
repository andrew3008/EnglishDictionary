package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Неизменяемый набор входных evidence для классификации remote JMX.
 */
public final class RemoteJmxExposureInputs {

    static final String JMX_REMOTE_PREFIX = "com.sun.management.jmxremote";
    static final String DISABLE_ATTACH_MECHANISM_ARGUMENT = "-XX:+DisableAttachMechanism";
    static final String ENABLE_ATTACH_MECHANISM_ARGUMENT = "-XX:-DisableAttachMechanism";
    private static final String CAPTURE_SUCCESS_CODE = "NONE";
    private static final Pattern CAPTURE_FAILURE_CODE_PATTERN =
            Pattern.compile("[A-Z][A-Z0-9_]{0,127}");

    private final Map<String, String> systemProperties;
    private final List<String> jvmArguments;
    private final RemoteJmxDeploymentDeclaration deploymentDeclaration;
    private final boolean captureComplete;
    private final String captureFailureCode;

    private RemoteJmxExposureInputs(
            Map<String, String> systemProperties,
            List<String> jvmArguments,
            RemoteJmxDeploymentDeclaration deploymentDeclaration,
            boolean captureComplete,
            String captureFailureCode) {
        this.systemProperties = Map.copyOf(systemProperties);
        this.jvmArguments = List.copyOf(jvmArguments);
        this.deploymentDeclaration = Objects.requireNonNull(
                deploymentDeclaration,
                "Deployment JMX declaration must not be null");
        this.captureComplete = captureComplete;
        this.captureFailureCode = requireCaptureFailureCode(captureComplete, captureFailureCode);
    }

    public static RemoteJmxExposureInputs of(
            Map<String, String> systemProperties,
            List<String> jvmArguments,
            RemoteJmxDeploymentDeclaration deploymentDeclaration) {
        Objects.requireNonNull(systemProperties, "System properties must not be null");
        Objects.requireNonNull(jvmArguments, "JVM arguments must not be null");
        return new RemoteJmxExposureInputs(
                systemProperties,
                jvmArguments,
                deploymentDeclaration,
                true,
                CAPTURE_SUCCESS_CODE);
    }

    public static RemoteJmxExposureInputs incomplete(
            Map<String, String> systemProperties,
            List<String> jvmArguments,
            RemoteJmxDeploymentDeclaration deploymentDeclaration,
            String captureFailureCode) {
        Objects.requireNonNull(systemProperties, "System properties must not be null");
        Objects.requireNonNull(jvmArguments, "JVM arguments must not be null");
        return new RemoteJmxExposureInputs(
                systemProperties,
                jvmArguments,
                deploymentDeclaration,
                false,
                captureFailureCode);
    }

    /**
     * Снимает только релевантные JMX-свойства и аргументы, не сохраняя произвольные JVM secrets.
     */
    public static RemoteJmxExposureInputs currentProcess(
            RemoteJmxDeploymentDeclaration deploymentDeclaration) {
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
                    "SYSTEM_PROPERTIES_UNAVAILABLE");
        }

        try {
            arguments = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                    .filter(RemoteJmxExposureInputs::isRelevantArgument)
                    .toList();
        } catch (RuntimeException exception) {
            return incomplete(
                    properties,
                    arguments,
                    deploymentDeclaration,
                    "JVM_ARGUMENTS_UNAVAILABLE");
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

    public boolean captureComplete() {
        return captureComplete;
    }

    public String captureFailureCode() {
        return captureFailureCode;
    }

    private static boolean isRelevantArgument(String argument) {
        return argument.startsWith("-D" + JMX_REMOTE_PREFIX)
                || argument.equals(DISABLE_ATTACH_MECHANISM_ARGUMENT)
                || argument.equals(ENABLE_ATTACH_MECHANISM_ARGUMENT);
    }

    private static String requireCaptureFailureCode(
            boolean captureComplete,
            String captureFailureCode) {
        if (captureFailureCode == null
                || !CAPTURE_FAILURE_CODE_PATTERN.matcher(captureFailureCode).matches()) {
            throw new IllegalArgumentException(
                    "Capture failure code must match [A-Z][A-Z0-9_]{0,127}");
        }
        if (captureComplete != CAPTURE_SUCCESS_CODE.equals(captureFailureCode)) {
            throw new IllegalArgumentException(
                    "Capture failure code must be NONE exactly when capture is complete");
        }
        return captureFailureCode;
    }
}
