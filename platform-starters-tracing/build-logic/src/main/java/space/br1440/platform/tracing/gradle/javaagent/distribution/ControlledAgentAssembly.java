package space.br1440.platform.tracing.gradle.javaagent.distribution;

import java.io.File;
import java.util.ArrayList;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

import space.br1440.platform.tracing.gradle.javaagent.ComponentIdentity;
import space.br1440.platform.tracing.gradle.javaagent.PackagedExtensionAttributes;

final class ControlledAgentAssembly {

    private ControlledAgentAssembly() {
    }

    static Inputs declareInputs(Project project) {
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
                    PackagedExtensionAttributes.configure(project.getObjects(), configuration.getAttributes());
                }
        );

        Provider<ResolvedArtifactResult> upstreamArtifact = singleArtifact(otelJavaAgent);
        Provider<ResolvedArtifactResult> extensionArtifact = singleArtifact(packagedExtension);
        return new Inputs(
                upstreamArtifact.map(ResolvedArtifactResult::getFile),
                upstreamArtifact.map(artifact -> ComponentIdentity.of(artifact.getId().getComponentIdentifier()).value()),
                extensionArtifact.map(ResolvedArtifactResult::getFile)
        );
    }

    private static Provider<ResolvedArtifactResult> singleArtifact(Configuration configuration) {
        String configurationName = configuration.getName();
        return configuration.getIncoming().getArtifacts().getResolvedArtifacts().map(artifacts -> {
            ArrayList<ResolvedArtifactResult> resolved = new ArrayList<>(artifacts);
            if (resolved.size() != 1) {
                throw new GradleException(
                        "%s must resolve to exactly one artifact, got %d: %s"
                                .formatted(configurationName, resolved.size(), resolved)
                );
            }
            return resolved.getFirst();
        });
    }

    static Artifacts register(Project project, Provider<String> version, Inputs inputs) {
        Provider<RegularFile> controlledAgentJar = project.getLayout().getBuildDirectory().file(
                version.map(value -> "libs/" + JavaAgentDistribution.MAVEN_ARTIFACT_ID + "-" + value + ".jar")
        );

        TaskProvider<PrepareControlledAgentIdentityTask> prepareIdentity = project.getTasks().register(
                "prepareControlledAgentIdentity",
                PrepareControlledAgentIdentityTask.class,
                task -> {
                    task.setGroup(BasePlugin.BUILD_GROUP);
                    task.setDescription("Validates agent inputs and writes the controlled distribution identity");
                    task.getUpstreamAgentJar().fileProvider(inputs.upstreamAgentFile());
                    task.getPackagedExtensionJar().fileProvider(inputs.packagedExtensionFile());
                    task.getDistributionVersion().set(version);
                    task.getUpstreamCoordinate().set(inputs.upstreamCoordinate());
                    task.getIdentityFile().set(project.getLayout().getBuildDirectory()
                            .file("controlled-agent/staging/" + JavaAgentDistribution.IDENTITY_FILE_NAME));
                }
        );

        FileTree upstreamAgentTree = project.zipTree(inputs.upstreamAgentFile());
        Provider<File> upstreamManifest = upstreamAgentTree
                .matching(patterns -> patterns.include(JavaAgentDistribution.MANIFEST_ENTRY_PATH))
                .getElements()
                .map(elements -> {
                    if (elements.size() != 1) {
                        throw new GradleException(
                                "Expected upstream javaagent to contain exactly one %s, found %d"
                                        .formatted(JavaAgentDistribution.MANIFEST_ENTRY_PATH, elements.size())
                        );
                    }

                    return elements.iterator().next().getAsFile();
                });

        TaskProvider<Jar> assembleJar = project.getTasks().register(
                "assembleControlledJavaAgentJar",
                Jar.class,
                task -> {
                    task.setGroup(BasePlugin.BUILD_GROUP);
                    task.getArchiveFileName().set(controlledAgentJar.map(file -> file.getAsFile().getName()));

                    task.getDestinationDirectory().set(
                            project.getLayout()
                                    .getBuildDirectory()
                                    .dir("controlled-agent/staging")
                    );

                    task.getRootSpec().from(
                            upstreamAgentTree,
                            spec -> spec.exclude(JavaAgentDistribution.MANIFEST_ENTRY_PATH)
                    );

                    task.getRootSpec().from(
                            inputs.packagedExtensionFile(),
                            spec -> {
                                spec.into(JavaAgentDistribution.EXTENSIONS_DIRECTORY);
                                spec.rename(ignored -> JavaAgentDistribution.EMBEDDED_EXTENSION_NAME);
                            });

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
        TaskProvider<WriteJavaAgentPinTask> writePin = project.getTasks().register(
                "writeControlledAgentPin",
                WriteJavaAgentPinTask.class,
                task -> {
                    task.setGroup(BasePlugin.BUILD_GROUP);
                    task.setDescription("Writes the controlled Java agent version and SHA-256 pin");
                    task.getAgentJar().set(publishedJar);
                    task.getAgentVersion().set(version);
                    task.getPinFile().set(
                            project.getLayout()
                                    .getBuildDirectory()
                                    .file("controlled-agent/controlled-agent-pin.properties")
                    );
                }
        );
        project.getTasks().named(BasePlugin.ASSEMBLE_TASK_NAME).configure(task -> task.dependsOn(writePin));
        return new Artifacts(publishedJar, writePin.flatMap(WriteJavaAgentPinTask::getPinFile));
    }

    record Inputs(Provider<File> upstreamAgentFile,
                  Provider<String> upstreamCoordinate,
                  Provider<File> packagedExtensionFile) {
    }

    record Artifacts(Provider<RegularFile> agentJar, Provider<RegularFile> pinFile) {
    }
}
