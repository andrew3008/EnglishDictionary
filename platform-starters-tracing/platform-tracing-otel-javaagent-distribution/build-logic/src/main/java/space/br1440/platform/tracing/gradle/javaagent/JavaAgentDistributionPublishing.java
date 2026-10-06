package space.br1440.platform.tracing.gradle.javaagent;

import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven;
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;

final class JavaAgentDistributionPublishing {

    private static final String CONTROLLED_AGENT_POM_NAME =
            "Platform Tracing Controlled OpenTelemetry Java Agent";
    private static final String CONTROLLED_AGENT_POM_DESCRIPTION =
            "Controlled OpenTelemetry Java agent distribution with the platform tracing extension embedded";
    private static final String EXTENSION_POM_NAME =
            "Platform Tracing OTel Java agent extension";
    private static final String EXTENSION_POM_DESCRIPTION =
            "Standalone platform OpenTelemetry Java agent extension artifact";
    private static final String RESET_TEST_REPOSITORY_TASK = "resetTestRepository";
    private static final String TEST_REPOSITORY_NAME = "testRepository";

    private JavaAgentDistributionPublishing() {
    }

    static void configure(Project project,
                          JavaAgentDistributionAssembly.Registration assembly,
                          Provider<Directory> testRepository,
                          TaskProvider<Test> artifactTest) {
        configurePublications(project, assembly, testRepository);
        configureTestRepositoryWorkflow(project, testRepository, artifactTest);
    }

    private static void configurePublications(Project project,
                                              JavaAgentDistributionAssembly.Registration assembly,
                                              Provider<Directory> testRepository) {
        JavaAgentDistributionAssembly.OutputLocations outputs = assembly.outputs();
        JavaAgentDistributionAssembly.DistributionTasks distributionTasks = assembly.distributionTasks();

        PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
        publishing.getRepositories().maven(repository -> {
            repository.setName(TEST_REPOSITORY_NAME);
            repository.setUrl(testRepository);
        });

        publishing.getPublications().create("mavenJava", MavenPublication.class, publication -> {
            publication.setArtifactId(JavaAgentDistribution.MAVEN_ARTIFACT_ID);
            publication.artifact(outputs.controlledAgentJar(), artifact -> {
                artifact.builtBy(distributionTasks.assembleDistribution());
                artifact.setExtension("jar");
            });

            publication.getPom().getName().set(CONTROLLED_AGENT_POM_NAME);
            publication.getPom().getDescription().set(CONTROLLED_AGENT_POM_DESCRIPTION);
        });

        publishing.getPublications().create("extension", MavenPublication.class, publication -> {
            publication.setArtifactId(JavaAgentExtensionPackaging.MAVEN_ARTIFACT_ID);
            publication.artifact(assembly.packageExtension());
            publication.getPom().getName().set(EXTENSION_POM_NAME);
            publication.getPom().getDescription().set(EXTENSION_POM_DESCRIPTION);
        });
    }

    private static void configureTestRepositoryWorkflow(Project project,
                                                        Provider<Directory> testRepository,
                                                        TaskProvider<Test> artifactTest) {
        TaskProvider<Delete> resetRepository = project.getTasks().register(
                RESET_TEST_REPOSITORY_TASK,
                Delete.class,
                task -> task.delete(testRepository)
        );

        TaskCollection<AbstractPublishToMaven> testRepositoryPublications = project.getTasks()
                .withType(AbstractPublishToMaven.class)
                .matching(JavaAgentDistributionPublishing::targetsTestRepository);

        project.getTasks().withType(AbstractPublishToMaven.class).configureEach(task -> {
            if (targetsTestRepository(task)) {
                task.dependsOn(resetRepository);
            } else {
                task.dependsOn(project.getTasks().named("check"));
            }
        });

        ConfigurableFileTree repositoryInventory = project.fileTree(testRepository);
        repositoryInventory.exclude("**/maven-metadata*");
        artifactTest.configure(task -> {
            task.dependsOn(testRepositoryPublications);
            task.getInputs().files(repositoryInventory)
                    .withPropertyName("publishedRepositoryArtifacts")
                    .withPathSensitivity(PathSensitivity.RELATIVE);
        });
    }

    private static boolean targetsTestRepository(AbstractPublishToMaven task) {
        return task instanceof PublishToMavenRepository repositoryTask
                && repositoryTask.getRepository() != null
                && TEST_REPOSITORY_NAME.equals(repositoryTask.getRepository().getName());
    }
}
