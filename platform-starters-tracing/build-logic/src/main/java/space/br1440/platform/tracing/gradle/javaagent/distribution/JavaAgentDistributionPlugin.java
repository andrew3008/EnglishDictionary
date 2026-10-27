package space.br1440.platform.tracing.gradle.javaagent.distribution;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;

@SuppressWarnings("unused")
public final class JavaAgentDistributionPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaBasePlugin.class);
        project.getPluginManager().apply(MavenPublishPlugin.class);

        Provider<String> version = project.provider(() -> project.getVersion().toString());
        ControlledAgentAssembly.Inputs inputs = ControlledAgentAssembly.declareInputs(project);
        ControlledAgentAssembly.Artifacts artifacts = ControlledAgentAssembly.register(project, version, inputs);
        ControlledAgentPublication.TestPublication publication = ControlledAgentPublication.register(project, artifacts);
        ControlledAgentVerification.register(project, version, inputs, artifacts, publication);
    }
}
