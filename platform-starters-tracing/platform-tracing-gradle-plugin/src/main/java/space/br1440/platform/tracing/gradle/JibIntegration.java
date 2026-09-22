package space.br1440.platform.tracing.gradle;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.NonNull;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskProvider;

import com.google.cloud.tools.jib.gradle.ExtensionParameters;
import com.google.cloud.tools.jib.gradle.JibExtension;

import lombok.experimental.UtilityClass;

@UtilityClass
final class JibIntegration {

    private static final List<String> JIB_IMAGE_TASK_NAMES = List.of("jib", "jibDockerBuild", "jibBuildTar");
    private static final String AGENT_INPUT_PROPERTY = "controlledAgentJar";

    static void configure(Project project,
                          TaskProvider<VerifyControlledAgentTask> verify,
                          Provider<RegularFile> verifiedAgentJar,
                          Provider<RegularFile> verifiedOutput,
                          ControlledAgentPin pin) {
        Map<String, String> properties = Map.of(
                PlatformTracingJibExtension.AGENT_PATH_PROPERTY, verifiedAgentJar.get().getAsFile().getAbsolutePath(),
                PlatformTracingJibExtension.EXPECTED_SHA256_PROPERTY, pin.sha256()
        );

        JibExtension jib = project.getExtensions().getByType(JibExtension.class);
        jib.pluginExtensions(spec -> spec.pluginExtension(parameters -> {
            parameters.setImplementation(PlatformTracingJibExtension.class.getName());
            parameters.setProperties(properties);
        }));

        RegistrationGuard guard = new RegistrationGuard(jib);
        JIB_IMAGE_TASK_NAMES.forEach(taskName -> project.getTasks().named(taskName, task -> {
            task.dependsOn(verify);
            task.getInputs()
                    .file(verifiedOutput)
                    .withPropertyName(AGENT_INPUT_PROPERTY)
                    .withPathSensitivity(PathSensitivity.NONE);
            task.doFirst(guard);
        }));
    }

    private static final class RegistrationGuard implements Action<Task> {

        private final JibExtension jib;

        private RegistrationGuard(JibExtension jib) {
            this.jib = jib;
        }

        @Override
        public void execute(@NonNull Task task) {
            List<String> implementations = jib.getPluginExtensions().get().stream()
                    .map(ExtensionParameters::getImplementation)
                    .toList();

            Optional<String> violation = PlatformTracingJibExtension.registrationViolation(implementations);
            if (violation.isPresent()) {
                throw new GradleException(violation.get());
            }
        }
    }
}
