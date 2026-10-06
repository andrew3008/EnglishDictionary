package space.br1440.platform.tracing.gradle.javaagent;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.DocsType;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;

final class JavaAgentDistributionVerification {

    private static final String VERIFICATION_GROUP = "verification";
    private static final String ARTIFACT_TEST_SUITE = "controlledAgentArtifactTest";
    private static final String RUNTIME_TEST_SUITE = "controlledAgentRuntimeTest";
    private static final String OTLP_PROBE_CLASSPATH_CONFIGURATION = "otlpProbeApiRuntimeClasspath";
    private static final String JMX_APPLICATION_CONTRACT_SOURCES_CONFIGURATION = "jmxApplicationContractSources";
    private static final String JMX_APPLICATION_CONTRACT_SOURCES_USAGE = "platform-jmx-application-contract-sources";

    private JavaAgentDistributionVerification() {
    }

    static TaskProvider<Test> configure(Project project,
                                        JavaAgentDistributionDependencies dependencies,
                                        JavaAgentDistributionAssembly.Registration assembly,
                                        Provider<Directory> testRepository) {
        JavaPluginExtension javaPlugin = project.getExtensions().getByType(JavaPluginExtension.class);

        SourceSet artifactTestSourceSet = createTestSourceSet(
                project,
                javaPlugin,
                ARTIFACT_TEST_SUITE
        );

        TaskProvider<Test> artifactTest = registerTestTask(
                project,
                artifactTestSourceSet,
                "Runs the controlled agent artifact test suite."
        );

        SourceSet runtimeTestSourceSet = createTestSourceSet(
                project,
                javaPlugin,
                RUNTIME_TEST_SUITE
        );

        TaskProvider<Test> runtimeTest = registerTestTask(
                project,
                runtimeTestSourceSet,
                "Runs the controlled agent runtime test suite."
        );

        configureOtlpProbeClasspath(project, runtimeTest);
        configureJmxGeneratedSource(project, runtimeTestSourceSet);
        configureControlledTest(project, artifactTest, dependencies, assembly, testRepository);
        configureControlledTest(project, runtimeTest, dependencies, assembly, testRepository);

        project.getTasks().named("check", task -> task.dependsOn(artifactTest, runtimeTest));
        return artifactTest;
    }

    private static void configureControlledTest(Project project,
                                                TaskProvider<Test> testTask,
                                                JavaAgentDistributionDependencies dependencies,
                                                JavaAgentDistributionAssembly.Registration assembly,
                                                Provider<Directory> testRepository) {
        testTask.configure(task -> {
            task.setEnabled(true);
            task.dependsOn(assembly.distributionTasks().assembleDistribution());

            ControlledAgentTestArguments arguments = project.getObjects().newInstance(ControlledAgentTestArguments.class);
            arguments.getControlledAgentJar().set(assembly.outputs().controlledAgentJar());
            arguments.getExtensionJar().set(assembly.packagedExtension());
            arguments.getExtensionLibraryJar().fileProvider(dependencies.extensionLibrary());
            arguments.getUpstreamAgentJar().fileProvider(dependencies.upstreamAgent());
            arguments.getDistributionVersion().set(project.getVersion().toString());
            arguments.getUpstreamCoordinate().set(dependencies.upstreamCoordinate());
            arguments.getTestRepositoryRoot().set(testRepository);
            task.getJvmArgumentProviders().add(arguments);
        });
    }

    private static SourceSet createTestSourceSet(Project project,
                                                 JavaPluginExtension javaPlugin,
                                                 String name) {
        SourceSet sourceSet = javaPlugin.getSourceSets().create(name);
        SourceSet standardTest = javaPlugin.getSourceSets().getByName(SourceSet.TEST_SOURCE_SET_NAME);

        Configuration sourceSetImplementation = project.getConfigurations()
                .getByName(sourceSet.getImplementationConfigurationName());

        Configuration sourceSetRuntimeOnly = project.getConfigurations()
                .getByName(sourceSet.getRuntimeOnlyConfigurationName());

        sourceSetImplementation.extendsFrom(project.getConfigurations()
                .getByName(standardTest.getImplementationConfigurationName()));

        sourceSetRuntimeOnly.extendsFrom(project.getConfigurations()
                .getByName(standardTest.getRuntimeOnlyConfigurationName()));

        return sourceSet;
    }

    private static TaskProvider<Test> registerTestTask(Project project,
                                                       SourceSet sourceSet,
                                                       String description) {
        return project.getTasks().register(sourceSet.getName(), Test.class, task -> {
            task.setGroup(VERIFICATION_GROUP);
            task.setDescription(description);
            task.setTestClassesDirs(sourceSet.getOutput().getClassesDirs());
            task.setClasspath(sourceSet.getRuntimeClasspath());
            task.useJUnitPlatform();
        });
    }

    private static void configureOtlpProbeClasspath(Project project, TaskProvider<Test> runtimeTest) {
        Configuration classpath = project.getConfigurations().create(
                OTLP_PROBE_CLASSPATH_CONFIGURATION,
                configuration -> {
                    configuration.setCanBeConsumed(false);
                    configuration.setCanBeResolved(true);
                });

        runtimeTest.configure(task -> {
            OtlpProbeClasspathArguments arguments = project.getObjects().newInstance(OtlpProbeClasspathArguments.class);
            arguments.getClasspath().from(classpath);
            task.getJvmArgumentProviders().add(arguments);
        });
    }

    private static void configureJmxGeneratedSource(Project project, SourceSet runtimeTestSourceSet) {
        Configuration contractSources = project.getConfigurations().create(
                JMX_APPLICATION_CONTRACT_SOURCES_CONFIGURATION,
                configuration -> {
                    configuration.setCanBeConsumed(false);
                    configuration.setCanBeResolved(true);
                    configuration.setTransitive(false);
                    configuration.attributes(attributes -> {
                        attributes.attribute(
                                Usage.USAGE_ATTRIBUTE,
                                project.getObjects().named(
                                        Usage.class,
                                        JMX_APPLICATION_CONTRACT_SOURCES_USAGE
                                )
                        );

                        attributes.attribute(
                                Category.CATEGORY_ATTRIBUTE,
                                project.getObjects().named(Category.class, Category.DOCUMENTATION)
                        );

                        attributes.attribute(
                                DocsType.DOCS_TYPE_ATTRIBUTE,
                                project.getObjects().named(DocsType.class, DocsType.SOURCES)
                        );
                    });
                });

        runtimeTestSourceSet.getJava().srcDir(contractSources);
    }
}
