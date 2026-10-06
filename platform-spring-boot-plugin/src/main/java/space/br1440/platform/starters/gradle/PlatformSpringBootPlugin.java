package space.br1440.platform.starters.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.jetbrains.annotations.NotNull;

@SuppressWarnings("unused")
public final class PlatformSpringBootPlugin implements Plugin<Project> {

    private static final String SPRING_BOOT_PLUGIN_ID = "org.springframework.boot";

    @Override
    public void apply(@NotNull Project project) {
        project.getPluginManager().apply(SPRING_BOOT_PLUGIN_ID);
    }
}
