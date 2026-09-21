package space.br1440.platform.tracing.gradle;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

public abstract class VerifyJibLaunchContractTask extends DefaultTask {

    private static final Set<String> RESERVED_JVM_ENVIRONMENT_NAMES = Set.of(
            "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"
    );

    private static final String JAVAAGENT_FLAG_PREFIX = "-javaagent:";

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getStagedAgentJar();

    @Input
    public abstract ListProperty<String> getRequiredJvmFlags();

    @Input
    public abstract ListProperty<String> getForbiddenJvmFlags();

    @Input
    public abstract ListProperty<String> getForbiddenSystemPropertyKeyPrefixes();

    @Input
    public abstract ListProperty<String> getManagedSystemPropertyKeys();

    @Input
    public abstract ListProperty<String> getJvmFlags();

    @Input
    public abstract ListProperty<String> getEntrypoint();

    @Input
    public abstract MapProperty<String, String> getEnvironment();

    @Input
    public abstract ListProperty<String> getExtraDirectoryDestinations();

    @Input
    public abstract Property<String> getRequiredAgentDestination();

    @Input
    public abstract ListProperty<String> getPluginExtensionImplementations();

    @TaskAction
    public void verify() {
        rejectPluginExtensions();
        rejectCustomEntrypoint();
        rejectManagedNamesInContainerEnvironment();
        requireManagedJvmFlags();
        requireStagedAgentLayer();
    }

    private void rejectPluginExtensions() {
        List<String> implementations = getPluginExtensionImplementations().get();
        if (!implementations.isEmpty()) {
            throw new GradleException("""
                    jib.pluginExtensions must stay empty when platform.tracing-javaagent \
                    manages the launch contract: %s. The platform does not register its own Jib \
                    extension, so any registered extension is unsupported here: Jib applies \
                    extensions after this check runs and could drop the controlled agent layer \
                    or rewrite the entrypoint without failing the build. Remove it from \
                    jib.pluginExtensions."""
                    .formatted(implementations)
            );
        }
    }

    private void rejectCustomEntrypoint() {
        List<String> entrypoint = getEntrypoint().get();
        if (!entrypoint.isEmpty()) {
            throw new GradleException("""
                    jib.container.entrypoint must stay empty when platform.tracing-javaagent \
                    manages the launch contract: Jib ignores jvmFlags, mainClass and classpath once \
                    entrypoint is set, including the built-in 'INHERIT' value, so the controlled \
                    agent and the platform -D flags would silently disappear from the running JVM. \
                    Configured entrypoint: %s"""
                    .formatted(entrypoint)
            );
        }
    }

    private void rejectManagedNamesInContainerEnvironment() {
        Map<String, String> environment = getEnvironment().get();
        for (String name : environment.keySet()) {
            if (RESERVED_JVM_ENVIRONMENT_NAMES.contains(name)) {
                throw new GradleException("""
                        jib.container.environment must not set %s: the controlled agent and the \
                        platform -D flags are already delivered through jib.container.jvmFlags. \
                        Baking a competing value into the image would not be visible to the \
                        Kubernetes Deployment render checks."""
                        .formatted(name)
                );
            }

            if (name.startsWith("OTEL_")) {
                throw new GradleException("""
                        jib.container.environment must not set %s: OTEL_* variables are managed by \
                        the platform-tracing Helm helper, not by the image."""
                        .formatted(name)
                );
            }
        }
    }

    private void requireManagedJvmFlags() {
        List<String> current = getJvmFlags().get();
        List<String> required = getRequiredJvmFlags().get();

        reportMultiplicityViolations(current, required);
        rejectForbiddenFlags(current, required);
    }

    private static void reportMultiplicityViolations(List<String> current, List<String> required) {
        Set<String> missing = new LinkedHashSet<>();
        Set<String> duplicated = new LinkedHashSet<>();
        for (String flag : required) {
            long count = current.stream().filter(flag::equals).count();
            if (count == 0) {
                missing.add(flag);
            } else if (count > 1) {
                duplicated.add(flag);
            }
        }

        if (!missing.isEmpty()) {
            throw new GradleException("""
                    jib.container.jvmFlags no longer contains the platform-tracing launch contract: \
                    %s. A later assignment in build.gradle replaced the managed flags after \
                    configuration. Append to jib.container.jvmFlags instead of reassigning it after \
                    platform.tracing-javaagent is applied."""
                    .formatted(missing)
            );
        }

        if (!duplicated.isEmpty()) {
            throw new GradleException("""
                    jib.container.jvmFlags contains the platform-tracing launch contract more \
                    than once: %s. Remove the duplicate application-side entry; \
                    platform.tracing-javaagent already appends it exactly once."""
                    .formatted(duplicated)
            );
        }
    }

    private void rejectForbiddenFlags(List<String> current, List<String> required) {
        List<String> forbiddenExact = getForbiddenJvmFlags().get();
        List<String> forbiddenKeyPrefixes = getForbiddenSystemPropertyKeyPrefixes().get();
        List<String> managedSystemPropertyKeys = getManagedSystemPropertyKeys().get();

        for (String flag : current) {
            if (required.contains(flag)) {
                continue;
            }

            if (flag.startsWith(JAVAAGENT_FLAG_PREFIX)) {
                throw conflict(flag, "a second -javaagent; the controlled agent is already wired by the platform");
            }

            if (forbiddenExact.contains(flag)) {
                throw conflict(flag, "a flag that the platform already manages as part of the launch contract");
            }

            Optional<SystemPropertyFlag> property = SystemPropertyFlag.parse(flag);
            if (property.isEmpty()) {
                continue;
            }

            String key = property.get().key();
            if (startsWithAny(key, forbiddenKeyPrefixes)) {
                throw conflict(flag, "a JDK management agent activation argument; "
                        + "the agent denies runtime mutation registration when it is present");
            }

            if (managedSystemPropertyKeys.contains(key)) {
                throw conflict(flag, "the managed OpenTelemetry key " + key);
            }

            if (isPlatformOwnedKey(key, required)) {
                throw conflict(flag, "a platform-managed system property with an unexpected value");
            }
        }
    }

    private static boolean startsWithAny(String value, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isPlatformOwnedKey(String key, List<String> required) {
        for (String flag : required) {
            Optional<SystemPropertyFlag> property = SystemPropertyFlag.parse(flag);
            if (property.isPresent() && property.get().key().equals(key)) {
                return true;
            }
        }

        return false;
    }

    private static GradleException conflict(String flag, String reason) {
        return new GradleException("""
                jib.container.jvmFlags contains %s, which duplicates %s. Remove it: \
                platform.tracing-javaagent owns this flag as part of the controlled agent launch \
                contract."""
                .formatted(flag, reason)
        );
    }

    private void requireStagedAgentLayer() {
        File stagedAgent = getStagedAgentJar().get().getAsFile();
        if (!stagedAgent.isFile()) {
            throw new GradleException("""
                    Staged controlled agent is missing: %s. The Jib extra-directory source must \
                    contain agent.jar before the image is built."""
                    .formatted(stagedAgent.getAbsolutePath())
            );
        }

        String requiredDestination = getRequiredAgentDestination().get();
        if (!getExtraDirectoryDestinations().get().contains(requiredDestination)) {
            throw new GradleException("""
                    jib.extraDirectories no longer copies the controlled agent into %s. \
                    A later assignment replaced the managed extra directory. Append a path \
                    instead of reassigning extraDirectories.paths after \
                    platform.tracing-javaagent is applied."""
                    .formatted(requiredDestination)
            );
        }
    }
}
