package space.br1440.platform.tracing.gradle.javaagent.distribution;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.DocsType;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;

import space.br1440.platform.tracing.gradle.javaagent.VersionCatalogs;

final class ControlledAgentVerification {

    private ControlledAgentVerification() {
    }

    static void register(Project project,
                         Provider<String> version,
                         ControlledAgentAssembly.Inputs inputs,
                         ControlledAgentAssembly.Artifacts artifacts,
                         ControlledAgentPublication.TestPublication publication) {
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        SourceSet artifactSources = sourceSets.create("controlledAgentArtifactTest");
        SourceSet runtimeSources = sourceSets.create("controlledAgentRuntimeTest");
        Provider<String> sdkVersion = VersionCatalogs.requiredVersion(project, "bom.opentelemetry");

        TestRegistration artifactTest = registerTest(
                project, artifactSources,
                "Runs the controlled agent artifact test suite.", version, sdkVersion, inputs, artifacts
        );

        TestRegistration runtimeTest = registerTest(
                project, runtimeSources,
                "Runs the controlled agent runtime test suite.", version, sdkVersion, inputs, artifacts
        );

        artifactTest.arguments().getTestRepositoryRoot().set(publication.repository());
        ConfigurableFileTree repositoryInventory = project.fileTree(publication.repository());
        repositoryInventory.exclude("**/maven-metadata*");
        artifactTest.task().configure(task -> {
            task.dependsOn(publication.tasks());
            task.getInputs().files(repositoryInventory)
                    .withPropertyName("publishedRepositoryArtifacts")
                    .withPathSensitivity(PathSensitivity.RELATIVE);
        });

        configureRuntimeProbes(project, runtimeTest.arguments(), runtimeSources);

        project.getTasks().named(JavaBasePlugin.CHECK_TASK_NAME).configure(task ->
                task.dependsOn(artifactTest.task(), runtimeTest.task()));
    }

    private static void configureRuntimeProbes(Project project,
                                              ControlledAgentTestArguments arguments,
                                              SourceSet runtimeSources) {
        Configuration probeClasspath = project.getConfigurations().create("otlpProbeApiRuntimeClasspath", configuration -> {
            configuration.setCanBeConsumed(false);
            configuration.setCanBeResolved(true);
        });
        arguments.getOtlpProbeClasspath().from(probeClasspath);

        Configuration jmxSources = project.getConfigurations().create("jmxApplicationContractSources", configuration -> {
            configuration.setCanBeConsumed(false);
            configuration.setCanBeResolved(true);
            configuration.setTransitive(false);

            configuration.getAttributes().attribute(
                    Usage.USAGE_ATTRIBUTE,
                    project.getObjects().named(Usage.class, "platform-jmx-application-contract")
            );

            configuration.getAttributes().attribute(
                    Category.CATEGORY_ATTRIBUTE,
                    project.getObjects().named(Category.class, Category.DOCUMENTATION)
            );

            configuration.getAttributes().attribute(
                    DocsType.DOCS_TYPE_ATTRIBUTE,
                    project.getObjects().named(DocsType.class, DocsType.SOURCES)
            );
        });

        TaskProvider<Sync> jmxContractSources = project.getTasks().register(
                "syncControlledAgentRuntimeTestJmxContractSources",
                Sync.class,
                task -> {
                    task.from(jmxSources);
                    task.into(project.getLayout().getBuildDirectory()
                            .dir("generated/controlled-agent-runtime-test/jmx-contract"));
                }
        );
        runtimeSources.getJava().srcDir(jmxContractSources);
    }

    private static TestRegistration registerTest(Project project,
                                                 SourceSet sources,
                                                 String description,
                                                 Provider<String> version,
                                                 Provider<String> sdkVersion,
                                                 ControlledAgentAssembly.Inputs inputs,
                                                 ControlledAgentAssembly.Artifacts artifacts) {
        ControlledAgentTestArguments arguments = project.getObjects().newInstance(ControlledAgentTestArguments.class);
        arguments.getControlledAgentJar().set(artifacts.agentJar());
        arguments.getExtensionJar().fileProvider(inputs.packagedExtensionFile());
        arguments.getUpstreamAgentJar().fileProvider(inputs.upstreamAgentFile());
        arguments.getDistributionVersion().set(version);
        arguments.getExpectedOtelSdkVersion().set(sdkVersion);
        arguments.getExpectedUpstreamCoordinate().set(inputs.upstreamCoordinate());

        TaskProvider<Test> task = project.getTasks().register(sources.getName(), Test.class, test -> {
            test.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
            test.setDescription(description);
            test.setTestClassesDirs(sources.getOutput().getClassesDirs());
            test.setClasspath(sources.getRuntimeClasspath());
            test.useJUnitPlatform();
            test.getJvmArgumentProviders().add(arguments);
        });

        return new TestRegistration(task, arguments);
    }

    private record TestRegistration(TaskProvider<Test> task, ControlledAgentTestArguments arguments) {
    }
}
