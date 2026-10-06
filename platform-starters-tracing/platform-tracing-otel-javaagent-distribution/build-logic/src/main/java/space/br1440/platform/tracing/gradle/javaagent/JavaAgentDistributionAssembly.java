package space.br1440.platform.tracing.gradle.javaagent;

import java.util.List;
import java.util.Set;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationPublications;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.bundling.Jar;

import com.github.jengelman.gradle.plugins.shadow.ShadowBasePlugin;
import com.github.jengelman.gradle.plugins.shadow.ShadowJavaPlugin;
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;

final class JavaAgentDistributionAssembly {

    private static final String BUILD_GROUP = "build";

    private static final String EMBEDDED_EXTENSION_JAR_NAME = "platform-tracing-otel-javaagent-extension.jar";
    private static final String EXTENSION_ENTRY_PATH = "extensions/" + EMBEDDED_EXTENSION_JAR_NAME;
    private static final String IDENTITY_ENTRY_PATH = "META-INF/platform-tracing/javaagent-distribution.properties";

    private static final String CONTROLLED_AGENT_ELEMENTS_CONFIGURATION = "controlledAgentElements";
    private static final String CONTROLLED_AGENT_PIN_ELEMENTS_CONFIGURATION = "controlledAgentPinElements";
    private static final List<String> STANDARD_AGENT_VARIANT_CONFIGURATIONS = List.of(
            "apiElements",
            "runtimeElements"
    );
    private static final String DEFAULT_CONFIGURATION = "default";

    private static final String VALIDATE_INPUTS_TASK = "validateControlledAgentInputs";
    private static final String SYNC_STAGING_TASK = "syncControlledAgentStaging";
    private static final String ASSEMBLE_JAR_TASK = "assembleControlledJavaAgentJar";
    private static final String PUBLISH_AGENT_TASK = "publishControlledJavaAgent";
    private static final String ASSEMBLE_AGENT_TASK = "assembleControlledJavaAgent";
    private static final String WRITE_PIN_TASK = "writeControlledAgentPin";

    private JavaAgentDistributionAssembly() {
    }

    static Registration configure(Project project, JavaAgentDistributionDependencies dependencies) {
        OutputLocations outputs = createOutputLocations(project);

        TaskProvider<ShadowJar> packageExtension = JavaAgentExtensionPackaging.configure(
                project,
                dependencies.extensionRuntime(),
                dependencies.extensionLibrary()
        );
        Provider<RegularFile> packagedExtension = packageExtension.flatMap(AbstractArchiveTask::getArchiveFile);
        DistributionTasks distributionTasks = registerDistributionTasks(
                project,
                dependencies,
                outputs,
                packagedExtension
        );

        registerOutgoingArtifacts(project, outputs, distributionTasks);

        return new Registration(
                outputs,
                packagedExtension,
                packageExtension,
                distributionTasks
        );
    }

    private static OutputLocations createOutputLocations(Project project) {
        String agentFileName = JavaAgentDistribution.MAVEN_ARTIFACT_ID + "-" + project.getVersion() + ".jar";
        return new OutputLocations(
                project.getLayout().getBuildDirectory().file("libs/" + agentFileName),
                project.getLayout().getBuildDirectory()
                        .file("controlled-agent/staging/javaagent-distribution.properties"),
                project.getLayout().getBuildDirectory().dir("controlled-agent/staging-tree"),
                project.getLayout().getBuildDirectory()
                        .file("controlled-agent/controlled-agent-pin.properties")
        );
    }

    private static DistributionTasks registerDistributionTasks(Project project,
                                                               JavaAgentDistributionDependencies dependencies,
                                                               OutputLocations outputs,
                                                               Provider<RegularFile> packagedExtension) {
        TaskProvider<ValidateJavaAgentDistributionTask> validate = registerValidationTask(
                project,
                dependencies,
                outputs,
                packagedExtension
        );

        TaskProvider<PublishJavaAgentTask> publish = registerAssemblyPipeline(
                project,
                dependencies,
                outputs,
                packagedExtension,
                validate
        );

        return registerLifecycleTasks(project, outputs, publish);
    }

