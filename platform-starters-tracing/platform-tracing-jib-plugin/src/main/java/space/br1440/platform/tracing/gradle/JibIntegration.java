package space.br1440.platform.tracing.gradle;

import java.util.List;
import java.util.Map;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
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
    private static final String PLUGIN_VERSION_INPUT = "platformTracingJibPluginVersion";

    static void configure(Project project,
                          TaskProvider<VerifyControlledAgentTask> verify,
                          Provider<RegularFile> verifiedOutput,
                          TaskProvider<VerifyAgentClasspathTask> runtimeGuard,
                          ControlledAgentPin pin) {
        Map<String, String> properties = Map.of(
                PlatformTracingJibExtension.EXPECTED_SHA256_PROPERTY, pin.sha256()
        );

        JibExtension jib = project.getExtensions().getByType(JibExtension.class);
        jib.pluginExtensions(spec -> spec.pluginExtension(parameters -> {
            parameters.setImplementation(PlatformTracingJibExtension.class.getName());
            parameters.setProperties(properties);
        }));

        runtimeGuard.configure(task -> task.getClasspathRoots().add(jibClasspathRoot(project, jib)));

        String pluginVersion = JibPluginVersion.load();
        JIB_IMAGE_TASK_NAMES.forEach(taskName -> project.getTasks().named(taskName, task -> {
            task.dependsOn(verify, runtimeGuard);
            task.getInputs()
                    .file(verifiedOutput)
                    .withPropertyName(AGENT_INPUT_PROPERTY)
                    .withPathSensitivity(PathSensitivity.NONE);
            task.getInputs().property(PLUGIN_VERSION_INPUT, pluginVersion);
            task.notCompatibleWithConfigurationCache("GoogleContainerTools/jib#3132");
            task.doFirst(ignored -> requireSingleRegistration(jib));
        }));
    }

    private static void requireSingleRegistration(JibExtension jib) {
        List<String> implementations = jib.getPluginExtensions().get().stream()
                .map(ExtensionParameters::getImplementation)
                .toList();

        PlatformTracingJibExtension.registrationViolation(implementations).ifPresent(message -> {
            throw new GradleException(message);
        });
    }

    private static Provider<ResolvedComponentResult> jibClasspathRoot(Project project, JibExtension jib) {
        return jib.getConfigurationName()
                .flatMap(name -> project.getConfigurations().named(name))
                .flatMap(configuration -> configuration.getIncoming().getResolutionResult().getRootComponent());
    }
}
