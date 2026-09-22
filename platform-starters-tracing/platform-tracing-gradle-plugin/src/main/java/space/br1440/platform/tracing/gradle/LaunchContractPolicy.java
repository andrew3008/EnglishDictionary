package space.br1440.platform.tracing.gradle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.experimental.UtilityClass;

@UtilityClass
final class LaunchContractPolicy {

    static final String IMAGE_AGENT_PATH = "/opt/platform-tracing/agent.jar";

    private static final String JAVA_LAUNCHER = "java";
    private static final String JAVAAGENT_FLAG_PREFIX = "-javaagent:";
    private static final String MANAGEMENT_SERVER_FLAG = "-XX:+ManagementServer";
    private static final String MANAGEMENT_AGENT_PROPERTY_PREFIX = "com.sun.management";
    private static final String OTEL_ENVIRONMENT_PREFIX = "OTEL_";
    private static final String MANAGEMENT_AGENT_REASON = """
            a JDK management agent activation argument; \
            the agent denies runtime mutation registration when it is present""";

    private static final Set<String> RESERVED_JVM_ENVIRONMENT_NAMES = Set.of(
            "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "_JAVA_OPTIONS"
    );

    private static final Set<String> MANAGED_OTEL_PROPERTY_KEYS = Set.of(
            "otel.propagators",
            "otel.exporter.otlp.traces.endpoint",
            "otel.exporter.otlp.traces.protocol",
            "otel.traces.exporter",
            "otel.metrics.exporter",
            "otel.logs.exporter",
            "otel.service.name",
            "otel.resource.attributes",
            "otel.javaagent.configuration-file",
            "otel.javaagent.extensions",
            "otel.experimental.config.file",
            "otel.sdk.disabled",
            "otel.javaagent.enabled"
    );

    static List<String> violations(List<String> entrypoint, Map<String, String> environment) {
        List<String> violations = new ArrayList<>();
        checkEntrypoint(entrypoint, violations);
        checkEnvironment(environment, violations);
        return List.copyOf(violations);
    }

    static List<String> withManagedFlags(List<String> generatedEntrypoint, String expectedSha256) {
        List<String> result = new ArrayList<>(generatedEntrypoint);
        result.addAll(1, ManagedAgentJvmFlags.forAgentPath(IMAGE_AGENT_PATH, expectedSha256));
        return List.copyOf(result);
    }

    private static void checkEntrypoint(List<String> entrypoint, List<String> violations) {
        if (entrypoint == null) {
            violations.add("""
                    jib.container.entrypoint is INHERIT: the base image entrypoint is unknown to the build, \
                    so the controlled agent and the platform -D flags cannot be added to the JVM command. \
                    Remove the entrypoint setting and let Jib generate the java launch command.""");
            return;
        }

        if (entrypoint.isEmpty() || !JAVA_LAUNCHER.equals(entrypoint.getFirst())) {
            violations.add("""
                    jib.container.entrypoint is customised (%s): Jib ignores jvmFlags, mainClass and classpath \
                    once entrypoint is set, so the controlled agent and the platform -D flags would silently \
                    disappear from the running JVM. Remove the entrypoint setting."""
                    .formatted(entrypoint));
            return;
        }

        for (String token : entrypoint.subList(1, entrypoint.size())) {
            checkJvmFlag(token, violations);
        }
    }

    private static void checkJvmFlag(String flag, List<String> violations) {
        if (flag.startsWith(JAVAAGENT_FLAG_PREFIX)) {
            violations.add(conflict(flag, "a second -javaagent; the controlled agent is already wired by the platform"));
            return;
        }

        if (MANAGEMENT_SERVER_FLAG.equals(flag)) {
            violations.add(conflict(flag, MANAGEMENT_AGENT_REASON));
            return;
        }

        Optional<SystemPropertyFlag> property = SystemPropertyFlag.parse(flag);
        if (property.isEmpty()) {
            return;
        }

        String key = property.get().key();
        if (key.startsWith(MANAGEMENT_AGENT_PROPERTY_PREFIX)) {
            violations.add(conflict(flag, MANAGEMENT_AGENT_REASON));
        } else if (MANAGED_OTEL_PROPERTY_KEYS.contains(key)) {
            violations.add(conflict(flag, "the managed OpenTelemetry key " + key));
        } else if (ManagedAgentJvmFlags.EXPECTED_SHA256_PROPERTY.equals(key)) {
            violations.add(conflict(flag, "a platform-managed system property"));
        }
    }

    private static void checkEnvironment(Map<String, String> environment, List<String> violations) {
        for (String name : environment.keySet()) {
            if (RESERVED_JVM_ENVIRONMENT_NAMES.contains(name)) {
                violations.add("""
                        jib.container.environment must not set %s: the controlled agent and the \
                        platform -D flags are delivered through the image entrypoint. Baking a competing \
                        value into the image would not be visible to the Kubernetes Deployment render checks."""
                        .formatted(name));
            } else if (name.startsWith(OTEL_ENVIRONMENT_PREFIX)) {
                violations.add("""
                        jib.container.environment must not set %s: OTEL_* variables are managed by \
                        the platform-tracing Helm helper, not by the image."""
                        .formatted(name));
            }
        }
    }

    private static String conflict(String flag, String reason) {
        return """
                jib.container.jvmFlags contains %s, which duplicates %s. Remove it: \
                platform.tracing-javaagent owns this flag as part of the controlled agent launch contract."""
                .formatted(flag, reason);
    }
}