    private static TaskProvider<ValidateJavaAgentDistributionTask> registerValidationTask(Project project,
                                                                                          JavaAgentDistributionDependencies dependencies,
                                                                                          OutputLocations outputs,
                                                                                          Provider<RegularFile> packagedExtension) {
        return project.getTasks().register(
                VALIDATE_INPUTS_TASK,
                ValidateJavaAgentDistributionTask.class,
                task -> {
                    task.setGroup(BUILD_GROUP);
                    task.setDescription("Pin/SHA check of the OTel agent, extension dependency check, identity write");
                    task.getUpstreamAgentJar().fileProvider(dependencies.upstreamAgent());
                    task.getExtensionLibraryJar().fileProvider(dependencies.extensionLibrary());
                    task.getPackagedExtensionJar().set(packagedExtension);
                    task.getDistributionVersion().set(project.getVersion().toString());
                    task.getUpstreamCoordinate().set(dependencies.upstreamCoordinate());
                    task.getExtensionPath().set(EXTENSION_ENTRY_PATH);
                    task.getReservedEntries().set(Set.of(EXTENSION_ENTRY_PATH, IDENTITY_ENTRY_PATH));
                    task.getIdentityFile().set(outputs.identityFile());
                });
    }

    private static TaskProvider<PublishJavaAgentTask> registerAssemblyPipeline(Project project,
                                                                               JavaAgentDistributionDependencies dependencies,
                                                                               OutputLocations outputs,
                                                                               Provider<RegularFile> packagedExtension,
                                                                               TaskProvider<ValidateJavaAgentDistributionTask> validate) {
        TaskProvider<Sync> syncStaging = project.getTasks().register(
                SYNC_STAGING_TASK,
                Sync.class,
                task -> {
                    task.setGroup(BUILD_GROUP);
                    task.dependsOn(validate);
                    task.from(dependencies.upstreamAgent().map(project::zipTree));
                    task.from(packagedExtension, spec -> {
                        spec.into("extensions");
                        spec.rename(ignored -> EMBEDDED_EXTENSION_JAR_NAME);
                    });
                    task.from(outputs.identityFile(), spec ->
                            spec.into("META-INF/platform-tracing"));
                    task.into(outputs.stagingTree());
                    task.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
                });

        TaskProvider<Jar> assembleJar = project.getTasks().register(
                ASSEMBLE_JAR_TASK,
                Jar.class,
                task -> {
                    task.setGroup(BUILD_GROUP);
                    task.dependsOn(syncStaging);

                    task.getArchiveFileName().set(outputs.controlledAgentJar()
                            .map(file -> file.getAsFile().getName()));

                    task.getDestinationDirectory().set(
                            project.getLayout().getBuildDirectory().dir("controlled-agent/staging")
                    );

                    task.from(outputs.stagingTree(), spec ->
                            spec.exclude("META-INF/MANIFEST.MF"));

                    task.getManifest().from(outputs.stagingTree().map(
                            directory -> directory.file("META-INF/MANIFEST.MF").getAsFile())
                    );

                    task.setPreserveFileTimestamps(false);
                    task.setReproducibleFileOrder(true);
                    task.setMetadataCharset("UTF-8");
                    task.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
                });

        return project.getTasks().register(
                PUBLISH_AGENT_TASK,
                PublishJavaAgentTask.class,
                task -> {
                    task.setGroup(BUILD_GROUP);
                    task.dependsOn(assembleJar);
                    task.getStagedJar().set(assembleJar.flatMap(AbstractArchiveTask::getArchiveFile));
                    task.getPublishedJar().set(outputs.controlledAgentJar());
                });
    }

