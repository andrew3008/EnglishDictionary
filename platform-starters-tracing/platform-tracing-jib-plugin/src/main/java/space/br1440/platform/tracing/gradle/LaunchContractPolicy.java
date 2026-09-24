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
    static final String IMAGE_AGENT_DIRECTORY = "/opt/platform-tracing";

    private static final String JAVA_LAUNCHER = "java";
    private static final String CLASSPATH_FLAG = "-cp";
    private static final String CLASSPATH_LONG_FLAG = "-classpath";
    private static final String CLASSPATH_GNU_FLAG = "--class-path";
    private static final String JAVAAGENT_FLAG_PREFIX = "-javaagent:";
    private static final String AGENTPATH_FLAG_PREFIX = "-agentpath:";
    private static final String AGENTLIB_FLAG_PREFIX = "-agentlib:";
    private static final String XRUN_FLAG_PREFIX = "-Xrun";
    private static final String BOOTCLASSPATH_FLAG_PREFIX = "-Xbootclasspath";
    private static final String VM_OPTIONS_FILE_PREFIX = "-XX:VMOptionsFile=";
    private static final String FLAGS_FILE_PREFIX = "-XX:Flags=";
    private static final String MANAGEMENT_SERVER_FLAG = "-XX:+ManagementServer";
    private static final String MODULE_FLAG = "--module";
    private static final String MODULE_SHORT_FLAG = "-m";
    private static final String JAR_FLAG = "-jar";
    private static final String ARGFILE_PREFIX = "@";
    private static final String OTEL_PROPERTY_PREFIX = "otel.";
    private static final String PLATFORM_PROPERTY_PREFIX = "platform.tracing.";
    private static final String MANAGEMENT_AGENT_PROPERTY_PREFIX = "com.sun.management";
    private static final String OTEL_ENVIRONMENT_PREFIX = "OTEL_";
    private static final String CURRENT_DIRECTORY = ".";
    private static final String PARENT_DIRECTORY = "..";
    private static final String MANAGEMENT_AGENT_REASON = """
            a JDK management agent activation argument; \
            the agent denies runtime mutation registration when it is present""";

    private static final Set<String> RESERVED_JVM_ENVIRONMENT_NAMES = Set.of(
            "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "_JAVA_OPTIONS"
    );

    static List<String> violations(List<String> entrypoint, Map<String, String> environment) {
        List<String> violations = new ArrayList<>();
        parsed(entrypoint).ifPresentOrElse(
                launch -> checkJvmSegment(launch.jvmFlags(), violations),
                () -> violations.add(formViolation(entrypoint))
        );
        checkEnvironment(environment, violations);
        return List.copyOf(violations);
    }

    static Optional<String> classpathToken(List<String> entrypoint) {
        return parsed(entrypoint).map(ParsedLaunch::classpathToken);
    }

    static List<String> classpathElementViolations(String classpath) {
        List<String> violations = new ArrayList<>();
        for (String element : classpath.split(":", -1)) {
            if (element.isEmpty()) {
                continue;
            }
            classpathElementViolation(element).ifPresent(violations::add);
        }
        return List.copyOf(violations);
    }

    static List<String> withManagedFlags(List<String> generatedEntrypoint, String expectedSha256) {
        List<String> result = new ArrayList<>(generatedEntrypoint);
        int classpathFlag = result.indexOf(CLASSPATH_FLAG);
        int insertion = classpathFlag < 0 ? 1 : classpathFlag;
        result.addAll(insertion, ManagedAgentJvmFlags.forAgentPath(IMAGE_AGENT_PATH, expectedSha256));
        return List.copyOf(result);
    }

    private static Optional<ParsedLaunch> parsed(List<String> entrypoint) {
        if (entrypoint == null || entrypoint.isEmpty() || !JAVA_LAUNCHER.equals(entrypoint.getFirst())) {
            return Optional.empty();
        }

        int classpathFlag = entrypoint.indexOf(CLASSPATH_FLAG);
        if (classpathFlag < 1 || classpathFlag + 2 >= entrypoint.size()) {
            return Optional.empty();
        }

        String mainClass = entrypoint.get(classpathFlag + 2);
        if (mainClass.startsWith("-")) {
            return Optional.empty();
        }

        for (String token : entrypoint.subList(1, classpathFlag)) {
            if (CLASSPATH_LONG_FLAG.equals(token) || CLASSPATH_GNU_FLAG.equals(token)
                    || JAR_FLAG.equals(token) || MODULE_FLAG.equals(token) || MODULE_SHORT_FLAG.equals(token)) {
                return Optional.empty();
            }
        }

        return Optional.of(new ParsedLaunch(
                List.copyOf(entrypoint.subList(1, classpathFlag)),
                entrypoint.get(classpathFlag + 1),
                mainClass
        ));
    }

    private static void checkJvmSegment(List<String> jvmFlags, List<String> violations) {
        for (String flag : jvmFlags) {
            checkJvmFlag(flag, violations);
        }
    }

    private static void checkJvmFlag(String flag, List<String> violations) {
        if (flag.startsWith(ARGFILE_PREFIX)) {
            violations.add(conflict(flag, "a JVM argument file; its contents are not part of the image contract"));
            return;
        }

        if (flag.startsWith(JAVAAGENT_FLAG_PREFIX)) {
            violations.add(conflict(flag, "a second -javaagent; the controlled agent is already wired by the platform"));
            return;
        }

        if (flag.startsWith(AGENTPATH_FLAG_PREFIX) || flag.startsWith(AGENTLIB_FLAG_PREFIX)
                || flag.startsWith(XRUN_FLAG_PREFIX)) {
            violations.add(conflict(flag, "a JVM agent library"));
            return;
        }

        if (flag.startsWith(BOOTCLASSPATH_FLAG_PREFIX)) {
            violations.add(conflict(flag, "a boot classpath override"));
            return;
        }

        if (flag.startsWith(VM_OPTIONS_FILE_PREFIX) || flag.startsWith(FLAGS_FILE_PREFIX)) {
            violations.add(conflict(flag, "a JVM options file"));
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
        if (key.isEmpty()) {
            return;
        }

        if (key.startsWith(OTEL_PROPERTY_PREFIX)) {
            violations.add(conflict(flag, "the OpenTelemetry system property prefix " + OTEL_PROPERTY_PREFIX));
        } else if (key.startsWith(PLATFORM_PROPERTY_PREFIX)) {
            violations.add(conflict(flag, "the platform system property prefix " + PLATFORM_PROPERTY_PREFIX));
        } else if (key.startsWith(MANAGEMENT_AGENT_PROPERTY_PREFIX)) {
            violations.add(conflict(flag, MANAGEMENT_AGENT_REASON));
        }
    }

    static Optional<String> classpathElementViolation(String element) {
        if (!element.startsWith("/")) {
            return Optional.of("classpath element " + element + " is not an absolute Unix path");
        }

        List<String> components = new ArrayList<>();
        for (String component : element.split("/", -1)) {
            if (component.isEmpty()) {
                continue;
            }

            if (CURRENT_DIRECTORY.equals(component) || PARENT_DIRECTORY.equals(component)) {
                return Optional.of("classpath element " + element + " is not canonical");
            }

            components.add(component);
        }

        String canonical = "/" + String.join("/", components);
        if (canonical.equals(IMAGE_AGENT_DIRECTORY) || canonical.startsWith(IMAGE_AGENT_DIRECTORY + "/")) {
            return Optional.of("classpath element " + element + " is inside " + IMAGE_AGENT_DIRECTORY);
        }

        return Optional.empty();
    }

    private static String formViolation(List<String> entrypoint) {
        if (entrypoint == null) {
            return """
                    jib.container.entrypoint is INHERIT: the base image entrypoint is unknown to the build, \
                    so the controlled agent and the platform -D flags cannot be added to the JVM command. \
                    Remove the entrypoint setting and let Jib generate the java launch command.""";
        }

        return """
                jib.container.entrypoint %s is not a Jib Java classpath launch \
                (java <jvm flags> -cp <classpath> <main class> <application args>). \
                -classpath and --class-path are not accepted; Jib emits -cp. \
                java -jar, modular launch, and a main class token that starts with '-' are rejected."""
                .formatted(entrypoint);
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
                platform.tracing-jib owns this flag as part of the controlled agent launch contract."""
                .formatted(flag, reason);
    }

    private record ParsedLaunch(List<String> jvmFlags, String classpathToken, String mainClass) {
    }
}
