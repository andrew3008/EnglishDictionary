package space.br1440.platform.tracing.gradle.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Category;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

class JavaAgentDistributionPluginTest {

    @Test
    void prevents_distribution_from_being_consumed_as_java_library() {
        // given
        Project project = ProjectBuilder.builder().build();

        // when
        project.getPluginManager().apply(JavaAgentDistributionPlugin.class);

        // then
        List<String> configurationNames = List.of(
                JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME,
                JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME
        );

        for (String configurationName : configurationNames) {
            Configuration configuration = project.getConfigurations().getByName(configurationName);

            assertThat(configuration.getOutgoing().getArtifacts())
                    .as("%s outgoing artifacts", configurationName)
                    .isEmpty();

            assertThat(configuration.getAttributes().getAttribute(Category.CATEGORY_ATTRIBUTE))
                    .as("%s category", configurationName)
                    .extracting(Category::getName)
                    .isEqualTo(JavaAgentDistributionPlugin.NON_LIBRARY_CATEGORY);
        }

        assertThat(project.getTasks().getByName(JavaPlugin.JAR_TASK_NAME).getEnabled()).isFalse();
    }
}