    private static DistributionTasks registerLifecycleTasks(Project project,
                                                            OutputLocations outputs,
                                                            TaskProvider<PublishJavaAgentTask> publish) {
        TaskProvider<Task> assembleDistribution = project.getTasks().register(
                ASSEMBLE_AGENT_TASK,
                task -> {
                    task.setGroup(BUILD_GROUP);
                    task.setDescription("Assemble the published controlled Java agent");
                    task.dependsOn(publish);
                });

        TaskProvider<WriteJavaAgentPinTask> writePin = project.getTasks().register(
                WRITE_PIN_TASK,
                WriteJavaAgentPinTask.class,
                task -> {
                    task.setGroup(BUILD_GROUP);
                    task.setDescription(
                            "Writes the controlled Java agent pin (version and SHA-256) consumed by the Gradle plugin");
                    task.dependsOn(publish);
                    task.getAgentJar().set(outputs.controlledAgentJar());
                    task.getAgentVersion().set(project.getVersion().toString());
                    task.getPinFile().set(outputs.pinFile());
                });

        project.getTasks().named("assemble", task ->
                task.dependsOn(assembleDistribution, writePin));

        return new DistributionTasks(assembleDistribution, writePin);
    }

    private static void registerOutgoingArtifacts(Project project,
                                                  OutputLocations outputs,
                                                  DistributionTasks tasks) {
        Configuration agentElements = createConsumableConfiguration(project, CONTROLLED_AGENT_ELEMENTS_CONFIGURATION);
        project.getArtifacts().add(agentElements.getName(), outputs.controlledAgentJar(), artifact -> {
            artifact.builtBy(tasks.assembleDistribution());
            artifact.setType("jar");
        });

        Configuration pinElements = createConsumableConfiguration(project, CONTROLLED_AGENT_PIN_ELEMENTS_CONFIGURATION);
        project.getArtifacts().add(pinElements.getName(), outputs.pinFile(), artifact -> {
            artifact.builtBy(tasks.writePin());
            artifact.setType("properties");
        });

        configureStandardOutgoingArtifacts(project, outputs, tasks);
    }

    private static void configureStandardOutgoingArtifacts(Project project,
                                                           OutputLocations outputs,
                                                           DistributionTasks tasks) {
        STANDARD_AGENT_VARIANT_CONFIGURATIONS.forEach(configurationName ->
                replaceOutgoingArtifact(
                        project.getConfigurations().getByName(configurationName),
                        outputs.controlledAgentJar(),
                        tasks.assembleDistribution()
                ));

        clearOutgoingArtifacts(project.getConfigurations().getByName(DEFAULT_CONFIGURATION));
        clearOutgoingArtifacts(project.getConfigurations().getByName(ShadowBasePlugin.CONFIGURATION_NAME));

        replaceOutgoingArtifact(
                project.getConfigurations().getByName(
                        ShadowJavaPlugin.SHADOW_RUNTIME_ELEMENTS_CONFIGURATION_NAME
                ),
                outputs.controlledAgentJar(),
                tasks.assembleDistribution()
        );

        project.getTasks().named("jar", Jar.class, task -> task.setEnabled(false));
    }

    private static void replaceOutgoingArtifact(Configuration configuration,
                                                Provider<RegularFile> artifact,
                                                TaskProvider<? extends Task> builtBy) {
        ConfigurationPublications outgoing = configuration.getOutgoing();
        outgoing.getArtifacts().clear();
        outgoing.getVariants().clear();
        outgoing.artifact(artifact, candidate -> {
            candidate.builtBy(builtBy);
            candidate.setType("jar");
        });
    }

    private static void clearOutgoingArtifacts(Configuration configuration) {
        ConfigurationPublications outgoing = configuration.getOutgoing();
        outgoing.getArtifacts().clear();
        outgoing.getVariants().clear();
    }

    private static Configuration createConsumableConfiguration(Project project, String name) {
        return project.getConfigurations().create(name, configuration -> {
            configuration.setCanBeConsumed(true);
            configuration.setCanBeResolved(false);
        });
    }

    record Registration(OutputLocations outputs,
                        Provider<RegularFile> packagedExtension,
                        TaskProvider<ShadowJar> packageExtension,
                        DistributionTasks distributionTasks) {
    }

    record OutputLocations(Provider<RegularFile> controlledAgentJar,
                           Provider<RegularFile> identityFile,
                           Provider<Directory> stagingTree,
                           Provider<RegularFile> pinFile) {
    }

    record DistributionTasks(TaskProvider<Task> assembleDistribution,
                             TaskProvider<WriteJavaAgentPinTask> writePin) {
    }
}
