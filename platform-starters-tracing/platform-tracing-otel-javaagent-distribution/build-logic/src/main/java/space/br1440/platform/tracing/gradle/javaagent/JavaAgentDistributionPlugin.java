package space.br1440.platform.tracing.gradle.javaagent;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;

import com.github.jengelman.gradle.plugins.shadow.ShadowExtension;
import com.github.jengelman.gradle.plugins.shadow.ShadowPlugin;

public final class JavaAgentDistributionPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaLibraryPlugin.class);
        project.getPluginManager().apply(MavenPublishPlugin.class);
        project.getPluginManager().apply(ShadowPlugin.class);
        project.getExtensions()
                .getByType(ShadowExtension.class)
                .getAddShadowVariantIntoJavaComponent()
                .set(false);

        JavaAgentDistributionDependencies dependencies = JavaAgentDistributionDependencies.configure(project);
        JavaAgentDistributionAssembly.Registration assembly = JavaAgentDistributionAssembly.configure(project, dependencies);
        Provider<Directory> testRepository = project.getLayout()
                .getBuildDirectory()
                .dir("test-repository");

        TaskProvider<Test> artifactTest = JavaAgentDistributionVerification.configure(
                project,
                dependencies,
                assembly,
                testRepository
        );

        JavaAgentDistributionPublishing.configure(project, assembly, testRepository, artifactTest);
    }
}
