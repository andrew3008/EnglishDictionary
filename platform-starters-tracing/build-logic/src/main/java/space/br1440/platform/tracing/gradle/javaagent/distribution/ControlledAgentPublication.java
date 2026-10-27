package space.br1440.platform.tracing.gradle.javaagent.distribution;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven;
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.TaskProvider;

final class ControlledAgentPublication {

    static final String NON_LIBRARY_CATEGORY = "platform-controlled-javaagent";
    private static final String TEST_REPOSITORY_NAME = "testRepository";

    private ControlledAgentPublication() {
    }

    static TestPublication register(Project project, ControlledAgentAssembly.Artifacts artifacts) {
        configureOutgoingVariants(project, artifacts);

        Provider<Directory> repositoryRoot = project.getLayout().getBuildDirectory().dir("test-repository");
        PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
        publishing.getRepositories().maven(repository -> {
            repository.setName(TEST_REPOSITORY_NAME);
            repository.setUrl(repositoryRoot);
        });

        publishing.getPublications().create("mavenJava", MavenPublication.class, publication -> {
            publication.setArtifactId(JavaAgentDistribution.MAVEN_ARTIFACT_ID);
            publication.artifact(artifacts.agentJar(), artifact -> artifact.setExtension(ArtifactTypeDefinition.JAR_TYPE));
            publication.getPom().getName().set("Platform Tracing Controlled OpenTelemetry Java Agent");
            publication.getPom().getDescription().set(
                    "Controlled OpenTelemetry Java agent distribution with the platform tracing extension embedded"
            );
        });

        TaskProvider<Delete> resetTestRepository = project.getTasks().register(
                "resetTestRepository", Delete.class, task -> task.delete(repositoryRoot)
        );

        TaskCollection<AbstractPublishToMaven> testPublications = project.getTasks()
                .withType(AbstractPublishToMaven.class)
                .matching(ControlledAgentPublication::targetsTestRepository);

        project.getTasks().withType(AbstractPublishToMaven.class).configureEach(task -> {
            if (targetsTestRepository(task)) {
                task.dependsOn(resetTestRepository);
            } else {
                task.dependsOn(project.getTasks().named(JavaBasePlugin.CHECK_TASK_NAME));
            }
        });

        return new TestPublication(repositoryRoot, testPublications);
    }

    private static void configureOutgoingVariants(Project project, ControlledAgentAssembly.Artifacts artifacts) {
        Configuration agentElements = createConsumable(project, "controlledAgentElements", LibraryElements.JAR);
        project.getArtifacts().add(
                agentElements.getName(),
                artifacts.agentJar(),
                artifact -> artifact.setType(ArtifactTypeDefinition.JAR_TYPE)
        );
        Configuration pinElements = createConsumable(project, "controlledAgentPinElements", "properties");
        project.getArtifacts().add(pinElements.getName(), artifacts.pinFile(), artifact -> artifact.setType("properties"));

        for (String name : new String[]{Dependency.DEFAULT_CONFIGURATION, Dependency.ARCHIVES_CONFIGURATION}) {
            project.getConfigurations().named(name).configure(configuration -> {
                if (!configuration.getOutgoing().getArtifacts().isEmpty()
                        || !configuration.getOutgoing().getVariants().isEmpty()) {
                    throw new GradleException(name + " must not publish distribution artifacts");
                }

                configuration.setCanBeConsumed(false);
            });
        }
    }

    private static Configuration createConsumable(Project project, String name, String libraryElements) {
        return project.getConfigurations().create(name, configuration -> {
            configuration.setCanBeConsumed(true);
            configuration.setCanBeResolved(false);
            //noinspection UnstableApiUsage — Gradle 8.14: setCanBeDeclared ещё @Incubating; вариант не принимает объявления зависимостей
            configuration.setCanBeDeclared(false);
            configuration.setVisible(false);
            configuration.getAttributes().attribute(
                    Category.CATEGORY_ATTRIBUTE,
                    project.getObjects().named(Category.class, NON_LIBRARY_CATEGORY)
            );
            configuration.getAttributes().attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    project.getObjects().named(LibraryElements.class, libraryElements)
            );
        });
    }

    private static boolean targetsTestRepository(AbstractPublishToMaven task) {
        return task instanceof PublishToMavenRepository publish
                && publish.getRepository() != null
                && TEST_REPOSITORY_NAME.equals(publish.getRepository().getName());
    }

    record TestPublication(Provider<Directory> repository, TaskCollection<AbstractPublishToMaven> tasks) {
    }
}
