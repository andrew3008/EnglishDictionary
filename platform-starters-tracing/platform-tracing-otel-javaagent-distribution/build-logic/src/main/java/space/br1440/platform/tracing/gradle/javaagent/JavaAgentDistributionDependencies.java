package space.br1440.platform.tracing.gradle.javaagent;

import java.io.File;
import java.util.List;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.provider.Provider;

record JavaAgentDistributionDependencies(Configuration extensionRuntime,
                                         Provider<File> upstreamAgent,
                                         Provider<String> upstreamCoordinate,
                                         Provider<File> extensionLibrary) {

    private static final String OTEL_JAVA_AGENT_CONFIGURATION = "otelJavaAgent";
    private static final String AGENT_EXTENSION_LIBRARY_CONFIGURATION = "agentExtensionLibrary";
    private static final String AGENT_EXTENSION_CONFIGURATION = "agentExtension";

    static JavaAgentDistributionDependencies configure(Project project) {
        Configuration upstreamAgent = createUpstreamAgentConfiguration(project);
        Configuration extensionLibrary = createExtensionLibraryConfiguration(project);
        Configuration extensionRuntime = createExtensionRuntimeConfiguration(project, extensionLibrary);
        Provider<ResolvedArtifactResult> resolvedUpstreamAgent = resolveSingleArtifact(upstreamAgent);
        Provider<ResolvedArtifactResult> resolvedExtensionLibrary = resolveSingleArtifact(extensionLibrary);

        return new JavaAgentDistributionDependencies(
                extensionRuntime,
                resolvedUpstreamAgent.map(ResolvedArtifactResult::getFile),
                resolvedUpstreamAgent.map(artifact -> coordinateOf(artifact.getId().getComponentIdentifier())),
                resolvedExtensionLibrary.map(ResolvedArtifactResult::getFile)
        );
    }

    private static Configuration createUpstreamAgentConfiguration(Project project) {
        return project.getConfigurations().create(
                OTEL_JAVA_AGENT_CONFIGURATION,
                candidate -> {
                    candidate.setCanBeConsumed(false);
                    candidate.setCanBeResolved(true);
                    candidate.setTransitive(false);
                });
    }

    private static Configuration createExtensionLibraryConfiguration(Project project) {
        return project.getConfigurations().create(
                AGENT_EXTENSION_LIBRARY_CONFIGURATION,
                candidate -> {
                    candidate.setCanBeConsumed(false);
                    candidate.setCanBeResolved(true);
                    candidate.setTransitive(false);
                    configureExtensionAttributes(project, candidate);
                });
    }

    private static Configuration createExtensionRuntimeConfiguration(Project project,
                                                                     Configuration extensionLibrary) {
        return project.getConfigurations().create(
                AGENT_EXTENSION_CONFIGURATION,
                candidate -> {
                    candidate.setCanBeConsumed(false);
                    candidate.setCanBeResolved(true);
                    candidate.setTransitive(true);
                    candidate.extendsFrom(extensionLibrary);
                    configureExtensionAttributes(project, candidate);
                });
    }

    private static void configureExtensionAttributes(Project project, Configuration configuration) {
        configuration.attributes(attributes -> {
            attributes.attribute(
                    Usage.USAGE_ATTRIBUTE,
                    project.getObjects().named(Usage.class, Usage.JAVA_RUNTIME)
            );

            attributes.attribute(
                    Category.CATEGORY_ATTRIBUTE,
                    project.getObjects().named(Category.class, Category.LIBRARY)
            );

            attributes.attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    project.getObjects().named(LibraryElements.class, LibraryElements.JAR)
            );

            attributes.attribute(
                    Bundling.BUNDLING_ATTRIBUTE,
                    project.getObjects().named(Bundling.class, Bundling.EXTERNAL)
            );
        });
    }

    private static Provider<ResolvedArtifactResult> resolveSingleArtifact(Configuration configuration) {
        String configurationName = configuration.getName();
        return configuration.getIncoming()
                .getArtifacts()
                .getResolvedArtifacts()
                .map(artifacts -> {
                    List<ResolvedArtifactResult> matches = artifacts.stream().toList();
                    if (matches.size() != 1) {
                        throw new GradleException(configurationName
                                + " must resolve to exactly one artifact, got "
                                + matches.size() + ": " + matches);
                    }

                    return matches.getFirst();
                });
    }

    private static String coordinateOf(ComponentIdentifier component) {
        if (component instanceof ProjectComponentIdentifier projectIdentifier) {
            return "project:" + projectIdentifier.getProjectPath();
        }

        if (component instanceof ModuleComponentIdentifier moduleIdentifier) {
            return moduleIdentifier.getGroup() + ":" + moduleIdentifier.getModule() + ":" + moduleIdentifier.getVersion();
        }

        throw new GradleException("Unsupported Java agent distribution component identifier: " + component);
    }
}
