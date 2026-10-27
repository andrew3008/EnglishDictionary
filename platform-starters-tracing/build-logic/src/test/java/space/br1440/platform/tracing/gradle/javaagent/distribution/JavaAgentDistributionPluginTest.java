package space.br1440.platform.tracing.gradle.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

class JavaAgentDistributionPluginTest {

    @Test
    void exposes_only_controlled_agent_artifacts() {
        // given
        Project project = ProjectBuilder.builder().build();

        // when
        project.getPluginManager().apply(JavaAgentDistributionPlugin.class);

        // then
        assertThat(project.getPlugins().hasPlugin(JavaPlugin.class)).isFalse();
        assertThat(project.getTasks().findByName(JavaPlugin.JAR_TASK_NAME)).isNull();
        assertThat(project.getConfigurations().findByName(JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME)).isNull();
        assertThat(project.getConfigurations().findByName(JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME)).isNull();

        Configuration agentElements = project.getConfigurations().getByName("controlledAgentElements");
        Configuration pinElements = project.getConfigurations().getByName("controlledAgentPinElements");
        assertControlled(agentElements);
        assertControlled(pinElements);
        assertThat(libraryElements(agentElements)).isEqualTo(LibraryElements.JAR);
        assertThat(libraryElements(pinElements)).isEqualTo("properties");
        assertNotConsumable(project.getConfigurations().getByName(Dependency.DEFAULT_CONFIGURATION));
        assertNotConsumable(project.getConfigurations().getByName(Dependency.ARCHIVES_CONFIGURATION));
    }

    private static void assertControlled(Configuration configuration) {
        assertThat(configuration.isCanBeConsumed()).isTrue();
        assertThat(configuration.isCanBeResolved()).isFalse();
        //noinspection UnstableApiUsage — Gradle 8.14: isCanBeDeclared ещё @Incubating; вариант не принимает объявления зависимостей
        assertThat(configuration.isCanBeDeclared()).isFalse();
        assertThat(configuration.isVisible()).isFalse();
        assertThat(configuration.getAttributes().getAttribute(Category.CATEGORY_ATTRIBUTE))
                .extracting(Category::getName)
                .isEqualTo(ControlledAgentPublication.NON_LIBRARY_CATEGORY);
    }

    private static String libraryElements(Configuration configuration) {
        return Objects.requireNonNull(
                configuration.getAttributes().getAttribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE),
                "controlled agent variant must declare library elements"
        ).getName();
    }

    private static void assertNotConsumable(Configuration configuration) {
        assertThat(configuration.isCanBeConsumed()).isFalse();
        assertThat(configuration.getOutgoing().getArtifacts()).isEmpty();
        assertThat(configuration.getOutgoing().getVariants()).isEmpty();
    }
}
