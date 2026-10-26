package space.br1440.platform.tracing.gradle.javaagent;

import java.io.File;
import java.util.ArrayList;
import java.util.Set;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.DocsType;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven;
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.testing.Test;

@SuppressWarnings("unused")
public final class JavaAgentDistributionPlugin implements Plugin<Project> {

    private static final String EMBEDDED_EXTENSION_NAME = "platform-tracing-otel-javaagent-extension.jar";
    private static final String EXTENSION_ENTRY_PATH = JavaAgentDistribution.EXTENSIONS_DIRECTORY + "/" + EMBEDDED_EXTENSION_NAME;
    private static final String MANIFEST_ENTRY_PATH = "META-INF/MANIFEST.MF";
    private static final String ARTIFACT_TEST_NAME = "controlledAgentArtifactTest";
    private static final String RUNTIME_TEST_NAME = "controlledAgentRuntimeTest";
    private static final String TEST_REPOSITORY_NAME = "testRepository";

    static final String NON_LIBRARY_CATEGORY = "platform-controlled-javaagent";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaLibraryPlugin.class);
        project.getPluginManager().apply(MavenPublishPlugin.class);

        Provider<String> distributionVersion = project.provider(() -> project.getVersion().toString());
        Provider<String> expectedOtelSdkVersion = VersionCatalogs.requiredVersion(project, "bom.opentelemetry");

        AgentInputs inputs = createAgentInputs(project);
        Provider<Directory> testRepository = project.getLayout()
                .getBuildDirectory()
                .dir("test-repository");

        AgentOutputs outputs = registerAgentTasks(project, distributionVersion, inputs);
        AgentTestFixture fixture = new AgentTestFixture(
                distributionVersion,
                expectedOtelSdkVersion,
                inputs,
                outputs
        );
        configureOutgoingVariants(project, outputs);
        TaskProvider<Test> artifactTest = configureTestSuites(
                project,
                fixture,
                testRepository
        );
        configurePublication(
                project,
                outputs,
                testRepository,
                artifactTest
        );
    }

    private static AgentInputs createAgentInputs(Project project) {
        Configuration otelJavaAgent = project.getConfigurations().create("otelJavaAgent", configuration -> {
            configuration.setCanBeConsumed(false);
            configuration.setCanBeResolved(true);
            configuration.setTransitive(false);
        });

        Configuration packagedExtension = project.getConfigurations().create(
                "packagedJavaAgentExtension",
                configuration -> {
                    configuration.setCanBeConsumed(false);
                    configuration.setCanBeResolved(true);
                    configuration.setTransitive(false);
                    JavaAgentExtensionPackagingPlugin.packagedExtensionAttributes(
                            project.getObjects(),
                            configuration.getAttributes()
                    );
                }
        );

        Provider<ResolvedArtifactResult> upstreamArtifact = singleArtifact(otelJavaAgent);
        Provider<ResolvedArtifactResult> packagedExtensionArtifact = singleArtifact(packagedExtension);
        Provider<File> upstreamAgentFile = upstreamArtifact.map(ResolvedArtifactResult::getFile);
        Provider<String> upstreamCoordinate = upstreamArtifact.map(artifact ->
                ComponentIdentity.of(artifact.getId().getComponentIdentifier()).value()
        );
        Provider<File> packagedExtensionFile = packagedExtensionArtifact.map(ResolvedArtifactResult::getFile);

        return new AgentInputs(upstreamAgentFile, upstreamCoordinate, packagedExtensionFile);
    }

    private static Provider<ResolvedArtifactResult> singleArtifact(Configuration configuration) {
        String configurationName = configuration.getName();
        return configuration.getIncoming().getArtifacts().getResolvedArtifacts().map(artifacts -> {
            ArrayList<ResolvedArtifactResult> resolved = new ArrayList<>(artifacts);
            if (resolved.size() != 1) {
                throw new GradleException(
                        configurationName + " must resolve to exactly one artifact, got "
                                + resolved.size() + ": " + resolved
                );
            }
            return resolved.getFirst();
        });
    }

    private static AgentOutputs registerAgentTasks(Project project,
                                                   Provider<String> distributionVersion,
                                                   AgentInputs inputs) {
        Provider<RegularFile> controlledAgentJar = project.getLayout()
                .getBuildDirectory()
                .file(distributionVersion.map(version ->
                        "libs/" + JavaAgentDistribution.MAVEN_ARTIFACT_ID + "-" + version + ".jar"
                ));

        Provider<RegularFile> identityFile = project.getLayout()
                .getBuildDirectory()
                .file("controlled-agent/staging/" + JavaAgentDistribution.IDENTITY_FILE_NAME);

        Provider<RegularFile> pinFile = project.getLayout()
                .getBuildDirectory()
                .file("controlled-agent/controlled-agent-pin.properties");

        TaskProvider<PrepareControlledAgentIdentityTask> prepareIdentity = project.getTasks().register(
                "prepareControlledAgentIdentity",
                PrepareControlledAgentIdentityTask.class,
                task -> {
                    task.setGroup(BasePlugin.BUILD_GROUP);
                    task.setDescription("Validates agent inputs and writes the controlled distribution identity");
                    task.getUpstreamAgentJar().fileProvider(inputs.upstreamAgentFile());
                    task.getPackagedExtensionJar().fileProvider(inputs.packagedExtensionFile());
                    task.getDistributionVersion().set(distributionVersion);
                    task.getUpstreamCoordinate().set(inputs.upstreamCoordinate());
                    task.getExtensionPath().set(EXTENSION_ENTRY_PATH);
                    task.getReservedEntries().set(Set.of(
                            EXTENSION_ENTRY_PATH,
                            JavaAgentDistribution.IDENTITY_ENTRY_PATH
                    ));
                    task.getIdentityFile().set(identityFile);
                }
        );

        FileTree upstreamAgentTree = project.zipTree(inputs.upstreamAgentFile());
        Provider<File> upstreamManifest = upstreamAgentTree
                .matching(patterns -> patterns.include(MANIFEST_ENTRY_PATH))
                .getElements()
                .map(elements -> {
                    if (elements.size() != 1) {
                        throw new GradleException(
                                "Expected upstream javaagent to contain exactly one %s, found %d"
                                        .formatted(MANIFEST_ENTRY_PATH, elements.size())
                        );
                    }
                    return elements.iterator().next().getAsFile();
                });

        TaskProvider<Jar> assembleJar = project.getTasks().register(
                "assembleControlledJavaAgentJar",
                Jar.class,
                task -> {
                    task.setGroup(BasePlugin.BUILD_GROUP);

                    task.getArchiveFileName().set(
                            controlledAgentJar.map(file -> file.getAsFile().getName())
                    );

                    task.getDestinationDirectory().set(
                            project.getLayout().getBuildDirectory().dir("controlled-agent/staging")
                    );

                    task.getRootSpec().from(
                            upstreamAgentTree,
                            spec -> spec.exclude(MANIFEST_ENTRY_PATH)
                    );

                    task.getRootSpec().from(
                            inputs.packagedExtensionFile(),
                            spec -> {
                                spec.into(JavaAgentDistribution.EXTENSIONS_DIRECTORY);
                                spec.rename(ignored -> EMBEDDED_EXTENSION_NAME);
                            }
                    );

                    task.getRootSpec().from(
                            prepareIdentity.flatMap(PrepareControlledAgentIdentityTask::getIdentityFile),
                            spec -> spec.into(JavaAgentDistribution.IDENTITY_DIRECTORY)
                    );

                    task.getManifest().from(upstreamManifest);
                    task.setPreserveFileTimestamps(false);
                    task.setReproducibleFileOrder(true);
                    task.setMetadataCharset("UTF-8");
                    task.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
                }
        );

        TaskProvider<PromoteControlledAgentTask> promoteAgent = project.getTasks().register(
                "promoteControlledAgent",
                PromoteControlledAgentTask.class,
                task -> {
                    task.setGroup(BasePlugin.BUILD_GROUP);
                    task.getStagedJar().set(assembleJar.flatMap(Jar::getArchiveFile));
                    task.getPublishedJar().set(controlledAgentJar);
                }
        );
        Provider<RegularFile> publishedJar = promoteAgent.flatMap(PromoteControlledAgentTask::getPublishedJar);

        TaskProvider<Task> assembleAgent = project.getTasks().register(
                "assembleControlledJavaAgent",
                task -> {
                    task.setGroup(BasePlugin.BUILD_GROUP);
                    task.setDescription("Assembles the published controlled Java agent");
                    task.dependsOn(promoteAgent);
                }
        );

        TaskProvider<WriteJavaAgentPinTask> writePin = project.getTasks().register(
                "writeControlledAgentPin",
                WriteJavaAgentPinTask.class,
                task -> {
                    task.setGroup(BasePlugin.BUILD_GROUP);
                    task.setDescription("Writes the controlled Java agent version and SHA-256 pin");
                    task.getAgentJar().set(publishedJar);
                    task.getAgentVersion().set(distributionVersion);
                    task.getPinFile().set(pinFile);
                }
        );
        Provider<RegularFile> publishedPin = writePin.flatMap(WriteJavaAgentPinTask::getPinFile);

        project.getTasks().named(BasePlugin.ASSEMBLE_TASK_NAME).configure(task ->
                task.dependsOn(assembleAgent, writePin)
        );
        return new AgentOutputs(publishedJar, publishedPin);
    }

    private static void configureOutgoingVariants(Project project,
                                                  AgentOutputs outputs) {
        Configuration controlledAgentElements = createConsumable(project, "controlledAgentElements");
        project.getArtifacts().add(
                controlledAgentElements.getName(),
                outputs.publishedJar(),
                artifact -> artifact.setType(ArtifactTypeDefinition.JAR_TYPE)
        );

        Configuration controlledAgentPinElements = createConsumable(project, "controlledAgentPinElements");
        project.getArtifacts().add(
                controlledAgentPinElements.getName(),
                outputs.pinFile(),
                artifact -> artifact.setType("properties")
        );

        for (String configurationName : new String[]{
                JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME,
                JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME
        }) {
            project.getConfigurations().named(configurationName).configure(configuration -> {
                configuration.getOutgoing().getArtifacts().clear();
                configuration.getOutgoing().getVariants().clear();
                configuration.getAttributes().attribute(
                        Category.CATEGORY_ATTRIBUTE,
                        project.getObjects().named(Category.class, NON_LIBRARY_CATEGORY)
                );
            });
        }

        project.getConfigurations().named(Dependency.DEFAULT_CONFIGURATION).configure(configuration -> {
            configuration.getOutgoing().getArtifacts().clear();
            configuration.getOutgoing().getVariants().clear();
        });

        project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class).configure(task ->
                task.setEnabled(false)
        );
    }

    private static Configuration createConsumable(Project project, String name) {
        return project.getConfigurations().create(name, configuration -> {
            configuration.setCanBeConsumed(true);
            configuration.setCanBeResolved(false);
        });
    }

    private static TaskProvider<Test> configureTestSuites(Project project,
                                                          AgentTestFixture fixture,
                                                          Provider<Directory> testRepository) {
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        Provider<Configuration> standardTestImplementation = project.getConfigurations()
                .named(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME);
        Provider<Configuration> standardTestRuntimeOnly = project.getConfigurations()
                .named(JavaPlugin.TEST_RUNTIME_ONLY_CONFIGURATION_NAME);

        SourceSet artifactTestSourceSet = createTestSourceSet(
                project,
                sourceSets,
                ARTIFACT_TEST_NAME,
                standardTestImplementation,
                standardTestRuntimeOnly
        );

        SourceSet runtimeTestSourceSet = createTestSourceSet(
                project,
                sourceSets,
                RUNTIME_TEST_NAME,
                standardTestImplementation,
                standardTestRuntimeOnly
        );

        TestRegistration artifactTest = registerTestTask(
                project,
                artifactTestSourceSet,
                "Runs the controlled agent artifact test suite.",
                fixture
        );

        TestRegistration runtimeTest = registerTestTask(
                project,
                runtimeTestSourceSet,
                "Runs the controlled agent runtime test suite.",
                fixture
        );

        artifactTest.arguments().getTestRepositoryRoot().set(testRepository);
        configureRuntimeTestProbes(project, runtimeTest, runtimeTestSourceSet);

        project.getTasks().named(JavaBasePlugin.CHECK_TASK_NAME).configure(task ->
                task.dependsOn(artifactTest.task(), runtimeTest.task())
        );

        return artifactTest.task();
    }

    private static void configureRuntimeTestProbes(Project project,
                                                   TestRegistration runtimeTest,
                                                   SourceSet runtimeTestSourceSet) {
        Configuration otlpProbeClasspath = project.getConfigurations().create(
                "otlpProbeApiRuntimeClasspath",
                configuration -> {
                    configuration.setCanBeConsumed(false);
                    configuration.setCanBeResolved(true);
                }
        );

        runtimeTest.task().configure(task -> {
            OtlpProbeClasspathArguments arguments = project.getObjects()
                    .newInstance(OtlpProbeClasspathArguments.class);
            arguments.getClasspath().from(otlpProbeClasspath);
            task.getJvmArgumentProviders().add(arguments);
        });

        Configuration jmxContractSources = project.getConfigurations().create(
                "jmxApplicationContractSources",
                configuration -> {
                    configuration.setCanBeConsumed(false);
                    configuration.setCanBeResolved(true);
                    configuration.setTransitive(false);
                    configuration.getAttributes().attribute(
                            Usage.USAGE_ATTRIBUTE,
                            project.getObjects().named(
                                    Usage.class,
                                    "platform-jmx-application-contract-sources"
                            )
                    );

                    configuration.getAttributes().attribute(
                            Category.CATEGORY_ATTRIBUTE,
                            project.getObjects().named(Category.class, Category.DOCUMENTATION)
                    );

                    configuration.getAttributes().attribute(
                            DocsType.DOCS_TYPE_ATTRIBUTE,
                            project.getObjects().named(DocsType.class, DocsType.SOURCES)
                    );
                }
        );

        TaskProvider<Sync> syncJmxContractSources = project.getTasks().register(
                "syncControlledAgentRuntimeTestJmxContractSources",
                Sync.class,
                task -> {
                    task.from(jmxContractSources);
                    task.into(
                            project.getLayout()
                                    .getBuildDirectory()
                                    .dir("generated/sources/controlledAgentRuntimeTest/jmxContract/java")
                    );
                }
        );

        runtimeTestSourceSet.getJava().srcDir(syncJmxContractSources);
    }

    private static SourceSet createTestSourceSet(Project project,
                                                 SourceSetContainer sourceSets,
                                                 String name,
                                                 Provider<Configuration> standardTestImplementation,
                                                 Provider<Configuration> standardTestRuntimeOnly) {
        SourceSet sourceSet = sourceSets.create(name);

        project.getConfigurations()
                .named(sourceSet.getImplementationConfigurationName())
                .configure(configuration ->
                        configuration.extendsFrom(standardTestImplementation.get())
                );

        project.getConfigurations()
                .named(sourceSet.getRuntimeOnlyConfigurationName())
                .configure(configuration ->
                        configuration.extendsFrom(standardTestRuntimeOnly.get())
                );

        return sourceSet;
    }

    private static TestRegistration registerTestTask(Project project,
                                                     SourceSet sourceSet,
                                                     String taskDescription,
                                                     AgentTestFixture fixture) {
        ControlledAgentTestArguments arguments = project.getObjects()
                .newInstance(ControlledAgentTestArguments.class);
        arguments.getControlledAgentJar().set(fixture.outputs().publishedJar());
        arguments.getExtensionJar().fileProvider(fixture.inputs().packagedExtensionFile());
        arguments.getUpstreamAgentJar().fileProvider(fixture.inputs().upstreamAgentFile());
        arguments.getDistributionVersion().set(fixture.version());
        arguments.getExpectedOtelSdkVersion().set(fixture.expectedOtelSdkVersion());
        arguments.getExpectedUpstreamCoordinate().set(fixture.inputs().upstreamCoordinate());

        TaskProvider<Test> testTask = project.getTasks().register(
                sourceSet.getName(),
                Test.class,
                task -> {
                    task.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
                    task.setDescription(taskDescription);
                    task.setEnabled(true);
                    task.setTestClassesDirs(sourceSet.getOutput().getClassesDirs());
                    task.setClasspath(sourceSet.getRuntimeClasspath());
                    task.useJUnitPlatform();
                    task.getJvmArgumentProviders().add(arguments);
                }
        );

        return new TestRegistration(testTask, arguments);
    }

    private static void configurePublication(Project project,
                                             AgentOutputs outputs,
                                             Provider<Directory> testRepository,
                                             TaskProvider<Test> artifactTest) {
        PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
        publishing.getRepositories().maven(repository -> {
            repository.setName(TEST_REPOSITORY_NAME);
            repository.setUrl(testRepository);
        });
        publishing.getPublications().create(
                "mavenJava",
                MavenPublication.class,
                publication -> {
                    publication.setArtifactId(JavaAgentDistribution.MAVEN_ARTIFACT_ID);

                    publication.artifact(
                            outputs.publishedJar(),
                            artifact -> artifact.setExtension(ArtifactTypeDefinition.JAR_TYPE)
                    );

                    publication.getPom()
                            .getName()
                            .set("Platform Tracing Controlled OpenTelemetry Java Agent");

                    publication.getPom()
                            .getDescription()
                            .set(
                                    "Controlled OpenTelemetry Java agent distribution "
                                            + "with the platform tracing extension embedded"
                            );
                }
        );

        TaskProvider<Delete> resetTestRepository = project.getTasks().register(
                "resetTestRepository",
                Delete.class,
                task -> task.delete(testRepository)
        );

        Spec<AbstractPublishToMaven> targetsTestRepository = JavaAgentDistributionPlugin::targetsTestRepository;
        TaskCollection<AbstractPublishToMaven> testRepositoryPublications = project.getTasks()
                .withType(AbstractPublishToMaven.class)
                .matching(targetsTestRepository);
        project.getTasks().withType(AbstractPublishToMaven.class).configureEach(task -> {
            if (targetsTestRepository.isSatisfiedBy(task)) {
                task.dependsOn(resetTestRepository);
            } else {
                task.dependsOn(project.getTasks().named(JavaBasePlugin.CHECK_TASK_NAME));
            }
        });

        ConfigurableFileTree repositoryInventory = project.fileTree(testRepository);
        repositoryInventory.exclude("**/maven-metadata*");
        artifactTest.configure(task -> {
            task.dependsOn(testRepositoryPublications);
            task.getInputs()
                    .files(repositoryInventory)
                    .withPropertyName("publishedRepositoryArtifacts")
                    .withPathSensitivity(PathSensitivity.RELATIVE);
        });
    }

    private static boolean targetsTestRepository(AbstractPublishToMaven task) {
        return task instanceof PublishToMavenRepository publish
                && publish.getRepository() != null
                && TEST_REPOSITORY_NAME.equals(publish.getRepository().getName());
    }

    private record AgentInputs(Provider<File> upstreamAgentFile,
                               Provider<String> upstreamCoordinate,
                               Provider<File> packagedExtensionFile) {
    }

    private record AgentOutputs(Provider<RegularFile> publishedJar,
                                Provider<RegularFile> pinFile) {
    }

    private record AgentTestFixture(Provider<String> version,
                                    Provider<String> expectedOtelSdkVersion,
                                    AgentInputs inputs,
                                    AgentOutputs outputs) {
    }

    private record TestRegistration(TaskProvider<Test> task,
                                    ControlledAgentTestArguments arguments) {
    }
}
